// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. OpenStack Glance (image service), one node (tst13), Kolla image under
// podman, images stored in the existing Ceph cluster as RBD images.
// Modelled on cinder_single_node.scala; read its header for the conventions.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala,
//     mariadb_haproxy_keepalived.scala)
//   - Ceph healthy with OSDs (cephadm_*.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - agent on tst13; tst9's Kolla cache is optional
//
// What it builds on tst13: one container, glance-api (Kolla image, plain HTTP on
// 9292, host networking), run by systemd as orphera-glance-api.service. Unlike
// Cinder, Glance needs no message bus here, so there is no RabbitMQ.
//
// Stages:
//   - glance-database (tst3, a Galera node): the `glance` database and user.
//   - ceph-pool-and-user (tst0, a Ceph node): the `images` pool and the
//     `client.glance` cephx user with a FIXED test key (see cephKey; the reason
//     is the same as for Cinder: a stage cannot capture another node's output).
//   - install-glance (tst13): packages, cache mirror, image pull, Keystone's CA
//     from its TLS handshake, ceph client config, glance-api.conf, Kolla config,
//     `glance-manage db_sync`, the unit, then registration in Keystone (user
//     `glance` in project `service`, service `image`, three endpoints).
//   - confirm-glance-healthy (tst13): the API answers and an image can be
//     listed through Keystone-authenticated access.
//
// Ceph: Glance's RBD store keeps each image as an RBD image in pool `images`
// with a protected snapshot, so Cinder can later create volumes from it by
// copy-on-write clone (show_image_direct_url is on for that).
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. Not compiled where this was written; the first run on scala0 is
// the first compile.
object glance_single_node extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val release = "2025.1"
  private val baseTag = "ubuntu-noble"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"
  private val apiImage = s"$registry/glance-api:$release-$baseTag"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbName = "glance"
  private val dbUser = "glance"
  private val dbPassword = "orphera-test-glance-db-password"
  private val glancePassword = "orphera-test-glance-service-password"

  // A valid cephx key (base64 of a type/timestamp/length header plus 16 random
  // bytes), generated once and committed on purpose: test infrastructure only.
  private val cephKey = "AQDNtctqAAAAABAA2XKzaceaWSLHtjaNxQ4qeg=="
  private val cephPool = "images"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/glance"
  private val logDir = "/var/log/kolla/glance"
  private val stateDir = "/var/lib/orphera/glance"
  private val caDir = "/etc/kolla/glance-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // ---- tst3 (Galera) and tst0 (Ceph) ----------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // Same cipher compromise as cinder_single_node.scala (idempotent; see there).
  private val cephScript =
    s"""set -e
       |cephadm shell -- ceph mon set auth_allowed_ciphers aes,aes256k
       |cephadm shell -- ceph osd pool create $cephPool
       |cephadm shell -- rbd pool init $cephPool
       |cat > /tmp/glance.keyring <<'KEYRING'
       |[client.glance]
       |    key = $cephKey
       |    caps mon = "profile rbd"
       |    caps osd = "profile rbd pool=$cephPool"
       |KEYRING
       |cephadm shell --mount /tmp/glance.keyring -- ceph auth import -i /mnt/glance.keyring
       |rm -f /tmp/glance.keyring
       |cephadm shell -- ceph auth get client.glance >/dev/null
       |echo "pool $cephPool and client.glance ready"""".stripMargin

  // ---- tst13 ----------------------------------------------------------

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
    s"""podman pull $apiImage || { echo "could not pull $apiImage - check registry/release/baseTag at the top of glance_single_node.scala" >&2; exit 1; }
       |echo "image ready"""".stripMargin

  // Identical in method to cinder_single_node.scala: the last certificate
  // Keystone presents is its CA; trust on first use.
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

  // The only task whose script contains template expressions, kept separate so
  // the runner's template pass does not touch the large scripts elsewhere.
  private val cephClientScript =
    s"""mkdir -p $srcDir
       |cat > $srcDir/ceph.conf <<'CEPHCONF'
       |[global]
       |mon_host = {{nodes.tst0.cluster_ip}},{{nodes.tst1.cluster_ip}},{{nodes.tst2.cluster_ip}}
       |CEPHCONF
       |cat > $srcDir/ceph.client.glance.keyring <<'KEYRING'
       |[client.glance]
       |    key = $cephKey
       |KEYRING
       |chmod 640 $srcDir/ceph.client.glance.keyring
       |echo "ceph client config written"""".stripMargin

  // Multi-store layout (enabled_backends), the form current Glance expects;
  // the store is called `ceph` and is the default.
  private val glanceConfScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |cat > $srcDir/glance-api.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/glance
       |use_stderr = true
       |bind_host = 0.0.0.0
       |bind_port = 9292
       |show_image_direct_url = true
       |enabled_backends = ceph:rbd
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/$dbName
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = glance
       |password = $glancePassword
       |cafile = /etc/glance/ssl/ca.crt
       |
       |[paste_deploy]
       |flavor = keystone
       |
       |[glance_store]
       |default_backend = ceph
       |
       |[ceph]
       |rbd_store_pool = $cephPool
       |rbd_store_user = glance
       |rbd_store_ceph_conf = /etc/ceph/ceph.conf
       |rbd_store_chunk_size = 8
       |store_description = Ceph RBD images
       |CONF
       |chmod 640 $srcDir/glance-api.conf
       |echo "glance-api.conf written"""".stripMargin

  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/glance-api
       |cp $srcDir/glance-api.conf $confRoot/glance-api/glance-api.conf
       |cp $caDir/ca.crt $confRoot/glance-api/ca.crt
       |cp $srcDir/ceph.conf $srcDir/ceph.client.glance.keyring $confRoot/glance-api/
       |cat > $confRoot/glance-api/config.json <<JSON
       |{"command": "glance-api", "config_files": [{"source": "/var/lib/kolla/config_files/glance-api.conf", "dest": "/etc/glance/glance-api.conf", "owner": "glance", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/glance/ssl/ca.crt", "owner": "glance", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "glance", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.glance.keyring", "dest": "/etc/ceph/ceph.client.glance.keyring", "owner": "glance", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/glance", "owner": "glance:kolla", "recurse": true}, {"path": "/var/lib/glance", "owner": "glance:glance", "recurse": true}]}
       |JSON
       |echo "kolla config written under $confRoot/glance-api"""".stripMargin

  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint glance-manage -v $srcDir/glance-api.conf:/etc/glance/glance-api.conf:ro,z -v $logDir:/var/log/kolla/glance:z -v $stateDir:/var/lib/glance:z $apiImage db_sync
       |echo "glance database synced"""".stripMargin

  private val unitScript =
    s"""set -e
       |cat > /etc/systemd/system/orphera-glance-api.service <<'UNIT'
       |[Unit]
       |Description=glance-api (Kolla container) managed by Orphera
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f glance-api
       |ExecStart=/usr/bin/podman run --rm --name glance-api --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confRoot/glance-api:/var/lib/kolla/config_files:ro,z -v $logDir:/var/log/kolla/glance:z -v $stateDir:/var/lib/glance:z $apiImage
       |ExecStop=/usr/bin/podman stop -t 30 glance-api
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |systemctl enable orphera-glance-api
       |systemctl restart orphera-glance-api
       |sleep 8
       |if ! systemctl is-active --quiet orphera-glance-api; then
       |  echo "orphera-glance-api failed to start - see 'journalctl -xeu orphera-glance-api' and $logDir" >&2
       |  exit 1
       |fi
       |echo "glance-api running"""".stripMargin

  // Standard-library Python, as in cinder_single_node.scala. Plain string filled
  // in with replace(). Subcommands:
  //   register | unregister | catalog | image-list | image-create | image-delete <id>
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
        die('usage: orphera-glance-api register | unregister | catalog | image-list | image-create | image-delete <id>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-glance-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@GLANCE@@", s"http://$fqdn:9292")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@GLANCEPW@@", glancePassword)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-glance-api\necho wrote /usr/local/sbin/orphera-glance-api\n"

  // Glance answers with a version document (HTTP 200 or 300) once it is up.
  private val apiHealthScript =
    """CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9292/)
      |[ "$CODE" = "200" ] || [ "$CODE" = "300" ]""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("glance-single-node")(
      stage("glance-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("ceph-pool-and-user", "tst0")
        .task(s"create the $cephPool pool and the client.glance cephx user")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build,

      stage("install-glance", node)
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Glance image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the ceph client config")(
          Task.RunCommand(List("sh", "-c", cephClientScript))
        )
        .task("write glance-api.conf")(
          Task.RunCommand(List("sh", "-c", glanceConfScript))
        )
        .task("write the Kolla config")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 60
          )
        )
        .task("glance-manage db_sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
        .task("write the Glance API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("start glance-api")(
          Task.RunCommand(List("sh", "-c", unitScript), timeoutSeconds = 300)
        )
        .task("register Glance in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-glance-api register"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-glance-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 90
          )
        )
        .task("list images through Keystone-authenticated access")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-glance-api image-list"),
            timeoutSeconds = 60
          )
        )
        .task("glance confirmed")(
          Task.Debug(
            s"Glance up at http://$fqdn:9292 ($nodeIp): glance-api with the RBD store in Ceph pool $cephPool, database via $vip:3306, identity from $ksUrl. Test: manifests/test_glance.sh"
          )
        )
        .build
    )
