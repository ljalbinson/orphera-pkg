// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Trove (database as a service), Kolla images under podman, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). Part 1 of two: this installs the Trove service;
// trove_guest_image.scala builds the guest image on tst21 and registers the MySQL datastore. It replaced the
// cluster playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows
// what would change. Modelled on designate_single_node.scala, which explains the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/trove_database.scala      # the trove database and user (tst3)
//   orphera apply manifests/trove_rabbitmq.scala      # the `trove` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/trove_single_node.scala   # this one (tst25)
//
// Where it runs: tst25 (10.10.5.37), provisioned from config/tst25.yaml (2 vCPU, 4096 MiB, on gs3). To move
// it, change `node` and `fqdn` below (and the teardown's `node`).
//
// How Trove works here:
//   - trove-api (port 8779) is the REST API; trove-taskmanager makes the guests (Nova server, Cinder volume,
//     Neutron ports) and talks to them; trove-conductor receives what the guests report. All three read one
//     trove.conf. The taskmanager also gives every guest a trove-guestagent.conf (file below).
//   - A guest is a Nova server booted from the Trove guest image. Its guest agent runs the database as a
//     container (MySQL from Docker Hub) and talks to the control plane over RabbitMQ: the vhost `trove` on
//     tst10. So the guests must reach 10.10.5.22:5672 and Docker Hub: the provider network (flat, VLAN 106)
//     routes to both on this lab.
//   - The guests' management network is the provider network, found by name (`provider`, or
//     TROVE_MGMT_NETWORK in the environment of the run). The helper also makes a security group `trove-mgmt`
//     (ssh and ping in) and a keypair `trove-mgmt` in project `service`, where Trove makes its guests; the
//     private key is kept in /var/lib/orphera/trove/trove-mgmt.pem on tst25 for troubleshooting.
//   - `default_neutron_networks` is the same network, so a database can be created without `--nic`.
//
// What it adds to Keystone (done by the helper's `register`, repeatable): the user `trove` in project `service`
// with the admin role; the service `trove` of type `database` with the endpoints
// http://<fqdn>:8779/v1.0/$(tenant_id)s.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ on tst10 (rabbitmq_single_node.scala), Glance, Nova with
//     compute nodes, Neutron with the flat network `provider` (neutron_single_node.scala), Cinder
//   - the agent on tst25; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Trove images (trove-api, trove-conductor, trove-taskmanager) are in tst21's build list, but need
//     `orphera-kolla-build sources refresh` and a build of those the first time
//
// How it stays idempotent (as for Designate):
//   - Keystone's CA is compared as a certificate with the installed copy each run; when it changes the
//     three services are restarted (they mount the host's CA bundle).
//   - trove.conf holds the ids of the guest network and security group, which only exist once the helper's
//     `mgmt-resources` has run. The file with placeholders (trove.conf.tmpl, a secret file) is declared; a
//     command fills the placeholders from /var/lib/orphera/trove/mgmt.json and writes the result to
//     /etc/kolla/trove (which trove_guest_image.scala mounts) and to each service's directory. It is skipped
//     while all of them are identical to what it would write.
//   - `mgmt-resources` is skipped while the helper's `mgmt-ready` passes: mgmt.json names the network that
//     the name resolves to now, and the security group and keypair it names exist.
//   - `trove-manage db_sync` is skipped while its stamp matches and the `datastores` table exists (a query as
//     the trove user, through the VIP).
//   - Trove is registered in Keystone before the units start, skipped while the helper's `registered`
//     check passes.
//
// Facts this was written from, to check if a run fails: the Kolla commands are `trove-api`,
// `trove-taskmanager` and `trove-conductor` with --config-file /etc/trove/trove.conf, user `trove`; the options
// management_networks, management_security_groups, nova_keypair, cinder_volume_type, network_driver,
// taskmanager_manager, default_neutron_networks and guest_config are Trove's; the guests take their RabbitMQ
// settings from the guest agent file (transport_url, control_exchange); the default Cinder volume type is
// `__DEFAULT__`; the Keystone endpoint template is $(tenant_id)s. The Trove API root answers without a token.
//
// Not in this version: backups to Swift (the guests need a Swift endpoint they can reach and credentials),
// replication, the Trove dashboard, DNS records for instances.
//
// Test-only credentials come from the secret store (trove/db, trove/service, trove/rabbit), made on first use.
object trove_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst25"
  private val fqdn = "tst25.ljalbinson.com"
  private val trovePort = 8779
  private val troveUrl = s"http://$fqdn:$trovePort"

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val troveRabbitPassword = Secrets.secret("trove/rabbit")

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "trove"
  private val dbUser = "trove"
  private val dbPassword = Secrets.secret("trove/db")
  private val trovePassword = Secrets.secret("trove/service")

  // The network the guests use for their management port, by name.
  private val mgmtNetwork = sys.env
    .get("TROVE_MGMT_NETWORK")
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("provider")

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/trove"
  private val caDir = "/etc/kolla/trove-ca"
  private val caCert = s"$caDir/ca.crt"
  private val logDir = "/var/log/kolla/trove"
  private val stateDir = "/var/lib/orphera/trove"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val hostCaBundle = "/etc/ssl/certs/ca-certificates.crt"
  private val helperPath = "/usr/local/sbin/orphera-trove-api"

  private def kolla(service: String): Image =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry)

  private val serviceNames =
    List("trove-api", "trove-taskmanager", "trove-conductor")

  // The host's CA bundle has Keystone's CA in it (the `ca` step): the clients in the containers verify
  // Keystone's certificate through it. Defined after confRoot, logDir, stateDir and hostCaBundle, which it
  // reads; a val used before its definition is null in an object.
  private def troveContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = kolla(service),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/trove", Access.ReadWrite),
        Mount(s"$stateDir/trove", "/var/lib/trove", Access.ReadWrite),
        Mount(hostCaBundle, hostCaBundle, Access.ReadOnlyUnlabelled)
      ),
      env = List(
        "KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS",
        "REQUESTS_CA_BUNDLE" -> hostCaBundle,
        "SSL_CERT_FILE" -> hostCaBundle
      )
    )

  private val containers: List[Container] = serviceNames.map(troveContainer)
  private val apiImage = kolla("trove-api").ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in trove_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(" ")}; do podman image exists $$img || exit 1; done"""

  // Keystone's CA: the last certificate it presents (trust on first use), which must be self-signed.
  // `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem, each holding the certificate only: the text s_client
  // prints after the last one differs on every handshake, and would make the comparison fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""

  // Current when the installed CA is the one Keystone presents now, and is in the system trust store.
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

  // ---- configuration ---------------------------------------------------

  // @@MGMTNET@@ and @@MGMTSG@@ are put in on the node from $stateDir/mgmt.json, which the helper's
  // `mgmt-resources` writes (see `render` below).
  private val troveConfTemplate: String =
    s"""# Managed by Orphera (trove_single_node.scala)
       |[DEFAULT]
       |bind_host = 0.0.0.0
       |bind_port = $trovePort
       |transport_url = rabbit://trove:${troveRabbitPassword.reveal}@$rabbitIp:5672/trove
       |control_exchange = trove
       |auth_strategy = keystone
       |log_dir = /var/log/kolla/trove
       |use_stderr = true
       |network_driver = trove.network.neutron.NeutronDriver
       |taskmanager_manager = trove.taskmanager.manager.Manager
       |management_networks = @@MGMTNET@@
       |management_security_groups = @@MGMTSG@@
       |default_neutron_networks = @@MGMTNET@@
       |nova_keypair = trove-mgmt
       |cinder_volume_type = __DEFAULT__
       |guest_config = /etc/trove/trove-guestagent.conf
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |# haproxy on the VIP closes connections idle for 30 s; recycle older pooled ones before they are reused
       |connection_recycle_time = 20
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = trove
       |password = ${trovePassword.reveal}
       |cafile = /etc/trove/ssl/ca.crt
       |
       |[service_credentials]
       |auth_url = $ksUrl
       |username = trove
       |password = ${trovePassword.reveal}
       |user_domain_name = Default
       |project_name = service
       |project_domain_name = Default
       |region_name = RegionOne
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/trove/tmp
       |""".stripMargin

  // What the taskmanager gives each guest, with the guest's own id and datastore settings added. The guests
  // reach RabbitMQ over the provider network.
  private val guestConf: String =
    s"""# Managed by Orphera (trove_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://trove:${troveRabbitPassword.reveal}@$rabbitIp:5672/trove
       |control_exchange = trove
       |use_stderr = true
       |""".stripMargin

  private val template = s"$srcDir/trove.conf.tmpl"

  // The directories that hold a rendered trove.conf: /etc/kolla/trove (trove_guest_image.scala mounts the file
  // from there) and one per service.
  private val renderedDirs =
    srcDir :: serviceNames.map(s => s"$confRoot/$s")

  // Fills the placeholders of the template from mgmt.json into $T/trove.conf. Exits 1 when mgmt.json is missing or has no ids.
  private val renderToTemp =
    s"""NETID=$$(python3 -c "import json; print(json.load(open('$stateDir/mgmt.json'))['network_id'])" 2>/dev/null) || exit 1
       |SGID=$$(python3 -c "import json; print(json.load(open('$stateDir/mgmt.json'))['security_group_id'])" 2>/dev/null) || exit 1
       |if [ -z "$$NETID" ] || [ -z "$$SGID" ]; then echo "no network or security group id in $stateDir/mgmt.json" >&2; exit 1; fi
       |umask 077
       |sed "s/@@MGMTNET@@/$$NETID/g; s/@@MGMTSG@@/$$SGID/g" $template > $$T/trove.conf""".stripMargin

  private val dirList = renderedDirs.mkString(" ")

  private val renderScript =
    s"""set -e
       |T=$$(mktemp -d)
       |trap 'rm -rf $$T' EXIT
       |$renderToTemp
       |for d in $dirList; do
       |  install -m 0600 -o root -g root $$T/trove.conf $$d/trove.conf
       |done
       |echo "trove.conf written to: $dirList"""".stripMargin

  private val renderCurrent =
    s"""T=$$(mktemp -d)
       |trap 'rm -rf $$T' EXIT
       |$renderToTemp
       |R=0
       |for d in $dirList; do cmp -s $$T/trove.conf $$d/trove.conf || R=1; done
       |exit $$R""".stripMargin

  // The files every Trove service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/trove.conf", "dest": "/etc/trove/trove.conf", "owner": "trove", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/trove/ssl/ca.crt", "owner": "trove", "perm": "0644"}"""
  private val guestFile =
    """{"source": "/var/lib/kolla/config_files/trove-guestagent.conf", "dest": "/etc/trove/trove-guestagent.conf", "owner": "trove", "perm": "0600"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/trove", "owner": "trove:kolla", "recurse": true}, {"path": "/var/lib/trove", "owner": "trove:trove", "recurse": true}]"""
  private def configJson(service: String): String =
    val files =
      if service == "trove-taskmanager" then s"$commonFiles, $guestFile"
      else commonFiles
    s"""{"command": "$service --config-file /etc/trove/trove.conf", "config_files": [$files], $permissions}
       |""".stripMargin

  // trove-manage runs in a one-off container with trove.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint trove-manage -v $srcDir/trove.conf:/etc/trove/trove.conf:ro,z -v $logDir:/var/log/kolla/trove:z -v $hostCaBundle:$hostCaBundle:ro -e REQUESTS_CA_BUNDLE=$hostCaBundle $apiImage --config-file /etc/trove/trove.conf db_sync
       |echo "trove database synced"""".stripMargin

  // Runs a Python snippet in the trove-api image, with its own interpreter (the one named on the first line
  // of trove-manage), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`.
  // Exit 0 when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v trove-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbStamp = Resource.sha256(dbSyncScript + troveConfTemplate)
  // True when the trove database, queried as the trove user through the VIP, has the `datastores` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM datastores')")
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other service descriptions. Plain string filled in with replace().
  // Subcommands:
  //   register | registered | unregister | mgmt-resources | mgmt-ready | check | catalog
  // mgmt-resources finds the management network by name and makes the security group and the keypair of the
  // guests in project service; it writes the result to /var/lib/orphera/trove/mgmt.json.
  // mgmt-ready is the check for it (no change). check calls the Trove API with an admin token and looks at
  // what mgmt-resources made.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, os, ssl, sys, urllib.parse, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
TROVE = '@@TROVE@@'
ADMIN_PW = '@@ADMINPW@@'
TROVE_PW = '@@TROVEPW@@'
MGMT_NET = '@@MGMTNET@@'
STATE = '@@STATE@@'
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
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def endpoint(token_body, stype):
    for c in token_body['token']['catalog']:
        if c['type'] == stype:
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    return e['url'].rstrip('/')
    die('no public endpoint of service type %s in the Keystone catalog' % stype)


def ensure_user(token, project, admin_role, name, password):
    user = find(token, '/users?name=' + name, 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': name, 'domain_id': 'default', 'password': password}}, token)
        if s != 201:
            die('creating user %s failed: HTTP %s %s' % (name, s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': password}}, token)
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to %s failed: HTTP %s %s' % (name, s, p))


def ensure_endpoints(token, stype, sname, url):
    service = ensure(token, 'services', '/services?type=' + stype, 'services', {'service': {'name': sname, 'type': stype}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint of %s failed: HTTP %s %s' % (iface, stype, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    ensure_user(token, project, admin_role, 'trove', TROVE_PW)
    ensure_endpoints(token, 'database', 'trove', TROVE)
    print('registered in keystone (user trove, service type database, 3 endpoints)')


def registered():
    # Exit 0 only when everything register() makes is in place: the trove user can get a token in project
    # service, and the database service has its three endpoints at the right URL. Makes no change.
    get_token('trove', TROVE_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=database', 'services')
    if service is None:
        die('no service of type database')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != TROVE:
            die('endpoint %s of database missing or wrong' % iface)
    print('trove is registered in keystone')


def verify_guest(st, token, body):
    neutron = endpoint(body, 'network')
    nova = endpoint(body, 'compute')
    s, p, _ = call('GET', neutron + '/v2.0/security-groups/' + st['security_group_id'], None, token)
    if s != 200:
        die('security group %s: HTTP %s' % (st['security_group_id'], s))
    s, p, _ = call('GET', nova + '/os-keypairs/' + st['keypair'], None, token)
    if s != 200:
        die('keypair %s: HTTP %s' % (st['keypair'], s))


def mgmt_ready():
    # Exit 0 only when mgmt.json names the network that MGMT_NET is now, and the security group and keypair
    # it names exist. Makes no change.
    st = json.load(open(STATE + '/mgmt.json'))
    atoken, abody = get_token('admin', ADMIN_PW, 'admin')
    neutron = endpoint(abody, 'network')
    s, p, _ = call('GET', neutron + '/v2.0/networks?name=' + urllib.parse.quote(MGMT_NET), None, atoken)
    nets = p.get('networks', []) if s == 200 else []
    if not nets or nets[0]['id'] != st['network_id']:
        die('mgmt.json does not name the network %s' % MGMT_NET)
    token, body = get_token('trove', TROVE_PW, 'service')
    verify_guest(st, token, body)
    print('guest management resources are in place')


def mgmt_resources():
    atoken, abody = get_token('admin', ADMIN_PW, 'admin')
    neutron = endpoint(abody, 'network')
    s, p, _ = call('GET', neutron + '/v2.0/networks?name=' + urllib.parse.quote(MGMT_NET), None, atoken)
    nets = p.get('networks', []) if s == 200 else []
    if not nets:
        die('no Neutron network named %s (HTTP %s): set TROVE_MGMT_NETWORK to the name of the guests\' management network' % (MGMT_NET, s))
    net_id = nets[0]['id']

    token, body = get_token('trove', TROVE_PW, 'service')
    project_id = body['token']['project']['id']
    neutron = endpoint(body, 'network')
    nova = endpoint(body, 'compute')

    s, p, _ = call('GET', neutron + '/v2.0/security-groups?name=trove-mgmt&project_id=' + project_id, None, token)
    groups = p.get('security_groups', []) if s == 200 else []
    if groups:
        sg_id = groups[0]['id']
    else:
        s, p, _ = call('POST', neutron + '/v2.0/security-groups', {'security_group': {'name': 'trove-mgmt', 'description': 'Trove guest management port'}}, token)
        if s != 201:
            die('creating the security group trove-mgmt failed: HTTP %s %s' % (s, p))
        sg_id = p['security_group']['id']
        for rule in ({'protocol': 'tcp', 'port_range_min': 22, 'port_range_max': 22}, {'protocol': 'icmp'}):
            r = {'security_group_id': sg_id, 'direction': 'ingress', 'ethertype': 'IPv4'}
            r.update(rule)
            s, p, _ = call('POST', neutron + '/v2.0/security-group-rules', {'security_group_rule': r}, token)
            if s not in (201, 409):
                die('adding a rule to trove-mgmt failed: HTTP %s %s' % (s, p))

    s, p, _ = call('GET', nova + '/os-keypairs/trove-mgmt', None, token)
    if s != 200:
        s, p, _ = call('POST', nova + '/os-keypairs', {'keypair': {'name': 'trove-mgmt'}}, token)
        if s != 200:
            die('creating the keypair trove-mgmt failed: HTTP %s %s' % (s, p))
        os.makedirs(STATE, exist_ok=True)
        key = STATE + '/trove-mgmt.pem'
        fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(p['keypair']['private_key'])
    os.makedirs(STATE, exist_ok=True)
    with open(STATE + '/mgmt.json', 'w') as f:
        json.dump({'network_id': net_id, 'security_group_id': sg_id, 'keypair': 'trove-mgmt'}, f)
    print('guest management: network %s (%s), security group trove-mgmt %s, keypair trove-mgmt' % (MGMT_NET, net_id, sg_id))


def cleanup_mgmt():
    try:
        token, body = get_token('trove', TROVE_PW, 'service')
    except SystemExit:
        return
    project_id = body['token']['project']['id']
    neutron = endpoint(body, 'network')
    nova = endpoint(body, 'compute')
    s, p, _ = call('GET', neutron + '/v2.0/security-groups?name=trove-mgmt&project_id=' + project_id, None, token)
    for g in (p.get('security_groups', []) if s == 200 else []):
        call('DELETE', neutron + '/v2.0/security-groups/' + g['id'], None, token)
    call('DELETE', nova + '/os-keypairs/trove-mgmt', None, token)


def unregister():
    cleanup_mgmt()
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=database', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=trove', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed trove, its security group and keypair from keystone, neutron and nova')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def check():
    st = json.load(open(STATE + '/mgmt.json'))
    token, body = get_token('trove', TROVE_PW, 'service')
    verify_guest(st, token, body)
    neutron = endpoint(body, 'network')
    s, p, _ = call('GET', neutron + '/v2.0/networks/' + st['network_id'], None, token)
    if s != 200:
        die('management network %s: HTTP %s' % (st['network_id'], s))
    atoken, abody = get_token('admin', ADMIN_PW, 'admin')
    url = TROVE.replace('$(tenant_id)s', abody['token']['project']['id'])
    s, p, _ = call('GET', url + '/datastores', None, atoken)
    if s != 200:
        die('Trove API %s/datastores: HTTP %s %s' % (url, s, p))
    names = ' '.join(d.get('name', '?') for d in p.get('datastores', [])) or 'none yet'
    print('trove check ok: API answers an admin request, guest network, security group and keypair exist; datastores: ' + names)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'registered':
        registered()
    elif cmd == 'mgmt-ready':
        mgmt_ready()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'mgmt-resources':
        mgmt_resources()
    elif cmd == 'check':
        check()
    elif cmd == 'catalog':
        catalog()
    else:
        die('usage: orphera-trove-api register | registered | unregister | mgmt-resources | mgmt-ready | check | catalog')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@TROVE@@", s"$troveUrl/v1.0/$$(tenant_id)s")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@TROVEPW@@", trovePassword.reveal)
      .replace("@@MGMTNET@@", mgmtNetwork)
      .replace("@@STATE@@", stateDir)
      .replace("@@CA@@", caCert)

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$trovePort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 36); do
       |  ( $healthy ) && exit 0
       |  sleep 5
       |done
       |echo "trove-api does not answer on $trovePort - see 'journalctl -xeu orphera-trove-api' and $logDir" >&2
       |exit 1""".stripMargin

  // The API accepts an admin token and the guest resources are there.
  private val acceptsToken = s"$helperPath check >/dev/null 2>&1"
  private val waitAcceptsToken =
    s"""for i in $$(seq 1 36); do $helperPath check && exit 0; sleep 5; done
       |$helperPath check
       |exit 1""".stripMargin

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
      "pull the Trove images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)

    // The units come first so that what they depend on can name them in `notifies`.
    val unitOf: Map[String, ApplyResource] =
      serviceNames.zip(containers).map((n, c) => n -> service(c.unitName)).toMap
    val allUnits = serviceNames.map(unitOf)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    // The units mount the host's CA bundle, which this rewrites; they restart when it changes.
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1)).notifies(allUnits*)

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)
    val troveState = directory(s"$stateDir/trove").after(stateDirectory)
    val troveTmp = directory(s"$stateDir/trove/tmp").after(troveState)
    val srcDirectory = directory(srcDir, mode = "0755")

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and trove passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Trove in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)
    val mgmt = command(
      "make the guest security group and keypair, find the management network",
      s"$helperPath mgmt-resources",
      unless = s"$helperPath mgmt-ready >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, registered, stateDirectory)

    val tmplFile = secretFile(
      template,
      Secret(troveConfTemplate), // it holds the database, message bus and service passwords
      mode = "0600",
      owner = "root",
      group = "root"
    ).after(srcDirectory)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val perService = serviceNames.zip(containers).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = unitOf(name)
      val dirRes = directory(dir, mode = "0755")
      val kollaCa = command(
        s"copy Keystone's CA to the $name config",
        s"cp $caCert $dir/ca.crt",
        unless = s"cmp -s $caCert $dir/ca.crt"
      ).after(ca, dirRes).notifies(unitService)
      val json = file(
        s"$dir/config.json",
        configJson(name),
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
      val extras: List[ApplyResource] =
        if name == "trove-taskmanager" then
          List(
            secretFile(
              s"$dir/trove-guestagent.conf",
              Secret(guestConf),
              mode = "0600",
              owner = "root",
              group = "root"
            ).after(dirRes).notifies(unitService)
          )
        else Nil
      (name, dirRes, List(dirRes, kollaCa, json, unitFile) ++ extras)
    }
    val dirResources = perService.map(_._2)

    val rendered = command(
      "fill the guest network and security group ids into trove.conf",
      renderScript,
      unless = renderCurrent,
      timeout = 60
    ).after((tmplFile :: mgmt :: srcDirectory :: dirResources)*).notifies(allUnits*)

    val dbSync = command(
      "trove-manage db_sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, rendered, logDirectory, stateDirectory)

    val running = perService.map { (name, _, resources) =>
      unitOf(name).after(
        (dbSync :: registered :: rendered :: pulled :: logDirectory :: troveTmp :: resources)*
      )
    }

    val answering = command(
      "trove-api answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 240
    ).after(running*)
    val authenticating = command(
      "the API accepts an admin token and the guest resources exist",
      waitAcceptsToken,
      unless = acceptsToken,
      timeout = 240
    ).after((answering :: running)*)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory,
      troveState,
      troveTmp,
      srcDirectory,
      helperFile,
      registered,
      mgmt,
      tmplFile
    ) ++ perService.flatMap(_._3) ++
      List(rendered, dbSync) ++ running ++ List(answering, authenticating)
