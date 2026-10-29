// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Trove (database as a service), Kolla images under podman. Part 1 of two: this playbook
// installs the Trove service; trove_guest_image.scala builds the guest image on tst21 and registers the MySQL
// datastore. Modelled on telemetry_single_node.scala; read its header for the conventions.
//
// Where it runs: tst25 (10.10.5.37), a new VM provisioned from config/tst25.yaml (2 vCPU, 4096 MiB, on gs3).
// To move it, change `node`, `fqdn` and `nodeIp` below (and the teardown's `node`).
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
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ on tst10 (cinder_single_node.scala), Glance, Nova with
//     compute nodes, Neutron with the flat network `provider` (neutron_single_node.scala), Cinder
//   - the agent on tst25; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Trove images (trove-api, trove-conductor, trove-taskmanager) are in tst21's build list, but need
//     `orphera-kolla-build sources refresh` and a build of those the first time
//
// Stages:
//   - trove-database (tst3): the `trove` database + user.
//   - trove-rabbitmq (tst10): the vhost and user `trove`, idempotent.
//   - install-trove (tst25): packages, cache mirror, image pulls, Keystone's CA, the helper, the registration,
//     the guest network resources, configuration, Kolla config, `trove-manage db_sync`, then the units.
//   - confirm-trove-healthy (tst25): the API answers and an authenticated request to it works.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/trove_single_node.scala` changes nothing and
// reports whether the units on tst25 match what this playbook writes.
//
// Facts this was written from, to check if the first run fails: the Kolla commands are `trove-api`,
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
// Not compiled where this was written; the first run on scala0 is the first compile.
object trove_single_node extends OrpheraClusterPlaybook:

  private val node = "tst25"
  private val fqdn = "tst25.ljalbinson.com"
  private val nodeIp = "10.10.5.37"
  private val trovePort = 8779
  private val troveUrl = s"http://$fqdn:$trovePort"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
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
  private val logDir = "/var/log/kolla/trove"
  private val stateDir = "/var/lib/orphera/trove"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val hostCaBundle = "/etc/ssl/certs/ca-certificates.crt"

  private def kolla(service: String): Image =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry)

  private val services = List("trove-api", "trove-taskmanager", "trove-conductor")

  // The host's CA bundle has Keystone's CA in it (fetchCaScript): the clients in the containers verify
  // Keystone's certificate through it.
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

  private val nodeContainers: List[Container] = services.map(troveContainer)

  private val apiImage = kolla("trove-api").ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ALTER USER '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

  private val rabbitScript =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - run cinder_single_node.scala first" >&2
       |  exit 1
       |fi
       |if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx 'trove'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost 'trove'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx 'trove'; then
       |  podman exec rabbitmq rabbitmqctl change_password 'trove' '${troveRabbitPassword.reveal}'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user 'trove' '${troveRabbitPassword.reveal}'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p 'trove' 'trove' '.*' '.*' '.*'
       |echo "rabbitmq vhost trove and user trove ready"""".stripMargin

  // ---- tst25 -----------------------------------------------------------

  private val useCacheScript =
    s"""mkdir -p /etc/containers/registries.conf.d
       |cat > /etc/containers/registries.conf.d/orphera-kolla-cache.conf <<'REG'
       |[[registry]]
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
       |REG
       |echo "podman will try tst21.ljalbinson.com:5000 (built images), then $cacheHostPort, before quay.io"""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in trove_single_node.scala"
      )}
       |for img in ${nodeContainers.map(_.image.ref).mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  // Same method as manila_single_node.scala: the last certificate Keystone presents is its CA; trust on first
  // use.
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
       |cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |update-ca-certificates
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  private val dirsScript =
    s"""mkdir -p $srcDir $logDir $stateDir/trove/tmp
       |echo "directories ready"""".stripMargin

  // ---- configuration ---------------------------------------------------

  // @@MGMTNET@@ and @@MGMTSG@@ are put in on the node from $stateDir/mgmt.json, which the helper's
  // `mgmt-resources` writes before this file is made.
  private val troveConf: String =
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

  // Writes one file with a quoted heredoc.
  private def put(content: String, dest: String): String =
    s"cat > $dest <<'ORPHERA_EOF'\n$content\nORPHERA_EOF\n"

  private val kollaScript =
    put(troveConf, s"$srcDir/trove.conf") +
      put(guestConf, s"$srcDir/trove-guestagent.conf") +
      s"""set -e
         |NETID=$$(python3 -c "import json; print(json.load(open('$stateDir/mgmt.json'))['network_id'])")
         |SGID=$$(python3 -c "import json; print(json.load(open('$stateDir/mgmt.json'))['security_group_id'])")
         |if [ -z "$$NETID" ] || [ -z "$$SGID" ]; then echo "no network or security group id in $stateDir/mgmt.json" >&2; exit 1; fi
         |sed -i "s/@@MGMTNET@@/$$NETID/g; s/@@MGMTSG@@/$$SGID/g" $srcDir/trove.conf
         |chmod 600 $srcDir/trove.conf $srcDir/trove-guestagent.conf
         |COMMON='{"source": "/var/lib/kolla/config_files/trove.conf", "dest": "/etc/trove/trove.conf", "owner": "trove", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/trove/ssl/ca.crt", "owner": "trove", "perm": "0644"}'
         |GUEST='{"source": "/var/lib/kolla/config_files/trove-guestagent.conf", "dest": "/etc/trove/trove-guestagent.conf", "owner": "trove", "perm": "0600"}'
         |PERMS='"permissions": [{"path": "/var/log/kolla/trove", "owner": "trove:kolla", "recurse": true}, {"path": "/var/lib/trove", "owner": "trove:trove", "recurse": true}]'
         |for svc in ${services.mkString(" ")}; do
         |  mkdir -p $confRoot/$$svc
         |  cp $srcDir/trove.conf $confRoot/$$svc/trove.conf
         |  cp $srcDir/trove-guestagent.conf $confRoot/$$svc/trove-guestagent.conf
         |  cp $caDir/ca.crt $confRoot/$$svc/ca.crt
         |done
         |cat > $confRoot/trove-api/config.json <<JSON
         |{"command": "trove-api --config-file /etc/trove/trove.conf", "config_files": [$$COMMON], $$PERMS}
         |JSON
         |cat > $confRoot/trove-conductor/config.json <<JSON
         |{"command": "trove-conductor --config-file /etc/trove/trove.conf", "config_files": [$$COMMON], $$PERMS}
         |JSON
         |cat > $confRoot/trove-taskmanager/config.json <<JSON
         |{"command": "trove-taskmanager --config-file /etc/trove/trove.conf", "config_files": [$$COMMON, $$GUEST], $$PERMS}
         |JSON
         |python3 -c "import json; [json.load(open('$confRoot/' + s + '/config.json')) for s in '${services.mkString(" ")}'.split()]; print('config.json files are valid JSON')"
         |echo "kolla config written under $confRoot"
         |""".stripMargin

  // trove-manage runs in a one-off container with trove.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint trove-manage -v $srcDir/trove.conf:/etc/trove/trove.conf:ro,z -v $logDir:/var/log/kolla/trove:z -v $hostCaBundle:$hostCaBundle:ro -e REQUESTS_CA_BUNDLE=$hostCaBundle $apiImage --config-file /etc/trove/trove.conf db_sync
       |echo "trove database synced"""".stripMargin

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | mgmt-resources | check | catalog
  // mgmt-resources finds the management network by name and makes the security group and the keypair of the
  // guests in project service; it writes the result to /var/lib/orphera/trove/mgmt.json.
  // check calls the Trove API with an admin token and looks at what mgmt-resources made.
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
    neutron = endpoint(body, 'network')
    nova = endpoint(body, 'compute')
    s, p, _ = call('GET', neutron + '/v2.0/security-groups/' + st['security_group_id'], None, token)
    if s != 200:
        die('security group %s: HTTP %s' % (st['security_group_id'], s))
    s, p, _ = call('GET', nova + '/os-keypairs/' + st['keypair'], None, token)
    if s != 200:
        die('keypair %s: HTTP %s' % (st['keypair'], s))
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
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'mgmt-resources':
        mgmt_resources()
    elif cmd == 'check':
        check()
    elif cmd == 'catalog':
        catalog()
    else:
        die('usage: orphera-trove-api register | unregister | mgmt-resources | check | catalog')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-trove-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@TROVE@@", s"$troveUrl/v1.0/$$(tenant_id)s")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@TROVEPW@@", trovePassword.reveal)
        .replace("@@MGMTNET@@", mgmtNetwork)
        .replace("@@STATE@@", stateDir)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-trove-api\necho wrote /usr/local/sbin/orphera-trove-api\n"

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$trovePort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if a
  // unit differs from what this playbook would write.
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  private def withUnits(
      b: StageBuilder,
      containers: List[Container]
  ): StageBuilder =
    val written = containers.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    containers.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("trove-single-node-check")(
        (nodeContainers
          .foldLeft(stage("check-trove", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          ))
          .build
      )
    else applyPlaybook

  private def installTrove: StageBuilder =
    withUnits(
      stage("install-trove", node)
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
        .task("pull the Trove images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the Trove directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the Trove API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Trove in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-trove-api register"),
            timeoutSeconds = 120
          )
        )
        .task(
          "make the guest security group and keypair, find the management network"
        )(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-trove-api mgmt-resources"
            ),
            timeoutSeconds = 120
          )
        )
        .task("write the Trove Kolla config")(
          Task.RunCommand(List("sh", "-c", kollaScript), timeoutSeconds = 120)
        )
        .task("trove-manage db_sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        ),
      nodeContainers
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("trove-single-node")(
      stage("trove-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("trove-rabbitmq", rabbitNode)
        .task("create the trove vhost and user on RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      installTrove.build,

      stage("confirm-trove-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 180
          )
        )
        .task("an authenticated request to the Trove API works")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-trove-api check"),
            timeoutSeconds = 180
          )
        )
        .task("trove confirmed")(
          Task.Debug(
            s"Trove up: API at $troveUrl ($nodeIp), trove-taskmanager and trove-conductor on $node, guests on the network '$mgmtNetwork', RabbitMQ vhost trove on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Next: trove_guest_image.scala (guest image and MySQL datastore). Test: manifests/test_trove.sh"
          )
        )
        .build
    )
