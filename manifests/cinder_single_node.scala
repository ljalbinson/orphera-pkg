// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Cinder (block storage), one node (tst10), Kolla images under podman,
// volumes stored in the existing Ceph cluster as RBD images, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing,
// and `--check` shows what would change. Modelled on nova_single_node.scala and
// glance_single_node.scala, which explain the conventions.
//
// Four descriptions, since one description cannot order its nodes. Apply them in
// this order:
//
//   orphera apply manifests/cinder_database.scala     # the cinder database and user (tst3)
//   orphera apply manifests/cinder_ceph.scala         # the `volumes` pool and client.cinder (tst0)
//   orphera apply manifests/rabbitmq_single_node.scala # the RabbitMQ broker (tst10)
//   orphera apply manifests/cinder_single_node.scala  # this one (tst10)
//
// The broker was part of the playbook; it is a description of its own now, shared
// by Nova, Neutron and the other services, so that Cinder can be torn down
// without taking their vhosts with it.
//
// Where it runs: tst10. Keep `node`, `fqdn` and `nodeIp` in step with
// cinder_teardown.scala and test_cinder.sh.
//
// Prerequisites, not checked here: Galera + VIP healthy, Ceph healthy with OSDs,
// Keystone up on tst6 serving the CA chain (the version that writes
// server-chain.crt), the agent on tst10. tst9's Kolla cache is optional.
//
// What it builds on tst10, as containers run by systemd units
// (orphera-<name>.service), host networking:
//   - cinder-api        Kolla image, Apache + mod_wsgi on 8776, plain HTTP
//   - cinder-scheduler, cinder-volume   Kolla images; the volume service uses
//                       the RBD driver against pool `volumes`
//
// TLS: Keystone is TLS; Cinder trusts its CA, which is fetched from the Keystone
// handshake (trust on first use - fine for a lab; a real deployment would
// distribute the CA out of band). Cinder's own API is plain HTTP.
//
// How it stays idempotent (as for Glance, Placement and Nova):
//   - Keystone's CA is compared as a certificate with the installed copy each
//     run; each service's copy is refreshed (and the service restarted) when it
//     changes.
//   - cinder.conf holds secrets, so it is a secret file, one per service
//     directory; so is the Ceph keyring. The Apache vhost and the API's
//     config.json depend on a path read from the image, so they are generated
//     into a temporary directory and compared with the installed files.
//   - `cinder-manage db sync` is skipped while its stamp matches and the
//     `volumes` table exists (a query as the cinder user, through the VIP).
//   - Cinder is registered in Keystone before the units start. The registration
//     is skipped while the helper's `registered` check passes.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object cinder_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst10"
  private val fqdn = "tst10.ljalbinson.com"
  private val nodeIp = "10.10.5.22"
  private val apiPort = 8776

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "cinder"
  private val dbUser = "cinder"
  private val dbPassword = Secrets.secret("cinder/db")
  private val cinderPassword = Secrets.secret("cinder/service")
  // The broker is rabbitmq_single_node.scala's: same user and password.
  private val rabbitUser = "openstack"
  private val rabbitPassword = Secrets.secret("cinder/rabbit")

  // A valid cephx key (base64 of a type/timestamp/length header plus 16 random
  // bytes), generated once and kept in the secret store: test infrastructure
  // only. cinder_ceph.scala puts the same key in Ceph. Rotate it with
  // ceph_key_rotation.scala.
  private val cephKey = Secrets.secret("cinder/ceph-key")
  private val cephPool = "volumes"
  // The UUID of the libvirt secret that holds this key on the compute nodes
  // (rbd_secret_uuid below). Cinder gives it to Nova when a volume is attached;
  // nova_compute.scala defines the secret under the same UUID. Keep the two in
  // step.
  private val rbdSecretUuid = "8c256f90-1c37-4fb6-b8be-cedcf0fffd3c"
  private val cephMons = List("tst0", "tst1", "tst2")

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/cinder"
  private val stateDir = "/var/lib/orphera/cinder"
  private val caDir = "/etc/kolla/cinder-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The containers as data (orchestrator/.../kolla/Container.scala, checked by
  // ContainerCheck). Defined after confRoot, logDir and stateDir, which they
  // read; a val used before its definition is null in an object.
  //
  // cinder-volume gets three extra capabilities: its privsep helper (used when a
  // volume is created from an image that has to be downloaded and inspected or
  // converted, e.g. a qcow2 image) must be able to set CAP_SYS_ADMIN,
  // CAP_NET_ADMIN and CAP_DAC_READ_SEARCH, which podman drops by default; without
  // them it fails with "FailedToDropPrivileges: Privsep daemon failed to start".
  // (kolla-ansible runs this container privileged; these three are the narrower
  // equivalent.) Plain volumes and raw-image clones do not need them.
  private def cinderContainer(
      service: String,
      capabilities: List[String] = Nil
  ): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/cinder", Access.ReadWrite),
        Mount(stateDir, "/var/lib/cinder", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      capabilities = capabilities,
      after = List("orphera-rabbitmq.service")
    )
  private val serviceNames =
    List("cinder-api", "cinder-scheduler", "cinder-volume")
  private val cinderServices: List[Container] =
    serviceNames.map(n =>
      cinderContainer(
        n,
        if n == "cinder-volume" then List("SYS_ADMIN", "NET_ADMIN", "DAC_READ_SEARCH")
        else Nil
      )
    )
  private val apiImage = cinderServices.head.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // The option lists of the image (cinder), from catalogues/. Every section and
  // key of the file below is checked against them when this manifest is built.
  private val cinderCatalogue: Catalogue =
    Catalogues.load("cinder", Set("ceph"))

  private val cinderConf: ConfFile =
    ConfFile.text(s"$confRoot/cinder-api/cinder.conf", cinderCatalogue)(
      ("DEFAULT", "log_dir", "/var/log/kolla/cinder"),
      ("DEFAULT", "state_path", "/var/lib/cinder"),
      ("DEFAULT", "my_ip", s"$nodeIp"),
      ("DEFAULT", "auth_strategy", "keystone"),
      (
        "DEFAULT",
        "transport_url",
        s"rabbit://$rabbitUser:${rabbitPassword.reveal}@$nodeIp:5672/"
      ),
      ("DEFAULT", "enabled_backends", "ceph"),
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
      ("keystone_authtoken", "username", "cinder"),
      ("keystone_authtoken", "password", s"${cinderPassword.reveal}"),
      ("keystone_authtoken", "cafile", "/etc/cinder/ssl/ca.crt"),
      ("oslo_concurrency", "lock_path", "/var/lib/cinder/tmp"),
      ("ceph", "volume_driver", "cinder.volume.drivers.rbd.RBDDriver"),
      ("ceph", "volume_backend_name", "ceph"),
      ("ceph", "rbd_pool", s"$cephPool"),
      ("ceph", "rbd_ceph_conf", "/etc/ceph/ceph.conf"),
      ("ceph", "rbd_user", "cinder"),
      ("ceph", "rbd_secret_uuid", rbdSecretUuid),
      ("ceph", "rbd_flatten_volume_from_snapshot", "false"),
      ("ceph", "rbd_max_clone_depth", "5"),
      ("ceph", "rados_connect_timeout", "-1")
    )
  private val cinderConfText: String = cinderConf.render

  // ceph.conf needs only the monitors for a client; their addresses come from
  // the inventory (`cluster_ip`), as the playbook's {{nodes.tst0.cluster_ip}} did.
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
    s"""[client.cinder]
       |    key = ${cephKey.reveal}
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

  private val images = cinderServices.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) in cinderContainer in cinder_single_node.scala"
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

  // The files every service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/cinder.conf", "dest": "/etc/cinder/cinder.conf", "owner": "cinder", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/cinder/ssl/ca.crt", "owner": "cinder", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/cinder", "owner": "cinder:kolla", "recurse": true}, {"path": "/var/lib/cinder", "owner": "cinder:cinder", "recurse": true}]"""
  private val schedulerConfigJson =
    s"""{"command": "cinder-scheduler --config-file /etc/cinder/cinder.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin
  private val volumeConfigJson =
    s"""{"command": "cinder-volume --config-file /etc/cinder/cinder.conf", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "cinder", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.cinder.keyring", "dest": "/etc/ceph/ceph.client.cinder.keyring", "owner": "cinder", "perm": "0600"}], $permissions}
       |""".stripMargin

  // The API runs behind Apache + mod_wsgi like Placement and Nova; the WSGI
  // script path and the Apache flavour are read from the image. The vhost and
  // the API's config.json are generated into `out` (the real directory, or a
  // temporary one for the guard to compare with).
  private def kollaApiScript(out: String): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v cinder-wsgi')
       |if [ -z "$$WSGI" ]; then echo "cinder-wsgi not found in $apiImage - see the header of cinder_single_node.scala" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/wsgi-cinder.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/wsgi-cinder.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $out/wsgi-cinder.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
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
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/wsgi-cinder.conf", "dest": "$$APACHE_CONF", "owner": "cinder", "perm": "0600"}], $permissions}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  private val kollaApiCurrent =
    s"""T=$$(mktemp -d)
       |${kollaApiScript("$T")} >/dev/null
       |R=0
       |cmp -s $$T/wsgi-cinder.conf $confRoot/cinder-api/wsgi-cinder.conf && cmp -s $$T/config.json $confRoot/cinder-api/config.json || R=1
       |rm -rf $$T
       |exit $$R""".stripMargin

  // cinder-manage runs in a one-off container with cinder.conf bind-mounted
  // (entrypoint overridden, so kolla_set_configs does not run). `:z` relabels
  // for SELinux hosts and is ignored elsewhere.
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint cinder-manage -v $confRoot/cinder-api/cinder.conf:/etc/cinder/cinder.conf:ro,z -v $logDir:/var/log/kolla/cinder:z -v $stateDir:/var/lib/cinder:z $apiImage db sync
       |echo "cinder database synced"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + cinderConfText)

  // True when the cinder database, queried as the cinder user through the VIP,
  // has the `volumes` table. The query runs with the image's own Python (the
  // interpreter named on the first line of cinder-manage), which has pymysql.
  private val dbInState =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v cinder-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |    c.cursor().execute('SELECT COUNT(*) FROM volumes')
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in the other services. Plain string filled in
  // with replace(). Subcommands:
  //   register | registered | unregister | catalog | volume-services |
  //   volume-create | volume-delete <id>
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
    return headers['X-Subject-Token'], parsed


def admin():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    return token, parsed['token']['project']['id']


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


ENDPOINT = CINDER + '/v3/%(project_id)s'


def register():
    token, _ = admin()
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
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': ENDPOINT, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != ENDPOINT:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': ENDPOINT}}, token)
    print('registered cinder in keystone (project service, user cinder, service volumev3, 3 endpoints)')


def registered():
    # Exit 0 only when everything register() makes is in place: the cinder user
    # can get a token in project service, and service volumev3 has the three
    # endpoints at ENDPOINT. Makes no change.
    get_token('cinder', CINDER_PW, 'service')
    token, _ = admin()
    service = find(token, '/services?type=volumev3', 'services')
    if service is None:
        die('no service of type volumev3')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != ENDPOINT:
            die('endpoint %s missing or wrong' % iface)
    print('cinder is registered in keystone')


def unregister():
    token, _ = admin()
    service = find(token, '/services?type=volumev3', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=cinder', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed cinder from keystone (service volumev3, its endpoints, user cinder)')


def catalog():
    _, parsed = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in parsed['token']['catalog'])))


def volume_services():
    token, pid = admin()
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
    token, pid = admin()
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
    token, pid = admin()
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
    elif cmd == 'registered':
        registered()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'volume-services':
        volume_services()
    elif cmd == 'volume-create':
        volume_create()
    elif cmd == 'volume-delete' and len(sys.argv) > 2:
        volume_delete(sys.argv[2])
    else:
        die('usage: orphera-openstack-api register | registered | unregister | catalog | volume-services | volume-create | volume-delete <id>')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@CINDER@@", s"http://$fqdn:$apiPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@CINDERPW@@", cinderPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-openstack-api"

  // Cinder answers with a version document (HTTP 200 or 300) once Apache is up.
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 24); do
       |  $healthy && exit 0
       |  sleep 5
       |done
       |echo "cinder-api does not answer on http://localhost:$apiPort - see 'journalctl -xeu orphera-cinder-api' and $logDir" >&2
       |exit 1""".stripMargin

  // The scheduler and volume services report in every few seconds; give them a
  // minute and a half to appear as `up` through the API.
  private val servicesUp = s"$helperPath volume-services >/dev/null 2>&1"
  private val waitServicesUp =
    s"""for i in $$(seq 1 18); do $helperPath volume-services && exit 0; sleep 5; done
       |echo "cinder-scheduler / cinder-volume never reported up - see 'journalctl -xeu orphera-cinder-volume' (RBD/ceph errors show there)" >&2
       |exit 1""".stripMargin

  // One service: its resources (written before it starts), the ones that decide
  // when it restarts, and its cinder.conf (the database sync waits for the API's).
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
    val pulled = command(
      "pull the Cinder images",
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
    val svcs = serviceNames.zip(cinderServices).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/cinder.conf",
        Secret(cinderConfText), // it holds the database, message bus and service passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = fileCopy(
        s"$dir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, dirRes).notifies(unitService)
      val kollaJson = name match
        case "cinder-api" =>
          command(
            "write the Apache vhost and the API's Kolla config.json",
            kollaApiScript(dir),
            unless = kollaApiCurrent,
            timeout = 180
          ).after(pulled, dirRes, logDirectory).notifies(unitService)
        case "cinder-scheduler" =>
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
            volumeConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
      // The volume service also gets the Ceph client config and its keyring.
      val ceph: List[ApplyResource] =
        if name == "cinder-volume" then
          List(
            file(
              s"$dir/ceph.conf",
              cephConf,
              mode = "0644",
              owner = "root",
              group = "root"
            ).after(dirRes).notifies(unitService),
            secretFile(
              s"$dir/ceph.client.cinder.keyring",
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
      Svc(
        unitService,
        List(dirRes, conf, kollaCa, kollaJson) ++ ceph :+ unitFile,
        List(conf, kollaCa, kollaJson, unitFile) ++ ceph,
        conf
      )
    }
    val apiConf = svcs.head.conf

    val dbSync = command(
      "cinder-manage db sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and cinder passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start, as for Nova.
    val registered = command(
      "register Cinder in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val running = svcs.map { s =>
      s.unit.after((dbSync :: registered :: s.deps)*)
    }

    val answering = command(
      "cinder-api answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 180
    ).after(running*)
    val reporting = command(
      "scheduler and volume services report up through the API",
      waitServicesUp,
      unless = servicesUp,
      timeout = 180
    ).after((answering :: running)*)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory
    ) ++ svcs.flatMap(_.resources) ++ List(dbSync, helperFile, registered) ++
      running ++ List(answering, reporting)
