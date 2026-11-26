// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Neutron: neutron-server, the Open vSwitch agent, the DHCP agent and
// the L3 agent on one node, Kolla images under podman, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing,
// and `--check` shows what would change. Modelled on nova_single_node.scala and
// placement_single_node.scala, which explain the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them
// in this order:
//
//   orphera apply manifests/neutron_database.scala     # the database and user (tst3)
//   orphera apply manifests/neutron_rabbitmq.scala     # the `neutron` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/neutron_single_node.scala  # this one (tst19)
//
// Networks. Tenant networks are VXLAN (VNI 1-1000). The tunnel endpoint of each
// node is its management address (10.10.5.x, UDP 4789 between the nodes; nothing
// here opens a firewall), so tenant ports get an MTU of 1450 from Neutron's own
// calculation. The flat provider network `provider` (plain layer 2 on the second
// NIC) is the external network: routers take their gateway address from it and
// floating IPs come from it. The last step of this description makes it (flat,
// shared, external, 10.10.6.0/24 with DHCP, pool .200-.220) and keeps it that
// way; Magnum, Trove and the compute boot test find it by name. The L3 agent
// does the NAT in router namespaces on this node. There is no metadata agent: Nova's metadata API is not deployed, so
// instances use a config drive. The compute nodes need the matching VXLAN
// settings (nova_compute.scala); apply that description after this one.
//
// Where it runs: tst19, whose second NIC (enp2s0, on the hypervisor's br-net106)
// is the provider network. This description first removes the address that NIC
// was given at provisioning (10.10.6.31/24, table 2 policy routing) so it is a
// plain layer-2 port, then makes it a port of the Open vSwitch bridge
// `br-provider`. tst19 stays reachable on its first NIC (10.10.5.31). Keep
// `node`, `fqdn` and `nodeIp` in step with neutron_teardown.scala and
// test_neutron.sh.
//
// Messaging: its own vhost (`neutron`) and user on the RabbitMQ on tst10, as for
// Nova (neutron_rabbitmq.scala).
//
// Prerequisites, not checked here: Galera + VIP healthy, Keystone up on tst6
// (serving the CA chain), RabbitMQ running on tst10 (rabbitmq_single_node.scala),
// Nova registered (nova_single_node.scala): Neutron tells Nova about port events
// as the `nova` service user, and Nova calls Neutron as the `neutron` user this
// description registers. The agent on tst19; tst9's Kolla cache is optional.
//
// How it stays idempotent (as for Nova):
//   - The provider NIC is "done" when the netplan file has no address, route,
//     policy or nameserver for it and the kernel has no IPv4 address on it; the
//     bridge is "done" when it exists, has the NIC as a port and is up.
//   - Keystone's CA is compared as a certificate with the installed copy each
//     run; each service's copy is refreshed (and the service restarts) when it
//     changes.
//   - neutron.conf holds secrets, so it is a secret file in each service's Kolla
//     directory; each agent's own .ini and config.json are plain files. A service
//     restarts only when its own files or the CA changed.
//   - `neutron-db-manage upgrade heads` is skipped while its stamp matches and a
//     query as the neutron user finds the `networks` table.
//   - The Keystone registration is skipped while the registration check
//     passes. Registration comes before the units start.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object neutron_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst19"
  private val fqdn = "tst19.ljalbinson.com"
  private val nodeIp = "10.10.5.31"
  private val apiPort = 9696

  override def nodes: List[String] = List(node)

  // Provider network: physical network name `provider`, mapped to the OVS bridge
  // `br-provider`, which has the NIC `providerNic` as its only physical port.
  private val physnet = "provider"
  private val providerBridge = "br-provider"
  private val providerNic = "enp2s0"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "neutron"
  private val rabbitPassword = Secrets.secret("neutron/rabbit")
  private val rabbitVhost = "neutron"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "neutron"
  private val dbUser = "neutron"
  private val dbPassword = Secrets.secret("neutron/db")
  private val neutronPassword = Secrets.secret("neutron/service")
  // Neutron notifies Nova as the nova service user (created by nova_single_node.scala).
  private val novaPassword = Secrets.secret("nova/service")

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/neutron"
  private val stateDir = "/var/lib/orphera/neutron"
  private val caDir = "/etc/kolla/neutron-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The four Neutron containers as data (orchestrator/.../kolla/Container.scala, checked by
  // ContainerCheck). All share the config/log/state mounts; the three agents also run privileged
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
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = if agent then common ++ agentMounts else common,
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      privileged = agent,
      hostPid = agent,
      after = List("openvswitch-switch.service"),
      preStart =
        if agent then
          List(
            "/bin/sh -c 'mkdir -p /run/netns && (mountpoint -q /run/netns || mount --bind /run/netns /run/netns) && mount --make-rshared /run/netns'"
          )
        else Nil,
      settleSeconds = 12
    )
  private val neutronServer = neutronContainer("neutron-server", agent = false)
  private val neutronServices: List[Container] =
    List(
      neutronServer,
      neutronContainer("neutron-openvswitch-agent", agent = true),
      neutronContainer("neutron-dhcp-agent", agent = true),
      neutronContainer("neutron-l3-agent", agent = true)
    )
  private val serviceNames =
    List(
      "neutron-server",
      "neutron-openvswitch-agent",
      "neutron-dhcp-agent",
      "neutron-l3-agent"
    )
  private val serverImage = neutronServer.image.ref
  private val ovsAgentImage = neutronServices(1).image.ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "openvswitch-switch"
    )

  // The provisioning script gave enp2s0 an address, a table-2 route and a
  // "from <address>" rule (netplan, generated by cloud-init). Neutron needs the
  // port to be plain layer 2, so: stop cloud-init regenerating the network config,
  // strip the address, routes, routing policy and nameservers of that interface
  // from /etc/netplan/50-cloud-init.yaml (kept: name, MAC match, MTU), apply, and
  // clear what is left in the kernel. The management NIC is not touched. Needs
  // PyYAML, which cloud-init already depends on.
  //
  // `stripPython` edits the file, or, with `check` as its argument, only says
  // (exit status) whether there is anything to strip.
  private val stripPython =
    s"""import sys, yaml
       |path = '/etc/netplan/50-cloud-init.yaml'
       |nic = '$providerNic'
       |check = len(sys.argv) > 1 and sys.argv[1] == 'check'
       |try:
       |    doc = yaml.safe_load(open(path))
       |except FileNotFoundError:
       |    print('no %s - nothing to strip' % path); sys.exit(0)
       |changed = False
       |for name, cfg in (doc.get('network', {}).get('ethernets', {}) or {}).items():
       |    if name == nic or cfg.get('set-name') == nic:
       |        for key in ('addresses', 'routes', 'routing-policy', 'nameservers', 'gateway4', 'gateway6'):
       |            if key in cfg:
       |                if not check:
       |                    del cfg[key]
       |                changed = True
       |        if cfg.get('dhcp4') is not False or cfg.get('dhcp6') is not False or cfg.get('link-local') != []:
       |            if not check:
       |                cfg['dhcp4'] = False
       |                cfg['dhcp6'] = False
       |                cfg['link-local'] = []
       |            changed = True
       |if check:
       |    sys.exit(1 if changed else 0)
       |if changed:
       |    yaml.safe_dump(doc, open(path, 'w'), default_flow_style=False)
       |    print('removed the address and policy routing of %s from %s' % (nic, path))
       |else:
       |    print('%s already has no address in %s' % (nic, path))""".stripMargin

  private val cloudInitOff =
    "/etc/cloud/cloud.cfg.d/99-orphera-disable-network-config.cfg"
  private val dropAddressScript =
    s"""set -e
       |mkdir -p /etc/cloud/cloud.cfg.d
       |printf 'network: {config: disabled}\\n' > $cloudInitOff
       |python3 - <<'PY'
       |$stripPython
       |PY
       |chmod 600 /etc/netplan/50-cloud-init.yaml
       |netplan apply
       |ip addr flush dev $providerNic || true
       |while ip rule del priority 100 2>/dev/null; do :; done
       |ip route flush table 2 2>/dev/null || true
       |ip -4 -br addr show $providerNic
       |echo "$providerNic is an address-less layer-2 port"""".stripMargin
  // Done when cloud-init is told to leave the network alone, the netplan file
  // has nothing to strip, and the kernel has no IPv4 address on the NIC.
  private val addressDropped =
    s"""[ -f $cloudInitOff ] && python3 - check <<'PY' && ! ip -4 -o addr show dev $providerNic | grep -q inet
       |$stripPython
       |PY""".stripMargin

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

  private val images = neutronServices.map(_.image.ref)

  // The server's command and the rootwrap config must be in the images.
  private val imagesCarry =
    s"""podman run --rm --entrypoint sh $serverImage -c 'command -v neutron-server >/dev/null' && podman run --rm --entrypoint sh $ovsAgentImage -c 'test -f /etc/neutron/rootwrap.conf'"""
  private val imagesCarryFail =
    s"""echo "neutron-server or /etc/neutron/rootwrap.conf not found in the images ($serverImage, $ovsAgentImage) - see the header of neutron_single_node.scala" >&2; exit 1"""

  // The option lists of the image (neutron), from catalogues/. Every section and key of the
  // files below is checked against them when this manifest is built.
  private val neutronCatalogue: Catalogue =
    Catalogues.load("neutron", Set.empty[String])

  private val neutronConf: ConfFile =
    ConfFile.text(s"$confRoot/neutron-server/neutron.conf", neutronCatalogue)(
      ("DEFAULT", "log_dir", "/var/log/kolla/neutron"),
      ("DEFAULT", "state_path", "/var/lib/neutron"),
      ("DEFAULT", "use_stderr", "true"),
      ("DEFAULT", "core_plugin", "ml2"),
      ("DEFAULT", "service_plugins", "router"),
      ("DEFAULT", "auth_strategy", "keystone"),
      (
        "DEFAULT",
        "transport_url",
        s"rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost"
      ),
      ("DEFAULT", "notify_nova_on_port_status_changes", "true"),
      ("DEFAULT", "notify_nova_on_port_data_changes", "true"),
      ("DEFAULT", "api_workers", "2"),
      ("DEFAULT", "rpc_workers", "2"),
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
      ("keystone_authtoken", "username", "neutron"),
      ("keystone_authtoken", "password", s"${neutronPassword.reveal}"),
      ("keystone_authtoken", "cafile", "/etc/neutron/ssl/ca.crt"),
      ("nova", "auth_url", s"$ksUrl"),
      ("nova", "auth_type", "password"),
      ("nova", "project_domain_name", "Default"),
      ("nova", "user_domain_name", "Default"),
      ("nova", "region_name", "RegionOne"),
      ("nova", "project_name", "service"),
      ("nova", "username", "nova"),
      ("nova", "password", s"${novaPassword.reveal}"),
      ("nova", "endpoint_type", "internal"),
      ("nova", "cafile", "/etc/neutron/ssl/ca.crt"),
      ("oslo_concurrency", "lock_path", "/var/lib/neutron/tmp"),
      (
        "agent",
        "root_helper",
        "sudo neutron-rootwrap /etc/neutron/rootwrap.conf"
      ),
      (
        "agent",
        "root_helper_daemon",
        "sudo neutron-rootwrap-daemon /etc/neutron/rootwrap.conf"
      )
    )

  private val ml2ConfIni: ConfFile =
    ConfFile.text(s"$confRoot/neutron-server/ml2_conf.ini", neutronCatalogue)(
      ("ml2", "type_drivers", "flat,vxlan"),
      ("ml2", "tenant_network_types", "vxlan"),
      ("ml2", "mechanism_drivers", "openvswitch,l2population"),
      ("ml2", "extension_drivers", "port_security"),
      ("ml2_type_flat", "flat_networks", s"$physnet"),
      ("ml2_type_vxlan", "vni_ranges", "1:1000"),
      ("securitygroup", "enable_security_group", "true")
    )

  private val openvswitchAgentIni: ConfFile =
    ConfFile.text(
      s"$confRoot/neutron-server/openvswitch_agent.ini",
      neutronCatalogue
    )(
      ("ovs", "bridge_mappings", s"$physnet:$providerBridge"),
      ("ovs", "local_ip", nodeIp),
      ("securitygroup", "firewall_driver", "openvswitch"),
      ("securitygroup", "enable_security_group", "true"),
      ("agent", "tunnel_types", "vxlan"),
      ("agent", "l2_population", "true")
    )

  // The DHCP agent's dnsmasq runs in a network namespace, where the host's resolver stub
  // (127.0.0.53) does not exist, so without upstream servers the guests' DNS (the DHCP port) answers
  // nothing for outside names. These are the lab's resolvers (the dns1 and dns2 of config/*.yaml);
  // NEUTRON_DNS_SERVERS (comma-separated) overrides them.
  private val dnsServers = sys.env
    .get("NEUTRON_DNS_SERVERS")
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("192.168.1.70,192.168.1.71")

  private val dhcpAgentIni: ConfFile =
    ConfFile.text(s"$confRoot/neutron-server/dhcp_agent.ini", neutronCatalogue)(
      ("DEFAULT", "interface_driver", "openvswitch"),
      ("DEFAULT", "dhcp_driver", "neutron.agent.linux.dhcp.Dnsmasq"),
      ("DEFAULT", "enable_isolated_metadata", "false"),
      ("DEFAULT", "dnsmasq_dns_servers", dnsServers)
    )

  // Routers and floating IPs: legacy (centralised) routers in namespaces on this node. No
  // external_network_bridge: the external network is the flat provider network, wired to
  // br-provider by the Open vSwitch agent's bridge mapping. No metadata proxy (no metadata
  // agent; instances use a config drive).
  private val l3AgentIni: ConfFile =
    ConfFile.text(s"$confRoot/neutron-server/l3_agent.ini", neutronCatalogue)(
      ("DEFAULT", "interface_driver", "openvswitch"),
      ("DEFAULT", "agent_mode", "legacy"),
      ("DEFAULT", "enable_metadata_proxy", "false")
    )

  private val neutronConfText: String = neutronConf.render
  private val ml2Text: String = ml2ConfIni.render
  private val ovsAgentText: String = openvswitchAgentIni.render
  private val dhcpText: String = dhcpAgentIni.render
  private val l3Text: String = l3AgentIni.render

  // The files every service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/neutron.conf", "dest": "/etc/neutron/neutron.conf", "owner": "neutron", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/neutron/ssl/ca.crt", "owner": "neutron", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/neutron", "owner": "neutron:kolla", "recurse": true}, {"path": "/var/lib/neutron", "owner": "neutron:neutron", "recurse": true}]"""

  // One entry per service: its command line, and the .ini it carries (source
  // name, destination under /etc/neutron, text).
  private final case class Svc(
      name: String,
      command: String,
      ini: String,
      iniDest: String,
      iniText: String
  )
  private val svcs = List(
    Svc(
      "neutron-server",
      "neutron-server --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/ml2_conf.ini",
      "ml2_conf.ini",
      "/etc/neutron/plugins/ml2/ml2_conf.ini",
      ml2Text
    ),
    Svc(
      "neutron-openvswitch-agent",
      "neutron-openvswitch-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/openvswitch_agent.ini",
      "openvswitch_agent.ini",
      "/etc/neutron/plugins/ml2/openvswitch_agent.ini",
      ovsAgentText
    ),
    Svc(
      "neutron-dhcp-agent",
      "neutron-dhcp-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/dhcp_agent.ini",
      "dhcp_agent.ini",
      "/etc/neutron/dhcp_agent.ini",
      dhcpText
    ),
    Svc(
      "neutron-l3-agent",
      "neutron-l3-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/l3_agent.ini",
      "l3_agent.ini",
      "/etc/neutron/l3_agent.ini",
      l3Text
    )
  )
  private def configJson(s: Svc): String =
    s"""{"command": "${s.command}", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/${s.ini}", "dest": "${s.iniDest}", "owner": "neutron", "perm": "0600"}], $permissions}
       |""".stripMargin

  // neutron-db-manage runs in a one-off container with the two config files
  // bind-mounted (entrypoint overridden, so kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint neutron-db-manage -v $confRoot/neutron-server/neutron.conf:/etc/neutron/neutron.conf:ro,z -v $confRoot/neutron-server/ml2_conf.ini:/etc/neutron/plugins/ml2/ml2_conf.ini:ro,z -v $logDir:/var/log/kolla/neutron:z $serverImage --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/ml2_conf.ini upgrade heads
       |echo "neutron database synced"""".stripMargin
  private val dbStamp =
    Resource.sha256(dbSyncScript + neutronConfText + ml2Text)

  // True when the database, queried as the neutron user through the VIP, has the
  // `networks` table. The query runs with the image's own Python (the
  // interpreter named on the first line of neutron-db-manage), which has pymysql.
  private val dbHasTables =
    s"""podman run --rm -i --network host --entrypoint sh $serverImage -c 'exec $$(head -1 "$$(command -v neutron-db-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |    c.cursor().execute('SELECT COUNT(*) FROM networks')
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbHasTables""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled
  // in with replace(). Subcommands:
  //   catalog | agents | agents-up |
  //   net-list | net-create | net-delete <id> | tenant-cycle
  // (net-create makes a flat network on the physical network `provider` and a
  // subnet with DHCP off, net-delete removes both: that exercises the API, the
  // database and the ML2 flat type driver. Only one flat network can exist per
  // physical network, so net-create fails with a clear message if one is there.
  // tenant-cycle makes a VXLAN tenant network and subnet, a router with its gateway on the
  // external network and the subnet as an interface, and a floating IP, waits for the router
  // and its ports to go ACTIVE, and removes everything. It uses the existing external network,
  // or a temporary flat external one if there is none; it refuses to run if the only flat
  // network on the physical network is not marked external.
  // The persistent flat network `provider` (shared, external, subnet 10.10.6.0/24 with DHCP, pool
  // .200-.220), which Magnum, Trove and nova_compute.scala's boot test use, is made by the
  // description with openstackNetwork, not by this helper.)
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
NEUTRON = '@@NEUTRON@@'
ADMIN_PW = '@@ADMINPW@@'
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
    missing = [b for b in ('neutron-openvswitch-agent', 'neutron-dhcp-agent', 'neutron-l3-agent') if b not in up]
    if missing:
        die('not alive yet: ' + ', '.join(missing))
    print('neutron-openvswitch-agent, neutron-dhcp-agent and neutron-l3-agent are alive')


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


def get_json(token, path):
    s, p, _ = call('GET', NEUTRON + '/v2.0' + path, None, token)
    if s != 200:
        die('GET %s failed: HTTP %s %s' % (path, s, p))
    return p


def tenant_cycle():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    base = NEUTRON + '/v2.0'

    def api(method, path, body=None, ok=(200, 201, 204)):
        s, p, _ = call(method, base + path, body, token)
        if s not in ok:
            die('%s %s failed: HTTP %s %s' % (method, path, s, p))
        return p

    def wait(what, check, tries=30, delay=3):
        for _ in range(tries):
            r = check()
            if r:
                return r
            time.sleep(delay)
        die('timed out waiting for ' + what)

    nets = get_json(token, '/networks').get('networks', [])
    ext = None
    for n in nets:
        if n.get('router:external'):
            ext = n
            break
    undo = []
    stamp = int(time.time())
    iface = None
    router = None
    try:
        if ext is None:
            for n in nets:
                if n.get('provider:physical_network') == PHYSNET:
                    die('the flat network %s (%s) on %s is not external; run: openstack network set --external %s' % (n.get('name'), n['id'], PHYSNET, n['id']))
            ext = api('POST', '/networks', {'network': {'name': 'orphera-ext-%d' % stamp, 'router:external': True, 'provider:network_type': 'flat', 'provider:physical_network': PHYSNET}})['network']
            undo.append('/networks/' + ext['id'])
            sub = api('POST', '/subnets', {'subnet': {'network_id': ext['id'], 'ip_version': 4, 'cidr': '10.99.98.0/24', 'enable_dhcp': False, 'name': 'orphera-ext-subnet'}})['subnet']
            undo.append('/subnets/' + sub['id'])

        net = api('POST', '/networks', {'network': {'name': 'orphera-tenant-%d' % stamp}})['network']
        undo.append('/networks/' + net['id'])
        if net.get('provider:network_type') != 'vxlan':
            die('the tenant network is %s, expected vxlan' % net.get('provider:network_type'))
        subnet = api('POST', '/subnets', {'subnet': {'network_id': net['id'], 'ip_version': 4, 'cidr': '192.168.77.0/24', 'name': 'orphera-tenant-subnet'}})['subnet']
        undo.append('/subnets/' + subnet['id'])

        router = api('POST', '/routers', {'router': {'name': 'orphera-router-%d' % stamp, 'external_gateway_info': {'network_id': ext['id']}}})['router']
        undo.append('/routers/' + router['id'])
        api('PUT', '/routers/%s/add_router_interface' % router['id'], {'subnet_id': subnet['id']})
        iface = subnet['id']

        def router_active():
            r = api('GET', '/routers/' + router['id'])['router']
            return r if r.get('status') == 'ACTIVE' else None

        wait('the router to go ACTIVE (is neutron-l3-agent running?)', router_active)

        def ports_active():
            ports = api('GET', '/ports?device_id=' + router['id'])['ports']
            owners = set(p['device_owner'] for p in ports if p.get('status') == 'ACTIVE')
            return {'network:router_gateway', 'network:router_interface'} <= owners

        wait('the router gateway and interface ports to go ACTIVE', ports_active)

        fip = api('POST', '/floatingips', {'floatingip': {'floating_network_id': ext['id']}})['floatingip']
        undo.append('/floatingips/' + fip['id'])
        print('tenant cycle ok: vxlan segment %s, router ACTIVE with gateway on %s, floating IP %s' % (net.get('provider:segmentation_id'), ext.get('name'), fip.get('floating_ip_address')))
    finally:
        if iface is not None:
            s, p, _ = call('PUT', base + '/routers/%s/remove_router_interface' % router['id'], {'subnet_id': iface}, token)
        failed = []
        for path in reversed(undo):
            s, p, _ = call('DELETE', base + path, None, token)
            if s not in (200, 204, 404):
                failed.append('%s (HTTP %s)' % (path, s))
        if failed:
            sys.stderr.write('could not remove: ' + ', '.join(failed) + '\n')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'catalog':
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
    elif cmd == 'tenant-cycle':
        tenant_cycle()
    else:
        die('usage: orphera-neutron-api catalog | agents | agents-up | net-list | net-create | net-delete <id> | tenant-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@NEUTRON@@", s"http://$fqdn:$apiPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@PHYSNET@@", physnet)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-neutron-api"

  private val agentsUp = s"$helperPath agents-up >/dev/null 2>&1"
  private val waitAgentsUp =
    s"""for i in $$(seq 1 30); do $helperPath agents-up && exit 0; sleep 5; done
       |$helperPath agents
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val ovsPackage = tools.last

    val ovsService = service("openvswitch-switch.service").after(ovsPackage)
    val noAddress = command(
      s"make $providerNic an address-less layer-2 port",
      dropAddressScript,
      unless = addressDropped,
      timeout = 120
    ).after(tools*)
    val bridge = ovsBridge(
      s"create $providerBridge with $providerNic as its port",
      providerBridge,
      List(providerNic)
    ).after(ovsService, noAddress)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = pullImages(
      "pull the Neutron images",
      images,
      hint = "the Image(...) in neutronContainer in neutron_single_node.scala",
      timeout = 2400
    ).after(podman, mirror)
    val carried = command(
      "check the images carry neutron-server and the rootwrap config",
      imagesCarryFail,
      unless = imagesCarry,
      timeout = 120
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

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val units = svcs.zip(neutronServices).map { (svc, c) =>
      val dir = s"$confRoot/${svc.name}"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/neutron.conf",
        Secret(
          neutronConfText
        ), // it holds the database, message bus and service passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val ini = file(
        s"$dir/${svc.ini}",
        svc.iniText,
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = fileCopy(
        s"$dir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, dirRes).notifies(unitService)
      val json = file(
        s"$dir/config.json",
        configJson(svc),
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
      (
        unitService,
        List(dirRes, conf, ini, kollaCa, json, unitFile),
        List(conf, ini, kollaCa, json, unitFile)
      )
    }
    val serverConf = units.head._2(1)
    val serverIni = units.head._2(2)

    val dbSync = command(
      "neutron-db-manage upgrade heads",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 900
    ).after(carried, serverConf, serverIni, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start, as for Nova.
    val (keystoneHelper, ensured) = keystoneEntries(
      "register Neutron in Keystone",
      "neutron",
      neutronPassword,
      List(KeystoneService("neutron", "network", s"http://$fqdn:$apiPort")),
      ksUrl,
      adminPassword,
      caCert
    )
    val registered = ensured.after(ca)

    val running = units.map { (unitService, _, deps) =>
      unitService.after((dbSync :: registered :: bridge :: carried :: deps)*)
    }

    // The root answers its version document without a token (200, or 300).
    val answering = waitForHttp(
      "neutron-server answers on its port",
      s"http://localhost:$apiPort/",
      status = List(200, 300),
      timeout = 120,
      hint = s"see 'journalctl -xeu orphera-neutron-server' and $logDir"
    ).after(running*)
    val reporting = command(
      "the Open vSwitch, DHCP and L3 agents report in through the API",
      waitAgentsUp,
      unless = agentsUp,
      timeout = 240
    ).after((answering :: running)*)

    // Magnum, Trove and the compute boot test need this network and find it by name, so the description makes it
    // (openstackNetwork: a helper of its own, `check` is its read-only test).
    val (providerHelper, providerMade) = openstackNetwork(
      "make the flat provider network with its subnet",
      "provider",
      ksUrl,
      adminPassword,
      caCert,
      flat = Some(physnet),
      shared = true,
      external = true,
      subnet = Some(
        OpenstackSubnet("provider", "10.10.6.0/24", Some("10.10.6.1"), Some(("10.10.6.200", "10.10.6.220")))
      )
    )
    val providerNet = providerMade.after(reporting)

    tools ++ List(
      ovsService,
      noAddress,
      bridge,
      mirrorDir,
      mirror,
      pulled,
      carried,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory
    ) ++ units.flatMap(_._2) ++ List(dbSync, helperFile, keystoneHelper, registered) ++
      running ++ List(answering, reporting, providerHelper, providerNet)
