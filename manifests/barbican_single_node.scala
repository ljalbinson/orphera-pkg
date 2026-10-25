// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Barbican (key manager: stores secrets, keys and certificates for other services and
// users), Kolla images under podman. Modelled on heat_single_node.scala and designate_single_node.scala; read
// their headers for the conventions.
//
// Where it runs: tst20, next to Heat. Both are small, and no VM is free, so they share the node. To move it,
// change `node`, `fqdn` and `nodeIp` below (and the teardown's `node`); nothing else depends on the location.
//
// What it builds:
//   - barbican-api     the REST API, behind Apache + mod_wsgi (the image's barbican-wsgi-api), port 9311, plain
//                      HTTP like the other APIs here
//   - barbican-worker  processes the orders (for example "make me a 256 bit AES key") from the queue
// The Keystone listener, which deletes a project's secrets when Keystone deletes the project, is left out: it
// needs Keystone to publish notifications to RabbitMQ, which it does not here.
//
// The secret store is the simple_crypto plugin: each secret is encrypted with a key-encrypting key (KEK) and
// kept in the database. This is for a lab. The KEK is derived (SHA-256, base64) from the secret
// `barbican/kek` in the secret store, so it is the same on every run; LOSE IT AND EVERY STORED SECRET IS
// UNREADABLE (back up the secret store). A real deployment uses a hardware module (PKCS#11) or Vault.
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `barbican` in project `service`
// with the admin role; the roles `creator`, `observer` and `audit` (the users of a project who may create,
// read and audit secrets; admin may do all); the service `barbican` of type `key-manager`.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved. Heat on the same node is untouched (separate directories, containers and ports).
//
// Stages:
//   - barbican-database (tst3, a Galera node): the `barbican` database + user.
//   - barbican-rabbitmq (tst10): vhost and user, idempotent.
//   - install-barbican (the node): packages, cache mirror, image pulls, Keystone's CA from its TLS handshake,
//     the helper and the registration in Keystone, barbican.conf, Kolla config, `barbican-manage db upgrade`,
//     then the units.
//   - confirm-barbican-healthy (the node): the API answers and a secret can be stored and read back.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/barbican_single_node.scala` changes nothing
// and reports whether the units on the node match what this playbook writes.
//
// Facts about Barbican this was written from, to check if the first run fails: the database connection is
// given in both [DEFAULT] sql_connection and [database] connection, because releases have used either;
// [queue] enable = true makes the API hand orders to the worker (false would run them inside the API); the
// image has barbican-wsgi-api (the run stops with a message if it does not); the Kolla images are the 2025.1
// ubuntu-noble ones, as for the other services; barbican.conf is plain text, a typed version needs a
// catalogue of the image's options (manifests/dump_oslo_options.sh barbican-api).
//
// Test-only credentials come from the secret store (barbican/db, barbican/service, barbican/rabbit,
// barbican/kek), made on first use. Not compiled where this was written; the first run on scala0 is the first
// compile.
object barbican_single_node extends OrpheraClusterPlaybook:

  private val node = "tst20"
  private val fqdn = "tst20.ljalbinson.com"
  private val nodeIp = "10.10.5.32"
  private val apiPort = 9311

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "barbican"
  private val rabbitPassword = Secrets.secret("barbican/rabbit")
  private val rabbitVhost = "barbican"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "barbican"
  private val dbUser = "barbican"
  private val dbPassword = Secrets.secret("barbican/db")
  private val barbicanPassword = Secrets.secret("barbican/service")

  // 32 bytes, base64: what the simple_crypto plugin wants as its KEK. Derived from a stored secret so that it
  // is the same on every run and on a rebuilt node.
  private val kek: String =
    java.util.Base64.getEncoder.encodeToString(
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(
          Secrets
            .secret("barbican/kek")
            .reveal
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
    )

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/barbican"
  private val logDir = "/var/log/kolla/barbican"
  private val stateDir = "/var/lib/orphera/barbican"
  private val caDir = "/etc/kolla/barbican-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The two containers as data: the unit file text and the start task come from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined after confRoot, logDir and
  // stateDir, which they read; a val used before its definition is null in an object.
  private def barbicanContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/barbican", Access.ReadWrite),
        Mount(stateDir, "/var/lib/barbican", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
  private val barbicanServices: List[Container] =
    List("barbican-worker", "barbican-api").map(barbicanContainer)

  private val apiImage =
    Image("barbican-api", Release.R2025_1, Base.UbuntuNoble, registry).ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
      |exit 1""".stripMargin

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

  private val rabbitScript =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - run cinder_single_node.scala first" >&2
       |  exit 1
       |fi
       |if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$rabbitVhost'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$rabbitUser' '${rabbitPassword.reveal}'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$rabbitUser' '${rabbitPassword.reveal}'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$rabbitVhost' '$rabbitUser' '.*' '.*' '.*'
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser ready"""".stripMargin

  // ---- the node --------------------------------------------------------

  private val useCacheScript =
    s"""mkdir -p /etc/containers/registries.conf.d
       |cat > /etc/containers/registries.conf.d/orphera-kolla-cache.conf <<'REG'
       |[[registry]]
       |prefix = "quay.io"
       |location = "quay.io"
       |
       |[[registry.mirror]]
       |location = "$cacheHostPort"
       |insecure = true
       |REG
       |echo "podman will try $cacheHostPort before quay.io"""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in barbican_single_node.scala"
      )}
       |for img in ${barbicanServices.map(_.image.ref).mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  // Same method as placement_single_node.scala: the last certificate Keystone presents is its CA; trust on
  // first use.
  private val fetchCaScript =
    s"""set -e
       |mkdir -p $caDir
       |rm -f /tmp/ks-chain-*.pem
       |openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/ks-chain-" n ".pem")}'
       |COUNT=$$(ls /tmp/ks-chain-*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - re-run keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp /tmp/ks-chain-$$COUNT.pem $caDir/ca.crt
       |openssl verify -CAfile $caDir/ca.crt /tmp/ks-chain-1.pem
       |SUBJ=$$(openssl x509 -in $caDir/ca.crt -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caDir/ca.crt -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |rm -f /tmp/ks-chain-*.pem
       |if command -v update-ca-certificates >/dev/null 2>&1; then
       |  cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |  update-ca-certificates
       |else
       |  cp $caDir/ca.crt /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt
       |  update-ca-trust
       |fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  private val dirsScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |echo "directories ready"""".stripMargin

  // barbican.conf, plain text (see the header). The same file goes to both containers.
  private val barbicanConf: String =
    s"""# Managed by Orphera (barbican_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/barbican
       |use_stderr = true
       |host_href = http://$fqdn:$apiPort
       |bind_port = $apiPort
       |sql_connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |db_auto_create = false
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[queue]
       |enable = true
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = barbican
       |password = ${barbicanPassword.reveal}
       |cafile = /etc/barbican/ssl/ca.crt
       |
       |[secretstore]
       |namespace = barbican.secretstore.plugin
       |enabled_secretstore_plugins = store_crypto
       |
       |[crypto]
       |namespace = barbican.crypto.plugin
       |enabled_crypto_plugins = simple_crypto
       |
       |[simple_crypto_plugin]
       |kek = $kek
       |""".stripMargin

  // One config directory per container (Kolla mounts it at /var/lib/kolla/config_files). The API runs behind
  // Apache + mod_wsgi like Placement and Nova; the WSGI script path and the Apache flavour are read from the
  // image, and the run stops if the image has no barbican-wsgi-api.
  private val kollaConfigScript =
    s"""set -e
       |for svc in api worker; do
       |  mkdir -p $confRoot/barbican-$$svc
       |  cp $srcDir/barbican.conf $confRoot/barbican-$$svc/barbican.conf
       |  cp $caDir/ca.crt $confRoot/barbican-$$svc/ca.crt
       |done
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v barbican-wsgi-api' || true)
       |if [ -z "$$WSGI" ]; then echo "barbican-wsgi-api not found in $apiImage - see the header of barbican_single_node.scala" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/barbican-api.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/barbican-api.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $confRoot/barbican-api/barbican-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/barbican-apache-error.log"
       |    CustomLog "$logDir/barbican-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess barbican-api group=barbican processes=2 threads=1 user=barbican
       |    WSGIProcessGroup barbican-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |COMMON='{"source": "/var/lib/kolla/config_files/barbican.conf", "dest": "/etc/barbican/barbican.conf", "owner": "barbican", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/barbican/ssl/ca.crt", "owner": "barbican", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/barbican", "owner": "barbican:kolla", "recurse": true}, {"path": "/var/lib/barbican", "owner": "barbican:barbican", "recurse": true}]'
       |cat > $confRoot/barbican-api/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/barbican-api.conf", "dest": "$$APACHE_CONF", "owner": "barbican", "perm": "0600"}], $$PERMS}
       |JSON
       |cat > $confRoot/barbican-worker/config.json <<JSON
       |{"command": "barbican-worker --config-file /etc/barbican/barbican.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |echo "kolla config written under $confRoot"""".stripMargin

  // barbican-manage runs in a one-off container with barbican.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint barbican-manage -v $srcDir/barbican.conf:/etc/barbican/barbican.conf:ro,z -v $logDir:/var/log/kolla/barbican:z $apiImage db upgrade
       |echo "barbican database upgraded"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | catalog | secret-list | secret-cycle | order-cycle
  // secret-cycle stores a secret, reads its payload back, deletes it and checks it is gone. order-cycle asks
  // for a 256 bit AES key, which the WORKER has to make from the queue, waits for the order to be ACTIVE,
  // reads the 32 bytes of the key back, and deletes the secret and the order.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
BARBICAN = '@@BARBICAN@@'
ADMIN_PW = '@@ADMINPW@@'
BARBICAN_PW = '@@BARBICANPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')


def call(method, url, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=60)
        status, text, headers = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, headers = e.code, e.read().decode(), e.headers
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {}
    return status, parsed, headers


def fetch_raw(url, token, accept):
    req = urllib.request.Request(url, method='GET')
    req.add_header('X-Auth-Token', token)
    req.add_header('Accept', accept)
    try:
        r = urllib.request.urlopen(req, timeout=60)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token'], parsed


def admin_token():
    return get_token('admin', ADMIN_PW, 'admin')[0]


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def ensure(token, what, path, key, body, create_key):
    # the first object that matches `path`, or the one made from `body`
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=barbican', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'barbican', 'domain_id': 'default', 'password': BARBICAN_PW}}, token)
        if s != 201:
            die('creating user barbican failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': BARBICAN_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to barbican failed: HTTP %s %s' % (s, p))
    for role in ('creator', 'observer', 'audit'):
        ensure(token, 'roles', '/roles?name=' + role, 'roles', {'role': {'name': role}}, 'role')
    service = ensure(token, 'services', '/services?type=key-manager', 'services', {'service': {'name': 'barbican', 'type': 'key-manager'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': BARBICAN, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != BARBICAN:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': BARBICAN}}, token)
    print('registered barbican in keystone (user barbican, roles creator observer audit, service type key-manager, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=key-manager', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=barbican', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed barbican from keystone (service key-manager, its endpoints, user barbican)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def secret_list():
    token = admin_token()
    s, p, _ = call('GET', BARBICAN + '/v1/secrets', None, token)
    if s != 200:
        die('secret list failed: HTTP %s %s' % (s, p))
    for sec in p.get('secrets', []):
        print('secret %s name=%s status=%s' % (sec.get('secret_ref', '').rsplit('/', 1)[-1], sec.get('name'), sec.get('status')))
    print('secrets: %d' % len(p.get('secrets', [])))


def gone(token, ref):
    s, _, _ = call('GET', ref, None, token)
    return s == 404


def secret_cycle():
    token = admin_token()
    payload = 'orphera-secret-%d' % int(time.time())
    s, p, _ = call('POST', BARBICAN + '/v1/secrets', {'name': 'orphera-test-%d' % int(time.time()), 'payload': payload, 'payload_content_type': 'text/plain'}, token)
    if s != 201:
        die('secret create failed: HTTP %s %s' % (s, p))
    ref = p['secret_ref']
    try:
        s, body = fetch_raw(ref + '/payload', token, 'text/plain')
        if s != 200 or body.decode() != payload:
            die('secret payload read back wrongly: HTTP %s %r' % (s, body[:80]))
    finally:
        call('DELETE', ref, None, token)
    if not gone(token, ref):
        die('secret %s is still there after the delete' % ref)
    print('secret cycle ok: %s stored, payload read back, deleted' % ref.rsplit('/', 1)[-1])


def order_cycle():
    token = admin_token()
    meta = {'name': 'orphera-test-key-%d' % int(time.time()), 'algorithm': 'aes', 'bit_length': 256, 'mode': 'cbc', 'payload_content_type': 'application/octet-stream'}
    s, p, _ = call('POST', BARBICAN + '/v1/orders', {'type': 'key', 'meta': meta}, token)
    if s not in (201, 202):
        die('order create failed: HTTP %s %s' % (s, p))
    order_ref = p['order_ref']
    secret_ref = None
    try:
        end = time.time() + 120
        status = None
        while time.time() < end:
            s, p, _ = call('GET', order_ref, None, token)
            if s == 200:
                status = p.get('status')
                if status == 'ACTIVE':
                    secret_ref = p.get('secret_ref')
                    break
                if status == 'ERROR':
                    die('order %s went to ERROR: %s %s' % (order_ref, p.get('error_status_code'), p.get('error_reason')))
            time.sleep(2)
        if secret_ref is None:
            die('order %s did not become ACTIVE within 120 s (last status %s); is barbican-worker running?' % (order_ref, status))
        s, body = fetch_raw(secret_ref + '/payload', token, 'application/octet-stream')
        if s != 200 or len(body) != 32:
            die('the key made by the order is not 32 bytes: HTTP %s, %d bytes' % (s, len(body)))
    finally:
        if secret_ref:
            call('DELETE', secret_ref, None, token)
        call('DELETE', order_ref, None, token)
    print('order cycle ok: a 256 bit AES key made by the worker (%s), read back as 32 bytes, deleted' % secret_ref.rsplit('/', 1)[-1])


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'secret-list':
        secret_list()
    elif cmd == 'secret-cycle':
        secret_cycle()
    elif cmd == 'order-cycle':
        order_cycle()
    else:
        die('usage: orphera-barbican-api register | unregister | catalog | secret-list | secret-cycle | order-cycle')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-barbican-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@BARBICAN@@", s"http://$fqdn:$apiPort")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@BARBICANPW@@", barbicanPassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-barbican-api\necho wrote /usr/local/sbin/orphera-barbican-api\n"

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if
  // a unit differs from what this playbook would write. The check task comes from the same Container
  // value as the write task (see Resource.scala).
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  // Adds one "write the unit" task per container, then one "start" task per container (the worker first).
  private def withServices(b: StageBuilder): StageBuilder =
    val written = barbicanServices.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    barbicanServices.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("barbican-single-node-check")(
        barbicanServices
          .foldLeft(stage("check-barbican", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          )
          .build
      )
    else applyPlaybook

  // The whole install stage, ending with every container started.
  private def installBarbican: StageBuilder =
    withServices(
      stage("install-barbican", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Barbican images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the Barbican directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the Barbican API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Barbican in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-barbican-api register"),
            timeoutSeconds = 120
          )
        )
        .task("write barbican.conf")(
          Task.WriteFile(
            content = barbicanConf,
            dest = s"$srcDir/barbican.conf",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("write the Kolla config and the Apache vhost")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 180
          )
        )
        .task("barbican-manage db upgrade")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("barbican-single-node")(
      stage("barbican-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("barbican-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      installBarbican.build,

      stage("confirm-barbican-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("store a secret and read it back with an admin token")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-barbican-api secret-cycle"
            ),
            timeoutSeconds = 120
          )
        )
        .task("barbican confirmed")(
          Task.Debug(
            s"Barbican up at http://$fqdn:$apiPort ($nodeIp): barbican-api and barbican-worker, RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_barbican.sh"
          )
        )
        .build
    )
