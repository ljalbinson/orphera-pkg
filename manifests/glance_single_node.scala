// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Glance (image service), one node (tst13), Kolla image under podman,
// images stored in the existing Ceph cluster as RBD images, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts
// nothing, and `--check` shows what would change. Modelled on
// keystone_single_node.scala, which explains the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them
// in this order:
//
//   orphera apply manifests/glance_database.scala     # the database and user (tst3)
//   orphera apply manifests/glance_ceph.scala         # the `images` pool and client.glance (tst0)
//   orphera apply manifests/glance_single_node.scala  # this one (tst13)
//
// Prerequisites, not checked here: Galera + VIP healthy, Ceph healthy with
// OSDs, Keystone up on tst6 serving the CA chain (keystone_single_node.scala),
// the agent on tst13. tst9's Kolla cache is optional.
//
// What it builds on tst13: one container, glance-api (Kolla image, plain HTTP
// on 9292, host networking), run by systemd as orphera-glance-api.service, and
// the registration in Keystone (user `glance` in project `service`, service
// `image`, three endpoints). No message bus is needed.
//
// How it stays idempotent:
//   - Keystone's CA is fetched from its TLS handshake (trust on first use) and
//     compared with the installed copy each run, so a Keystone rebuilt with a
//     new CA is trusted again and Glance restarts.
//   - glance-api.conf, the Ceph keyring and the helper hold secrets, so they
//     are secret files (never shown in a plan or diff). The Kolla files are
//     written straight into /etc/kolla/glance-api.
//   - db_sync (and the stamp of what it was run with) is skipped while the stamp
//     matches and `glance-manage db check` says the schema is current, so a
//     database dropped since the last run is synced again.
//   - The Keystone registration is skipped while the helper's `registered`
//     check (a real token for the glance user, the service and three endpoint
//     URLs) passes.
//
// Ceph: Glance's RBD store keeps each image as an RBD image in pool `images`
// with a protected snapshot, so Cinder can later create volumes from it by
// copy-on-write clone (show_image_direct_url is on for that).
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object glance_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "glance"
  private val dbUser = "glance"
  private val dbPassword = Secrets.secret("glance/db")
  private val glancePassword = Secrets.secret("glance/service")

  // Keep in step with glance_ceph.scala.
  private val cephKey = Secrets.secret("glance/ceph-key")
  private val cephPool = "images"
  private val cephMons = List("tst0", "tst1", "tst2")

  private val confRoot = "/etc/kolla"
  private val apiDir = "/etc/kolla/glance-api"
  private val logDir = "/var/log/kolla/glance"
  private val stateDir = "/var/lib/orphera/glance"
  private val caDir = "/etc/kolla/glance-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The container as data: the unit file text comes from it
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined
  // after the directories it reads; a val used before its definition is null in
  // an object.
  private val glanceApi = Container(
    name = ContainerName("glance-api"),
    image = Image("glance-api", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(apiDir, "/var/lib/kolla/config_files"),
      Mount(logDir, "/var/log/kolla/glance", Access.ReadWrite),
      Mount(stateDir, "/var/lib/glance", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )
  private val apiImage = glanceApi.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // The option lists of the image (glance), from catalogues/. Every section and
  // key of the file below is checked against them when this manifest is built.
  private val glanceCatalogue: Catalogue =
    Catalogues.load("glance", Set("ceph"))

  // Multi-store layout (enabled_backends), the form current Glance expects; the
  // store is called `ceph` and is the default.
  private val glanceApiConf: ConfFile =
    ConfFile.text(s"$apiDir/glance-api.conf", glanceCatalogue)(
      ("DEFAULT", "log_dir", "/var/log/kolla/glance"),
      ("DEFAULT", "use_stderr", "true"),
      ("DEFAULT", "bind_host", "0.0.0.0"),
      ("DEFAULT", "bind_port", "9292"),
      ("DEFAULT", "show_image_direct_url", "true"),
      ("DEFAULT", "enabled_backends", "ceph:rbd"),
      (
        "database",
        "connection",
        s"mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName"
      ),
      ("keystone_authtoken", "www_authenticate_uri", s"$ksUrl"),
      ("keystone_authtoken", "auth_url", s"$ksUrl"),
      ("keystone_authtoken", "auth_type", "password"),
      ("keystone_authtoken", "project_domain_name", "Default"),
      ("keystone_authtoken", "user_domain_name", "Default"),
      ("keystone_authtoken", "project_name", "service"),
      ("keystone_authtoken", "username", "glance"),
      ("keystone_authtoken", "password", s"${glancePassword.reveal}"),
      ("keystone_authtoken", "cafile", "/etc/glance/ssl/ca.crt"),
      ("paste_deploy", "flavor", "keystone"),
      ("glance_store", "default_backend", "ceph"),
      ("ceph", "rbd_store_pool", s"$cephPool"),
      ("ceph", "rbd_store_user", "glance"),
      ("ceph", "rbd_store_ceph_conf", "/etc/ceph/ceph.conf"),
      ("ceph", "rbd_store_chunk_size", "8"),
      ("ceph", "store_description", "Ceph RBD images")
    )
  private val glanceApiConfText: String = glanceApiConf.render

  // The monitors' addresses come from the inventory (`cluster_ip`), as the
  // playbook's {{nodes.tst0.cluster_ip}} did.
  private val cephConf: String =
    val ips = cephMons.map { n =>
      Inventory.all
        .find(_.name == n)
        .flatMap(_.vars.get("cluster_ip"))
        .getOrElse(sys.error(s"inventory: node $n has no cluster_ip"))
    }
    s"""[global]
       |mon_host = ${ips.mkString(",")}
       |""".stripMargin

  private val cephKeyring =
    s"""[client.glance]
       |    key = ${cephKey.reveal}
       |""".stripMargin

  // What Kolla copies into the container, and where.
  private val configJson =
    """{"command": "glance-api", "config_files": [{"source": "/var/lib/kolla/config_files/glance-api.conf", "dest": "/etc/glance/glance-api.conf", "owner": "glance", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/glance/ssl/ca.crt", "owner": "glance", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "glance", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.glance.keyring", "dest": "/etc/ceph/ceph.client.glance.keyring", "owner": "glance", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/glance", "owner": "glance:kolla", "recurse": true}, {"path": "/var/lib/glance", "owner": "glance:glance", "recurse": true}]}
      |""".stripMargin

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
        "the Image(...) in glanceApi in glance_single_node.scala"
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

  // The kolla copy of the CA is a separate file (the container mounts apiDir).
  // Mounts read-only; glance-manage reads the config only.
  private val glanceManage =
    s"podman run --rm --network host --user root --entrypoint glance-manage -v $apiDir/glance-api.conf:/etc/glance/glance-api.conf:ro,z -v $logDir:/var/log/kolla/glance:z -v $stateDir:/var/lib/glance:z $apiImage"

  private val dbSyncScript =
    s"""set -e
       |$glanceManage db_sync
       |echo "glance database synced"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + glanceApiConfText)
  // Current when the stamp matches AND the database schema is current, so a
  // database dropped or replaced since the stamp was written is synced again.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] && $glanceManage db check >/dev/null 2>&1"""
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in cinder_single_node.scala. Plain string filled
  // in with replace(). Subcommands:
  //   register | registered | unregister | catalog | image-list | image-create | image-delete <id>
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
GLANCE = '@@GLANCE@@'
ADMIN_PW = '@@ADMINPW@@'
GLANCE_PW = '@@GLANCEPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')


def call(method, url, body=None, token=None, raw=None, ctype='application/json'):
    if raw is not None:
        data = raw
    else:
        data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', ctype)
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=120)
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
    return headers['X-Subject-Token']


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def register():
    token = get_token('admin', ADMIN_PW, 'admin')
    project = find(token, '/projects?name=service', 'projects')
    if project is None:
        s, p, _ = call('POST', KS + '/projects', {'project': {'name': 'service', 'domain_id': 'default'}}, token)
        if s != 201:
            die('creating project service failed: HTTP %s %s' % (s, p))
        project = p['project']
    user = find(token, '/users?name=glance', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'glance', 'domain_id': 'default', 'password': GLANCE_PW}}, token)
        if s != 201:
            die('creating user glance failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': GLANCE_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to glance failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=image', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'glance', 'type': 'image'}}, token)
        if s != 201:
            die('creating service image failed: HTTP %s %s' % (s, p))
        service = p['service']
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': GLANCE, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != GLANCE:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': GLANCE}}, token)
    print('registered glance in keystone (project service, user glance, service image, 3 endpoints)')


def registered():
    # Exit 0 only when everything register() makes is in place: the glance user
    # can get a token in project service, and service `image` has the three
    # endpoints at GLANCE. Makes no change.
    get_token('glance', GLANCE_PW, 'service')
    token = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=image', 'services')
    if service is None:
        die('no service of type image')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != GLANCE:
            die('endpoint %s missing or wrong' % iface)
    print('glance is registered in keystone')


def unregister():
    token = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=image', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=glance', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed glance from keystone (service image, its endpoints, user glance)')


def catalog():
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'admin', 'domain': {'name': 'Default'}, 'password': ADMIN_PW}}}, 'scope': {'project': {'name': 'admin', 'domain': {'name': 'Default'}}}}}
    s, p, _ = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('token request failed: HTTP %s %s' % (s, p))
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def admin_token():
    return get_token('admin', ADMIN_PW, 'admin')


def image_list():
    token = admin_token()
    s, p, _ = call('GET', GLANCE + '/v2/images', None, token)
    if s != 200:
        die('image list failed: HTTP %s %s' % (s, p))
    for img in p.get('images', []):
        print('image %s name=%s status=%s' % (img.get('id'), img.get('name'), img.get('status')))
    print('images: %d' % len(p.get('images', [])))


def image_create():
    token = admin_token()
    s, p, _ = call('POST', GLANCE + '/v2/images', {'name': 'orphera-test', 'disk_format': 'raw', 'container_format': 'bare'}, token)
    if s != 201:
        die('image create failed: HTTP %s %s' % (s, p))
    iid = p['id']
    s, p, _ = call('PUT', GLANCE + '/v2/images/%s/file' % iid, token=token, raw=b'orphera glance test image\n' * 40000, ctype='application/octet-stream')
    if s not in (200, 204):
        die('image upload failed: HTTP %s %s' % (s, p))
    status = None
    for _ in range(30):
        s, p, _ = call('GET', GLANCE + '/v2/images/' + iid, None, token)
        status = p.get('status')
        if status == 'active':
            print(iid)
            return
        if status in ('killed', 'deleted'):
            die('image %s went to %s' % (iid, status))
        time.sleep(2)
    die('image %s not active after 60s (last status %s)' % (iid, status))


def image_delete(iid):
    token = admin_token()
    s, p, _ = call('DELETE', GLANCE + '/v2/images/' + iid, None, token)
    if s not in (200, 202, 204):
        die('image delete failed: HTTP %s %s' % (s, p))
    for _ in range(30):
        s, p, _ = call('GET', GLANCE + '/v2/images/' + iid, None, token)
        if s == 404:
            print('deleted ' + iid)
            return
        time.sleep(2)
    die('image %s still present after 60s' % iid)


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
    elif cmd == 'image-list':
        image_list()
    elif cmd == 'image-create':
        image_create()
    elif cmd == 'image-delete' and len(sys.argv) > 2:
        image_delete(sys.argv[2])
    else:
        die('usage: orphera-glance-api register | registered | unregister | catalog | image-list | image-create | image-delete <id>')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@GLANCE@@", s"http://$fqdn:9292")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@GLANCEPW@@", glancePassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-glance-api"

  // Glance answers with a version document (HTTP 200 or 300) once it is up.
  private val healthy =
    """CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9292/); [ "$CODE" = "200" ] || [ "$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 18); do
       |  $healthy && exit 0
       |  sleep 5
       |done
       |echo "glance does not answer on http://localhost:9292 - see 'journalctl -xeu ${glanceApi.unitName}' and $logDir" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val unit = glanceApi.unitName
    val unitService = service(unit)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Kolla glance-api image",
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
      s"$apiDir/glance-api.conf",
      Secret(glanceApiConfText), // it holds the database and service passwords
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(apiDirectory).notifies(unitService)
    val kollaCa = fileCopy(
      s"$apiDir/ca.crt",
      caCert,
      mode = "0644"
    ).after(ca, apiDirectory).notifies(unitService)
    val cephConfFile = file(
      s"$apiDir/ceph.conf",
      cephConf,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(apiDirectory).notifies(unitService)
    val keyring = secretFile(
      s"$apiDir/ceph.client.glance.keyring",
      Secret(cephKeyring),
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(apiDirectory).notifies(unitService)
    val configFile = file(
      s"$apiDir/config.json",
      configJson,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(apiDirectory).notifies(unitService)

    val dbSync = command(
      "glance-manage db_sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, conf, logDirectory, stateDirectory)

    val unitFile = file(
      glanceApi.unitPath,
      glanceApi.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unitService)
    val running = unitService.after(
      dbSync,
      unitFile,
      conf,
      kollaCa,
      cephConfFile,
      keyring,
      configFile
    )

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and glance passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val answering = command(
      "glance answers on 9292",
      waitHealthy,
      unless = healthy,
      timeout = 120
    ).after(running)
    val registered = command(
      "register Glance in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(answering, helperFile, ca)
    val listing = command(
      "list images through Keystone-authenticated access",
      s"echo 'the image list failed: see the Keystone registration, glance-api.conf and the logs' >&2; exit 1",
      unless = s"$helperPath image-list >/dev/null 2>&1",
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
      cephConfFile,
      keyring,
      configFile,
      dbSync,
      unitFile,
      running,
      helperFile,
      answering,
      registered,
      listing
    )
