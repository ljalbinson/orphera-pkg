// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Nova control plane (compute API, scheduler, conductor and the
// noVNC console proxy), Kolla images under podman. Modelled on
// placement_single_node.scala and cinder_single_node.scala; read their headers
// for the conventions. There is no hypervisor here: nova-compute comes later, on
// other nodes. Until one exists the API, scheduler and conductor all work, but
// no server can be booted. Neutron is not configured either.
//
// Where it runs: tst14. Keep `node`, `fqdn` and `nodeIp` in step with
// nova_teardown.scala and test_nova.sh.
//
// Messaging: Nova gets its own vhost (`nova`) and user (`nova`) on the RabbitMQ
// that cinder_single_node.scala runs on tst10, rather than a second broker. To
// give Nova a broker of its own, change `rabbitNode`/`rabbitIp` and run the
// RabbitMQ part of cinder_single_node.scala there.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala,
//     mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - Placement up on tst13 (placement_single_node.scala), registered in Keystone
//   - agent on tst14; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// Stages:
//   - nova-databases (tst3, a Galera node): the three databases Nova uses
//     (`nova_api`, `nova`, and `nova_cell0` for instances that never got a host)
//     and one user with rights on all of them.
//   - nova-rabbitmq (tst10): vhost and user, idempotent.
//   - install-nova (tst14): wait for apt, packages, cache mirror, image pulls,
//     Keystone's CA from its TLS handshake, nova.conf, Kolla config for the four
//     services (the API behind Apache + mod_wsgi), `nova-manage api_db sync`,
//     cell0 and cell1 mapping, `nova-manage db sync`, registration in Keystone
//     (user `nova` in project `service`, service type `compute`, three
//     endpoints), then the units. Registration comes first because the
//     scheduler and conductor authenticate as `nova` when they start; on a fresh
//     Keystone they exited with HTTP 401 and the start check failed.
//   - confirm-nova-healthy (tst14): the API answers, and the scheduler and
//     conductor report in as "up" through the API.
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. Not compiled where this was written; the first run on scala0 is
// the first compile. The most likely things to need a fix on the first run: the
// WSGI script name (`nova-api-wsgi`) and /etc/nova/api-paste.ini inside the
// image, and the noVNC web directory.
object nova_single_node extends OrpheraClusterPlaybook:

  private val node = "tst14"
  private val fqdn = "tst14.ljalbinson.com"
  private val nodeIp = "10.10.5.26"
  private val apiPort = 8774
  private val vncPort = 6080

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "nova"
  private val rabbitPassword = "orphera-test-nova-rabbit-password"
  private val rabbitVhost = "nova"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbUser = "nova"
  private val dbPassword = "orphera-test-nova-db-password"
  private val novaPassword = "orphera-test-nova-service-password"
  // Nova calls Neutron as the `neutron` service user (created by neutron_single_node.scala).
  private val neutronPassword = "orphera-test-neutron-service-password"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/nova"
  private val logDir = "/var/log/kolla/nova"
  private val stateDir = "/var/lib/orphera/nova"
  private val caDir = "/etc/kolla/nova-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The four Nova containers as data: the unit file text and the start task come from
  // Container (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. They
  // share one set of mounts apart from their own config directory. Defined after confRoot,
  // logDir and stateDir, which they read; a val used before its definition is null in an
  // object.
  private def novaContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/nova", Access.ReadWrite),
        Mount(stateDir, "/var/lib/nova", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
  private val novaServices: List[Container] =
    List("nova-api", "nova-scheduler", "nova-conductor", "nova-novncproxy").map(
      novaContainer
    )
  private val apiImage = novaServices.head.image.ref

  // Adds one "write the unit" task per container, then one "start" task per container.
  private def withServices(b: StageBuilder): StageBuilder =
    val written = novaServices.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    novaServices.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabasesScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS nova_api; CREATE DATABASE IF NOT EXISTS nova; CREATE DATABASE IF NOT EXISTS nova_cell0; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON nova_api.* TO '$dbUser'@'%'; GRANT ALL PRIVILEGES ON nova.* TO '$dbUser'@'%'; GRANT ALL PRIVILEGES ON nova_cell0.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

  // No {{...}} in any command: Orphera renders those as a template before running
  // the command, so `podman ps --format '{{.Names}}'` arrives empty. Use filters.
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
       |  podman exec rabbitmq rabbitmqctl change_password '$rabbitUser' '$rabbitPassword'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$rabbitUser' '$rabbitPassword'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$rabbitVhost' '$rabbitUser' '.*' '.*' '.*'
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser ready"""".stripMargin

  // ---- the node --------------------------------------------------------

  // A fresh VM may still be running unattended-upgrades (see
  // disable_unattended_upgrades.scala); when it finishes needrestart restarts the
  // agent and kills the running task. Wait for it before touching packages.
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
        "the Image(...) in novaContainer in nova_single_node.scala"
      )}
       |for img in ${novaServices.map(_.image.ref).mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  // Same method as placement_single_node.scala: the last certificate Keystone
  // presents is its CA; trust on first use.
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

  // The same file serves the API, scheduler, conductor and the proxy. The
  // heredoc is quoted so the shell expands nothing; Scala has already filled in
  // the values.
  private val novaConfScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |cat > $srcDir/nova.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/nova
       |state_path = /var/lib/nova
       |use_stderr = true
       |my_ip = $nodeIp
       |transport_url = rabbit://$rabbitUser:$rabbitPassword@$rabbitIp:5672/$rabbitVhost
       |
       |[api]
       |auth_strategy = keystone
       |
       |[api_database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/nova_api
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/nova
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[service_user]
       |send_service_user_token = true
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[placement]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |region_name = RegionOne
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[glance]
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[neutron]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |region_name = RegionOne
       |project_name = service
       |username = neutron
       |password = $neutronPassword
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/nova/tmp
       |
       |[scheduler]
       |discover_hosts_in_cells_interval = 60
       |
       |[vnc]
       |enabled = true
       |novncproxy_host = 0.0.0.0
       |novncproxy_port = $vncPort
       |novncproxy_base_url = http://$fqdn:$vncPort/vnc_auto.html
       |CONF
       |chmod 640 $srcDir/nova.conf
       |echo "nova.conf written"""".stripMargin

  // One config directory per service (Kolla mounts it at
  // /var/lib/kolla/config_files). The API runs behind Apache + mod_wsgi like
  // Placement and Cinder; the WSGI script path and the Apache flavour are read
  // from the image.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/nova-api $confRoot/nova-scheduler $confRoot/nova-conductor $confRoot/nova-novncproxy
       |for svc in api scheduler conductor novncproxy; do
       |  cp $srcDir/nova.conf $confRoot/nova-$$svc/nova.conf
       |  cp $caDir/ca.crt $confRoot/nova-$$svc/ca.crt
       |done
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v nova-api-wsgi')
       |if [ -z "$$WSGI" ]; then echo "nova-api-wsgi not found in $apiImage - see the header of nova_single_node.scala" >&2; exit 1; fi
       |if ! podman run --rm --entrypoint sh $apiImage -c 'test -f /etc/nova/api-paste.ini'; then
       |  echo "/etc/nova/api-paste.ini not found in $apiImage; found:" >&2
       |  podman run --rm --entrypoint sh $apiImage -c 'find / -name api-paste.ini -path "*nova*" 2>/dev/null | head' >&2
       |  exit 1
       |fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/nova-api.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/nova-api.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $confRoot/nova-api/nova-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/nova-apache-error.log"
       |    CustomLog "$logDir/nova-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess nova-api group=nova processes=2 threads=1 user=nova
       |    WSGIProcessGroup nova-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |COMMON='{"source": "/var/lib/kolla/config_files/nova.conf", "dest": "/etc/nova/nova.conf", "owner": "nova", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/nova/ssl/ca.crt", "owner": "nova", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/nova", "owner": "nova:kolla", "recurse": true}, {"path": "/var/lib/nova", "owner": "nova:nova", "recurse": true}]'
       |cat > $confRoot/nova-api/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/nova-api.conf", "dest": "$$APACHE_CONF", "owner": "nova", "perm": "0600"}], $$PERMS}
       |JSON
       |cat > $confRoot/nova-scheduler/config.json <<JSON
       |{"command": "nova-scheduler --config-file /etc/nova/nova.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |cat > $confRoot/nova-conductor/config.json <<JSON
       |{"command": "nova-conductor --config-file /etc/nova/nova.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |cat > $confRoot/nova-novncproxy/config.json <<JSON
       |{"command": "nova-novncproxy --config-file /etc/nova/nova.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |echo "kolla config written under $confRoot"""".stripMargin

  // nova-manage runs in a one-off container with nova.conf bind-mounted
  // (entrypoint overridden, so kolla_set_configs does not run). Order matters:
  // the API database, then cell0 (the database for instances that never got a
  // host; `map_cell0` derives `nova_cell0` from the [database] connection), then
  // cell1 (takes transport_url and the [database] connection from nova.conf),
  // then the main schema. Each mapping step is skipped when it already exists.
  private val dbSyncScript =
    s"""set -e
       |NM="podman run --rm --network host --user root --entrypoint nova-manage -v $srcDir/nova.conf:/etc/nova/nova.conf:ro,z -v $logDir:/var/log/kolla/nova:z $apiImage"
       |$$NM api_db sync
       |if ! $$NM cell_v2 list_cells | grep -qw cell0; then $$NM cell_v2 map_cell0; fi
       |if ! $$NM cell_v2 list_cells | grep -qw cell1; then $$NM cell_v2 create_cell --name cell1 --verbose; fi
       |$$NM db sync
       |$$NM cell_v2 list_cells
       |echo "nova databases synced; cell0 and cell1 mapped"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string
  // filled in with replace(). Subcommands:
  //   register | unregister | catalog | services | services-up |
  //   flavor-list | flavor-create | flavor-delete <id>
  // (a flavor is stored in the API database, so creating and deleting one proves
  // the whole path API -> Keystone -> database; services-up proves the scheduler
  // and conductor reach the API through RabbitMQ and the database.)
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
NOVA = '@@NOVA@@'
ADMIN_PW = '@@ADMINPW@@'
NOVA_PW = '@@NOVAPW@@'
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


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = find(token, '/projects?name=service', 'projects')
    if project is None:
        s, p, _ = call('POST', KS + '/projects', {'project': {'name': 'service', 'domain_id': 'default'}}, token)
        if s != 201:
            die('creating project service failed: HTTP %s %s' % (s, p))
        project = p['project']
    user = find(token, '/users?name=nova', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'nova', 'domain_id': 'default', 'password': NOVA_PW}}, token)
        if s != 201:
            die('creating user nova failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': NOVA_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to nova failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=compute', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'nova', 'type': 'compute'}}, token)
        if s != 201:
            die('creating service nova failed: HTTP %s %s' % (s, p))
        service = p['service']
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': NOVA, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != NOVA:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': NOVA}}, token)
    print('registered nova in keystone (project service, user nova, service type compute, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=compute', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=nova', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed nova from keystone (service compute, its endpoints, user nova)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def service_list():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', NOVA + '/os-services', None, token)
    if s != 200:
        die('service list failed: HTTP %s %s' % (s, p))
    return p.get('services', [])


def services():
    rows = service_list()
    for r in rows:
        print('service %s host=%s state=%s status=%s' % (r.get('binary'), r.get('host'), r.get('state'), r.get('status')))
    print('services: %d' % len(rows))


def services_up():
    up = set(r.get('binary') for r in service_list() if r.get('state') == 'up' and r.get('status') == 'enabled')
    missing = [b for b in ('nova-scheduler', 'nova-conductor') if b not in up]
    if missing:
        die('not up yet: ' + ', '.join(missing))
    print('nova-scheduler and nova-conductor are up')


def flavor_list():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', NOVA + '/flavors', None, token)
    if s != 200:
        die('flavor list failed: HTTP %s %s' % (s, p))
    for f in p.get('flavors', []):
        print('flavor %s name=%s' % (f.get('id'), f.get('name')))
    print('flavors: %d' % len(p.get('flavors', [])))


def flavor_create():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    body = {'flavor': {'name': 'orphera-test-%d' % int(time.time()), 'ram': 64, 'vcpus': 1, 'disk': 1}}
    s, p, _ = call('POST', NOVA + '/flavors', body, token)
    if s not in (200, 201):
        die('flavor create failed: HTTP %s %s' % (s, p))
    fid = p['flavor']['id']
    s, p, _ = call('GET', NOVA + '/flavors/' + fid, None, token)
    if s != 200:
        die('created flavor %s cannot be read back: HTTP %s %s' % (fid, s, p))
    print(fid)


def flavor_delete(fid):
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('DELETE', NOVA + '/flavors/' + fid, None, token)
    if s not in (200, 202, 204):
        die('flavor delete failed: HTTP %s %s' % (s, p))
    s, p, _ = call('GET', NOVA + '/flavors/' + fid, None, token)
    if s != 404:
        die('flavor %s still present: HTTP %s' % (fid, s))
    print('deleted ' + fid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'services':
        services()
    elif cmd == 'services-up':
        services_up()
    elif cmd == 'flavor-list':
        flavor_list()
    elif cmd == 'flavor-create':
        flavor_create()
    elif cmd == 'flavor-delete' and len(sys.argv) > 2:
        flavor_delete(sys.argv[2])
    else:
        die('usage: orphera-nova-api register | unregister | catalog | services | services-up | flavor-list | flavor-create | flavor-delete <id>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-nova-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@NOVA@@", s"http://$fqdn:$apiPort/v2.1")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@NOVAPW@@", novaPassword)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-nova-api\necho wrote /usr/local/sbin/orphera-nova-api\n"

  // The root answers its version document without a token (200, or 300 when it
  // offers several versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("nova-single-node")(
      stage("nova-databases", "tst3")
        .task(
          "create nova_api, nova and nova_cell0 and the nova user on the Galera cluster"
        )(
          Task.RunCommand(List("sh", "-c", createDatabasesScript))
        )
        .build,

      stage("nova-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      withServices(
        stage("install-nova", node)
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
          .task("pull the Nova images")(
            Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
          )
          .task("fetch Keystone's CA from its TLS handshake")(
            Task.RunCommand(
              List("sh", "-c", fetchCaScript),
              timeoutSeconds = 60
            )
          )
          .task("write nova.conf")(
            Task.RunCommand(List("sh", "-c", novaConfScript))
          )
          .task("write the Kolla config and the Apache vhost")(
            Task.RunCommand(
              List("sh", "-c", kollaConfigScript),
              timeoutSeconds = 180
            )
          )
          .task("nova-manage: api_db sync, map cell0, create cell1, db sync")(
            Task
              .RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 900)
          )
          .task("write the Nova API helper")(
            Task.RunCommand(List("sh", "-c", helperScript))
          )
          .task("register Nova in Keystone")(
            Task.RunCommand(
              List("sh", "-c", "/usr/local/sbin/orphera-nova-api register"),
              timeoutSeconds = 120
            )
          )
      ).build,

      stage("confirm-nova-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("scheduler and conductor report in through the API")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "for i in $(seq 1 24); do /usr/local/sbin/orphera-nova-api services-up && exit 0; sleep 5; done; /usr/local/sbin/orphera-nova-api services; exit 1"
            ),
            timeoutSeconds = 180
          )
        )
        .task("nova confirmed")(
          Task.Debug(
            s"Nova control plane up at http://$fqdn:$apiPort ($nodeIp): API, scheduler, conductor and noVNC proxy (:$vncPort), RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, databases nova_api/nova/nova_cell0 via $vip:3306, identity from $ksUrl. No compute node yet. Test: manifests/test_nova.sh"
          )
        )
        .build
    )
