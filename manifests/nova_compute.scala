// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. OpenStack compute nodes: nova-compute plus the Neutron Open vSwitch agent
// on tst17 and tst18, Kolla images under podman. Modelled on nova_single_node.scala
// and neutron_single_node.scala; read their headers for the conventions.
//
// Where it runs: tst17 and tst18, both VMs on gs2 (an AMD host with nested KVM on:
// /sys/module/kvm_amd/parameters/nested = 1). Their config has host_type
// host-passthrough, so the guests see svm and /dev/kvm exists; the playbook stops
// with a clear message when it does not, and nova-compute uses virt_type = kvm.
//
// libvirt and QEMU come from the host (apt: libvirt-daemon-system, qemu-system-x86),
// not from a nova-libvirt container: fewer moving parts, and the nova-compute
// container reaches libvirtd through /run/libvirt. /var/lib/nova is mounted at the
// same path in the container and on the host, because libvirt (on the host) opens
// the instance disks by the paths nova (in the container) writes into the domain XML.
//
// Networking: the second NIC (enp2s0, on the hypervisor's br-net106) is the flat
// provider network, exactly as on tst19: the playbook removes the address it was
// given at provisioning (10.10.6.29/30, table 2 policy routing) so it is a plain
// layer-2 port, and puts it on the Open vSwitch bridge `br-provider`. The compute
// node stays reachable on its first NIC. Instances are plugged into br-int by
// libvirt/os-vif; the Neutron OVS agent here wires br-int to br-provider with the
// same bridge mapping (provider:br-provider) as the agent on tst19.
//
// No metadata agent: nova.conf has force_config_drive = true, so every instance gets
// its configuration from a config drive.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ on tst10
//   - Nova (nova_single_node.scala, tst14) and Neutron (neutron_single_node.scala,
//     tst19) running, Glance and Placement up
//   - agents on tst17 and tst18; tst9's Kolla cache is optional
//
// Stages:
//   - install-compute (tst17, tst18): wait for apt, check /dev/kvm, packages
//     (podman, Open vSwitch, libvirt, QEMU), libvirtd, provider NIC and bridge, cache
//     mirror, image pulls, Keystone's CA, nova.conf and the Neutron agent config
//     (host name and IP filled in per node), Kolla config, units, helper.
//   - discover-compute-hosts (tst14): `nova-manage cell_v2 discover_hosts`, so the
//     scheduler can use the new hosts at once (it would also find them within the
//     60 s of discover_hosts_in_cells_interval).
//   - confirm-compute-healthy (tst17, tst18): the nova-compute service and the OVS
//     agent report alive through the APIs, and the node shows up as a hypervisor.
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. Not compiled where this was written; the first run on scala0 is the
// first compile. The most likely things to need a fix on the first run: the
// privileges nova-compute needs in its container, genisoimage in the image and the
// os-vif Open vSwitch connection.
object nova_compute extends OrpheraClusterPlaybook:

  private val nodes = List("tst17", "tst18")
  private val controlNode = "tst14"
  private val mgmtNic = "enp1s0"

  private val physnet = "provider"
  private val providerBridge = "br-provider"
  private val providerNic = "enp2s0"

  private val rabbitIp = "10.10.5.22"
  private val novaRabbitUser = "nova"
  private val novaRabbitPassword = "orphera-test-nova-rabbit-password"
  private val novaRabbitVhost = "nova"
  private val neutronRabbitUser = "neutron"
  private val neutronRabbitPassword = "orphera-test-neutron-rabbit-password"
  private val neutronRabbitVhost = "neutron"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val novaApi = "http://tst14.ljalbinson.com:8774/v2.1"
  private val neutronApi = "http://tst19.ljalbinson.com:9696"
  private val glanceApi = "http://tst13.ljalbinson.com:9292"

  // Keep in step with the other Kolla playbooks.
  private val registry = "quay.io/openstack.kolla"
  private val release = "2025.1"
  private val baseTag = "ubuntu-noble"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"
  private val computeImage = s"$registry/nova-compute:$release-$baseTag"
  private val ovsAgentImage = s"$registry/neutron-openvswitch-agent:$release-$baseTag"
  private val novaApiImage = s"$registry/nova-api:$release-$baseTag"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val novaPassword = "orphera-test-nova-service-password"
  private val neutronPassword = "orphera-test-neutron-service-password"

  private val confRoot = "/etc/kolla"
  private val novaSrcDir = "/etc/kolla/nova-compute-src"
  private val neutronSrcDir = "/etc/kolla/neutron-agent-src"
  private val novaLogDir = "/var/log/kolla/nova"
  private val neutronLogDir = "/var/log/kolla/neutron"
  private val novaStateDir = "/var/lib/nova"
  private val neutronStateDir = "/var/lib/orphera/neutron"
  private val caDir = "/etc/kolla/nova-compute-ca"

  private val hostPackages =
    List(
      "podman", "openssl", "curl", "ca-certificates", "python3", "openvswitch-switch",
      "libvirt-daemon-system", "libvirt-clients", "qemu-system-x86", "qemu-utils"
    )

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

  // Nested virtualization must be visible in the guest. Fail early with the reason.
  private val kvmCheckScript =
    """if [ ! -c /dev/kvm ]; then
      |  echo "no /dev/kvm on $(hostname): the VM does not see hardware virtualization." >&2
      |  echo "  cpu flags with svm/vmx: $(grep -cE 'svm|vmx' /proc/cpuinfo)" >&2
      |  echo "  on the hypervisor: nested must be enabled (/sys/module/kvm_amd/parameters/nested or kvm_intel) and the VM needs host_type host-passthrough" >&2
      |  exit 1
      |fi
      |echo "/dev/kvm present; cpus with svm/vmx: $(grep -cE 'svm|vmx' /proc/cpuinfo)"""".stripMargin

  // libvirtd from the host package. The default NAT network is not wanted. The
  // nova-compute container runs as the container's own `nova` user (uid 42436),
  // which is not in the host's libvirt group, so the libvirt sockets are made
  // world-accessible (test setup: anyone on the node can use libvirt). On Ubuntu the
  // socket is created by systemd, so the mode is set in a drop-in for libvirtd.socket
  // (libvirtd.conf is only used when libvirtd opens the socket itself). Polkit is
  // switched off (auth_unix_rw/ro = "none", as Kolla's own libvirt does): the caller's
  // uid 42436 is not a host user, and a non-root caller would be refused.
  // libvirtd also looks the caller's uid up in the host's user database to build its
  // identity ("Failed to find user record for uid"), so a host user with the
  // container nova user's uid (42436) is created first.
  private val libvirtScript =
    """set -e
      |if ! getent group 42436 >/dev/null; then groupadd -g 42436 nova-kolla; fi
      |if ! getent passwd 42436 >/dev/null; then useradd -r -u 42436 -g 42436 -M -s /usr/sbin/nologin nova-kolla; fi
      |getent passwd 42436
      |mkdir -p /etc/systemd/system/libvirtd.socket.d
      |printf '[Socket]\nSocketMode=0777\n' > /etc/systemd/system/libvirtd.socket.d/orphera.conf
      |if ! grep -q '^unix_sock_rw_perms' /etc/libvirt/libvirtd.conf; then
      |  printf 'unix_sock_rw_perms = "0777"\n' >> /etc/libvirt/libvirtd.conf
      |fi
      |for opt in auth_unix_rw auth_unix_ro; do
      |  if ! grep -q "^$opt " /etc/libvirt/libvirtd.conf; then
      |    printf '%s = "none"\n' "$opt" >> /etc/libvirt/libvirtd.conf
      |  fi
      |done
      |systemctl daemon-reload
      |systemctl stop libvirtd.service libvirtd.socket libvirtd-ro.socket libvirtd-admin.socket 2>/dev/null || true
      |systemctl start libvirtd.socket
      |systemctl enable --now libvirtd
      |for i in $(seq 1 20); do
      |  if virsh -c qemu:///system list --all >/dev/null 2>&1; then break; fi
      |  sleep 2
      |done
      |virsh -c qemu:///system list --all >/dev/null
      |virsh -c qemu:///system net-destroy default 2>/dev/null || true
      |virsh -c qemu:///system net-autostart --disable default 2>/dev/null || true
      |MODE=$(stat -c %a /run/libvirt/libvirt-sock)
      |if [ "$MODE" != "777" ]; then echo "libvirt socket mode is $MODE, expected 777" >&2; exit 1; fi
      |virsh -c qemu:///system version | head -3
      |echo "libvirtd running, socket mode $MODE"""".stripMargin

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
       |# nova-compute (uid 42436 in its container) cannot open the root-only unix socket;
       |# it talks to ovsdb over loopback TCP instead (os-vif's default). Stored in the OVS DB.
       |if ! ovs-vsctl get-manager | grep -qx 'ptcp:6640:127.0.0.1'; then ovs-vsctl set-manager ptcp:6640:127.0.0.1; fi
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
    s"""for img in $computeImage $ovsAgentImage; do
       |  podman pull $$img || { echo "could not pull $$img - check registry/release/baseTag at the top of nova_compute.scala" >&2; exit 1; }
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

  // Both config files carry @@HOST@@ and @@IP@@, replaced per node: the host name is
  // what Nova and Neutron both use as the binding host, so it is set explicitly and
  // identically in the two files.
  private val confScript =
    s"""set -e
       |mkdir -p $novaSrcDir $neutronSrcDir $novaLogDir $neutronLogDir $novaStateDir $neutronStateDir
       |MYIP=$$(ip -4 -o addr show dev $mgmtNic | awk '{print $$4}' | cut -d/ -f1 | head -1)
       |HOST=$$(hostname -s)
       |if [ -z "$$MYIP" ]; then echo "no IPv4 address on $mgmtNic" >&2; exit 1; fi
       |cat > $novaSrcDir/nova.conf <<'CONF'
       |[DEFAULT]
       |host = @@HOST@@
       |my_ip = @@IP@@
       |log_dir = /var/log/kolla/nova
       |state_path = /var/lib/nova
       |instances_path = /var/lib/nova/instances
       |use_stderr = true
       |compute_driver = libvirt.LibvirtDriver
       |transport_url = rabbit://$novaRabbitUser:$novaRabbitPassword@$rabbitIp:5672/$novaRabbitVhost
       |force_config_drive = true
       |vif_plugging_is_fatal = true
       |vif_plugging_timeout = 120
       |
       |[api]
       |auth_strategy = keystone
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[service_user]
       |send_service_user_token = true
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[placement]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = $novaPassword
       |region_name = RegionOne
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[glance]
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[neutron]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |region_name = RegionOne
       |project_name = service
       |username = neutron
       |password = $neutronPassword
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/nova/tmp
       |
       |[libvirt]
       |virt_type = kvm
       |cpu_mode = host-passthrough
       |connection_uri = qemu:///system
       |
       |[vnc]
       |enabled = true
       |server_listen = 0.0.0.0
       |server_proxyclient_address = @@IP@@
       |
       |[os_vif_ovs]
       |ovsdb_connection = tcp:127.0.0.1:6640
       |CONF
       |cat > $neutronSrcDir/neutron.conf <<'CONF'
       |[DEFAULT]
       |host = @@HOST@@
       |log_dir = /var/log/kolla/neutron
       |state_path = /var/lib/neutron
       |use_stderr = true
       |core_plugin = ml2
       |auth_strategy = keystone
       |transport_url = rabbit://$neutronRabbitUser:$neutronRabbitPassword@$rabbitIp:5672/$neutronRabbitVhost
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/neutron/tmp
       |
       |[agent]
       |root_helper = sudo neutron-rootwrap /etc/neutron/rootwrap.conf
       |root_helper_daemon = sudo neutron-rootwrap-daemon /etc/neutron/rootwrap.conf
       |CONF
       |cat > $neutronSrcDir/openvswitch_agent.ini <<'CONF'
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
       |sed -i "s/@@HOST@@/$$HOST/; s/@@IP@@/$$MYIP/" $novaSrcDir/nova.conf $neutronSrcDir/neutron.conf
       |chmod 640 $novaSrcDir/nova.conf $neutronSrcDir/neutron.conf $neutronSrcDir/openvswitch_agent.ini
       |echo "config files written for $$HOST ($$MYIP)"""".stripMargin

  // One Kolla config directory per service. The tools nova-compute needs are
  // checked in the image first, as in the other playbooks.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/nova-compute $confRoot/neutron-openvswitch-agent
       |cp $novaSrcDir/nova.conf $caDir/ca.crt $confRoot/nova-compute/
       |cp $neutronSrcDir/neutron.conf $neutronSrcDir/openvswitch_agent.ini $caDir/ca.crt $confRoot/neutron-openvswitch-agent/
       |if ! podman run --rm --entrypoint sh $computeImage -c 'command -v nova-compute >/dev/null && test -f /etc/nova/rootwrap.conf'; then
       |  echo "nova-compute or /etc/nova/rootwrap.conf not found in $computeImage - see the header of nova_compute.scala" >&2
       |  exit 1
       |fi
       |if ! podman run --rm --entrypoint sh $computeImage -c 'command -v genisoimage >/dev/null || command -v mkisofs >/dev/null'; then
       |  echo "neither genisoimage nor mkisofs is in $computeImage, which config drives need" >&2
       |  exit 1
       |fi
       |if ! podman run --rm --entrypoint sh $ovsAgentImage -c 'test -f /etc/neutron/rootwrap.conf'; then
       |  echo "/etc/neutron/rootwrap.conf not found in $ovsAgentImage" >&2
       |  exit 1
       |fi
       |cat > $confRoot/nova-compute/config.json <<JSON
       |{"command": "nova-compute --config-file /etc/nova/nova.conf", "config_files": [{"source": "/var/lib/kolla/config_files/nova.conf", "dest": "/etc/nova/nova.conf", "owner": "nova", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/nova/ssl/ca.crt", "owner": "nova", "perm": "0644"}], "permissions": [{"path": "/var/log/kolla/nova", "owner": "nova:kolla", "recurse": true}, {"path": "/var/lib/nova", "owner": "nova:nova"}]}
       |JSON
       |cat > $confRoot/neutron-openvswitch-agent/config.json <<JSON
       |{"command": "neutron-openvswitch-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/openvswitch_agent.ini", "config_files": [{"source": "/var/lib/kolla/config_files/neutron.conf", "dest": "/etc/neutron/neutron.conf", "owner": "neutron", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/neutron/ssl/ca.crt", "owner": "neutron", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/openvswitch_agent.ini", "dest": "/etc/neutron/plugins/ml2/openvswitch_agent.ini", "owner": "neutron", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/neutron", "owner": "neutron:kolla", "recurse": true}, {"path": "/var/lib/neutron", "owner": "neutron:neutron", "recurse": true}]}
       |JSON
       |echo "kolla config written under $confRoot"""".stripMargin

  // nova-compute runs privileged on the host PID and network namespaces with the
  // libvirt and Open vSwitch sockets and the kernel modules directory; it needs
  // privileged mode for privsep (os-vif, qemu-img, mounts), and /dev from the host:
  // the guest consoles are ptys (/dev/pts/N) that Nova must see to read the console. The OVS agent is set up
  // exactly like the one on tst19.
  private val unitsScript =
    s"""set -e
       |cat > /etc/systemd/system/orphera-nova-compute.service <<UNIT
       |[Unit]
       |Description=nova-compute (Kolla container) managed by Orphera
       |After=network-online.target openvswitch-switch.service libvirtd.service
       |Wants=network-online.target libvirtd.service
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f nova-compute
       |ExecStart=/usr/bin/podman run --rm --name nova-compute --network host --privileged --pid host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confRoot/nova-compute:/var/lib/kolla/config_files:ro,z -v $novaLogDir:/var/log/kolla/nova:z -v $novaStateDir:/var/lib/nova -v /run/libvirt:/run/libvirt -v /run/openvswitch:/run/openvswitch -v /lib/modules:/lib/modules:ro -v /dev:/dev $computeImage
       |ExecStop=/usr/bin/podman stop -t 60 nova-compute
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |cat > /etc/systemd/system/orphera-neutron-openvswitch-agent.service <<UNIT
       |[Unit]
       |Description=neutron-openvswitch-agent (Kolla container) managed by Orphera
       |After=network-online.target openvswitch-switch.service
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=/bin/sh -c 'mkdir -p /run/netns && (mountpoint -q /run/netns || mount --bind /run/netns /run/netns) && mount --make-rshared /run/netns'
       |ExecStartPre=-/usr/bin/podman rm -f neutron-openvswitch-agent
       |ExecStart=/usr/bin/podman run --rm --name neutron-openvswitch-agent --network host --privileged --pid host -v /run/openvswitch:/run/openvswitch -v /run/netns:/run/netns:rshared -v /lib/modules:/lib/modules:ro -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confRoot/neutron-openvswitch-agent:/var/lib/kolla/config_files:ro,z -v $neutronLogDir:/var/log/kolla/neutron:z -v $neutronStateDir:/var/lib/neutron:z $ovsAgentImage
       |ExecStop=/usr/bin/podman stop -t 30 neutron-openvswitch-agent
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |for u in neutron-openvswitch-agent nova-compute; do
       |  systemctl enable orphera-$$u
       |  systemctl restart orphera-$$u
       |done
       |sleep 15
       |for u in neutron-openvswitch-agent nova-compute; do
       |  if ! systemctl is-active --quiet orphera-$$u; then
       |    echo "orphera-$$u failed to start - see 'journalctl -xeu orphera-$$u' and $novaLogDir / $neutronLogDir" >&2
       |    exit 1
       |  fi
       |done
       |echo "nova-compute and neutron-openvswitch-agent running"""".stripMargin

  // Run on the control node: map the new compute hosts into cell1.
  private val discoverScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint nova-manage -v /etc/kolla/nova/nova.conf:/etc/nova/nova.conf:ro,z -v /var/log/kolla/nova:/var/log/kolla/nova:z $novaApiImage cell_v2 discover_hosts --verbose
       |echo "discover_hosts done"""".stripMargin

  // Standard-library Python, plain string filled in with replace(). Subcommands:
  //   compute-up <host> ...   every named host has an enabled nova-compute service that is up
  //   hypervisors             one line per hypervisor (state, vCPUs, memory)
  //   ovs-up <host> ...       every named host has an alive neutron-openvswitch-agent
  //   boot-cleanup           delete what an earlier boot-test --keep left behind
  //   boot-test <qcow2-file> [--keep]  upload an image, create a flavor and a flat provider
  //                           network with DHCP, boot an instance with a config drive,
  //                           wait for the console to show the login prompt and a DHCP
  //                           lease, then delete everything it created
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
NOVA = '@@NOVA@@'
NEUTRON = '@@NEUTRON@@'
GLANCE = '@@GLANCE@@'
ADMIN_PW = '@@ADMINPW@@'
PHYSNET = '@@PHYSNET@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
# Test network: addresses outside the ones used by the nodes (10.10.6.29-31).
CIDR = '10.10.6.0/24'
GATEWAY = '10.10.6.1'
POOL = ('10.10.6.200', '10.10.6.220')


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
        r = urllib.request.urlopen(req, context=ctx, timeout=300)
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


def admin_token():
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'admin', 'domain': {'name': 'Default'}, 'password': ADMIN_PW}}}, 'scope': {'project': {'name': 'admin', 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request failed: HTTP %s %s' % (status, parsed))
    return headers['X-Subject-Token']


def compute_up(hosts):
    token = admin_token()
    s, p, _ = call('GET', NOVA + '/os-services?binary=nova-compute', None, token)
    if s != 200:
        die('service list failed: HTTP %s %s' % (s, p))
    rows = dict((x['host'], x) for x in p.get('services', []))
    bad = [h for h in hosts if h not in rows or rows[h].get('state') != 'up' or rows[h].get('status') != 'enabled']
    for h in hosts:
        r = rows.get(h)
        print('nova-compute %s state=%s status=%s' % (h, r.get('state') if r else 'missing', r.get('status') if r else 'missing'))
    if bad:
        die('not up yet: ' + ', '.join(bad))
    print('nova-compute is up on ' + ', '.join(hosts))


def hypervisors():
    token = admin_token()
    s, p, _ = call('GET', NOVA + '/os-hypervisors/detail', None, token)
    if s != 200:
        die('hypervisor list failed: HTTP %s %s' % (s, p))
    rows = p.get('hypervisors', [])
    for h in rows:
        print('hypervisor %s state=%s status=%s type=%s vcpus=%s memory_mb=%s' % (h.get('hypervisor_hostname'), h.get('state'), h.get('status'), h.get('hypervisor_type'), h.get('vcpus'), h.get('memory_mb')))
    print('hypervisors: %d' % len(rows))


def ovs_up(hosts):
    token = admin_token()
    s, p, _ = call('GET', NEUTRON + '/v2.0/agents?binary=neutron-openvswitch-agent', None, token)
    if s != 200:
        die('agent list failed: HTTP %s %s' % (s, p))
    rows = dict((a['host'], a) for a in p.get('agents', []))
    bad = [h for h in hosts if h not in rows or not rows[h].get('alive')]
    for h in hosts:
        r = rows.get(h)
        print('neutron-openvswitch-agent %s alive=%s' % (h, r.get('alive') if r else 'missing'))
    if bad:
        die('not alive yet: ' + ', '.join(bad))
    print('neutron-openvswitch-agent is alive on ' + ', '.join(hosts))


def wait_for(fn, what, tries, delay):
    last = None
    for _ in range(tries):
        last = fn()
        if last[0]:
            return last[1]
        time.sleep(delay)
    die('%s: gave up (last: %s)' % (what, last[1]))


def boot_cleanup():
    token = admin_token()
    for s in call('GET', NOVA + '/servers?name=orphera-boot-test', None, token)[1].get('servers', []):
        call('DELETE', NOVA + '/servers/' + s['id'], None, token)
        for _ in range(30):
            if call('GET', NOVA + '/servers/' + s['id'], None, token)[0] == 404:
                break
            time.sleep(2)
        time.sleep(3)
    for n in call('GET', NEUTRON + '/v2.0/networks?name=orphera-boot-test', None, token)[1].get('networks', []):
        for sub in call('GET', NEUTRON + '/v2.0/subnets?network_id=' + n['id'], None, token)[1].get('subnets', []):
            call('DELETE', NEUTRON + '/v2.0/subnets/' + sub['id'], None, token)
        call('DELETE', NEUTRON + '/v2.0/networks/' + n['id'], None, token)
    for f in call('GET', NOVA + '/flavors', None, token)[1].get('flavors', []):
        if f.get('name', '').startswith('orphera-boot-'):
            call('DELETE', NOVA + '/flavors/' + f['id'], None, token)
    for i in call('GET', GLANCE + '/v2/images?name=orphera-boot-test', None, token)[1].get('images', []):
        call('DELETE', GLANCE + '/v2/images/' + i['id'], None, token)
    print('boot-test leftovers removed')


def boot_test(path, keep=False):
    token = admin_token()
    made = []

    def cleanup():
        if keep:
            print('--keep: left in place: ' + ', '.join(u for k, u in made))
            return
        for kind, url in reversed(made):
            try:
                call('DELETE', url, None, token)
                if kind == 'server':
                    for _ in range(30):
                        if call('GET', url, None, token)[0] == 404:
                            break
                        time.sleep(2)
                    time.sleep(3)
            except Exception as e:
                sys.stderr.write('cleanup of %s failed: %s\n' % (url, e))

    try:
        # image
        s, p, _ = call('POST', GLANCE + '/v2/images', {'name': 'orphera-boot-test', 'disk_format': 'qcow2', 'container_format': 'bare', 'visibility': 'private'}, token)
        if s != 201:
            die('image create failed: HTTP %s %s' % (s, p))
        image = p['id']
        made.append(('image', GLANCE + '/v2/images/' + image))
        s, p, _ = call('PUT', GLANCE + '/v2/images/%s/file' % image, token=token, raw=open(path, 'rb').read(), ctype='application/octet-stream')
        if s not in (200, 204):
            die('image upload failed: HTTP %s %s' % (s, p))
        wait_for(lambda: (lambda st: (st == 'active', st))(call('GET', GLANCE + '/v2/images/' + image, None, token)[1].get('status')), 'image active', 30, 2)
        print('image %s active' % image)
        # flavor
        s, p, _ = call('POST', NOVA + '/flavors', {'flavor': {'name': 'orphera-boot-%d' % int(time.time()), 'ram': 128, 'vcpus': 1, 'disk': 1}}, token)
        if s not in (200, 201):
            die('flavor create failed: HTTP %s %s' % (s, p))
        flavor = p['flavor']['id']
        made.append(('flavor', NOVA + '/flavors/' + flavor))
        # network + subnet (DHCP on)
        s, p, _ = call('POST', NEUTRON + '/v2.0/networks', {'network': {'name': 'orphera-boot-test', 'provider:network_type': 'flat', 'provider:physical_network': PHYSNET}}, token)
        if s == 409:
            die('a flat network on physical network %s already exists (only one is allowed); delete it first: %s' % (PHYSNET, p))
        if s not in (200, 201):
            die('network create failed: HTTP %s %s' % (s, p))
        net = p['network']['id']
        made.append(('network', NEUTRON + '/v2.0/networks/' + net))
        s, p, _ = call('POST', NEUTRON + '/v2.0/subnets', {'subnet': {'network_id': net, 'ip_version': 4, 'cidr': CIDR, 'gateway_ip': GATEWAY, 'enable_dhcp': True, 'allocation_pools': [{'start': POOL[0], 'end': POOL[1]}], 'name': 'orphera-boot-test'}}, token)
        if s not in (200, 201):
            die('subnet create failed: HTTP %s %s' % (s, p))
        made.append(('subnet', NEUTRON + '/v2.0/subnets/' + p['subnet']['id']))
        print('flavor %s, network %s ready' % (flavor, net))
        # server
        s, p, _ = call('POST', NOVA + '/servers', {'server': {'name': 'orphera-boot-test', 'imageRef': image, 'flavorRef': flavor, 'networks': [{'uuid': net}], 'config_drive': True}}, token)
        if s not in (200, 201, 202):
            die('server create failed: HTTP %s %s' % (s, p))
        server = p['server']['id']
        made.append(('server', NOVA + '/servers/' + server))

        def state():
            s, p, _ = call('GET', NOVA + '/servers/' + server, None, token)
            sv = p.get('server', {})
            if sv.get('status') == 'ERROR':
                die('server went to ERROR: %s' % json.dumps(sv.get('fault')))
            return (sv.get('status') == 'ACTIVE', sv.get('status'))
        wait_for(state, 'server ACTIVE', 60, 5)
        s, p, _ = call('GET', NOVA + '/servers/' + server, None, token)
        print('server %s ACTIVE on %s as %s' % (server, p['server'].get('OS-EXT-SRV-ATTR:host'), p['server'].get('OS-EXT-SRV-ATTR:instance_name')))
        sys.stdout.flush()

        def console():
            s, p, _ = call('POST', NOVA + '/servers/%s/action' % server, {'os-getConsoleOutput': {'length': 300}}, token)
            out = p.get('output', '') if s == 200 else ''
            return ('login:' in out, out)
        out = wait_for(console, 'login prompt on the console', 48, 5)
        print('console shows the login prompt')
        lease = [l for l in out.splitlines() if 'Lease of' in l or 'obtained' in l]
        if not any('10.10.6.' in l for l in lease):
            sys.stderr.write('no DHCP lease from %s in the console output:\n%s\n' % (CIDR, out[-1500:]))
            die('instance booted but did not get an address by DHCP')
        print(lease[0].strip())
        print('boot test passed')
    finally:
        cleanup()
        sys.stdout.flush()


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'compute-up' and len(sys.argv) > 2:
        compute_up(sys.argv[2:])
    elif cmd == 'hypervisors':
        hypervisors()
    elif cmd == 'ovs-up' and len(sys.argv) > 2:
        ovs_up(sys.argv[2:])
    elif cmd == 'boot-cleanup':
        boot_cleanup()
    elif cmd == 'boot-test' and len(sys.argv) > 2:
        boot_test(sys.argv[2], '--keep' in sys.argv[3:])
    else:
        die('usage: orphera-compute-api compute-up <host>... | hypervisors | ovs-up <host>... | boot-cleanup | boot-test <qcow2-file> [--keep]')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-compute-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@NOVA@@", novaApi)
        .replace("@@NEUTRON@@", neutronApi)
        .replace("@@GLANCE@@", glanceApi)
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@PHYSNET@@", physnet)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-compute-api\necho wrote /usr/local/sbin/orphera-compute-api\n"

  // Each node checks its own entries. nova-compute takes a while to register and
  // the OVS agent a little less.
  private val confirmScript =
    """H=$(hostname -s)
      |for i in $(seq 1 36); do
      |  if /usr/local/sbin/orphera-compute-api compute-up $H >/dev/null 2>&1 && /usr/local/sbin/orphera-compute-api ovs-up $H >/dev/null 2>&1; then
      |    /usr/local/sbin/orphera-compute-api compute-up $H
      |    /usr/local/sbin/orphera-compute-api ovs-up $H
      |    /usr/local/sbin/orphera-compute-api hypervisors
      |    exit 0
      |  fi
      |  sleep 5
      |done
      |/usr/local/sbin/orphera-compute-api compute-up $H
      |/usr/local/sbin/orphera-compute-api ovs-up $H
      |exit 1""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("nova-compute")(
      stage("install-compute", nodes*)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(List("sh", "-c", waitAptIdleScript), timeoutSeconds = 960)
        )
        .task("check that the VM has hardware virtualization")(
          Task.RunCommand(List("sh", "-c", kvmCheckScript))
        )
        .task("install podman, Open vSwitch, libvirt and QEMU")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("start libvirtd")(
          Task.RunCommand(List("sh", "-c", libvirtScript), timeoutSeconds = 120)
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
        .task("pull the Nova compute and Neutron OVS agent images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write nova.conf and the Neutron agent config for this node")(
          Task.RunCommand(List("sh", "-c", confScript))
        )
        .task("write the Kolla config")(
          Task.RunCommand(List("sh", "-c", kollaConfigScript), timeoutSeconds = 180)
        )
        .task("write the compute API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("start nova-compute and the Open vSwitch agent")(
          Task.RunCommand(List("sh", "-c", unitsScript), timeoutSeconds = 300)
        )
        .build,

      stage("discover-compute-hosts", controlNode)
        .task("nova-manage cell_v2 discover_hosts")(
          Task.RunCommand(List("sh", "-c", s"sleep 20; $discoverScript"), timeoutSeconds = 180)
        )
        .build,

      stage("confirm-compute-healthy", nodes*)
        .task("nova-compute and the OVS agent report in; the node is a hypervisor")(
          Task.RunCommand(List("sh", "-c", confirmScript), timeoutSeconds = 240)
        )
        .task("compute confirmed")(
          Task.Debug(
            s"nova-compute (KVM, config drive) and the Neutron Open vSwitch agent running on ${nodes.mkString(", ")}; flat provider network `$physnet` on $providerBridge/$providerNic. Test: manifests/test_nova_compute.sh"
          )
        )
        .build
    )
