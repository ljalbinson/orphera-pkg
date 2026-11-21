// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Placement (resource inventory and allocation service, which Nova
// needs), one container, Kolla image under podman, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster playbook of the
// same name; a second run changes nothing and restarts nothing, and `--check`
// shows what would change. Modelled on keystone_single_node.scala and
// glance_single_node.scala, which explain the conventions.
//
//   orphera apply manifests/placement_database.scala     # first: the database and user (tst3)
//   orphera apply manifests/placement_single_node.scala --check
//   orphera apply manifests/placement_single_node.scala
//
// Where it runs: tst13, next to Glance. Placement is a small stateless API
// (Apache + mod_wsgi on 8780, plain HTTP), so it shares the node rather than
// taking a VM of its own. To move it, change `node`, `fqdn` and `nodeIp` below
// (and in placement_teardown.scala); nothing else depends on the location.
//
// Prerequisites, not checked here: Galera + VIP healthy, Keystone up on tst6
// serving the CA chain (keystone_single_node.scala), the agent on the node.
// tst9's Kolla cache is optional. Ceph is not involved.
//
// How it stays idempotent (as for Glance):
//   - Keystone's CA is fetched from its TLS handshake (trust on first use, the
//     certificate only) and compared with the installed copy each run.
//   - placement.conf holds secrets, so it is a secret file. The Apache vhost
//     and config.json depend on paths read from the image, so they are
//     generated into a temporary directory and compared with the installed
//     files; the service restarts only when they differ.
//   - `placement-manage db sync` is skipped while its stamp matches and the
//     database has the `resource_providers` table (a query as the placement
//     user, through the VIP), so a database dropped since the last run is
//     synced again.
//   - The Keystone registration is skipped while the helper's `registered`
//     check (a token for the placement user, the service and three endpoint
//     URLs) passes.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object placement_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"
  private val port = 8780

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "placement"
  private val dbUser = "placement"
  private val dbPassword = Secrets.secret("placement/db")
  private val placementPassword = Secrets.secret("placement/service")

  private val apiDir = "/etc/kolla/placement-api"
  private val logDir = "/var/log/kolla/placement"
  private val stateDir = "/var/lib/orphera/placement"
  private val caDir = "/etc/kolla/placement-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The container as data: the unit file text comes from it
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined
  // after the directories it reads; a val used before its definition is null in
  // an object.
  private val placementApi = Container(
    name = ContainerName("placement-api"),
    image = Image("placement-api", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(apiDir, "/var/lib/kolla/config_files"),
      Mount(logDir, "/var/log/kolla/placement", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )
  private val apiImage = placementApi.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // placement.conf as a typed value: every section and key is checked against
  // the options the image declares (catalogues/placement-api-<tag>.json) when
  // this manifest is built. [keystone_authtoken] is open (the options of the
  // auth plugin are loaded at run time).
  private val placementCatalogue: Catalogue =
    Catalogues.load("placement-api", Set("keystone_authtoken"))

  private val placementConf: ConfFile =
    ConfFile
      .of(s"$apiDir/placement.conf", placementCatalogue)(
        ("DEFAULT", "log_dir", Value.Str("/var/log/kolla/placement")),
        ("DEFAULT", "use_stderr", Value.Bool(true)),
        ("api", "auth_strategy", Value.Str("keystone")),
        (
          "placement_database",
          "connection",
          Value.Str(
            s"mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName"
          )
        ),
        ("keystone_authtoken", "www_authenticate_uri", Value.Str(ksUrl)),
        ("keystone_authtoken", "auth_url", Value.Str(ksUrl)),
        ("keystone_authtoken", "auth_type", Value.Str("password")),
        ("keystone_authtoken", "project_domain_name", Value.Str("Default")),
        ("keystone_authtoken", "user_domain_name", Value.Str("Default")),
        ("keystone_authtoken", "project_name", Value.Str("service")),
        ("keystone_authtoken", "username", Value.Str("placement")),
        ("keystone_authtoken", "password", Value.Str(placementPassword.reveal)),
        ("keystone_authtoken", "cafile", Value.Str("/etc/placement/ssl/ca.crt"))
      )
      .fold(
        errs =>
          throw new RuntimeException(
            "placement.conf: " + errs.map(_.message).mkString("; ")
          ),
        identity
      )
  private val placementConfText: String = placementConf.render

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

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) in placementApi in placement_single_node.scala"
      )}
       |pull_retry $apiImage || exit 1
       |echo "image ready: $apiImage"""".stripMargin

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

  // The WSGI script path and the Apache flavour are read from the image. The
  // vhost and config.json are generated into `out` (the real directory, or a
  // temporary one for the guard to compare with).
  private def kollaConfigScript(out: String): String =
    s"""set -e
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
       |cat > $out/placement-api.conf <<WSGICONF
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
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [{"source": "/var/lib/kolla/config_files/placement.conf", "dest": "/etc/placement/placement.conf", "owner": "placement", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/placement/ssl/ca.crt", "owner": "placement", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/placement-api.conf", "dest": "$$APACHE_CONF", "owner": "placement", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/placement", "owner": "placement:kolla", "recurse": true}]}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  private val kollaFilesCurrent =
    s"""T=$$(mktemp -d)
       |${kollaConfigScript("$T")} >/dev/null
       |R=0
       |cmp -s $$T/placement-api.conf $apiDir/placement-api.conf && cmp -s $$T/config.json $apiDir/config.json || R=1
       |rm -rf $$T
       |exit $$R""".stripMargin

  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint placement-manage -v $apiDir/placement.conf:/etc/placement/placement.conf:ro,z -v $logDir:/var/log/kolla/placement:z $apiImage db sync
       |echo "placement database synced"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + placementConfText)

  // True when the database, queried as the placement user through the VIP, has
  // the resource_providers table. The query runs with the image's own Python
  // (the interpreter named on the first line of placement-manage), which has
  // pymysql.
  private val dbHasTables =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v placement-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |    c.cursor().execute('SELECT COUNT(*) FROM resource_providers')
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin
  // Current when the stamp matches AND the tables are there.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbHasTables""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in the other services. Plain string filled in
  // with replace(). Subcommands:
  //   register | registered | unregister | catalog | rp-list | rp-create | rp-delete <uuid>
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


def registered():
    # Exit 0 only when everything register() makes is in place: the placement
    # user can get a token in project service, and service `placement` has the
    # three endpoints at PLACEMENT. Makes no change.
    get_token('placement', PLACEMENT_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=placement', 'services')
    if service is None:
        die('no service of type placement')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != PLACEMENT:
            die('endpoint %s missing or wrong' % iface)
    print('placement is registered in keystone')


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
    elif cmd == 'registered':
        registered()
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
        die('usage: orphera-placement-api register | registered | unregister | catalog | rp-list | rp-create | rp-delete <uuid>')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@PLACEMENT@@", s"http://$fqdn:$port")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@PLACEMENTPW@@", placementPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-placement-api"

  // Placement answers its version document (HTTP 200) without a token.
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/); [ "$$CODE" = "200" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 18); do
       |  $healthy && exit 0
       |  sleep 5
       |done
       |echo "placement does not answer on http://localhost:$port - see 'journalctl -xeu ${placementApi.unitName}' and $logDir" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val unit = placementApi.unitName
    val unitService = service(unit)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Kolla placement-api image",
      pullScript,
      unless = s"podman image exists $apiImage",
      timeout = 1800
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val apiDirectory = directory(apiDir, mode = "0755")
    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)

    val conf = secretFile(
      s"$apiDir/placement.conf",
      Secret(placementConfText), // it holds the database and service passwords
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(apiDirectory).notifies(unitService)
    val kollaCa = fileCopy(
      s"$apiDir/ca.crt",
      caCert,
      mode = "0644"
    ).after(ca, apiDirectory).notifies(unitService)
    val kollaFiles = command(
      "write the Apache vhost and Kolla config.json",
      kollaConfigScript(apiDir),
      unless = kollaFilesCurrent,
      timeout = 120
    ).after(pulled, apiDirectory, logDirectory).notifies(unitService)

    val dbSync = command(
      "placement-manage db sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, conf, logDirectory, stateDirectory)

    val unitFile = file(
      placementApi.unitPath,
      placementApi.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unitService)
    val running = unitService.after(dbSync, unitFile, conf, kollaCa, kollaFiles)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and placement passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val answering = command(
      "placement answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 120
    ).after(running)
    val registered = command(
      "register Placement in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(answering, helperFile, ca)
    val listing = command(
      "list resource providers with an admin token",
      "echo 'the resource provider list failed: see the Keystone registration, placement.conf and the logs' >&2; exit 1",
      unless = s"$helperPath rp-list >/dev/null 2>&1",
      timeout = 60
    ).after(registered)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      apiDirectory,
      logDirectory,
      stateDirectory,
      conf,
      kollaCa,
      kollaFiles,
      dbSync,
      unitFile,
      running,
      helperFile,
      answering,
      registered,
      listing
    )
