// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. OpenStack Cinder (block storage), one node (tst10), Kolla images under
// podman, volumes stored in the existing Ceph cluster as RBD images.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala,
//     mariadb_haproxy_keepalived.scala)
//   - Ceph healthy with OSDs (cephadm_*.scala)
//   - Keystone up on tst7 (keystone_single_node.scala), serving the CA chain
//     (the version that writes server-chain.crt)
//   - agent on tst10; tst9's Kolla cache is optional
//
// What it builds on tst10, all as containers run by systemd units
// (orphera-<name>.service), host networking:
//   - rabbitmq        official docker.io/library/rabbitmq image (Cinder needs a
//                     message bus; this is the stock image, not a Kolla one,
//                     because its first-run user setup is a single env var)
//   - cinder-api      Kolla image, Apache + mod_wsgi on 8776, plain HTTP
//   - cinder-scheduler, cinder-volume   Kolla images; the volume service uses
//                     the RBD driver against pool `volumes`
//
// Stages:
//   - ceph-and-database (tst0): the `cinder` database and user on Galera, the
//     `volumes` pool, and the `client.cinder` cephx user with a FIXED test key
//     (see cephKey). A fixed key is used because a stage cannot capture
//     another node's command output (there is no register mechanism; see the
//     header of etcd_grow_cluster.scala), and importing a key we already know
//     lets tst10 be configured in the same run.
//   - install-cinder (tst10): packages, cache mirror, image pulls, CA fetched
//     from Keystone's TLS handshake, RabbitMQ, config for the three services,
//     db sync, the units, then registration in Keystone (project `service`,
//     user `cinder`, service volumev3, three endpoints).
//   - confirm-cinder-healthy (tst10): API answers, then both scheduler and
//     volume services report `up` through the API.
//
// TLS: Keystone is TLS; Cinder trusts its CA, which this playbook obtains from
// the Keystone handshake (trust on first use - fine for a lab; a real
// deployment would distribute the CA out of band). Cinder's own API is plain
// HTTP in this draft.
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. keystone's admin password below must match keystone_single_node.
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object cinder_single_node extends OrpheraClusterPlaybook:

  private val node = "tst10"
  private val fqdn = "tst10.ljalbinson.com"
  private val nodeIp = "10.10.5.22"

  private val ksFqdn = "tst7.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val release = "2025.1"
  private val baseTag = "ubuntu-noble"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"
  private def kolla(name: String) = s"$registry/$name:$release-$baseTag"
  private val apiImage = kolla("cinder-api")
  private val schedulerImage = kolla("cinder-scheduler")
  private val volumeImage = kolla("cinder-volume")
  private val rabbitImage = "docker.io/library/rabbitmq:3.13"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbName = "cinder"
  private val dbUser = "cinder"
  private val dbPassword = "orphera-test-cinder-db-password"
  private val cinderPassword = "orphera-test-cinder-service-password"
  private val rabbitUser = "openstack"
  private val rabbitPassword = "orphera-test-rabbit-password"

  // A valid cephx key (base64 of a type/timestamp/length header plus 16 random
  // bytes), generated once and committed on purpose: test infrastructure only.
  // Rotate it with `ceph auth import` / `ceph auth del client.cinder` if this
  // repository is ever used for anything real.
  private val cephKey = "AQAAeOdoAAAAABAAZtOwIBqwSdv7t+vxPfUfpQ=="
  private val cephPool = "volumes"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/cinder"
  private val logDir = "/var/log/kolla/cinder"
  private val stateDir = "/var/lib/orphera/cinder"
  private val caDir = "/etc/kolla/cinder-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // ---- tst0 -----------------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // The pool create is idempotent; `auth import` replaces the entity, so a
  // re-run just re-asserts the same key and caps. `cephadm shell --mount FILE`
  // makes FILE visible at /mnt/<name> inside the shell container.
  private val cephScript =
    s"""set -e
       |cephadm shell -- ceph osd pool create $cephPool
       |cephadm shell -- rbd pool init $cephPool
       |cat > /tmp/cinder.keyring <<'KEYRING'
       |[client.cinder]
       |    key = $cephKey
       |    caps mon = "profile rbd"
       |    caps osd = "profile rbd pool=$cephPool"
       |KEYRING
       |cephadm shell --mount /tmp/cinder.keyring -- ceph auth import -i /mnt/cinder.keyring
       |rm -f /tmp/cinder.keyring
       |cephadm shell -- ceph auth get client.cinder >/dev/null
       |echo "pool $cephPool and client.cinder ready"""".stripMargin

  // ---- tst10 ----------------------------------------------------------

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
    s"""set -e
       |for img in $apiImage $schedulerImage $volumeImage $rabbitImage; do
       |  podman pull $$img || { echo "could not pull $$img - check registry/release/baseTag at the top of cinder_single_node.scala" >&2; exit 1; }
       |done
       |echo "images ready"""".stripMargin

  // Takes the last certificate Keystone presents (its CA, because
  // keystone_single_node.scala serves leaf + CA) and checks that it is
  // self-signed and signs the leaf. Trust on first use.
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

  // ceph.conf needs only the monitors for a client. The only task whose script
  // contains template expressions, kept separate on purpose so the runner's
  // template pass does not touch the large scripts elsewhere.
  private val cephClientScript =
    s"""mkdir -p $srcDir
       |cat > $srcDir/ceph.conf <<'CEPHCONF'
       |[global]
       |mon_host = {{nodes.tst0.cluster_ip}},{{nodes.tst1.cluster_ip}},{{nodes.tst2.cluster_ip}}
       |CEPHCONF
       |cat > $srcDir/ceph.client.cinder.keyring <<'KEYRING'
       |[client.cinder]
       |    key = $cephKey
       |KEYRING
       |chmod 640 $srcDir/ceph.client.cinder.keyring
       |echo "ceph client config written"""".stripMargin

  private val rabbitScript =
    s"""set -e
       |mkdir -p /var/lib/orphera/rabbitmq
       |cat > /etc/systemd/system/orphera-rabbitmq.service <<'UNIT'
       |[Unit]
       |Description=RabbitMQ for OpenStack managed by Orphera
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f rabbitmq
       |ExecStart=/usr/bin/podman run --rm --name rabbitmq --network host -e RABBITMQ_DEFAULT_USER=$rabbitUser -e RABBITMQ_DEFAULT_PASS=$rabbitPassword -v /var/lib/orphera/rabbitmq:/var/lib/rabbitmq:z $rabbitImage
       |ExecStop=/usr/bin/podman stop -t 30 rabbitmq
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |systemctl enable orphera-rabbitmq
       |systemctl restart orphera-rabbitmq
       |for i in $$(seq 1 30); do
       |  if podman exec rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
       |    echo "rabbitmq up"
       |    exit 0
       |  fi
       |  sleep 3
       |done
       |echo "rabbitmq did not come up - see 'journalctl -xeu orphera-rabbitmq'" >&2
       |exit 1""".stripMargin

  // cinder.conf is shared by the three services. Quoted heredoc: nothing for
  // the shell to expand.
  private val cinderConfScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |cat > $srcDir/cinder.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/cinder
       |state_path = /var/lib/cinder
       |my_ip = $nodeIp
       |auth_strategy = keystone
       |transport_url = rabbit://$rabbitUser:$rabbitPassword@$nodeIp:5672/
       |enabled_backends = ceph
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
       |username = cinder
       |password = $cinderPassword
       |cafile = /etc/cinder/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/cinder/tmp
       |
       |[ceph]
       |volume_driver = cinder.volume.drivers.rbd.RBDDriver
       |volume_backend_name = ceph
       |rbd_pool = $cephPool
       |rbd_ceph_conf = /etc/ceph/ceph.conf
       |rbd_user = cinder
       |rbd_flatten_volume_from_snapshot = false
       |rbd_max_clone_depth = 5
       |rados_connect_timeout = -1
       |CONF
       |chmod 640 $srcDir/cinder.conf
       |echo "cinder.conf written"""".stripMargin

  // One config directory per service (Kolla mounts it at
  // /var/lib/kolla/config_files). The WSGI script path and the Apache flavour
  // are read from the image, as in keystone_single_node.scala.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/cinder-api $confRoot/cinder-scheduler $confRoot/cinder-volume
       |for svc in api scheduler volume; do
       |  cp $srcDir/cinder.conf $confRoot/cinder-$$svc/cinder.conf
       |  cp $caDir/ca.crt $confRoot/cinder-$$svc/ca.crt
       |done
       |cp $srcDir/ceph.conf $srcDir/ceph.client.cinder.keyring $confRoot/cinder-volume/
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v cinder-wsgi')
       |if [ -z "$$WSGI" ]; then echo "cinder-wsgi not found in $apiImage" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/wsgi-cinder.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/wsgi-cinder.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $confRoot/cinder-api/wsgi-cinder.conf <<WSGICONF
       |Listen 0.0.0.0:8776
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:8776>
       |    ErrorLog "$logDir/cinder-apache-error.log"
       |    CustomLog "$logDir/cinder-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess cinder-api group=cinder processes=2 threads=1 user=cinder
       |    WSGIProcessGroup cinder-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |COMMON='{"source": "/var/lib/kolla/config_files/cinder.conf", "dest": "/etc/cinder/cinder.conf", "owner": "cinder", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/cinder/ssl/ca.crt", "owner": "cinder", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/cinder", "owner": "cinder:kolla", "recurse": true}, {"path": "/var/lib/cinder", "owner": "cinder:cinder", "recurse": true}]'
       |cat > $confRoot/cinder-api/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/wsgi-cinder.conf", "dest": "$$APACHE_CONF", "owner": "cinder", "perm": "0600"}], $$PERMS}
       |JSON
       |cat > $confRoot/cinder-scheduler/config.json <<JSON
       |{"command": "cinder-scheduler --config-file /etc/cinder/cinder.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |cat > $confRoot/cinder-volume/config.json <<JSON
       |{"command": "cinder-volume --config-file /etc/cinder/cinder.conf", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "cinder", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.cinder.keyring", "dest": "/etc/ceph/ceph.client.cinder.keyring", "owner": "cinder", "perm": "0600"}], $$PERMS}
       |JSON
       |echo "kolla config written under $confRoot"""".stripMargin

  // cinder-manage runs in a one-off container with cinder.conf bind-mounted
  // (entrypoint overridden, so kolla_set_configs does not run). `:z` relabels
  // for SELinux hosts and is ignored elsewhere.
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --entrypoint cinder-manage -v $srcDir/cinder.conf:/etc/cinder/cinder.conf:ro,z -v $logDir:/var/log/kolla/cinder:z -v $stateDir:/var/lib/cinder:z $apiImage db sync
       |echo "cinder database synced"""".stripMargin

  private val unitsScript =
    s"""set -e
       |mkunit() {
       |  name=$$1
       |  image=$$2
       |  cat > /etc/systemd/system/orphera-$$name.service <<UNIT
       |[Unit]
       |Description=$$name (Kolla container) managed by Orphera
       |After=network-online.target orphera-rabbitmq.service
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f $$name
       |ExecStart=/usr/bin/podman run --rm --name $$name --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confRoot/$$name:/var/lib/kolla/config_files:ro,z -v $logDir:/var/log/kolla/cinder:z -v $stateDir:/var/lib/cinder:z $$image
       |ExecStop=/usr/bin/podman stop -t 30 $$name
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |}
       |mkunit cinder-api $apiImage
       |mkunit cinder-scheduler $schedulerImage
       |mkunit cinder-volume $volumeImage
       |systemctl daemon-reload
       |for u in cinder-api cinder-scheduler cinder-volume; do
       |  systemctl enable orphera-$$u
       |  systemctl restart orphera-$$u
       |done
       |sleep 8
       |for u in cinder-api cinder-scheduler cinder-volume; do
       |  if ! systemctl is-active --quiet orphera-$$u; then
       |    echo "orphera-$$u failed to start - see 'journalctl -xeu orphera-$$u' and $logDir" >&2
       |    exit 1
       |  fi
       |done
       |echo "cinder services running"""".stripMargin

  // Standard-library Python so nothing has to be installed on the node. Kept
  // as a plain (non-interpolated) string and filled in with replace(), so its
  // braces, percent signs and backslashes reach the file untouched. Subcommands:
  //   register | volume-services | volume-create | volume-delete <id>
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
CINDER = '@@CINDER@@'
ADMIN_PW = '@@ADMINPW@@'
CINDER_PW = '@@CINDERPW@@'
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
    return headers['X-Subject-Token'], parsed['token']['project']['id']


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
    user = find(token, '/users?name=cinder', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'cinder', 'domain_id': 'default', 'password': CINDER_PW}}, token)
        if s != 201:
            die('creating user cinder failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': CINDER_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to cinder failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=volumev3', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'cinderv3', 'type': 'volumev3'}}, token)
        if s != 201:
            die('creating service volumev3 failed: HTTP %s %s' % (s, p))
        service = p['service']
    url = CINDER + '/v3/%(project_id)s'
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)
    print('registered cinder in keystone (project service, user cinder, service volumev3, 3 endpoints)')


def volume_services():
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', '%s/v3/%s/os-services' % (CINDER, pid), None, token)
    if s != 200:
        die('os-services failed: HTTP %s %s' % (s, p))
    up = set()
    for svc in p.get('services', []):
        print('%s on %s: status=%s state=%s' % (svc.get('binary'), svc.get('host'), svc.get('status'), svc.get('state')))
        if svc.get('state') == 'up':
            up.add(svc.get('binary'))
    sys.exit(0 if ('cinder-scheduler' in up and 'cinder-volume' in up) else 1)


def volume_create():
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('POST', '%s/v3/%s/volumes' % (CINDER, pid), {'volume': {'size': 1, 'name': 'orphera-test'}}, token)
    if s not in (200, 202):
        die('volume create failed: HTTP %s %s' % (s, p))
    vid = p['volume']['id']
    for _ in range(45):
        s, p, _ = call('GET', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
        status = p.get('volume', {}).get('status')
        if status == 'available':
            print(vid)
            return
        if status == 'error':
            die('volume %s went to error' % vid)
        time.sleep(2)
    die('volume %s not available after 90s (last status %s)' % (vid, status))


def volume_delete(vid):
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('DELETE', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
    if s not in (200, 202, 204):
        die('volume delete failed: HTTP %s %s' % (s, p))
    for _ in range(45):
        s, p, _ = call('GET', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
        if s == 404:
            print('deleted ' + vid)
            return
        time.sleep(2)
    die('volume %s still present after 90s' % vid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'volume-services':
        volume_services()
    elif cmd == 'volume-create':
        volume_create()
    elif cmd == 'volume-delete' and len(sys.argv) > 2:
        volume_delete(sys.argv[2])
    else:
        die('usage: orphera-openstack-api register | volume-services | volume-create | volume-delete <id>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-openstack-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@CINDER@@", s"http://$fqdn:8776")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@CINDERPW@@", cinderPassword)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-openstack-api\necho wrote /usr/local/sbin/orphera-openstack-api\n"

  // Cinder answers with a version document (HTTP 200 or 300) once Apache is up.
  private val apiHealthScript =
    """CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8776/)
      |[ "$CODE" = "200" ] || [ "$CODE" = "300" ]""".stripMargin

  // The scheduler and volume services report in every few seconds; give them a
  // minute and a half to appear as `up` through the API.
  private val servicesScript =
    """for i in $(seq 1 18); do
      |  if /usr/local/sbin/orphera-openstack-api volume-services; then
      |    exit 0
      |  fi
      |  sleep 5
      |done
      |echo "cinder-scheduler / cinder-volume never reported up - see 'journalctl -xeu orphera-cinder-volume' (RBD/ceph errors show there)" >&2
      |exit 1""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("cinder-single-node")(
      stage("ceph-and-database", "tst0")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .task(s"create the $cephPool pool and the client.cinder cephx user")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build,

      stage("install-cinder", node)
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Cinder and RabbitMQ images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the ceph client config")(
          Task.RunCommand(List("sh", "-c", cephClientScript))
        )
        .task("run RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 300)
        )
        .task("write cinder.conf")(
          Task.RunCommand(List("sh", "-c", cinderConfScript))
        )
        .task("write the Kolla config for the three services")(
          Task.RunCommand(List("sh", "-c", kollaConfigScript), timeoutSeconds = 120)
        )
        .task("cinder-manage db sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
        .task("write the OpenStack API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("start the cinder services")(
          Task.RunCommand(List("sh", "-c", unitsScript), timeoutSeconds = 300)
        )
        .task("register Cinder in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-openstack-api register"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-cinder-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 90
          )
        )
        .task("scheduler and volume services report up")(
          Task.RunCommand(List("sh", "-c", servicesScript), timeoutSeconds = 150)
        )
        .task("cinder confirmed")(
          Task.Debug(
            s"Cinder up at http://$fqdn:8776 ($nodeIp): API, scheduler and volume (Ceph pool $cephPool), RabbitMQ on $nodeIp:5672, database via $vip:3306, identity from $ksUrl. Test: manifests/test_cinder.sh"
          )
        )
        .build
    )
