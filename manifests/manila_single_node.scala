// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Manila (shared file systems: NFS shares on demand), Kolla images under podman, with CephFS exported
// over NFS by NFS-Ganesha as the share backend, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It replaced the cluster playbook of the same name; a second run changes nothing and
// restarts nothing, and `--check` shows what would change. Modelled on cinder_single_node.scala and
// trove_single_node.scala, which explain the conventions.
//
// Four descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/manila_database.scala      # the manila database and user (tst3)
//   orphera apply manifests/manila_rabbitmq.scala      # the `manila` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/manila_ceph.scala          # CephFS, the NFS cluster and client.manila (tst0)
//   orphera apply manifests/manila_single_node.scala   # this one (tst22)
//
// Where it runs: tst22 (10.10.5.34), a VM provisioned from config/tst22.yaml. To move it, change `node`, `fqdn`
// and `nodeIp` below (and the teardown's `node`); nothing else depends on the location.
//
// What it builds:
//   - manila-api        the REST API, port 8786, plain HTTP like the other APIs here. Behind Apache + mod_wsgi
//                       when the image has manila-wsgi, otherwise the image's own manila-api server
//   - manila-scheduler  picks the backend for each new share
//   - manila-share      the backend driver: asks Ceph (through the mgr) to make a CephFS subvolume and an NFS
//                       export for every share
// The data service (share migration and backups) is left out.
//
// What manila_ceph.scala does on the Ceph cluster (on tst0, through `cephadm shell`): the CephFS filesystem
// `cephfs`, the NFS cluster `manila` (one NFS-Ganesha daemon on the Ceph host with the address `nfsIp`; shares
// are exported from there, port 2049) and the cephx user `client.manila` with caps mon "allow r" and mgr
// "allow rw", with the key of the secret `manila/ceph-key` (see that file for how the key is made). Clients of a
// share need only NFS (v4.1) and a route to nfsIp; they need no Ceph client and no key. Access is by client IP
// (access rules of type `ip`).
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `manila` in project `service`
// with the admin role; the service `manilav2` of type `sharev2` with the endpoint http://<fqdn>:8786/v2. The
// share type `default_share_type` (driver_handles_share_servers False) is made by the helper's `share-type`.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (rabbitmq_single_node.scala)
//   - Ceph healthy with OSDs and the mgr (cephadm_*.scala); tst0 has cephadm
//   - the agent on tst22; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Manila images (manila-api, manila-scheduler, manila-share) are in tst21's build list, but need
//     `orphera-kolla-build sources refresh` and a build of those three the first time
//
// How it stays idempotent (as for Trove and Cinder):
//   - Keystone's CA is compared as a certificate with the installed copy each run; the copies in the three
//     Kolla directories are compared with it, and a service restarts when its copy changes.
//   - Each service has its own `manila.conf` (a secret file; it holds the database, message bus and service
//     passwords), CA copy and `config.json` in its Kolla directory. The API's Apache vhost and config.json depend
//     on the image (manila-wsgi, the Apache flavour); they are generated into a temporary directory and compared
//     with the installed ones.
//   - The Ceph client files of the share service (ceph.conf with the monitors' addresses from inventory.yaml,
//     and the keyring) are declared files; a new key restarts manila-share.
//   - `manila-manage db sync` is skipped while its stamp matches and the `shares` table exists (a query as the
//     manila user, through the VIP).
//   - Manila is registered in Keystone before the units start, skipped while the helper's `registered` passes.
//   - The last steps (the scheduler and share service report up, the share type exists, and a share is made,
//     mounted over NFS, written, read back and deleted) each run only while they do not hold; the share round
//     trip is its own guard.
//
// Facts about Manila this was written from, to check if a run fails: the CephFS driver is
// manila.share.drivers.cephfs.driver.CephFSDriver with cephfs_protocol_helper_type = NFS and
// cephfs_nfs_cluster_id (it drives `ceph nfs export` through the mgr, so the cephx user needs mgr caps);
// cephfs_ganesha_server_ip and cephfs_ganesha_export_ips are given too and ignored if unknown; the API
// microversion used by the helper is 2.45 (access rules list); the share image has python3 `rados` and
// `cephfs` (a step checks, and stops with a message if not); the Kolla images are the 2025.1 ubuntu-noble ones;
// manila.conf is plain text, a typed version needs a catalogue of the image's options
// (manifests/dump_oslo_options.sh manila-api).
//
// Test-only credentials come from the secret store (manila/db, manila/service, manila/rabbit, manila/ceph-key),
// made on first use.
object manila_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst22"
  private val fqdn = "tst22.ljalbinson.com"
  private val nodeIp = "10.10.5.34"
  private val apiPort = 8786

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "manila"
  private val rabbitPassword = Secrets.secret("manila/rabbit")
  private val rabbitVhost = "manila"

  // The Ceph hosts and the host that runs NFS-Ganesha; its address is what clients mount. Keep in step with
  // manila_ceph.scala and inventory.yaml.
  private val cephMons = List("tst0", "tst1", "tst2")
  private val nfsIp = "10.10.5.12"
  private val cephFs = "cephfs"
  private val nfsCluster = "manila"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "manila"
  private val dbUser = "manila"
  private val dbPassword = Secrets.secret("manila/db")
  private val manilaPassword = Secrets.secret("manila/service")

  // The key of client.manila: the secret `manila/ceph-key` as it is when it is a whole cephx key, otherwise the
  // key derived from it (see manila_ceph.scala, which must make the same one).
  private val cephKey: String =
    val stored = Secrets.secret("manila/ceph-key").reveal
    if CephKeys.isKey(stored) then stored
    else
      val keyBytes = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .take(16)
      val header = java.nio.ByteBuffer
        .allocate(12)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putShort(1.toShort)
        .putInt(1700000000)
        .putInt(0)
        .putShort(16.toShort)
        .array()
      val derived =
        java.util.Base64.getEncoder.encodeToString(header ++ keyBytes)
      Secrets.register(derived)
      derived

  private val confRoot = "/etc/kolla"
  private val caDir = "/etc/kolla/manila-ca"
  private val caCert = s"$caDir/ca.crt"
  private val logDir = "/var/log/kolla/manila"
  private val stateDir = "/var/lib/orphera/manila"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val helperPath = "/usr/local/sbin/orphera-manila-api"

  private def kolla(service: String): Image =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry)

  // The scheduler and the share service first, the API last.
  private val serviceNames =
    List("manila-scheduler", "manila-share", "manila-api")

  // The three containers as data: the unit file text and the start task come from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined after confRoot, logDir and
  // stateDir, which they read; a val used before its definition is null in an object.
  private def manilaContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = kolla(service),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/manila", Access.ReadWrite),
        Mount(stateDir, "/var/lib/manila", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The API image may set a non-root USER, which cannot open Apache's log files; start it as root.
      user = if service == "manila-api" then Some("root") else None
    )
  private val containers: List[Container] = serviceNames.map(manilaContainer)

  private val apiImage = kolla("manila-api").ref
  private val shareImage = kolla("manila-share").ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "nfs-common"
    )

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

  private val images = containers.map(_.image.ref)

  // The share service imports rados and cephfs; without them it starts and then fails with an import error.
  private val shareImageHasCeph =
    s"""podman run --rm --entrypoint python3 $shareImage -c 'import rados, cephfs' >/dev/null 2>&1"""
  private val shareImageCheckScript =
    s"""if ! podman run --rm --entrypoint python3 $shareImage -c 'import rados, cephfs' 2>/tmp/manila-import.err; then
       |  cat /tmp/manila-import.err >&2
       |  echo "$shareImage has no python3 rados/cephfs bindings - the CephFS driver cannot run" >&2
       |  exit 1
       |fi
       |echo "the share image has the Ceph Python bindings"""".stripMargin

  // ---- configuration ---------------------------------------------------

  // manila.conf, plain text (see the header). The same file goes to all three containers.
  private val manilaConf: String =
    s"""# Managed by Orphera (manila_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/manila
       |use_stderr = true
       |auth_strategy = keystone
       |my_ip = $nodeIp
       |osapi_share_listen = 0.0.0.0
       |osapi_share_listen_port = $apiPort
       |state_path = /var/lib/manila
       |api_paste_config = /etc/manila/api-paste.ini
       |rootwrap_config = /etc/manila/rootwrap.conf
       |enabled_share_protocols = NFS
       |enabled_share_backends = cephfsnfs
       |default_share_type = default_share_type
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = manila
       |password = ${manilaPassword.reveal}
       |cafile = /etc/manila/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/manila/tmp
       |
       |[cephfsnfs]
       |driver_handles_share_servers = False
       |share_backend_name = CEPHFSNFS
       |share_driver = manila.share.drivers.cephfs.driver.CephFSDriver
       |cephfs_protocol_helper_type = NFS
       |cephfs_conf_path = /etc/ceph/ceph.conf
       |cephfs_auth_id = manila
       |cephfs_cluster_name = ceph
       |cephfs_filesystem_name = $cephFs
       |cephfs_nfs_cluster_id = $nfsCluster
       |cephfs_ganesha_server_ip = $nfsIp
       |cephfs_ganesha_export_ips = $nfsIp
       |""".stripMargin

  // ceph.conf needs only the monitors for a client; their addresses come from the inventory (`cluster_ip`), as
  // the playbook's {{nodes.tst0.cluster_ip}} did.
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
    s"""[client.manila]
       |    key = $cephKey
       |""".stripMargin

  // The files every service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/manila.conf", "dest": "/etc/manila/manila.conf", "owner": "manila", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/manila/ssl/ca.crt", "owner": "manila", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/manila", "owner": "manila:kolla", "recurse": true}, {"path": "/var/lib/manila", "owner": "manila:manila", "recurse": true}]"""
  private val schedulerConfigJson =
    s"""{"command": "manila-scheduler --config-file /etc/manila/manila.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin
  private val shareConfigJson =
    s"""{"command": "manila-share --config-file /etc/manila/manila.conf", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "manila", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.manila.keyring", "dest": "/etc/ceph/ceph.client.manila.keyring", "owner": "manila", "perm": "0600"}], $permissions}
       |""".stripMargin

  // The API runs behind Apache + mod_wsgi when the image has manila-wsgi (the WSGI script path and the Apache
  // flavour are read from the image); an older image without it runs its own manila-api server. The vhost and
  // the API's config.json are generated into `out` (the real directory, or a temporary one for the guard to
  // compare with).
  private def kollaApiScript(out: String): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v manila-wsgi' || true)
       |if [ -n "$$WSGI" ]; then
       |  if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |    APACHE_CONF=/etc/apache2/conf-enabled/manila-api.conf
       |    APACHE_CMD="apache2 -DFOREGROUND"
       |    # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |    if podman run --rm --entrypoint sh $apiImage -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |  else
       |    APACHE_CONF=/etc/httpd/conf.d/manila-api.conf
       |    APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |  fi
       |  echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |  cat > $out/manila-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/manila-apache-error.log"
       |    CustomLog "$logDir/manila-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess manila-api group=manila processes=2 threads=1 user=manila
       |    WSGIProcessGroup manila-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |  API_CMD="$$APACHE_CMD"
       |  API_EXTRA=", {\\"source\\": \\"/var/lib/kolla/config_files/manila-api.conf\\", \\"dest\\": \\"$$APACHE_CONF\\", \\"owner\\": \\"manila\\", \\"perm\\": \\"0600\\"}"
       |else
       |  echo "manila-wsgi not found in $apiImage: running the image's manila-api server"
       |  rm -f $out/manila-api.conf
       |  API_CMD="manila-api --config-file /etc/manila/manila.conf"
       |  API_EXTRA=""
       |fi
       |cat > $out/config.json <<JSON
       |{"command": "$$API_CMD", "config_files": [$commonFiles$$API_EXTRA], $permissions}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  // Current when the files the script would write are the installed ones (a vhost that the image does not
  // need must not be there).
  private val kollaApiCurrent =
    s"""T=$$(mktemp -d)
       |${kollaApiScript("$T")} >/dev/null
       |R=0
       |cmp -s $$T/config.json $confRoot/manila-api/config.json || R=1
       |if [ -f $$T/manila-api.conf ]; then cmp -s $$T/manila-api.conf $confRoot/manila-api/manila-api.conf || R=1; else [ ! -e $confRoot/manila-api/manila-api.conf ] || R=1; fi
       |rm -rf $$T
       |exit $$R""".stripMargin

  // manila-manage runs in a one-off container with manila.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run). `:z` relabels for SELinux hosts and is ignored elsewhere.
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint manila-manage -v $confRoot/manila-api/manila.conf:/etc/manila/manila.conf:ro,z -v $logDir:/var/log/kolla/manila:z $apiImage --config-file /etc/manila/manila.conf db sync
       |echo "manila database synced"""".stripMargin

  // Runs a Python snippet in the manila-api image, with its own interpreter (the one named on the first line
  // of manila-manage), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`.
  // Exit 0 when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v manila-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbStamp = Resource.sha256(dbSyncScript + manilaConf)
  // True when the manila database, queried as the manila user through the VIP, has the `shares` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM shares')")
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // ---- the helper ------------------------------------------------------

  private val apiHelperPython =
    """#!/usr/bin/python3
import json, os, ssl, subprocess, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
MANILA = '@@MANILA@@'
NODE_IP = '@@NODEIP@@'
ADMIN_PW = '@@ADMINPW@@'
MANILA_PW = '@@MANILAPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
# Manila's own header is the one older releases read; the generic one is for newer releases. Without a
# version the API serves 2.0, which has no /services and a different access call (404).
VERSION = {'X-OpenStack-Manila-API-Version': '2.45', 'X-OpenStack-API-Version': 'shared-file-system 2.45'}
TYPE_NAME = 'default_share_type'
MOUNT_DIR = '/mnt/orphera-manila-test'


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


def mcall(method, path, token, body=None):
    return call(method, MANILA + path, body, token, VERSION)


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
    user = find(token, '/users?name=manila', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'manila', 'domain_id': 'default', 'password': MANILA_PW}}, token)
        if s != 201:
            die('creating user manila failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': MANILA_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to manila failed: HTTP %s %s' % (s, p))
    service = ensure(token, 'services', '/services?type=sharev2', 'services', {'service': {'name': 'manilav2', 'type': 'sharev2'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': MANILA, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != MANILA:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': MANILA}}, token)
    print('registered manila in keystone (user manila, service type sharev2, 3 endpoints)')


def registered():
    # Exit 0 only when everything register() makes is in place: the manila user can get a token in project
    # service, and the sharev2 service has its three endpoints at the right URL. Makes no change.
    get_token('manila', MANILA_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=sharev2', 'services')
    if service is None:
        die('no service of type sharev2')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != MANILA:
            die('endpoint %s of sharev2 missing or wrong' % iface)
    print('manila is registered in keystone')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=sharev2', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=manila', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed manila from keystone (service sharev2, its endpoints, user manila)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def share_services(token):
    # /services is the current path; some releases serve only the older /os-services
    for path in ('/services', '/os-services'):
        s, p, _ = mcall('GET', path, token)
        if s == 200:
            return p.get('services', [])
        if s != 404:
            die('service list (%s) failed: HTTP %s %s' % (path, s, p))
    die('service list failed: HTTP 404 on both /services and /os-services')


def services():
    token = admin_token()
    svcs = share_services(token)
    print(' '.join('%s@%s=%s' % (x.get('binary'), x.get('host'), x.get('state')) for x in svcs))
    print('services: %d' % len(svcs))


def services_up():
    # Exit 0 only when the scheduler and the share service both report up. Does not wait.
    token = admin_token()
    up = set(x.get('binary') for x in share_services(token) if x.get('state') == 'up')
    if 'manila-scheduler' not in up or 'manila-share' not in up:
        die('manila-scheduler and manila-share are not both up (up: %s)' % sorted(up))
    print('manila-scheduler and manila-share are up')


def wait_services(token):
    end = time.time() + 180
    last = []
    while time.time() < end:
        last = share_services(token)
        up = set(x.get('binary') for x in last if x.get('state') == 'up')
        if 'manila-scheduler' in up and 'manila-share' in up:
            return
        time.sleep(5)
    die('manila-scheduler and manila-share are not both up after 180 s: %s' % ['%s=%s' % (x.get('binary'), x.get('state')) for x in last])


def ensure_type(token):
    s, p, _ = mcall('GET', '/types', token)
    if s != 200:
        die('share type list failed: HTTP %s %s' % (s, p))
    for t in p.get('share_types', []):
        if t.get('name') == TYPE_NAME:
            return t['id']
    body = {'share_type': {'name': TYPE_NAME, 'share_type_access:is_public': True, 'extra_specs': {'driver_handles_share_servers': 'False'}}}
    s, p, _ = mcall('POST', '/types', token, body)
    if s not in (200, 201):
        die('creating share type failed: HTTP %s %s' % (s, p))
    return p['share_type']['id']


def share_type():
    token = admin_token()
    tid = ensure_type(token)
    print('share type %s ready (%s)' % (TYPE_NAME, tid))


def share_type_ok():
    # Exit 0 only when the share type exists. Makes no change.
    token = admin_token()
    s, p, _ = mcall('GET', '/types', token)
    if s != 200:
        die('share type list failed: HTTP %s %s' % (s, p))
    for t in p.get('share_types', []):
        if t.get('name') == TYPE_NAME:
            print('share type %s exists (%s)' % (TYPE_NAME, t['id']))
            return
    die('share type %s does not exist' % TYPE_NAME)


def wait_gone(token, path, what):
    end = time.time() + 180
    while time.time() < end:
        s, _, _ = mcall('GET', path, token)
        if s == 404:
            return
        time.sleep(3)
    die('%s is still there 180 s after the delete' % what)


def share_cycle():
    token = admin_token()
    wait_services(token)
    tid = ensure_type(token)
    name = 'orphera-test-%d' % int(time.time())
    s, p, _ = mcall('POST', '/shares', token, {'share': {'share_proto': 'NFS', 'size': 1, 'name': name, 'share_type': tid}})
    if s not in (200, 202):
        die('share create failed: HTTP %s %s' % (s, p))
    sid = p['share']['id']
    access_id = None
    mounted = False
    try:
        end = time.time() + 240
        status = None
        while time.time() < end:
            s, p, _ = mcall('GET', '/shares/' + sid, token)
            if s == 200:
                status = p['share'].get('status')
                if status == 'available':
                    break
                if status and status.startswith('error'):
                    die('share %s went to %s; see manila-share on this node (journalctl -u orphera-manila-share)' % (sid, status))
            time.sleep(3)
        if status != 'available':
            die('share %s did not become available within 240 s (last status %s)' % (sid, status))
        s, p, _ = mcall('POST', '/shares/%s/action' % sid, token, {'allow_access': {'access_type': 'ip', 'access_to': NODE_IP, 'access_level': 'rw'}})
        if s not in (200, 202):
            die('allow_access failed: HTTP %s %s' % (s, p))
        access_id = p['access']['id']
        end = time.time() + 120
        state = None
        while time.time() < end:
            s, p, _ = mcall('GET', '/share-access-rules?share_id=' + sid, token)
            if s == 200:
                for rule in p.get('access_list', []):
                    if rule.get('id') == access_id:
                        state = rule.get('state')
                if state == 'active':
                    break
                if state == 'error':
                    die('access rule %s went to error' % access_id)
            time.sleep(3)
        if state != 'active':
            die('access rule %s is not active after 120 s (state %s)' % (access_id, state))
        s, p, _ = mcall('GET', '/shares/%s/export_locations' % sid, token)
        locs = p.get('export_locations', []) if s == 200 else []
        if not locs:
            die('share %s has no export location: HTTP %s %s' % (sid, s, p))
        path = locs[0]['path']
        os.makedirs(MOUNT_DIR, exist_ok=True)
        r = subprocess.run(['mount', '-t', 'nfs', '-o', 'nfsvers=4.1,proto=tcp,timeo=50,retrans=2', path, MOUNT_DIR], capture_output=True, text=True, timeout=90)
        if r.returncode != 0:
            die('mount of %s failed: %s %s' % (path, r.stdout.strip(), r.stderr.strip()))
        mounted = True
        payload = 'orphera-manila-%d' % int(time.time())
        f = os.path.join(MOUNT_DIR, 'orphera-test.txt')
        with open(f, 'w') as fh:
            fh.write(payload)
        subprocess.run(['sync'], timeout=60)
        with open(f) as fh:
            got = fh.read()
        if got != payload:
            die('file read back wrongly from %s: %r' % (path, got))
        print('share cycle ok: share %s exported as %s, mounted, file written and read back' % (sid, path))
    finally:
        if mounted:
            subprocess.run(['umount', MOUNT_DIR], timeout=60)
        if access_id:
            mcall('POST', '/shares/%s/action' % sid, token, {'deny_access': {'access_id': access_id}})
            time.sleep(3)
        mcall('DELETE', '/shares/' + sid, token)
    wait_gone(token, '/shares/' + sid, 'share ' + sid)
    print('share %s deleted and gone' % sid)


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
    elif cmd == 'share-type':
        share_type()
    elif cmd == 'share-type-ok':
        share_type_ok()
    elif cmd == 'share-cycle':
        share_cycle()
    else:
        die('usage: orphera-manila-api register | registered | unregister | catalog | services | services-up | share-type | share-type-ok | share-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@MANILA@@", s"http://$fqdn:$apiPort/v2")
      .replace("@@NODEIP@@", nodeIp)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@MANILAPW@@", manilaPassword.reveal)
      .replace("@@CA@@", caCert)

  // The scheduler and the share service report in every few seconds; give them three minutes to appear as `up`
  // through the API.
  private val servicesUp = s"$helperPath services-up >/dev/null 2>&1"
  private val waitServicesUp =
    s"""for i in $$(seq 1 36); do $helperPath services-up && exit 0; sleep 5; done
       |echo "manila-scheduler / manila-share never reported up - see 'journalctl -xeu orphera-manila-share' (Ceph errors show there)" >&2
       |exit 1""".stripMargin

  // One service: its resources (written before it starts), the ones that decide when it restarts.
  private final case class Svc(
      unit: ApplyResource,
      resources: List[ApplyResource],
      deps: List[ApplyResource],
      conf: ApplyResource
  )

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = pullImages(
      "pull the Manila images",
      images,
      hint = "the Image(...) values in manila_single_node.scala",
      timeout = 2400
    ).after(podman, mirror)
    val shareImageOk = command(
      "check that the share image has the Ceph Python bindings",
      shareImageCheckScript,
      unless = shareImageHasCeph,
      timeout = 180
    ).after(pulled)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = serverCa(
      "fetch Keystone's CA from its TLS handshake",
      ksFqdn,
      5000,
      caCert,
      hint = "apply keystone_single_node.scala (it must serve server-chain.crt)"
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)
    val tmpDirectory = directory(s"$stateDir/tmp").after(stateDirectory)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val svcs = serviceNames.zip(containers).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/manila.conf",
        Secret(
          manilaConf
        ), // it holds the database, message bus and service passwords
        mode = "0600",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = fileCopy(
        s"$dir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, dirRes).notifies(unitService)
      val kollaJson = name match
        case "manila-api" =>
          command(
            "write the Apache vhost and the API's Kolla config.json",
            kollaApiScript(dir),
            unless = kollaApiCurrent,
            timeout = 180
          ).after(pulled, dirRes, logDirectory).notifies(unitService)
        case "manila-scheduler" =>
          file(
            s"$dir/config.json",
            schedulerConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
        case _ =>
          file(
            s"$dir/config.json",
            shareConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
      // The share service also gets the Ceph client config and its keyring.
      val ceph: List[ApplyResource] =
        if name == "manila-share" then
          List(
            file(
              s"$dir/ceph.conf",
              cephConf,
              mode = "0644",
              owner = "root",
              group = "root"
            ).after(dirRes).notifies(unitService),
            secretFile(
              s"$dir/ceph.client.manila.keyring",
              Secret(cephKeyring), // the cephx key
              mode = "0640",
              owner = "root",
              group = "root"
            ).after(dirRes).notifies(unitService)
          )
        else Nil
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      val check: List[ApplyResource] =
        if name == "manila-share" then List(shareImageOk) else Nil
      Svc(
        unitService,
        List(dirRes, conf, kollaCa, kollaJson) ++ ceph :+ unitFile,
        List(conf, kollaCa, kollaJson, unitFile) ++ ceph ++ check,
        conf
      )
    }
    val apiConf = svcs.last.conf

    val dbSync = command(
      "manila-manage db sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and manila passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Manila in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val running = svcs.map { s =>
      s.unit.after(
        (dbSync :: registered :: pulled :: logDirectory :: tmpDirectory :: s.deps)*
      )
    }

    // The API answers its version document without a token (200, or 300 for a list of versions).
    val answering = waitForHttp(
      "manila-api answers on its port",
      s"http://localhost:$apiPort/",
      status = List(200, 300),
      timeout = 180,
      hint = s"see 'journalctl -xeu orphera-manila-api' and $logDir"
    ).after(running*)
    val reporting = command(
      "scheduler and share service report up through the API",
      waitServicesUp,
      unless = servicesUp,
      timeout = 240
    ).after((answering :: running)*)
    val shareType = command(
      "make sure the default share type exists",
      s"$helperPath share-type",
      unless = s"$helperPath share-type-ok >/dev/null 2>&1",
      timeout = 120
    ).after(reporting)
    val shareCycle = command(
      "make a share, mount it over NFS, write and read a file, delete it",
      s"$helperPath share-cycle",
      unless = s"$helperPath share-cycle >/dev/null 2>&1",
      timeout = 900
    ).after(shareType)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      shareImageOk,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory,
      tmpDirectory
    ) ++ svcs.flatMap(_.resources) ++ List(dbSync, helperFile, registered) ++
      running ++ List(answering, reporting, shareType, shareCycle)
