// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Heat (orchestration: whole stacks of networks, servers and volumes from one template),
// Kolla images under podman. Modelled on designate_single_node.scala and nova_single_node.scala; read their
// headers for the conventions.
//
// Where it runs: tst20, which had no role. Heat is three small Python services (the API, the CloudFormation
// compatible API and the engine), so one 4 GB VM holds all of it. To move it, change `node`, `fqdn` and
// `nodeIp` below (and the teardown's `node`); nothing else depends on the location.
//
// What it builds:
//   - heat-api       the REST API (Apache + mod_wsgi when the image has heat-wsgi-api, else the standalone
//                    heat-api), port 8004, plain HTTP like the other APIs here
//   - heat-api-cfn   the CloudFormation compatible API (heat-wsgi-api-cfn or heat-api-cfn), port 8000; the
//                    instances of a stack call it for wait conditions and metadata
//   - heat-engine    does the work: reads the template, calls Nova, Neutron, Cinder ... as the stack's owner
//                    through a Keystone trust, and keeps the state in the database
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `heat` in project `service`
// with the admin role; the stack user domain `heat` with its admin user `heat_domain_admin` (Heat makes
// the users that instances use for metadata there, so they never touch the Default domain); the roles
// `heat_stack_owner` and `heat_stack_user`; the services `heat` (type orchestration, endpoint
// .../v1/%(tenant_id)s) and `heat-cfn` (type cloudformation, .../v1).
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - Nova (nova_single_node.scala) and Neutron: a stack that makes servers or networks needs them; the
//     test stack here makes only a random string and a Nova flavor
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// Stages:
//   - heat-database (tst3, a Galera node): the `heat` database + user.
//   - heat-rabbitmq (tst10): vhost and user, idempotent.
//   - install-heat (the node): packages, cache mirror, image pulls, Keystone's CA from its TLS handshake,
//     the helper and the registration in Keystone (the stack domain must exist before the engine starts),
//     heat.conf, Kolla config for each container, `heat-manage db_sync`, then the units.
//   - confirm-heat-healthy (the node): both APIs answer, and the engine reports in through the API.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/heat_single_node.scala` changes nothing
// and reports whether the units on the node match what this playbook writes.
//
// Facts about Heat this was written from, to check if the first run fails: heat.conf needs
// stack_user_domain_name, stack_domain_admin and its password (the domain admin made by `register`);
// trusts are the default way the engine acts for a user (deferred_auth_method), and trusts_delegated_roles
// is left unset, which delegates all of the user's roles; the Kolla images are the 2025.1 ubuntu-noble ones,
// as for the other services; heat.conf is plain text, a typed version needs a catalogue of the image's
// options (manifests/dump_oslo_options.sh heat-api).
//
// Test-only credentials come from the secret store (heat/db, heat/service, heat/rabbit,
// heat/domain-admin), made on first use. Not compiled where this was written; the first run on scala0 is
// the first compile.
object heat_single_node extends OrpheraClusterPlaybook:

  private val node = "tst20"
  private val fqdn = "tst20.ljalbinson.com"
  private val nodeIp = "10.10.5.32"
  private val apiPort = 8004
  private val cfnPort = 8000

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "heat"
  private val rabbitPassword = Secrets.secret("heat/rabbit")
  private val rabbitVhost = "heat"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "heat"
  private val dbUser = "heat"
  private val dbPassword = Secrets.secret("heat/db")
  private val heatPassword = Secrets.secret("heat/service")
  private val domainAdminPassword = Secrets.secret("heat/domain-admin")

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/heat"
  private val logDir = "/var/log/kolla/heat"
  private val stateDir = "/var/lib/orphera/heat"
  private val caDir = "/etc/kolla/heat-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The three containers as data: the unit file text and the start task come from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. They share their mounts apart
  // from their own config directory. Defined after confRoot, logDir and stateDir, which they read; a val
  // used before its definition is null in an object.
  private def heatContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/heat", Access.ReadWrite),
        Mount(stateDir, "/var/lib/heat", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The API images may set a non-root USER, which cannot open Apache's log files; start them as root.
      user = if service.startsWith("heat-api") then Some("root") else None
    )
  private val heatServices: List[Container] =
    List("heat-engine", "heat-api", "heat-api-cfn").map(heatContainer)

  private def imageRef(service: String): String =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val apiImage = imageRef("heat-api")
  private val cfnImage = imageRef("heat-api-cfn")

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
       |location = "tst21.ljalbinson.com:5000"
       |insecure = true
       |
       |[[registry.mirror]]
       |location = "$cacheHostPort"
       |insecure = true
       |REG
       |echo "podman will try tst21.ljalbinson.com:5000 (built images), then $cacheHostPort, before quay.io"""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in heat_single_node.scala"
      )}
       |for img in ${heatServices.map(_.image.ref).mkString(" ")}; do
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

  // heat.conf, plain text (see the header). The same file goes to all three containers. The stack user
  // domain and its admin are made by the helper's `register`. `trustee` and the `clients` groups are how the
  // engine reaches Keystone (and, through the catalog, Nova, Neutron and the rest) as the stack's owner.
  private val heatConf: String =
    s"""# Managed by Orphera (heat_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/heat
       |use_stderr = true
       |heat_metadata_server_url = http://$fqdn:$cfnPort
       |heat_waitcondition_server_url = http://$fqdn:$cfnPort/v1/waitcondition
       |stack_user_domain_name = heat
       |stack_domain_admin = heat_domain_admin
       |stack_domain_admin_password = ${domainAdminPassword.reveal}
       |num_engine_workers = 2
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[heat_api]
       |bind_host = 0.0.0.0
       |bind_port = $apiPort
       |workers = 2
       |
       |[heat_api_cfn]
       |bind_host = 0.0.0.0
       |bind_port = $cfnPort
       |workers = 2
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = heat
       |password = ${heatPassword.reveal}
       |cafile = /etc/heat/ssl/ca.crt
       |
       |[trustee]
       |auth_type = password
       |auth_url = $ksUrl
       |username = heat
       |password = ${heatPassword.reveal}
       |user_domain_name = Default
       |cafile = /etc/heat/ssl/ca.crt
       |
       |[clients_keystone]
       |auth_uri = $ksUrl
       |ca_file = /etc/heat/ssl/ca.crt
       |
       |[clients]
       |ca_file = /etc/heat/ssl/ca.crt
       |""".stripMargin

  // One config directory per container (Kolla mounts it at /var/lib/kolla/config_files). The two APIs run
  // behind Apache + mod_wsgi like Placement and Nova when the image has the WSGI script, else as the
  // standalone program. Which one is read from each image.
  private val kollaConfigScript =
    s"""set -e
       |for svc in api api-cfn engine; do
       |  mkdir -p $confRoot/heat-$$svc
       |  cp $srcDir/heat.conf $confRoot/heat-$$svc/heat.conf
       |  cp $caDir/ca.crt $confRoot/heat-$$svc/ca.crt
       |done
       |COMMON='{"source": "/var/lib/kolla/config_files/heat.conf", "dest": "/etc/heat/heat.conf", "owner": "heat", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/heat/ssl/ca.crt", "owner": "heat", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/heat", "owner": "heat:kolla", "recurse": true}, {"path": "/var/lib/heat", "owner": "heat:heat", "recurse": true}]'
       |cat > $confRoot/heat-engine/config.json <<JSON
       |{"command": "heat-engine --config-file /etc/heat/heat.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |# webservice <dir suffix> <wsgi script> <standalone program> <port> <image>
       |webservice() {
       |  svc=$$1; wsgicmd=$$2; standalone=$$3; port=$$4; image=$$5
       |  WSGI=$$(podman run --rm --entrypoint sh $$image -c "command -v $$wsgicmd" || true)
       |  if [ -n "$$WSGI" ]; then
       |    if podman run --rm --entrypoint sh $$image -c 'test -d /etc/apache2/conf-enabled'; then
       |      APACHE_CONF=/etc/apache2/conf-enabled/heat-$$svc.conf
       |      APACHE_CMD="apache2 -DFOREGROUND"
       |      # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |      if podman run --rm --entrypoint sh $$image -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |    else
       |      APACHE_CONF=/etc/httpd/conf.d/heat-$$svc.conf
       |      APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |    fi
       |    echo "$$svc: wsgi script $$WSGI; apache config $$APACHE_CONF"
       |    cat > $confRoot/heat-$$svc/heat-$$svc.conf <<WSGICONF
       |Listen 0.0.0.0:$$port
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$$port>
       |    ErrorLog "$logDir/heat-$$svc-apache-error.log"
       |    CustomLog "$logDir/heat-$$svc-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess heat-$$svc group=heat processes=2 threads=1 user=heat
       |    WSGIProcessGroup heat-$$svc
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |    cat > $confRoot/heat-$$svc/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/heat-$$svc.conf", "dest": "$$APACHE_CONF", "owner": "heat", "perm": "0600"}], $$PERMS}
       |JSON
       |  else
       |    echo "$$svc: no $$wsgicmd in $$image; running the standalone $$standalone"
       |    cat > $confRoot/heat-$$svc/config.json <<JSON
       |{"command": "$$standalone --config-file /etc/heat/heat.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |  fi
       |}
       |webservice api heat-wsgi-api heat-api $apiPort $apiImage
       |webservice api-cfn heat-wsgi-api-cfn heat-api-cfn $cfnPort $cfnImage
       |echo "kolla config written under $confRoot"""".stripMargin

  // heat-manage runs in a one-off container with heat.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint heat-manage -v $srcDir/heat.conf:/etc/heat/heat.conf:ro,z -v $logDir:/var/log/kolla/heat:z $apiImage db_sync
       |echo "heat database synced"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | catalog | stack-list | engines-up | stack-cycle [basic]
  // stack-cycle makes a stack from a template (a random string, and a Nova flavor unless `basic`), waits for
  // CREATE_COMPLETE, reads an output back, deletes the stack and waits until it is gone. The flavor is made
  // by the engine through a Keystone trust, so a pass means the whole path works: API, RabbitMQ, engine,
  // database, trust, Nova.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
HEAT = '@@HEAT@@'
CFN = '@@CFN@@'
ADMIN_PW = '@@ADMINPW@@'
HEAT_PW = '@@HEATPW@@'
DOMAIN_ADMIN_PW = '@@DOMAINADMINPW@@'
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


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token'], parsed


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


def ensure_service(token, svc_type, name, url):
    service = ensure(token, 'services', '/services?type=' + svc_type, 'services', {'service': {'name': name, 'type': svc_type}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint of %s failed: HTTP %s %s' % (iface, name, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=heat', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'heat', 'domain_id': 'default', 'password': HEAT_PW}}, token)
        if s != 201:
            die('creating user heat failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': HEAT_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to heat failed: HTTP %s %s' % (s, p))
    for role in ('heat_stack_owner', 'heat_stack_user'):
        ensure(token, 'roles', '/roles?name=' + role, 'roles', {'role': {'name': role}}, 'role')
    domain = ensure(token, 'domains', '/domains?name=heat', 'domains', {'domain': {'name': 'heat', 'description': 'Owns the users and projects that Heat makes for stacks'}}, 'domain')
    dadmin = find(token, '/users?name=heat_domain_admin&domain_id=' + domain['id'], 'users')
    if dadmin is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'heat_domain_admin', 'domain_id': domain['id'], 'password': DOMAIN_ADMIN_PW}}, token)
        if s != 201:
            die('creating user heat_domain_admin failed: HTTP %s %s' % (s, p))
        dadmin = p['user']
    else:
        call('PATCH', KS + '/users/' + dadmin['id'], {'user': {'password': DOMAIN_ADMIN_PW}}, token)
    s, p, _ = call('PUT', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin on domain heat to heat_domain_admin failed: HTTP %s %s' % (s, p))
    ensure_service(token, 'orchestration', 'heat', HEAT + '/%(tenant_id)s')
    ensure_service(token, 'cloudformation', 'heat-cfn', CFN)
    print('registered heat in keystone (user heat, domain heat with heat_domain_admin, roles heat_stack_owner and heat_stack_user, services orchestration and cloudformation with 3 endpoints each)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for svc_type in ('orchestration', 'cloudformation'):
        service = find(token, '/services?type=' + svc_type, 'services')
        if service is not None:
            call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=heat', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    domain = find(token, '/domains?name=heat', 'domains')
    if domain is not None:
        # Heat makes users and projects in this domain for its stacks: remove them all, then the domain.
        s, p, _ = call('GET', KS + '/users?domain_id=' + domain['id'], None, token)
        for u in p.get('users', []):
            call('DELETE', KS + '/users/' + u['id'], None, token)
        s, p, _ = call('GET', KS + '/projects?domain_id=' + domain['id'], None, token)
        for pr in p.get('projects', []):
            call('DELETE', KS + '/projects/' + pr['id'], None, token)
        call('PATCH', KS + '/domains/' + domain['id'], {'domain': {'enabled': False}}, token)
        call('DELETE', KS + '/domains/' + domain['id'], None, token)
    print('removed heat from keystone (services orchestration and cloudformation, their endpoints, user heat, domain heat)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def heat_session():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    return token, HEAT + '/' + parsed['token']['project']['id']


def stack_list():
    token, base = heat_session()
    s, p, _ = call('GET', base + '/stacks', None, token)
    if s != 200:
        die('stack list failed: HTTP %s %s' % (s, p))
    for st in p.get('stacks', []):
        print('stack %s name=%s status=%s' % (st.get('id'), st.get('stack_name'), st.get('stack_status')))
    print('stacks: %d' % len(p.get('stacks', [])))


def engines_up():
    token, base = heat_session()
    s, p, _ = call('GET', base + '/services', None, token)
    if s != 200:
        die('service list failed: HTTP %s %s' % (s, p))
    up = sorted(set(x.get('host') for x in p.get('services', []) if x.get('binary') == 'heat-engine' and x.get('status') == 'up'))
    if not up:
        die('no heat-engine is up: %s' % p)
    print('heat-engine is up on %s' % ', '.join(up))


def stack_cycle(basic):
    token, base = heat_session()
    name = 'orphera-test-%d' % int(time.time())
    resources = {'secret': {'type': 'OS::Heat::RandomString', 'properties': {'length': 12}}}
    outputs = {'secret': {'value': {'get_attr': ['secret', 'value']}}}
    if not basic:
        resources['flavor'] = {'type': 'OS::Nova::Flavor', 'properties': {'ram': 64, 'vcpus': 1, 'disk': 1, 'is_public': False}}
    template = {'heat_template_version': '2018-08-31', 'resources': resources, 'outputs': outputs}
    s, p, _ = call('POST', base + '/stacks', {'stack_name': name, 'template': template, 'timeout_mins': 5}, token)
    if s != 201:
        die('stack create failed: HTTP %s %s' % (s, p))
    stack_id = p['stack']['id']
    url = '%s/stacks/%s/%s' % (base, name, stack_id)
    try:
        end = time.time() + 240
        status = None
        value = None
        while time.time() < end:
            s, p, _ = call('GET', url, None, token)
            if s == 200:
                st = p['stack']
                status = st['stack_status']
                if status == 'CREATE_COMPLETE':
                    for o in st.get('outputs', []):
                        if o.get('output_key') == 'secret':
                            value = o.get('output_value')
                    break
                if status.endswith('FAILED'):
                    die('stack %s: %s (%s)' % (name, status, st.get('stack_status_reason')))
            time.sleep(3)
        if status != 'CREATE_COMPLETE':
            die('stack %s did not reach CREATE_COMPLETE within 240 s (last status %s)' % (name, status))
        if not value:
            die('stack %s has no output `secret`' % name)
    finally:
        call('DELETE', url, None, token)
    end = time.time() + 240
    while time.time() < end:
        s, p, _ = call('GET', url, None, token)
        # Heat keeps answering 200 with DELETE_COMPLETE for a deleted stack read by name and id; 404 is
        # accepted too, in case a release hides it.
        if s == 404 or (s == 200 and p['stack']['stack_status'] == 'DELETE_COMPLETE'):
            print('stack cycle ok: %s created (%s resources), output read back, stack deleted' % (name, len(resources)))
            return
        if s == 200 and p['stack']['stack_status'] == 'DELETE_FAILED':
            die('stack %s: DELETE_FAILED (%s)' % (name, p['stack'].get('stack_status_reason')))
        time.sleep(3)
    die('stack %s was not deleted within 240 s' % name)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    args = sys.argv[2:]
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'stack-list':
        stack_list()
    elif cmd == 'engines-up':
        engines_up()
    elif cmd == 'stack-cycle':
        stack_cycle(args == ['basic'])
    else:
        die('usage: orphera-heat-api register | unregister | catalog | stack-list | engines-up | stack-cycle [basic]')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-heat-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@HEAT@@", s"http://$fqdn:$apiPort/v1")
        .replace("@@CFN@@", s"http://$fqdn:$cfnPort/v1")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@HEATPW@@", heatPassword.reveal)
        .replace("@@DOMAINADMINPW@@", domainAdminPassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-heat-api\necho wrote /usr/local/sbin/orphera-heat-api\n"

  // Both APIs answer their version document without a token (300 for a list of versions, or 200). One health
  // check covers both, because a stage takes only one.
  private val healthScript =
    s"""set -e
       |for port in $apiPort $cfnPort; do
       |  CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$$port/)
       |  if [ "$$CODE" != "200" ] && [ "$$CODE" != "300" ]; then echo "port $$port answered $$CODE" >&2; exit 1; fi
       |done""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if
  // a unit differs from what this playbook would write. The check task comes from the same Container
  // value as the write task (see Resource.scala).
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  // Adds one "write the unit" task per container, then one "start" task per container (the engine first).
  private def withServices(b: StageBuilder): StageBuilder =
    val written = heatServices.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    heatServices.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("heat-single-node-check")(
        heatServices
          .foldLeft(stage("check-heat", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          )
          .build
      )
    else applyPlaybook

  // The whole install stage, ending with every container started.
  private def installHeat: StageBuilder =
    withServices(
      stage("install-heat", node)
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
        .task("pull the Heat images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the Heat directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the Heat API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Heat in Keystone (the stack domain and its admin)")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-heat-api register"),
            timeoutSeconds = 120
          )
        )
        .task("write heat.conf")(
          Task.WriteFile(
            content = heatConf,
            dest = s"$srcDir/heat.conf",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("write the Kolla config for each container")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 300
          )
        )
        .task("heat-manage db_sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("heat-single-node")(
      stage("heat-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("heat-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      installHeat.build,

      stage("confirm-heat-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("the engine reports in through the API")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "for i in $(seq 1 24); do /usr/local/sbin/orphera-heat-api engines-up && exit 0; sleep 5; done; /usr/local/sbin/orphera-heat-api engines-up; exit 1"
            ),
            timeoutSeconds = 180
          )
        )
        .task("list stacks with an admin token")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-heat-api stack-list"),
            timeoutSeconds = 60
          )
        )
        .task("heat confirmed")(
          Task.Debug(
            s"Heat up at http://$fqdn:$apiPort ($nodeIp): heat-api, heat-api-cfn (:$cfnPort) and heat-engine, RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_heat.sh"
          )
        )
        .build
    )
