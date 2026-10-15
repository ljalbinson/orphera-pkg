// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Placement (resource inventory and allocation service, which
// Nova needs), one container, Kolla image under podman. Modelled on
// cinder_single_node.scala and glance_single_node.scala; read their headers for
// the conventions.
//
// Where it runs: tst13, next to Glance. Placement is a small stateless API
// (Apache + mod_wsgi on 8780, plain HTTP), so it shares the node rather than
// taking a VM of its own. To move it, change `node`, `fqdn` and `nodeIp`
// below (and the teardown's `node`); nothing else depends on the location.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala,
//     mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// Stages:
//   - placement-database (tst3, a Galera node): the `placement` database + user.
//   - install-placement (the node): packages, cache mirror, image pull,
//     Keystone's CA from its TLS handshake, placement.conf, Kolla config with
//     the Apache vhost, `placement-manage db sync`, the unit, then registration
//     in Keystone (user `placement` in project `service`, service `placement`,
//     three endpoints).
//   - confirm-placement-healthy (the node): the API answers, and the resource
//     provider list is readable with an admin token (empty until Nova exists).
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. Not compiled where this was written; the first run on scala0 is
// the first compile.
object placement_single_node extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"
  private val port = 8780

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbName = "placement"
  private val dbUser = "placement"
  private val dbPassword = "orphera-test-placement-db-password"
  private val placementPassword = "orphera-test-placement-service-password"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/placement"
  private val logDir = "/var/log/kolla/placement"
  private val stateDir = "/var/lib/orphera/placement"

  // The container as data: the unit file text and the start task come from it
  // (orchestrator/.../kolla/Container.scala), and are checked by ContainerCheck.
  // Defined after confRoot and logDir, which it reads; a val used before its
  // definition is null in an object.
  private val placementApi = Container(
    name = "placement-api",
    image = Image("placement-api", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(s"$confRoot/placement-api", "/var/lib/kolla/config_files"),
      Mount(logDir, "/var/log/kolla/placement", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )
  private val apiImage = placementApi.image.ref
  private val caDir = "/etc/kolla/placement-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

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
    s"""${Pull.shellFunction("the Image(...) in placementApi in placement_single_node.scala")}
       |pull_retry $apiImage || exit 1
       |echo "image ready"""".stripMargin

  // Same method as cinder_single_node.scala / glance_single_node.scala: the last
  // certificate Keystone presents is its CA; trust on first use.
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

  private val placementConfScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |cat > $srcDir/placement.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/placement
       |use_stderr = true
       |
       |[api]
       |auth_strategy = keystone
       |
       |[placement_database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/$dbName
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = placement
       |password = $placementPassword
       |cafile = /etc/placement/ssl/ca.crt
       |CONF
       |chmod 640 $srcDir/placement.conf
       |echo "placement.conf written"""".stripMargin

  // The WSGI script path and the Apache flavour are read from the image, as in
  // cinder_single_node.scala.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/placement-api
       |cp $srcDir/placement.conf $confRoot/placement-api/placement.conf
       |cp $caDir/ca.crt $confRoot/placement-api/ca.crt
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v placement-api')
       |if [ -z "$$WSGI" ]; then echo "placement-api not found in $apiImage" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/placement-api.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/placement-api.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $confRoot/placement-api/placement-api.conf <<WSGICONF
       |Listen 0.0.0.0:$port
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$port>
       |    ErrorLog "$logDir/placement-apache-error.log"
       |    CustomLog "$logDir/placement-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess placement-api group=placement processes=2 threads=1 user=placement
       |    WSGIProcessGroup placement-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $confRoot/placement-api/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [{"source": "/var/lib/kolla/config_files/placement.conf", "dest": "/etc/placement/placement.conf", "owner": "placement", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/placement/ssl/ca.crt", "owner": "placement", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/placement-api.conf", "dest": "$$APACHE_CONF", "owner": "placement", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/placement", "owner": "placement:kolla", "recurse": true}]}
       |JSON
       |echo "kolla config written under $confRoot/placement-api"""".stripMargin

  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint placement-manage -v $srcDir/placement.conf:/etc/placement/placement.conf:ro,z -v $logDir:/var/log/kolla/placement:z $apiImage db sync
       |echo "placement database synced"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string
  // filled in with replace(). Subcommands:
  //   register | unregister | catalog | rp-list | rp-create | rp-delete <uuid>
  // (rp = resource provider, the object Placement manages; the test makes and
  // deletes one.)
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
PLACEMENT = '@@PLACEMENT@@'
ADMIN_PW = '@@ADMINPW@@'
PLACEMENT_PW = '@@PLACEMENTPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
MICROVERSION = {'OpenStack-API-Version': 'placement 1.30'}


def call(method, url, body=None, token=None, extra=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    for k, v in (extra or {}).items():
        req.add_header(k, v)
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
    user = find(token, '/users?name=placement', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'placement', 'domain_id': 'default', 'password': PLACEMENT_PW}}, token)
        if s != 201:
            die('creating user placement failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': PLACEMENT_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to placement failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=placement', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'placement', 'type': 'placement'}}, token)
        if s != 201:
            die('creating service placement failed: HTTP %s %s' % (s, p))
        service = p['service']
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': PLACEMENT, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != PLACEMENT:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': PLACEMENT}}, token)
    print('registered placement in keystone (project service, user placement, service placement, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=placement', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=placement', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed placement from keystone (service placement, its endpoints, user placement)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def rp_list():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', PLACEMENT + '/resource_providers', None, token, MICROVERSION)
    if s != 200:
        die('resource provider list failed: HTTP %s %s' % (s, p))
    for rp in p.get('resource_providers', []):
        print('resource provider %s name=%s' % (rp.get('uuid'), rp.get('name')))
    print('resource providers: %d' % len(p.get('resource_providers', [])))


def rp_create():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, h = call('POST', PLACEMENT + '/resource_providers', {'name': 'orphera-test-%d' % int(time.time())}, token, MICROVERSION)
    if s not in (200, 201):
        die('resource provider create failed: HTTP %s %s' % (s, p))
    uuid = p.get('uuid') or (h.get('Location') or '').rstrip('/').split('/')[-1]
    if not uuid:
        die('resource provider created but no uuid returned: %s' % p)
    s, p, _ = call('GET', PLACEMENT + '/resource_providers/' + uuid, None, token, MICROVERSION)
    if s != 200:
        die('created provider %s cannot be read back: HTTP %s %s' % (uuid, s, p))
    print(uuid)


def rp_delete(uuid):
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('DELETE', PLACEMENT + '/resource_providers/' + uuid, None, token, MICROVERSION)
    if s not in (200, 204):
        die('resource provider delete failed: HTTP %s %s' % (s, p))
    s, p, _ = call('GET', PLACEMENT + '/resource_providers/' + uuid, None, token, MICROVERSION)
    if s != 404:
        die('resource provider %s still present: HTTP %s' % (uuid, s))
    print('deleted ' + uuid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'rp-list':
        rp_list()
    elif cmd == 'rp-create':
        rp_create()
    elif cmd == 'rp-delete' and len(sys.argv) > 2:
        rp_delete(sys.argv[2])
    else:
        die('usage: orphera-placement-api register | unregister | catalog | rp-list | rp-create | rp-delete <uuid>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-placement-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@PLACEMENT@@", s"http://$fqdn:$port")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@PLACEMENTPW@@", placementPassword)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-placement-api\necho wrote /usr/local/sbin/orphera-placement-api\n"

  // Placement answers its version document (HTTP 200) without a token.
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/)
       |[ "$$CODE" = "200" ]""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("placement-single-node")(
      stage("placement-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-placement", node)
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Placement image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write placement.conf")(
          Task.RunCommand(List("sh", "-c", placementConfScript))
        )
        .task("write the Kolla config and the Apache vhost")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 120
          )
        )
        .task("placement-manage db sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
        .task("write the Placement API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task(s"write ${placementApi.unitName}")(placementApi.writeUnit)
        .task(s"start ${placementApi.unitName}")(placementApi.start)
        .task("register Placement in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-placement-api register"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-placement-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 90
          )
        )
        .task("list resource providers with an admin token")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-placement-api rp-list"),
            timeoutSeconds = 60
          )
        )
        .task("placement confirmed")(
          Task.Debug(
            s"Placement up at http://$fqdn:$port ($nodeIp): placement-api, database via $vip:3306, identity from $ksUrl. Test: manifests/test_placement.sh"
          )
        )
        .build
    )
