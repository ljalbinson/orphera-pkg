// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Neutron for provider (flat) networks: neutron-server, the Open
// vSwitch agent and the DHCP agent on one node, Kolla images under podman. Modelled
// on nova_single_node.scala and placement_single_node.scala; read their headers for
// the conventions. No L3 agent, no tenant (VXLAN) networks and no metadata agent
// yet: instances get an address from the DHCP agent on a flat provider network that
// is plain layer 2 on the second NIC, and Nova's metadata API is not deployed (use
// config drive until it is).
//
// Where it runs: tst19, whose second NIC (enp2s0, on the hypervisor's br-net106)
// is the provider network. This playbook first removes the address that NIC was
// given at provisioning (10.10.6.31/24, table 2 policy routing) so it is a plain
// layer-2 port, then makes it a port of the Open vSwitch bridge `br-provider`.
// tst19 stays reachable on its first NIC (10.10.5.31). Keep `node`, `fqdn` and
// `nodeIp` in step with neutron_teardown.scala and test_neutron.sh.
//
// Messaging: its own vhost (`neutron`) and user on the RabbitMQ on tst10, as for Nova.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6 (serving the CA chain)
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - Nova registered (nova_single_node.scala): Neutron tells Nova about port events
//     as the `nova` service user, and Nova calls Neutron as the `neutron` user this
//     playbook creates
//   - agent on tst19; tst9's Kolla cache is optional
//
// Stages:
//   - neutron-database (tst3): the `neutron` database + user.
//   - neutron-rabbitmq (tst10): vhost and user, idempotent.
//   - install-neutron (tst19): wait for apt, packages (podman, openvswitch-switch),
//     make enp2s0 an address-less layer-2 port and put it on br-provider, cache
//     mirror, image pulls, Keystone's CA from its TLS handshake, the config files,
//     Kolla config for the three services, `neutron-db-manage upgrade heads`, the
//     units, then registration in Keystone (user `neutron` in project `service`,
//     service type `network`, three endpoints).
//   - confirm-neutron-healthy (tst19): the API answers and the Open vSwitch and DHCP
//     agents report alive through it.
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. Not compiled where this was written; the first run on scala0 is the
// first compile. The most likely things to need a fix on the first run: the
// `neutron-server` command and `/etc/neutron/rootwrap.conf` inside the image, and
// the privileges the two agents need in their containers.
object neutron_single_node extends OrpheraClusterPlaybook:

  private val node = "tst19"
  private val fqdn = "tst19.ljalbinson.com"
  private val nodeIp = "10.10.5.31"
  private val apiPort = 9696

  // Provider network: physical network name `provider`, mapped to the OVS bridge
  // `br-provider`, which has the NIC `providerNic` as its only physical port.
  private val physnet = "provider"
  private val providerBridge = "br-provider"
  private val providerNic = "enp2s0"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "neutron"
  private val rabbitPassword = "orphera-test-neutron-rabbit-password"
  private val rabbitVhost = "neutron"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbName = "neutron"
  private val dbUser = "neutron"
  private val dbPassword = "orphera-test-neutron-db-password"
  private val neutronPassword = "orphera-test-neutron-service-password"
  // Neutron notifies Nova as the nova service user (created by nova_single_node.scala).
  private val novaPassword = "orphera-test-nova-service-password"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/neutron"
  private val logDir = "/var/log/kolla/neutron"
  private val stateDir = "/var/lib/orphera/neutron"
  private val caDir = "/etc/kolla/neutron-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The three Neutron containers as data (orchestrator/.../kolla/Container.scala, checked by
  // ContainerCheck). All share the config/log/state mounts; the two agents also run privileged
  // in the host's process table with the Open vSwitch socket directory, the network-namespace
  // directory (shared both ways, so namespaces made in the container are visible on the host
  // and the other way round) and the kernel modules. The server needs none of that. Defined
  // after confRoot, logDir and stateDir, which they read; a val used before its definition is
  // null in an object.
  private def neutronContainer(service: String, agent: Boolean): Container =
    val common = List(
      Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
      Mount(logDir, "/var/log/kolla/neutron", Access.ReadWrite),
      Mount(stateDir, "/var/lib/neutron", Access.ReadWrite)
    )
    val agentMounts = List(
      Mount("/run/openvswitch", "/run/openvswitch", Access.Unlabelled),
      Mount("/run/netns", "/run/netns", Access.Shared),
      Mount("/lib/modules", "/lib/modules", Access.ReadOnlyUnlabelled)
    )
    Container(
      name = service,
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = if agent then common ++ agentMounts else common,
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      privileged = agent,
      hostPid = agent,
      after = List("openvswitch-switch.service"),
      preStart =
        if agent then
          List("/bin/sh -c 'mkdir -p /run/netns && (mountpoint -q /run/netns || mount --bind /run/netns /run/netns) && mount --make-rshared /run/netns'")
        else Nil,
      settleSeconds = 12
    )
  private val neutronServer = neutronContainer("neutron-server", agent = false)
  private val neutronServices: List[Container] =
    List(
      neutronServer,
      neutronContainer("neutron-openvswitch-agent", agent = true),
      neutronContainer("neutron-dhcp-agent", agent = true)
    )
  private val serverImage = neutronServer.image.ref
  private val ovsAgentImage = neutronServices(1).image.ref

  // Adds one "write the unit" task per container, then one "start" task per container.
  private def withServices(b: StageBuilder): StageBuilder =
    val written = neutronServices.foldLeft(b)((acc, c) => acc.task(s"write ${c.unitName}")(c.writeUnit))
    neutronServices.foldLeft(written)((acc, c) => acc.task(s"start ${c.unitName}")(c.start))

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3", "openvswitch-switch")

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

  // No {{...}} in any command: Orphera renders those as a template before running
  // the command (see nova_single_node.scala).
  private val rabbitScript =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - run cinder_single_node.scala first" >&2
       |  exit 1
       |fi
       |if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$rabbitVhost'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$rabbitUser' '$rabbitPassword'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$rabbitUser' '$rabbitPassword'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$rabbitVhost' '$rabbitUser' '.*' '.*' '.*'
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser ready"""".stripMargin

  // ---- the node --------------------------------------------------------

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

  // The provisioning script gave enp2s0 an address, a table-2 route and a
  // "from <address>" rule (netplan, generated by cloud-init). Neutron needs the
  // port to be plain layer 2, so: stop cloud-init regenerating the network config,
  // strip the address, routes, routing policy and nameservers of that interface
  // from /etc/netplan/50-cloud-init.yaml (kept: name, MAC match, MTU), apply, and
  // clear what is left in the kernel. The management NIC is not touched. Safe to
  // re-run (nothing to strip the second time). Needs PyYAML, which cloud-init
  // already depends on.
  private val dropAddressScript =
    s"""set -e
       |mkdir -p /etc/cloud/cloud.cfg.d
       |printf 'network: {config: disabled}\\n' > /etc/cloud/cloud.cfg.d/99-orphera-disable-network-config.cfg
       |python3 - <<'PY'
       |import sys, yaml
       |path = '/etc/netplan/50-cloud-init.yaml'
       |nic = '$providerNic'
       |try:
       |    doc = yaml.safe_load(open(path))
       |except FileNotFoundError:
       |    print('no %s - nothing to strip' % path); sys.exit(0)
       |changed = False
       |for name, cfg in (doc.get('network', {}).get('ethernets', {}) or {}).items():
       |    if name == nic or cfg.get('set-name') == nic:
       |        for key in ('addresses', 'routes', 'routing-policy', 'nameservers', 'gateway4', 'gateway6'):
       |            if key in cfg:
       |                del cfg[key]; changed = True
       |        cfg['dhcp4'] = False
       |        cfg['dhcp6'] = False
       |        cfg['link-local'] = []
       |if changed:
       |    yaml.safe_dump(doc, open(path, 'w'), default_flow_style=False)
       |    print('removed the address and policy routing of %s from %s' % (nic, path))
       |else:
       |    print('%s already has no address in %s' % (nic, path))
       |PY
       |chmod 600 /etc/netplan/50-cloud-init.yaml
       |netplan apply
       |ip addr flush dev $providerNic || true
       |while ip rule del priority 100 2>/dev/null; do :; done
       |ip route flush table 2 2>/dev/null || true
       |ip -4 -br addr show $providerNic
       |echo "$providerNic is an address-less layer-2 port"""".stripMargin

  // Open vSwitch comes from the host (apt). br-provider's only physical port is the
  // provider NIC; the OVS agent in the container creates br-int and the patch
  // ports. Idempotent.
  private val bridgeScript =
    s"""set -e
       |systemctl enable --now openvswitch-switch
       |ovs-vsctl --may-exist add-br $providerBridge
       |ovs-vsctl --may-exist add-port $providerBridge $providerNic
       |ip link set $providerBridge up
       |ovs-vsctl show
       |echo "$providerBridge has $providerNic"""".stripMargin

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
    s"""for img in ${neutronServices.map(_.image.ref).mkString(" ")}; do
       |  podman pull $$img || { echo "could not pull $$img - check the Image(...) in neutronContainer in neutron_single_node.scala" >&2; exit 1; }
       |done
       |echo "images ready"""".stripMargin

  // Same method as nova_single_node.scala: the last certificate Keystone presents
  // is its CA; trust on first use.
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

  // Four small files. The heredocs are quoted so the shell expands nothing; Scala
  // has already filled in the values.
  private val neutronConfScript =
    s"""mkdir -p $srcDir $logDir $stateDir
       |cat > $srcDir/neutron.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/neutron
       |state_path = /var/lib/neutron
       |use_stderr = true
       |core_plugin = ml2
       |service_plugins =
       |auth_strategy = keystone
       |transport_url = rabbit://$rabbitUser:$rabbitPassword@$rabbitIp:5672/$rabbitVhost
       |notify_nova_on_port_status_changes = true
       |notify_nova_on_port_data_changes = true
       |api_workers = 2
       |rpc_workers = 2
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
       |username = neutron
       |password = $neutronPassword
       |cafile = /etc/neutron/ssl/ca.crt
       |
       |[nova]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |region_name = RegionOne
       |project_name = service
       |username = nova
       |password = $novaPassword
       |endpoint_type = internal
       |cafile = /etc/neutron/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/neutron/tmp
       |
       |[agent]
       |root_helper = sudo neutron-rootwrap /etc/neutron/rootwrap.conf
       |root_helper_daemon = sudo neutron-rootwrap-daemon /etc/neutron/rootwrap.conf
       |CONF
       |cat > $srcDir/ml2_conf.ini <<'CONF'
       |[ml2]
       |type_drivers = flat
       |tenant_network_types =
       |mechanism_drivers = openvswitch
       |extension_drivers = port_security
       |
       |[ml2_type_flat]
       |flat_networks = $physnet
       |
       |[securitygroup]
       |enable_security_group = true
       |CONF
       |cat > $srcDir/openvswitch_agent.ini <<'CONF'
       |[ovs]
       |bridge_mappings = $physnet:$providerBridge
       |
       |[securitygroup]
       |firewall_driver = openvswitch
       |enable_security_group = true
       |
       |[agent]
       |tunnel_types =
       |CONF
       |cat > $srcDir/dhcp_agent.ini <<'CONF'
       |[DEFAULT]
       |interface_driver = openvswitch
       |dhcp_driver = neutron.agent.linux.dhcp.Dnsmasq
       |enable_isolated_metadata = false
       |CONF
       |chmod 640 $srcDir/neutron.conf $srcDir/ml2_conf.ini $srcDir/openvswitch_agent.ini $srcDir/dhcp_agent.ini
       |echo "neutron config files written"""".stripMargin

  // One config directory per service (Kolla mounts it at
  // /var/lib/kolla/config_files). The command for the server is checked in the
  // image first, as the WSGI paths are in the other playbooks.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/neutron-server $confRoot/neutron-openvswitch-agent $confRoot/neutron-dhcp-agent
       |for svc in server openvswitch-agent dhcp-agent; do
       |  cp $srcDir/neutron.conf $caDir/ca.crt $confRoot/neutron-$$svc/
       |done
       |cp $srcDir/ml2_conf.ini $confRoot/neutron-server/
       |cp $srcDir/openvswitch_agent.ini $confRoot/neutron-openvswitch-agent/
       |cp $srcDir/dhcp_agent.ini $confRoot/neutron-dhcp-agent/
       |if ! podman run --rm --entrypoint sh $serverImage -c 'command -v neutron-server >/dev/null'; then
       |  echo "neutron-server not found in $serverImage - see the header of neutron_single_node.scala" >&2
       |  exit 1
       |fi
       |if ! podman run --rm --entrypoint sh $ovsAgentImage -c 'test -f /etc/neutron/rootwrap.conf'; then
       |  echo "/etc/neutron/rootwrap.conf not found in $ovsAgentImage; found:" >&2
       |  podman run --rm --entrypoint sh $ovsAgentImage -c 'find / -name "rootwrap*.conf" -path "*neutron*" 2>/dev/null | head' >&2
       |  exit 1
       |fi
       |COMMON='{"source": "/var/lib/kolla/config_files/neutron.conf", "dest": "/etc/neutron/neutron.conf", "owner": "neutron", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/neutron/ssl/ca.crt", "owner": "neutron", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/neutron", "owner": "neutron:kolla", "recurse": true}, {"path": "/var/lib/neutron", "owner": "neutron:neutron", "recurse": true}]'
       |cat > $confRoot/neutron-server/config.json <<JSON
       |{"command": "neutron-server --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/ml2_conf.ini", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/ml2_conf.ini", "dest": "/etc/neutron/plugins/ml2/ml2_conf.ini", "owner": "neutron", "perm": "0600"}], $$PERMS}
       |JSON
       |cat > $confRoot/neutron-openvswitch-agent/config.json <<JSON
       |{"command": "neutron-openvswitch-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/openvswitch_agent.ini", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/openvswitch_agent.ini", "dest": "/etc/neutron/plugins/ml2/openvswitch_agent.ini", "owner": "neutron", "perm": "0600"}], $$PERMS}
       |JSON
       |cat > $confRoot/neutron-dhcp-agent/config.json <<JSON
       |{"command": "neutron-dhcp-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/dhcp_agent.ini", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/dhcp_agent.ini", "dest": "/etc/neutron/dhcp_agent.ini", "owner": "neutron", "perm": "0600"}], $$PERMS}
       |JSON
       |echo "kolla config written under $confRoot"""".stripMargin

  // neutron-db-manage runs in a one-off container with the two config files
  // bind-mounted (entrypoint overridden, so kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint neutron-db-manage -v $srcDir/neutron.conf:/etc/neutron/neutron.conf:ro,z -v $srcDir/ml2_conf.ini:/etc/neutron/plugins/ml2/ml2_conf.ini:ro,z -v $logDir:/var/log/kolla/neutron:z $serverImage --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/ml2_conf.ini upgrade heads
       |echo "neutron database synced"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled
  // in with replace(). Subcommands:
  //   register | unregister | catalog | agents | agents-up |
  //   net-list | net-create | net-delete <id>
  // (net-create makes a flat network on the physical network `provider` and a
  // subnet with DHCP off, net-delete removes both: that exercises the API, the
  // database and the ML2 flat type driver. Only one flat network can exist per
  // physical network, so net-create fails with a clear message if one is there.)
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
NEUTRON = '@@NEUTRON@@'
ADMIN_PW = '@@ADMINPW@@'
NEUTRON_PW = '@@NEUTRONPW@@'
PHYSNET = '@@PHYSNET@@'
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
    user = find(token, '/users?name=neutron', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'neutron', 'domain_id': 'default', 'password': NEUTRON_PW}}, token)
        if s != 201:
            die('creating user neutron failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': NEUTRON_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to neutron failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=network', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'neutron', 'type': 'network'}}, token)
        if s != 201:
            die('creating service neutron failed: HTTP %s %s' % (s, p))
        service = p['service']
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': NEUTRON, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != NEUTRON:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': NEUTRON}}, token)
    print('registered neutron in keystone (project service, user neutron, service type network, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=network', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=neutron', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed neutron from keystone (service network, its endpoints, user neutron)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def agent_list():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', NEUTRON + '/v2.0/agents', None, token)
    if s != 200:
        die('agent list failed: HTTP %s %s' % (s, p))
    return p.get('agents', [])


def agents():
    rows = agent_list()
    for a in rows:
        print('agent %s host=%s alive=%s admin_state_up=%s' % (a.get('binary'), a.get('host'), a.get('alive'), a.get('admin_state_up')))
    print('agents: %d' % len(rows))


def agents_up():
    up = set(a.get('binary') for a in agent_list() if a.get('alive') and a.get('admin_state_up'))
    missing = [b for b in ('neutron-openvswitch-agent', 'neutron-dhcp-agent') if b not in up]
    if missing:
        die('not alive yet: ' + ', '.join(missing))
    print('neutron-openvswitch-agent and neutron-dhcp-agent are alive')


def net_list():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', NEUTRON + '/v2.0/networks', None, token)
    if s != 200:
        die('network list failed: HTTP %s %s' % (s, p))
    for n in p.get('networks', []):
        print('network %s name=%s' % (n.get('id'), n.get('name')))
    print('networks: %d' % len(p.get('networks', [])))


def net_create():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    body = {'network': {'name': 'orphera-test-%d' % int(time.time()), 'provider:network_type': 'flat', 'provider:physical_network': PHYSNET}}
    s, p, _ = call('POST', NEUTRON + '/v2.0/networks', body, token)
    if s == 409:
        die('a flat network on physical network %s already exists (only one is allowed): %s' % (PHYSNET, p))
    if s not in (200, 201):
        die('network create failed: HTTP %s %s' % (s, p))
    nid = p['network']['id']
    sub = {'subnet': {'network_id': nid, 'ip_version': 4, 'cidr': '10.99.99.0/24', 'enable_dhcp': False, 'name': 'orphera-test-subnet'}}
    s, p, _ = call('POST', NEUTRON + '/v2.0/subnets', sub, token)
    if s not in (200, 201):
        call('DELETE', NEUTRON + '/v2.0/networks/' + nid, None, token)
        die('subnet create failed: HTTP %s %s' % (s, p))
    s, p, _ = call('GET', NEUTRON + '/v2.0/networks/' + nid, None, token)
    if s != 200:
        die('created network %s cannot be read back: HTTP %s %s' % (nid, s, p))
    print(nid)


def net_delete(nid):
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', NEUTRON + '/v2.0/subnets?network_id=' + nid, None, token)
    for sub in p.get('subnets', []):
        call('DELETE', NEUTRON + '/v2.0/subnets/' + sub['id'], None, token)
    s, p, _ = call('DELETE', NEUTRON + '/v2.0/networks/' + nid, None, token)
    if s not in (200, 204):
        die('network delete failed: HTTP %s %s' % (s, p))
    s, p, _ = call('GET', NEUTRON + '/v2.0/networks/' + nid, None, token)
    if s != 404:
        die('network %s still present: HTTP %s' % (nid, s))
    print('deleted ' + nid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'agents':
        agents()
    elif cmd == 'agents-up':
        agents_up()
    elif cmd == 'net-list':
        net_list()
    elif cmd == 'net-create':
        net_create()
    elif cmd == 'net-delete' and len(sys.argv) > 2:
        net_delete(sys.argv[2])
    else:
        die('usage: orphera-neutron-api register | unregister | catalog | agents | agents-up | net-list | net-create | net-delete <id>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-neutron-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@NEUTRON@@", s"http://$fqdn:$apiPort")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@NEUTRONPW@@", neutronPassword)
        .replace("@@PHYSNET@@", physnet)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-neutron-api\necho wrote /usr/local/sbin/orphera-neutron-api\n"

  // The root answers its version document without a token (200, or 300).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("neutron-single-node")(
      stage("neutron-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("neutron-rabbitmq", rabbitNode)
        .task(s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      withServices(
        stage("install-neutron", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(List("sh", "-c", waitAptIdleScript), timeoutSeconds = 960)
        )
        .task("install podman, Open vSwitch and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task(s"make $providerNic an address-less layer-2 port")(
          Task.RunCommand(List("sh", "-c", dropAddressScript), timeoutSeconds = 120)
        )
        .task(s"create $providerBridge with $providerNic as its port")(
          Task.RunCommand(List("sh", "-c", bridgeScript), timeoutSeconds = 60)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Neutron images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the Neutron config files")(
          Task.RunCommand(List("sh", "-c", neutronConfScript))
        )
        .task("write the Kolla config for the three services")(
          Task.RunCommand(List("sh", "-c", kollaConfigScript), timeoutSeconds = 180)
        )
        .task("neutron-db-manage upgrade heads")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 900)
        )
        .task("write the Neutron API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Neutron in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-neutron-api register"),
            timeoutSeconds = 120
          )
        )
      ).build,

      stage("confirm-neutron-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("the Open vSwitch and DHCP agents report in through the API")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "for i in $(seq 1 30); do /usr/local/sbin/orphera-neutron-api agents-up && exit 0; sleep 5; done; /usr/local/sbin/orphera-neutron-api agents; exit 1"
            ),
            timeoutSeconds = 240
          )
        )
        .task("neutron confirmed")(
          Task.Debug(
            s"Neutron up at http://$fqdn:$apiPort ($nodeIp): neutron-server, Open vSwitch agent and DHCP agent, flat provider network `$physnet` on $providerBridge/$providerNic, RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database via $vip:3306, identity from $ksUrl. No L3, tenant networks or metadata yet. Test: manifests/test_neutron.sh"
          )
        )
        .build
    )
