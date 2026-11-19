// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Nova control plane (compute API, scheduler, conductor and the noVNC
// console proxy), Kolla images under podman, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster playbook of the
// same name; a second run changes nothing and restarts nothing, and `--check`
// shows what would change. Modelled on placement_single_node.scala and
// glance_single_node.scala, which explain the conventions. There is no
// hypervisor here: nova-compute comes later (nova_compute.scala), on other
// nodes. Until one exists the API, scheduler and conductor all work, but no
// server can be booted.
//
// Three descriptions, since one description cannot order its nodes. Apply them
// in this order:
//
//   orphera apply manifests/nova_database.scala     # nova_api, nova, nova_cell0 and the user (tst3)
//   orphera apply manifests/nova_rabbitmq.scala     # the `nova` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/nova_single_node.scala  # this one (tst14)
//
// Where it runs: tst14. Keep `node`, `fqdn` and `nodeIp` in step with
// nova_teardown.scala and test_nova.sh.
//
// Messaging: Nova gets its own vhost (`nova`) and user (`nova`) on the RabbitMQ
// that cinder_single_node.scala runs on tst10, rather than a second broker.
//
// Prerequisites, not checked here: Galera + VIP healthy, Keystone up on tst6
// serving the CA chain, RabbitMQ running on tst10 (cinder_single_node.scala),
// Placement up on tst13 (placement_single_node.scala), the agent on tst14.
// tst9's Kolla cache is optional. Ceph is not involved.
//
// How it stays idempotent (as for Glance and Placement):
//   - Keystone's CA is compared as a certificate with the installed copy each
//     run; each service's copy is refreshed (and the service restarted) when it
//     changes.
//   - nova.conf holds secrets, so it is a secret file, one per service
//     directory. The Apache vhost and the API's config.json depend on paths read
//     from the image, so they are generated into a temporary directory and
//     compared with the installed files.
//   - The three `nova-manage` steps (api_db sync, cell0 and cell1 mapping, db
//     sync) are skipped while their stamp matches and the databases are in the
//     state they leave: cell0 and cell1 are in `cell_mappings` and the
//     `instances` tables exist in `nova` and `nova_cell0` (a query as the nova
//     user, through the VIP).
//   - Nova is registered in Keystone before the units start, since the
//     scheduler and conductor authenticate as `nova` when they start. The
//     registration is skipped while the helper's `registered` check passes.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object nova_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst14"
  private val fqdn = "tst14.ljalbinson.com"
  private val nodeIp = "10.10.5.26"
  private val apiPort = 8774
  private val vncPort = 6080

  override def nodes: List[String] = List(node)

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "nova"
  private val rabbitPassword = Secrets.secret("nova/rabbit")
  private val rabbitVhost = "nova"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbUser = "nova"
  private val dbPassword = Secrets.secret("nova/db")
  private val novaPassword = Secrets.secret("nova/service")
  // Nova calls Neutron as the `neutron` service user (created by neutron_single_node.scala).
  private val neutronPassword = Secrets.secret("neutron/service")

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/nova"
  private val stateDir = "/var/lib/orphera/nova"
  private val caDir = "/etc/kolla/nova-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The four Nova containers as data: the unit file text comes from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. They
  // share one set of mounts apart from their own config directory. Defined after
  // confRoot, logDir and stateDir, which they read; a val used before its
  // definition is null in an object.
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
  private val serviceNames =
    List("nova-api", "nova-scheduler", "nova-conductor", "nova-novncproxy")
  private val novaServices: List[Container] = serviceNames.map(novaContainer)
  private val apiImage = novaServices.head.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // A fresh VM may still be running unattended-upgrades (see
  // disable_unattended_upgrades.scala); when it finishes needrestart restarts the
  // agent and kills the running task. Wait for it before touching packages.
  private val aptIdle =
    """! pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null && ! pgrep -x apt-get >/dev/null && ! pgrep -x apt >/dev/null && ! pgrep -x dpkg >/dev/null"""
  private val waitAptIdleScript =
    s"""for i in $$(seq 1 180); do
       |  if $aptIdle; then
       |    echo "apt and dpkg are idle"
       |    exit 0
       |  fi
       |  sleep 5
       |done
       |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
       |exit 1""".stripMargin

  // The option lists of the image (nova), from catalogues/. Every section and key of the
  // files below is checked against them when this manifest is built.
  private val novaCatalogue: Catalogue =
    Catalogues.load("nova", Set("service_user"))

  private val novaConf: ConfFile =
    ConfFile.text(s"$confRoot/nova-api/nova.conf", novaCatalogue)(
      ("DEFAULT", "log_dir", "/var/log/kolla/nova"),
      ("DEFAULT", "state_path", "/var/lib/nova"),
      ("DEFAULT", "use_stderr", "true"),
      ("DEFAULT", "my_ip", s"$nodeIp"),
      (
        "DEFAULT",
        "transport_url",
        s"rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost"
      ),
      ("api", "auth_strategy", "keystone"),
      (
        "api_database",
        "connection",
        s"mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/nova_api"
      ),
      (
        "database",
        "connection",
        s"mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/nova"
      ),
      ("keystone_authtoken", "www_authenticate_uri", s"$ksUrl"),
      ("keystone_authtoken", "auth_url", s"$ksUrl"),
      ("keystone_authtoken", "auth_type", "password"),
      ("keystone_authtoken", "project_domain_name", "Default"),
      ("keystone_authtoken", "user_domain_name", "Default"),
      ("keystone_authtoken", "project_name", "service"),
      ("keystone_authtoken", "username", "nova"),
      ("keystone_authtoken", "password", s"${novaPassword.reveal}"),
      ("keystone_authtoken", "cafile", "/etc/nova/ssl/ca.crt"),
      ("service_user", "send_service_user_token", "true"),
      ("service_user", "auth_url", s"$ksUrl"),
      ("service_user", "auth_type", "password"),
      ("service_user", "project_domain_name", "Default"),
      ("service_user", "user_domain_name", "Default"),
      ("service_user", "project_name", "service"),
      ("service_user", "username", "nova"),
      ("service_user", "password", s"${novaPassword.reveal}"),
      ("service_user", "cafile", "/etc/nova/ssl/ca.crt"),
      ("placement", "auth_url", s"$ksUrl"),
      ("placement", "auth_type", "password"),
      ("placement", "project_domain_name", "Default"),
      ("placement", "user_domain_name", "Default"),
      ("placement", "project_name", "service"),
      ("placement", "username", "nova"),
      ("placement", "password", s"${novaPassword.reveal}"),
      ("placement", "region_name", "RegionOne"),
      ("placement", "valid_interfaces", "internal"),
      ("placement", "cafile", "/etc/nova/ssl/ca.crt"),
      ("glance", "valid_interfaces", "internal"),
      ("glance", "cafile", "/etc/nova/ssl/ca.crt"),
      ("neutron", "auth_url", s"$ksUrl"),
      ("neutron", "auth_type", "password"),
      ("neutron", "project_domain_name", "Default"),
      ("neutron", "user_domain_name", "Default"),
      ("neutron", "region_name", "RegionOne"),
      ("neutron", "project_name", "service"),
      ("neutron", "username", "neutron"),
      ("neutron", "password", s"${neutronPassword.reveal}"),
      ("neutron", "valid_interfaces", "internal"),
      ("neutron", "cafile", "/etc/nova/ssl/ca.crt"),
      ("cinder", "os_region_name", "RegionOne"),
      ("cinder", "cafile", "/etc/nova/ssl/ca.crt"),
      ("oslo_concurrency", "lock_path", "/var/lib/nova/tmp"),
      ("scheduler", "discover_hosts_in_cells_interval", "60"),
      ("vnc", "enabled", "true"),
      ("vnc", "novncproxy_host", "0.0.0.0"),
      ("vnc", "novncproxy_port", s"$vncPort"),
      ("vnc", "novncproxy_base_url", s"http://$fqdn:$vncPort/vnc_auto.html")
    )

  private val novaConfText: String = novaConf.render

  // The Kolla cache as a mirror of quay.io (podman registries.conf.d drop-in).
  private val registryMirror =
    s"""[[registry]]
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
       |""".stripMargin

  private val images = novaServices.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) in novaContainer in nova_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(" ")}; do podman image exists $$img || exit 1; done"""

  // Keystone's CA: the last certificate it presents (trust on first use),
  // which must be self-signed. `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem,
  // each holding the certificate only: the text s_client prints after the
  // last one (bytes read and written, session ticket) differs on every
  // handshake, and would make the comparison with the installed copy fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""

  // Current when the installed CA is the one Keystone presents now, and is in
  // the system trust store.
  private val caCurrent =
    s"""$chainTo
       |D=$$(mktemp -d)
       |chainTo $$D
       |N=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |R=1
       |if [ "$$N" -ge 2 ]; then
       |  $trustTarget
       |  cmp -s $$D/c$$N.pem $caCert && cmp -s $caCert "$$t" && R=0
       |fi
       |rm -rf $$D
       |exit $$R""".stripMargin

  private val caScript =
    s"""set -e
       |$chainTo
       |mkdir -p $caDir
       |D=$$(mktemp -d)
       |trap 'rm -rf $$D' EXIT
       |chainTo $$D
       |COUNT=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - apply keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp $$D/c$$COUNT.pem $caCert
       |openssl verify -CAfile $caCert $$D/c1.pem
       |SUBJ=$$(openssl x509 -in $caCert -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caCert -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |$trustTarget
       |cp $caCert "$$t"
       |if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; else update-ca-trust; fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  // The API runs behind Apache + mod_wsgi like Placement and Cinder; the WSGI
  // script path and the Apache flavour are read from the image. The vhost and
  // the API's config.json are generated into `out` (the real directory, or a
  // temporary one for the guard to compare with).
  private def kollaApiScript(out: String): String =
    s"""set -e
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
       |cat > $out/nova-api.conf <<WSGICONF
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
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/nova-api.conf", "dest": "$$APACHE_CONF", "owner": "nova", "perm": "0600"}], $permissions}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  // The files every service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/nova.conf", "dest": "/etc/nova/nova.conf", "owner": "nova", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/nova/ssl/ca.crt", "owner": "nova", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/nova", "owner": "nova:kolla", "recurse": true}, {"path": "/var/lib/nova", "owner": "nova:nova", "recurse": true}]"""
  private def simpleConfigJson(command: String): String =
    s"""{"command": "$command --config-file /etc/nova/nova.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin

  private val kollaApiCurrent =
    s"""T=$$(mktemp -d)
       |${kollaApiScript("$T")} >/dev/null
       |R=0
       |cmp -s $$T/nova-api.conf $confRoot/nova-api/nova-api.conf && cmp -s $$T/config.json $confRoot/nova-api/config.json || R=1
       |rm -rf $$T
       |exit $$R""".stripMargin

  // nova-manage runs in a one-off container with nova.conf bind-mounted
  // (entrypoint overridden, so kolla_set_configs does not run). Order matters:
  // the API database, then cell0 (the database for instances that never got a
  // host; `map_cell0` derives `nova_cell0` from the [database] connection), then
  // cell1 (takes transport_url and the [database] connection from nova.conf),
  // then the main schema. Each mapping step is skipped when it already exists.
  private val dbSyncScript =
    s"""set -e
       |NM="podman run --rm --network host --user root --entrypoint nova-manage -v $confRoot/nova-api/nova.conf:/etc/nova/nova.conf:ro,z -v $logDir:/var/log/kolla/nova:z $apiImage"
       |$$NM api_db sync
       |if ! $$NM cell_v2 list_cells | grep -qw cell0; then $$NM cell_v2 map_cell0; fi
       |if ! $$NM cell_v2 list_cells | grep -qw cell1; then $$NM cell_v2 create_cell --name cell1 --verbose; fi
       |$$NM db sync
       |$$NM cell_v2 list_cells
       |echo "nova databases synced; cell0 and cell1 mapped"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + novaConfText)

  // True when the databases, queried as the nova user through the VIP, are in
  // the state the steps above leave: cell0 and cell1 in cell_mappings, and the
  // instances tables in nova and nova_cell0. The query runs with the image's own
  // Python (the interpreter named on the first line of nova-manage), which has
  // pymysql.
  private val dbInState =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v nova-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='nova_api')
       |    cur = c.cursor()
       |    cur.execute("SELECT COUNT(*) FROM cell_mappings WHERE name IN ('cell0', 'cell1')")
       |    if cur.fetchone()[0] != 2:
       |        sys.exit(1)
       |    cur.execute('SELECT COUNT(*) FROM nova.instances')
       |    cur.execute('SELECT COUNT(*) FROM nova_cell0.instances')
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin
  // Current when the stamp matches AND the databases are in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in the other services. Plain string filled in
  // with replace(). Subcommands:
  //   register | registered | unregister | catalog | services | services-up |
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


def registered():
    # Exit 0 only when everything register() makes is in place: the nova user
    # can get a token in project service, and service `compute` has the three
    # endpoints at NOVA. Makes no change.
    get_token('nova', NOVA_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=compute', 'services')
    if service is None:
        die('no service of type compute')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != NOVA:
            die('endpoint %s missing or wrong' % iface)
    print('nova is registered in keystone')


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
    elif cmd == 'registered':
        registered()
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
        die('usage: orphera-nova-api register | registered | unregister | catalog | services | services-up | flavor-list | flavor-create | flavor-delete <id>')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@NOVA@@", s"http://$fqdn:$apiPort/v2.1")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@NOVAPW@@", novaPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-nova-api"

  // The root answers its version document without a token (200, or 300 when it
  // offers several versions).
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 24); do
       |  $healthy && exit 0
       |  sleep 5
       |done
       |echo "nova-api does not answer on http://localhost:$apiPort - see 'journalctl -xeu orphera-nova-api' and $logDir" >&2
       |exit 1""".stripMargin

  private val servicesUp = s"$helperPath services-up >/dev/null 2>&1"
  private val waitServicesUp =
    s"""for i in $$(seq 1 24); do $helperPath services-up && exit 0; sleep 5; done
       |$helperPath services
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val aptWait = command(
      "wait for apt and dpkg to be idle",
      waitAptIdleScript,
      unless = aptIdle,
      timeout = 960
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true).after(aptWait))
    val podman = tools(0)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Nova images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val units = serviceNames.zip(novaServices).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/nova.conf",
        Secret(novaConfText), // it holds the database, message bus and service passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = command(
        s"copy Keystone's CA to the $name config",
        s"cp $caCert $dir/ca.crt",
        unless = s"cmp -s $caCert $dir/ca.crt"
      ).after(ca, dirRes).notifies(unitService)
      val kollaJson =
        if name == "nova-api" then
          command(
            "write the Apache vhost and the API's Kolla config.json",
            kollaApiScript(dir),
            unless = kollaApiCurrent,
            timeout = 180
          ).after(pulled, dirRes, logDirectory).notifies(unitService)
        else
          file(
            s"$dir/config.json",
            simpleConfigJson(name),
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      (name, unitService, List(dirRes, conf, kollaCa, kollaJson, unitFile), conf, kollaCa, kollaJson, unitFile)
    }
    val apiConf = units.head._4

    val dbSync = command(
      "nova-manage: api_db sync, map cell0, create cell1, db sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 900
    ).after(pulled, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and nova passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start: the scheduler and conductor
    // authenticate as `nova` when they start.
    val registered = command(
      "register Nova in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val running = units.map { (_, unitService, _, conf, kollaCa, kollaJson, unitFile) =>
      unitService.after(dbSync, registered, unitFile, conf, kollaCa, kollaJson)
    }

    val answering = command(
      "nova-api answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 180
    ).after(running*)
    val reporting = command(
      "scheduler and conductor report in through the API",
      waitServicesUp,
      unless = servicesUp,
      timeout = 180
    ).after(answering, running*)

    tools ++ List(
      aptWait,
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory
    ) ++ units.flatMap(_._3) ++ List(dbSync, helperFile, registered) ++
      running ++ List(answering, reporting)
