// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack compute nodes: nova-compute plus the Neutron Open vSwitch agent on tst17 and tst18, Kolla images
// under podman, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows what
// would change. Modelled on nova_single_node.scala and neutron_single_node.scala, which explain the
// conventions.
//
// Two descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/nova_compute.scala           # the compute nodes (this one: tst17 and tst18)
//   orphera apply manifests/nova_compute_discover.scala  # map the new hosts into cell1 (tst14)
//
// The compute nodes are described in parallel; the host name and the management address that nova.conf and
// the Neutron agent config carry come from the inventory (`cluster_ip`), per node.
//
// Where it runs: tst17 and tst18, both VMs on gs2 (an AMD host with nested KVM on:
// /sys/module/kvm_amd/parameters/nested = 1). Their config has host_type host-passthrough, so the guests see
// svm and /dev/kvm exists; the description stops with a clear message when it does not, and nova-compute uses
// virt_type = kvm.
//
// libvirt and QEMU come from the host (apt: libvirt-daemon-system, qemu-system-x86), not from a nova-libvirt
// container: fewer moving parts, and the nova-compute container reaches libvirtd through /run/libvirt.
// /var/lib/nova is mounted at the same path in the container and on the host, because libvirt (on the host)
// opens the instance disks by the paths nova (in the container) writes into the domain XML.
//
// Networking: the second NIC (enp2s0, on the hypervisor's br-net106) is the flat provider network, exactly as
// on tst19: the description removes the address it was given at provisioning (10.10.6.29/30, table 2 policy
// routing) so it is a plain layer-2 port, and puts it on the Open vSwitch bridge `br-provider`. The compute
// node stays reachable on its first NIC. Instances are plugged into br-int by libvirt/os-vif; the Neutron OVS
// agent here wires br-int to br-provider with the same bridge mapping (provider:br-provider) as the agent on
// tst19.
//
// No metadata agent: nova.conf has force_config_drive = true, so every instance gets its configuration from a
// config drive.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ on tst10
//   - Nova (nova_single_node.scala, tst14) and Neutron (neutron_single_node.scala, tst19) running, Glance and
//     Placement up
//   - agents on tst17 and tst18; tst9's Kolla cache is optional
//
// How it stays idempotent (as for Heat and the other modules):
//   - Each step that changes the node is a command with a guard that tests what it makes: libvirtd's socket
//     mode and settings, the libvirt secret's value, the provider NIC having no address (in netplan and in the
//     kernel), br-provider with its port and the OVS manager, Keystone's CA compared as a certificate.
//   - nova.conf and neutron.conf hold secrets, so they are secret files (one per service directory, with the
//     node's own host name and address filled in); the agent's ini, config.json and the units are plain files.
//     Each notifies its service, so a change restarts exactly that service.
//   - The checks that the images have what nova-compute needs (rootwrap, genisoimage or mkisofs, the
//     Neutron rootwrap) are commands that only run, and fail with the reason, when the image lacks it.
//
// Not restored by the teardown: the address enp2s0 had from provisioning.
//
// Test-only credentials come from the secret store (nova/rabbit, neutron/rabbit, nova/service,
// neutron/service, cinder/ceph-key, keystone/admin), as before. The most likely things to need a fix if a run
// fails: the privileges nova-compute needs in its container, genisoimage in the image and the os-vif Open
// vSwitch connection.
object nova_compute extends OrpheraApply:
  import Resources.*

  private val computeNodes = List("tst17", "tst18")

  override def nodes: List[String] = computeNodes

  // Cinder's volumes are Ceph RBD images. QEMU on the node opens them with the cephx key of
  // client.cinder, which libvirt keeps as a secret named by this UUID; Cinder tells Nova the
  // UUID when a volume is attached (rbd_secret_uuid in cinder_single_node.scala: keep the two
  // in step) and the key is the one cinder_single_node.scala puts in Ceph.
  private val rbdSecretUuid = "8c256f90-1c37-4fb6-b8be-cedcf0fffd3c"
  private val cephKey = Secrets.secret("cinder/ceph-key")

  private val physnet = "provider"
  private val providerBridge = "br-provider"
  private val providerNic = "enp2s0"

  private val rabbitIp = "10.10.5.22"
  private val novaRabbitUser = "nova"
  private val novaRabbitPassword = Secrets.secret("nova/rabbit")
  private val novaRabbitVhost = "nova"
  private val neutronRabbitUser = "neutron"
  private val neutronRabbitPassword = Secrets.secret("neutron/rabbit")
  private val neutronRabbitVhost = "neutron"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val novaApi = "http://tst14.ljalbinson.com:8774/v2.1"
  private val neutronApi = "http://tst19.ljalbinson.com:9696"
  private val glanceApi = "http://tst13.ljalbinson.com:9292"

  // Keep in step with the other Kolla descriptions.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val novaPassword = Secrets.secret("nova/service")
  private val neutronPassword = Secrets.secret("neutron/service")

  private val confRoot = "/etc/kolla"
  private val novaLogDir = "/var/log/kolla/nova"
  private val neutronLogDir = "/var/log/kolla/neutron"
  private val novaStateDir = "/var/lib/nova"
  private val neutronStateDir = "/var/lib/orphera/neutron"
  private val caDir = "/etc/kolla/nova-compute-ca"
  private val caCert = s"$caDir/ca.crt"
  private val novaDir = s"$confRoot/nova-compute"
  private val ovsDir = s"$confRoot/neutron-openvswitch-agent"

  // The containers as data (orchestrator/.../kolla/Container.scala, checked by ContainerCheck).
  // Defined after the directory vals they read; a val used before its definition is null in an
  // object.
  //
  // nova-compute runs privileged on the host PID and network namespaces with the libvirt and
  // Open vSwitch sockets and the kernel modules directory; it needs privileged mode for privsep
  // (os-vif, qemu-img, mounts), and /dev from the host: the guest consoles are ptys (/dev/pts/N)
  // that Nova must see to read the console. It wants libvirtd, which it talks to.
  private val novaCompute = Container(
    name = ContainerName("nova-compute"),
    image = Image("nova-compute", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(novaDir, "/var/lib/kolla/config_files"),
      Mount(novaLogDir, "/var/log/kolla/nova", Access.ReadWrite),
      Mount(novaStateDir, "/var/lib/nova", Access.Unlabelled),
      Mount("/run/libvirt", "/run/libvirt", Access.Unlabelled),
      Mount("/run/openvswitch", "/run/openvswitch", Access.Unlabelled),
      Mount("/lib/modules", "/lib/modules", Access.ReadOnlyUnlabelled),
      Mount("/dev", "/dev", Access.Unlabelled)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    privileged = true,
    hostPid = true,
    after = List("openvswitch-switch.service", "libvirtd.service"),
    wants = List("libvirtd.service"),
    stopGraceSeconds = 60,
    settleSeconds = 15
  )
  // The Open vSwitch agent is set up exactly like the one on tst19 (neutron_single_node.scala).
  private val ovsAgent = Container(
    name = ContainerName("neutron-openvswitch-agent"),
    image = Image(
      "neutron-openvswitch-agent",
      Release.R2025_1,
      Base.UbuntuNoble,
      registry
    ),
    mounts = List(
      Mount(ovsDir, "/var/lib/kolla/config_files"),
      Mount(neutronLogDir, "/var/log/kolla/neutron", Access.ReadWrite),
      Mount(neutronStateDir, "/var/lib/neutron", Access.ReadWrite),
      Mount("/run/openvswitch", "/run/openvswitch", Access.Unlabelled),
      Mount("/run/netns", "/run/netns", Access.Shared),
      Mount("/lib/modules", "/lib/modules", Access.ReadOnlyUnlabelled)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    privileged = true,
    hostPid = true,
    after = List("openvswitch-switch.service"),
    preStart = List(
      "/bin/sh -c 'mkdir -p /run/netns && (mountpoint -q /run/netns || mount --bind /run/netns /run/netns) && mount --make-rshared /run/netns'"
    ),
    settleSeconds = 15
  )
  private val computeImage = novaCompute.image.ref
  private val ovsAgentImage = ovsAgent.image.ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "openvswitch-switch",
      "libvirt-daemon-system",
      "libvirt-clients",
      "qemu-system-x86",
      "qemu-block-extra",
      "qemu-utils"
    )

  // Nested virtualization must be visible in the guest. Fail early with the reason. The command only runs
  // (and fails) when /dev/kvm is missing.
  private val kvmPresent = "[ -c /dev/kvm ]"
  private val kvmMissingScript =
    """echo "no /dev/kvm on $(hostname): the VM does not see hardware virtualization." >&2
      |echo "  cpu flags with svm/vmx: $(grep -cE 'svm|vmx' /proc/cpuinfo)" >&2
      |echo "  on the hypervisor: nested must be enabled (/sys/module/kvm_amd/parameters/nested or kvm_intel) and the VM needs host_type host-passthrough" >&2
      |exit 1""".stripMargin

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
  // True when everything the script above makes is in place and libvirtd answers.
  private val libvirtReady =
    s"""getent group 42436 >/dev/null && getent passwd 42436 >/dev/null &&
       |grep -qx 'SocketMode=0777' /etc/systemd/system/libvirtd.socket.d/orphera.conf 2>/dev/null &&
       |grep -q '^unix_sock_rw_perms' /etc/libvirt/libvirtd.conf &&
       |grep -q '^auth_unix_rw ' /etc/libvirt/libvirtd.conf &&
       |grep -q '^auth_unix_ro ' /etc/libvirt/libvirtd.conf &&
       |[ "$$(stat -c %a /run/libvirt/libvirt-sock 2>/dev/null)" = "777" ] &&
       |systemctl is-active --quiet libvirtd &&
       |virsh -c qemu:///system list --all >/dev/null 2>&1 &&
       |! virsh -c qemu:///system net-list --name 2>/dev/null | grep -qx default &&
       |! virsh -c qemu:///system net-list --all --autostart --name 2>/dev/null | grep -qx default""".stripMargin

  // The libvirt secret that holds the client.cinder key (see rbdSecretUuid).
  private val cephSecretScript =
    s"""set -e
       |V="virsh -c qemu:///system"
       |cat > /tmp/orphera-ceph-secret.xml <<'XML'
       |<secret ephemeral='no' private='no'>
       |  <uuid>$rbdSecretUuid</uuid>
       |  <usage type='ceph'><name>client.cinder secret</name></usage>
       |</secret>
       |XML
       |$$V secret-define --file /tmp/orphera-ceph-secret.xml
       |rm -f /tmp/orphera-ceph-secret.xml
       |$$V secret-set-value --secret $rbdSecretUuid --base64 '${cephKey.reveal}'
       |echo "libvirt secret $rbdSecretUuid holds the client.cinder key"""".stripMargin
  private val cephSecretReady =
    s"""virsh -c qemu:///system secret-get-value $rbdSecretUuid 2>/dev/null | grep -qx '${cephKey.reveal}'"""

  // The provisioning script gave enp2s0 an address, a table-2 route and a
  // "from <address>" rule (netplan, generated by cloud-init). Neutron needs the
  // port to be plain layer 2, so: stop cloud-init regenerating the network config,
  // strip the address, routes, routing policy and nameservers of that interface
  // from /etc/netplan/50-cloud-init.yaml (kept: name, MAC match, MTU), apply, and
  // clear what is left in the kernel. The management NIC is not touched. Needs
  // PyYAML, which cloud-init already depends on.
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
  // True when the kernel has no IPv4 address on the NIC, cloud-init is stopped from regenerating the network
  // config, and netplan has none of the keys the script strips.
  private val addressDropped =
    s"""[ -z "$$(ip -4 -o addr show dev $providerNic 2>/dev/null)" ] &&
       |[ -f /etc/cloud/cloud.cfg.d/99-orphera-disable-network-config.cfg ] &&
       |python3 - <<'PY'
       |import sys, yaml
       |path = '/etc/netplan/50-cloud-init.yaml'
       |nic = '$providerNic'
       |try:
       |    doc = yaml.safe_load(open(path))
       |except FileNotFoundError:
       |    sys.exit(0)
       |for name, cfg in (doc.get('network', {}).get('ethernets', {}) or {}).items():
       |    if name == nic or cfg.get('set-name') == nic:
       |        if any(k in cfg for k in ('addresses', 'routes', 'routing-policy', 'nameservers', 'gateway4', 'gateway6')):
       |            sys.exit(1)
       |sys.exit(0)
       |PY""".stripMargin

  // Open vSwitch comes from the host (apt). br-provider's only physical port is the
  // provider NIC; the OVS agent in the container creates br-int and the patch
  // ports.
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
  private val bridgeReady =
    s"""systemctl is-active --quiet openvswitch-switch &&
       |systemctl is-enabled --quiet openvswitch-switch &&
       |ovs-vsctl br-exists $providerBridge &&
       |ovs-vsctl list-ports $providerBridge | grep -qx $providerNic &&
       |ovs-vsctl get-manager | grep -qx 'ptcp:6640:127.0.0.1' &&
       |ip link show $providerBridge | grep -q '<[^>]*UP'""".stripMargin

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

  private val images = List(computeImage, ovsAgentImage)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in novaCompute and ovsAgent in nova_compute.scala"
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

  // nova.conf and the Neutron agent config: the host name is what Nova and Neutron both use as the binding
  // host, so it is set explicitly and identically in the files; the address is the node's management address.
  private def novaConf(host: String, ip: String): String =
    s"""# Managed by Orphera (nova_compute.scala)
       |[DEFAULT]
       |host = $host
       |my_ip = $ip
       |log_dir = /var/log/kolla/nova
       |state_path = /var/lib/nova
       |instances_path = /var/lib/nova/instances
       |use_stderr = true
       |compute_driver = libvirt.LibvirtDriver
       |transport_url = rabbit://$novaRabbitUser:${novaRabbitPassword.reveal}@$rabbitIp:5672/$novaRabbitVhost
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
       |password = ${novaPassword.reveal}
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
       |password = ${novaPassword.reveal}
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[placement]
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = nova
       |password = ${novaPassword.reveal}
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
       |password = ${neutronPassword.reveal}
       |valid_interfaces = internal
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[cinder]
       |os_region_name = RegionOne
       |cafile = /etc/nova/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/nova/tmp
       |
       |[libvirt]
       |virt_type = kvm
       |cpu_mode = host-passthrough
       |connection_uri = qemu:///system
       |rbd_user = cinder
       |rbd_secret_uuid = $rbdSecretUuid
       |
       |[vnc]
       |enabled = true
       |server_listen = 0.0.0.0
       |server_proxyclient_address = $ip
       |
       |[os_vif_ovs]
       |ovsdb_connection = tcp:127.0.0.1:6640
       |""".stripMargin

  private def neutronConf(host: String): String =
    s"""# Managed by Orphera (nova_compute.scala)
       |[DEFAULT]
       |host = $host
       |log_dir = /var/log/kolla/neutron
       |state_path = /var/lib/neutron
       |use_stderr = true
       |core_plugin = ml2
       |auth_strategy = keystone
       |transport_url = rabbit://$neutronRabbitUser:${neutronRabbitPassword.reveal}@$rabbitIp:5672/$neutronRabbitVhost
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/neutron/tmp
       |
       |[agent]
       |root_helper = sudo neutron-rootwrap /etc/neutron/rootwrap.conf
       |root_helper_daemon = sudo neutron-rootwrap-daemon /etc/neutron/rootwrap.conf
       |""".stripMargin

  private def ovsAgentIni(ip: String): String =
    s"""# Managed by Orphera (nova_compute.scala)
       |[ovs]
       |bridge_mappings = $physnet:$providerBridge
       |local_ip = $ip
       |
       |[securitygroup]
       |firewall_driver = openvswitch
       |enable_security_group = true
       |
       |[agent]
       |tunnel_types = vxlan
       |l2_population = true
       |""".stripMargin

  private val novaConfigJson =
    """{"command": "nova-compute --config-file /etc/nova/nova.conf", "config_files": [{"source": "/var/lib/kolla/config_files/nova.conf", "dest": "/etc/nova/nova.conf", "owner": "nova", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/nova/ssl/ca.crt", "owner": "nova", "perm": "0644"}], "permissions": [{"path": "/var/log/kolla/nova", "owner": "nova:kolla", "recurse": true}, {"path": "/var/lib/nova", "owner": "nova:nova"}]}
      |""".stripMargin
  private val ovsConfigJson =
    """{"command": "neutron-openvswitch-agent --config-file /etc/neutron/neutron.conf --config-file /etc/neutron/plugins/ml2/openvswitch_agent.ini", "config_files": [{"source": "/var/lib/kolla/config_files/neutron.conf", "dest": "/etc/neutron/neutron.conf", "owner": "neutron", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/neutron/ssl/ca.crt", "owner": "neutron", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/openvswitch_agent.ini", "dest": "/etc/neutron/plugins/ml2/openvswitch_agent.ini", "owner": "neutron", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/neutron", "owner": "neutron:kolla", "recurse": true}, {"path": "/var/lib/neutron", "owner": "neutron:neutron", "recurse": true}]}
      |""".stripMargin

  // What the images must have; each check is a command that runs, and fails with the reason, only when the
  // image lacks it.
  private val novaImageOk =
    s"""podman run --rm --entrypoint sh $computeImage -c 'command -v nova-compute >/dev/null && test -f /etc/nova/rootwrap.conf'"""
  private val isoToolOk =
    s"""podman run --rm --entrypoint sh $computeImage -c 'command -v genisoimage >/dev/null || command -v mkisofs >/dev/null'"""
  private val ovsRootwrapOk =
    s"""podman run --rm --entrypoint sh $ovsAgentImage -c 'test -f /etc/neutron/rootwrap.conf'"""
  private def failWith(message: String): String =
    s"""echo "$message" >&2
       |exit 1""".stripMargin

  // Standard-library Python, plain string filled in with replace(). Subcommands:
  //   compute-up <host> ...   every named host has an enabled nova-compute service that is up
  //   hypervisors             one line per hypervisor (state, vCPUs, memory)
  //   ovs-up <host> ...       every named host has an alive neutron-openvswitch-agent
  //   known <host>            exit 0 when Nova has a nova-compute service or Neutron an agent for the host
  //   unregister <host>       delete the host's nova-compute service (and its compute node and resource
  //                           provider, which Nova removes with it) and its Neutron agents
  //   boot-cleanup           delete what an earlier boot-test --keep left behind
  //   boot-test <qcow2-file> [--keep]  upload an image, create a flavor, use the existing flat
  //                           network (`provider`, left in place) or else create a throwaway
  //                           one with DHCP, boot an instance with a config drive,
  //                           wait for the console to show the login prompt and for the
  //                           Neutron port to go ACTIVE, print "instance ready: ip=.. mac=..
  //                           network=..", then delete everything it created unless --keep.
  //                           The callers prove the lease with utils/check-guest-dhcp.sh
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, re, ssl, sys, time, urllib.request, urllib.error

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


def known(host):
    token = admin_token()
    s, p, _ = call('GET', NOVA + '/os-services?binary=nova-compute&host=' + host, None, token)
    if s == 200 and p.get('services'):
        print('nova-compute service of %s exists' % host)
        return
    s, p, _ = call('GET', NEUTRON + '/v2.0/agents?host=' + host, None, token)
    if s == 200 and p.get('agents'):
        print('neutron agents of %s exist' % host)
        return
    die('nothing is registered for ' + host)


def unregister(host):
    token = admin_token()
    s, p, _ = call('GET', NOVA + '/os-services?binary=nova-compute&host=' + host, None, token)
    for svc in (p.get('services', []) if s == 200 else []):
        s2, p2, _ = call('DELETE', NOVA + '/os-services/' + str(svc['id']), None, token)
        if s2 not in (200, 204):
            die('deleting the nova-compute service of %s failed: HTTP %s %s' % (host, s2, p2))
    s, p, _ = call('GET', NEUTRON + '/v2.0/agents?host=' + host, None, token)
    for a in (p.get('agents', []) if s == 200 else []):
        s2, p2, _ = call('DELETE', NEUTRON + '/v2.0/agents/' + a['id'], None, token)
        if s2 not in (200, 204):
            die('deleting the neutron agent %s of %s failed: HTTP %s %s' % (a.get('binary'), host, s2, p2))
    print('removed the nova-compute service and the Neutron agents of ' + host)


def wait_for(fn, what, tries, delay):
    last = None
    for _ in range(tries):
        last = fn()
        if last[0]:
            return last[1]
        time.sleep(delay)
    die('%s: gave up (last: %s)' % (what, str(last[1])[-400:]))


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
        # network + subnet (DHCP on). Only one flat network is allowed on PHYSNET: when one is
        # there (the persistent `provider` network, neutron_single_node.scala's provider-net), use it
        # and leave it in place; otherwise make a throwaway one.
        flat = [n for n in call('GET', NEUTRON + '/v2.0/networks', None, token)[1].get('networks', [])
                if n.get('provider:network_type') == 'flat' and n.get('provider:physical_network') == PHYSNET]
        if flat:
            net = flat[0]['id']
            subs = call('GET', NEUTRON + '/v2.0/subnets?network_id=' + net, None, token)[1].get('subnets', [])
            if not any(x.get('enable_dhcp') for x in subs):
                die('the flat network %s (%s) has no subnet with DHCP; the boot test needs one' % (flat[0].get('name'), net))
            print('using the existing flat network %s (%s)' % (flat[0].get('name'), net))
        else:
            s, p, _ = call('POST', NEUTRON + '/v2.0/networks', {'network': {'name': 'orphera-boot-test', 'provider:network_type': 'flat', 'provider:physical_network': PHYSNET}}, token)
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
            s, p, _ = call('POST', NOVA + '/servers/%s/action' % server, {'os-getConsoleOutput': {'length': 5000}}, token)
            out = p.get('output', '') if s == 200 else ''
            return ('login:' in out, out)
        out = wait_for(console, 'login prompt on the console', 48, 5)
        print('console shows the login prompt')
        # The console cannot prove DHCP: CirrOS prints its network debug within a second of
        # starting dhcpcd and syslog never reaches the console, so a lease granted a moment
        # later is not shown there. Report what Neutron knows (the port is ACTIVE, which
        # address it holds) and let the caller prove the guest took the address: it answers
        # ARP for it from the DHCP namespace (utils/check-guest-dhcp.sh).
        def port_active():
            s, p, _ = call('GET', NEUTRON + '/v2.0/ports?device_id=' + server, None, token)
            ports = p.get('ports', []) if s == 200 else []
            return (bool(ports) and all(x.get('status') == 'ACTIVE' for x in ports), ports)
        ports = wait_for(port_active, 'Neutron port ACTIVE', 24, 5)
        print('instance ready: ip=%s mac=%s network=%s' % (ports[0]['fixed_ips'][0]['ip_address'], ports[0]['mac_address'], net))
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
    elif cmd == 'known' and len(sys.argv) == 3:
        known(sys.argv[2])
    elif cmd == 'unregister' and len(sys.argv) == 3:
        unregister(sys.argv[2])
    elif cmd == 'boot-cleanup':
        boot_cleanup()
    elif cmd == 'boot-test' and len(sys.argv) > 2:
        boot_test(sys.argv[2], '--keep' in sys.argv[3:])
    else:
        die('usage: orphera-compute-api compute-up <host>... | hypervisors | ovs-up <host>... | known <host> | unregister <host> | boot-cleanup | boot-test <qcow2-file> [--keep]')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@NOVA@@", novaApi)
      .replace("@@NEUTRON@@", neutronApi)
      .replace("@@GLANCE@@", glanceApi)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@PHYSNET@@", physnet)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-compute-api"

  // Each node checks its own entries. nova-compute takes a while to register and the OVS agent a little
  // less.
  private val reportsIn =
    s"""H=$$(hostname -s)
       |$helperPath compute-up $$H >/dev/null 2>&1 && $helperPath ovs-up $$H >/dev/null 2>&1""".stripMargin
  private val waitReportsIn =
    s"""H=$$(hostname -s)
       |for i in $$(seq 1 36); do
       |  if $helperPath compute-up $$H >/dev/null 2>&1 && $helperPath ovs-up $$H >/dev/null 2>&1; then
       |    $helperPath compute-up $$H
       |    $helperPath ovs-up $$H
       |    exit 0
       |  fi
       |  sleep 5
       |done
       |$helperPath compute-up $$H
       |$helperPath ovs-up $$H
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val host = target.name
    val ip = target.vars
      .getOrElse("cluster_ip", sys.error(s"inventory: node $host has no cluster_ip"))

    val kvm = command(
      "check that the VM has hardware virtualization",
      kvmMissingScript,
      unless = kvmPresent
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    // libvirtd, the libvirt secret for the Ceph volumes, the provider NIC and its bridge.
    val libvirt = command(
      "start libvirtd with the sockets and settings nova-compute needs",
      libvirtScript,
      unless = libvirtReady,
      timeout = 120
    ).after(tools*)
    val cephSecret = command(
      "define the libvirt secret for the Ceph volumes",
      cephSecretScript,
      unless = cephSecretReady,
      timeout = 60
    ).after(libvirt)
    val addressless = command(
      s"make $providerNic an address-less layer-2 port",
      dropAddressScript,
      unless = addressDropped,
      timeout = 120
    ).after(tools*)
    val bridge = command(
      s"create $providerBridge with $providerNic as its port",
      bridgeScript,
      unless = bridgeReady,
      timeout = 60
    ).after((addressless :: tools)*)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Nova compute and Neutron OVS agent images",
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

    val novaLogDirectory = directory(novaLogDir)
    val neutronLogDirectory = directory(neutronLogDir)
    val novaState = directory(novaStateDir)
    val neutronState = directory(neutronStateDir)

    val novaImage = command(
      s"$computeImage has nova-compute and its rootwrap config",
      failWith(
        s"nova-compute or /etc/nova/rootwrap.conf not found in $computeImage - see the header of nova_compute.scala"
      ),
      unless = novaImageOk
    ).after(pulled)
    val isoTool = command(
      s"$computeImage has genisoimage or mkisofs (config drives)",
      failWith(
        s"neither genisoimage nor mkisofs is in $computeImage, which config drives need"
      ),
      unless = isoToolOk
    ).after(pulled)
    val ovsImage = command(
      s"$ovsAgentImage has the Neutron rootwrap config",
      failWith(s"/etc/neutron/rootwrap.conf not found in $ovsAgentImage"),
      unless = ovsRootwrapOk
    ).after(pulled)

    // ---- the Open vSwitch agent ----
    val ovsUnit = service(ovsAgent.unitName)
    val ovsDirRes = directory(ovsDir, mode = "0755")
    val ovsNeutronConf = secretFile(
      s"$ovsDir/neutron.conf",
      Secret(neutronConf(host)), // it holds the message bus password
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(ovsDirRes).notifies(ovsUnit)
    val ovsIni = file(
      s"$ovsDir/openvswitch_agent.ini",
      ovsAgentIni(ip),
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(ovsDirRes).notifies(ovsUnit)
    val ovsCa = command(
      "copy Keystone's CA to the neutron-openvswitch-agent config",
      s"cp $caCert $ovsDir/ca.crt",
      unless = s"cmp -s $caCert $ovsDir/ca.crt"
    ).after(ca, ovsDirRes).notifies(ovsUnit)
    val ovsJson = file(
      s"$ovsDir/config.json",
      ovsConfigJson,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(ovsDirRes).notifies(ovsUnit)
    val ovsUnitFile = file(
      ovsAgent.unitPath,
      ovsAgent.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(ovsUnit)
    val ovsResources =
      List(ovsDirRes, ovsNeutronConf, ovsIni, ovsCa, ovsJson, ovsUnitFile)

    // ---- nova-compute ----
    val novaUnit = service(novaCompute.unitName)
    val novaDirRes = directory(novaDir, mode = "0755")
    val novaConfFile = secretFile(
      s"$novaDir/nova.conf",
      Secret(novaConf(host, ip)), // it holds the message bus and service passwords
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(novaDirRes).notifies(novaUnit)
    val novaCa = command(
      "copy Keystone's CA to the nova-compute config",
      s"cp $caCert $novaDir/ca.crt",
      unless = s"cmp -s $caCert $novaDir/ca.crt"
    ).after(ca, novaDirRes).notifies(novaUnit)
    val novaJson = file(
      s"$novaDir/config.json",
      novaConfigJson,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(novaDirRes).notifies(novaUnit)
    val novaUnitFile = file(
      novaCompute.unitPath,
      novaCompute.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(novaUnit)
    val novaResources =
      List(novaDirRes, novaConfFile, novaCa, novaJson, novaUnitFile)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )

    // The agent first, then nova-compute (which wants libvirtd and the OVS manager on 127.0.0.1:6640).
    val ovsRunning = ovsUnit.after(
      (pulled :: bridge :: ovsImage :: neutronLogDirectory :: neutronState ::
        List(ovsNeutronConf, ovsIni, ovsCa, ovsJson, ovsUnitFile))*
    )
    val novaRunning = novaUnit.after(
      (ovsRunning :: kvm :: libvirt :: cephSecret :: bridge :: novaImage :: isoTool ::
        novaLogDirectory :: novaState :: List(
          novaConfFile,
          novaCa,
          novaJson,
          novaUnitFile
        ))*
    )

    val reporting = command(
      "nova-compute and the OVS agent report in",
      waitReportsIn,
      unless = reportsIn,
      timeout = 240
    ).after(helperFile, ovsRunning, novaRunning)

    tools ++ List(
      kvm,
      libvirt,
      cephSecret,
      addressless,
      bridge,
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      novaLogDirectory,
      neutronLogDirectory,
      novaState,
      neutronState,
      novaImage,
      isoTool,
      ovsImage
    ) ++ ovsResources ++ novaResources ++ List(
      helperFile,
      ovsRunning,
      novaRunning,
      reporting
    )
