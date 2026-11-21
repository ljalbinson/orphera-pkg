// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Octavia (load balancing as a service), Kolla images under podman, with the Amphora provider: every
// load balancer is a small VM that runs HAProxy, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It replaced the cluster playbook of the same name; a second run changes nothing and
// restarts nothing, and `--check` shows what would change. Modelled on manila_single_node.scala and
// trove_single_node.scala, which explain the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/octavia_database.scala      # the octavia database and user (tst3)
//   orphera apply manifests/octavia_rabbitmq.scala      # the `octavia` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/octavia_single_node.scala   # this one (tst19)
//
// Where it runs: tst19 (10.10.5.31), the Neutron node. The controller side of Octavia must reach the amphorae
// over a Neutron network, and tst19 already carries the Open vSwitch bridge for it. To move it the
// health-manager port would have to be plugged into br-int on the new node; `node` and `fqdn` below (and the
// teardown's `node`) are the places to change.
//
// What it builds (four Kolla containers on the host network):
//   - octavia-api            the REST API, port 9876, plain HTTP like the other APIs here
//   - octavia-worker         builds and changes load balancers (boots amphorae through Nova)
//   - octavia-health-manager receives the heartbeats of the amphorae (UDP 5555) and fails over
//   - octavia-housekeeping   removes spare and deleted amphorae, rotates certificates
//
// What it makes outside the containers (the helper's `plumbing`, all in project `service`):
//   - the network lb-mgmt-net (VXLAN) with the subnet 192.168.200.0/24 (DHCP on, no gateway): every amphora has
//     one port on it, and the controller reaches the amphora agent (TCP 9443) there
//   - the security groups lb-mgmt-sec-grp (TCP 9443, ICMP in) for the amphorae and lb-health-mgr-sec-grp
//     (UDP 5555, ICMP in) for the health manager's port
//   - the Nova flavor `orphera.amphora` (id orphera-amphora; 1 vCPU, 1024 MB, 5 GB)
//   - a Neutron port for this node on lb-mgmt-net (name octavia-hm-tst19, bound to this host). The unit
//     orphera-octavia-hm-port.service plugs it into br-int as the internal port `o-hm0` with the port's MAC and
//     address, which is how the containers (host network) reach the amphorae and the amphorae reach the health
//     manager. The health manager binds to that address.
//   - the amphora image: the Octavia project's TEST image (haproxy on Ubuntu noble, ~360 MB) is downloaded from
//     tarballs.opendev.org on the node and uploaded to Glance as `amphora-x64-haproxy-test`, public, with the
//     tag `amphora` (Octavia finds the image by that tag). It is for tests; build your own with
//     diskimage-builder for anything else. If the node cannot reach opendev.org, put the file at
//     /var/lib/orphera/octavia/amphora.qcow2 and run again.
//   - a certificate authority pair made with openssl (server CA and client CA, a client certificate for the
//     controller) under /etc/kolla/octavia/certs. The passphrase of the CA keys is the secret
//     octavia/ca-passphrase. The keys and certificates are made once and kept; they are made again only if a
//     file is missing or the passphrase no longer opens the key (existing amphorae then stop working).
//
// What it adds to Keystone (the helper's `register`): user `octavia` in project `service` with the admin role;
// the roles load-balancer_admin, _member, _observer, _global_observer and _quota_admin; the service `octavia`
// of type `load-balancer` with the endpoint http://<fqdn>:9876. The password of an existing user is changed
// only if a login with the stored one fails (a change revokes its tokens).
//
// Prerequisites, not checked here:
//   - Galera + VIP, Keystone, RabbitMQ (tst10, rabbitmq_single_node.scala) healthy
//   - Nova, Glance and Neutron working, with compute nodes (nova_compute.scala) and the Neutron L3 and DHCP
//     agents on tst19 (neutron_single_node.scala): the test boots a real VM
//   - the agent on tst19; the Octavia images (octavia-api, octavia-worker, octavia-health-manager,
//     octavia-housekeeping) on tst21's registry or on quay.io
//
// How it stays idempotent (as for Manila, Trove and Magnum):
//   - Keystone's CA is compared as a certificate with the installed copy each run; the copies in the four
//     Kolla directories are compared with it, and a service restarts when its copy changes.
//   - The helper's `plumbing` is skipped while its read-only check `plumbing-ok` passes: the network, the
//     groups, the flavor and the port exist, and ids.env says what Neutron has.
//   - octavia.conf needs the ids of the amphora network and security group and the address of the health
//     manager's port, which exist only after the plumbing. The template `octavia.conf.in` (a secret file) is
//     declared; a command fills it in from ids.env, skipped while the result is the installed file. The
//     services' copies (and the certificates) are compared with it, and a change restarts them.
//   - The o-hm0 plug-in script and unit are declared files; plugging is skipped while o-hm0 has the port's
//     address and MAC on br-int, the unit is enabled and active, and the stamp of the script and unit matches.
//   - `octavia-db-manage upgrade head` is skipped while its stamp matches and the `load_balancer` table
//     exists (a query as the octavia user, through the VIP).
//   - Octavia is registered in Keystone before the units start, skipped while the helper's `registered` passes.
//   - The amphora image is downloaded and uploaded in two steps (each prints a line every 20 seconds while it
//     works), skipped while Glance has an active image with the tag `amphora`.
//   - The load balancer test (an amphora is booted: several minutes) is made again only when its stamp changes
//     (the configuration, the plug-in script, the image address, the server CA), so a repeated run does not
//     boot one. manifests/test_octavia.sh makes one more by hand. Delete /var/lib/orphera/octavia/cycle.stamp to
//     have the next run make one.
//
// Facts about Octavia this was written from, to check if a run fails: the console scripts octavia-api,
// octavia-worker, octavia-health-manager, octavia-housekeeping and octavia-db-manage are in the images (the run
// stops with a message if not); the default drivers are the no-op ones, so network_driver, compute_driver and
// amphora_driver are set; the keystone session options (cafile) are needed in [nova], [neutron], [glance] and
// [service_auth] because Keystone here uses a private CA; the controller_ip_port_list entry is the address of
// o-hm0; octavia.conf is plain text.
//
// Test-only credentials come from the secret store (octavia/db, octavia/service, octavia/rabbit,
// octavia/ca-passphrase, octavia/heartbeat-key), made on first use.
object octavia_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst19"
  private val fqdn = "tst19.ljalbinson.com"
  private val apiPort = 9876

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "octavia"
  private val rabbitPassword = Secrets.secret("octavia/rabbit")
  private val rabbitVhost = "octavia"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "octavia"
  private val dbUser = "octavia"
  private val dbPassword = Secrets.secret("octavia/db")
  private val octaviaPassword = Secrets.secret("octavia/service")
  private val caPassphrase = Secrets.secret("octavia/ca-passphrase")
  private val heartbeatKey = Secrets.secret("octavia/heartbeat-key")

  private val amphoraImageUrl =
    "https://tarballs.opendev.org/openstack/octavia/test-images/test-only-amphora-x64-haproxy-ubuntu-noble.qcow2"
  private val amphoraFlavorId = "orphera-amphora"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/octavia"
  private val certDir = "/etc/kolla/octavia/certs"
  private val logDir = "/var/log/kolla/octavia"
  private val stateDir = "/var/lib/orphera/octavia"
  private val dataDir = "/var/lib/orphera/octavia/data"
  private val caDir = "/etc/kolla/octavia-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val hmUnit = "orphera-octavia-hm-port.service"
  private val helperPath = "/usr/local/sbin/orphera-octavia-api"

  // The four containers as data (orchestrator/.../kolla/Container.scala, checked by ContainerCheck). They start
  // after the unit that plugs the health-manager port into br-int, because the health manager binds to its
  // address and the worker reaches the amphorae through it. Defined after the directory vals, which they read;
  // a val used before its definition is null in an object.
  private def octaviaContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/octavia", Access.ReadWrite),
        Mount(dataDir, "/var/lib/octavia", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      after = List(hmUnit),
      wants = List(hmUnit)
    )
  // Housekeeping, worker and health manager first, the API last.
  private val octaviaServices: List[Container] =
    List(
      "octavia-housekeeping",
      "octavia-worker",
      "octavia-health-manager",
      "octavia-api"
    ).map(octaviaContainer)

  private val apiImage =
    Image("octavia-api", Release.R2025_1, Base.UbuntuNoble, registry).ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "iproute2",
      "openvswitch-switch"
    )

  // Plain text with @@NAME@@ markers; fill() replaces the directory markers, so shell `$` needs no escape.
  private def fill(s: String): String =
    s.replace("@@CONF@@", confRoot)
      .replace("@@SRC@@", srcDir)
      .replace("@@CERTS@@", certDir)
      .replace("@@LOG@@", logDir)
      .replace("@@STATE@@", stateDir)
      .replace("@@DATA@@", dataDir)
      .replace("@@CA@@", caDir)
      .replace("@@HELPER@@", helperPath)

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

  private val images = octaviaServices.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in octavia_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(" ")}; do podman image exists $$img || exit 1; done"""

  // Every command the units and the database step run must exist in its image.
  private val imageCheckScript: String =
    val checks = octaviaServices
      .map { c =>
        val cmd = c.name.value
        s"""if ! podman run --rm --entrypoint sh ${c.image.ref} -c 'command -v $cmd >/dev/null'; then
           |  echo "$cmd not found in ${c.image.ref}" >&2
           |  exit 1
           |fi""".stripMargin
      }
      .mkString("\n")
    s"""$checks
       |if ! podman run --rm --entrypoint sh $apiImage -c 'command -v octavia-db-manage >/dev/null'; then
       |  echo "octavia-db-manage not found in $apiImage" >&2
       |  exit 1
       |fi
       |echo "the Octavia commands are in the images"""".stripMargin


  private val imageCheckOk = s"( $imageCheckScript ) >/dev/null 2>&1"

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

  // ---- certificates ----------------------------------------------------

  // Two certificate authorities (the amphorae trust the client CA for the controller; the controller trusts
  // the server CA for the amphorae) and a client certificate. Made only when something is missing or the
  // passphrase no longer opens the server CA key.
  private val certsScript: String =
    fill("""set -e
      |D=@@CERTS@@
      |P='@@PASS@@'
      |mkdir -p $D
      |chmod 700 $D
      |need=0
      |for f in server_ca.key.pem server_ca.cert.pem client_ca.key.pem client_ca.cert.pem client.cert-and-key.pem; do
      |  [ -s $D/$f ] || need=1
      |done
      |if [ $need = 0 ] && ! openssl rsa -in $D/server_ca.key.pem -passin "pass:$P" -noout 2>/dev/null; then need=1; fi
      |if [ $need = 0 ] && ! openssl rsa -in $D/client_ca.key.pem -passin "pass:$P" -noout 2>/dev/null; then need=1; fi
      |if [ $need = 0 ]; then
      |  echo "certificates unchanged"
      |  exit 0
      |fi
      |rm -f $D/*
      |for ca in server client; do
      |  openssl genrsa -aes256 -passout "pass:$P" -out $D/${ca}_ca.key.pem 2048
      |  openssl req -x509 -new -key $D/${ca}_ca.key.pem -passin "pass:$P" -days 3650 -sha256 -subj "/O=Orphera/CN=Orphera Octavia $ca CA" -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out $D/${ca}_ca.cert.pem
      |done
      |openssl genrsa -out $D/client.key.pem 2048
      |openssl req -new -key $D/client.key.pem -subj "/O=Orphera/CN=octavia-controller" -out $D/client.csr.pem
      |printf 'basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=clientAuth\n' > $D/client.ext
      |openssl x509 -req -in $D/client.csr.pem -CA $D/client_ca.cert.pem -CAkey $D/client_ca.key.pem -passin "pass:$P" -CAcreateserial -days 3650 -sha256 -extfile $D/client.ext -out $D/client.cert.pem
      |cat $D/client.cert.pem $D/client.key.pem > $D/client.cert-and-key.pem
      |rm -f $D/client.ext $D/client.csr.pem $D/*.srl
      |chmod 600 $D/*
      |openssl verify -CAfile $D/client_ca.cert.pem $D/client.cert.pem
      |echo "certificates made"
      |""".stripMargin).replace("@@PASS@@", caPassphrase.reveal)

  // ---- the health-manager port ----------------------------------------

  private val hmPortScript: String =
    fill("""#!/bin/sh
      |# Managed by Orphera (octavia_single_node.scala). Plugs the Neutron port of this node on lb-mgmt-net into
      |# br-int as o-hm0. Repeatable.
      |set -e
      |. @@STATE@@/ids.env
      |for i in $(seq 1 60); do
      |  if ovs-vsctl br-exists br-int; then break; fi
      |  sleep 2
      |done
      |ovs-vsctl br-exists br-int
      |# An o-hm0 left from an earlier install carries another port's iface-id, and the Open vSwitch agent does not
      |# notice an iface-id that changes on a live interface. Make it again so the agent sees a new port.
      |if ovs-vsctl list-ports br-int | grep -qx o-hm0 && [ "$(ovs-vsctl get Interface o-hm0 external_ids:iface-id 2>/dev/null | tr -d '"')" != "$HM_PORT_ID" ]; then
      |  ovs-vsctl --if-exists del-port br-int o-hm0
      |fi
      |ovs-vsctl --may-exist add-port br-int o-hm0 -- set Interface o-hm0 type=internal -- set Interface o-hm0 external-ids:iface-status=active -- set Interface o-hm0 external-ids:attached-mac=$HM_MAC -- set Interface o-hm0 external-ids:iface-id=$HM_PORT_ID -- set Interface o-hm0 external-ids:skip_cleanup=true
      |ip link set dev o-hm0 address $HM_MAC
      |ip link set dev o-hm0 mtu $HM_MTU
      |ip addr flush dev o-hm0
      |ip addr add $HM_IP/$HM_PREFIX dev o-hm0
      |ip link set dev o-hm0 up
      |sysctl -qw net.ipv4.conf.o-hm0.rp_filter=0 || true
      |echo "o-hm0 is $HM_IP/$HM_PREFIX ($HM_MAC) on br-int"
      |""".stripMargin)

  private val hmUnitText =
    """# Managed by Orphera (octavia_single_node.scala)
      |[Unit]
      |Description=Octavia health manager port o-hm0 on br-int (Orphera)
      |After=network-online.target openvswitch-switch.service orphera-neutron-openvswitch-agent.service
      |Wants=openvswitch-switch.service
      |
      |[Service]
      |Type=oneshot
      |RemainAfterExit=yes
      |ExecStart=/usr/local/sbin/orphera-octavia-hm-port
      |
      |[Install]
      |WantedBy=multi-user.target
      |""".stripMargin

  private val hmStartScript =
    """set -e
      |systemctl daemon-reload
      |systemctl enable orphera-octavia-hm-port.service
      |/usr/local/sbin/orphera-octavia-hm-port
      |systemctl start orphera-octavia-hm-port.service
      |ip -4 -o addr show dev o-hm0""".stripMargin


  // Current when all the certificate files are there and the passphrase opens both CA keys (the test the script
  // above makes before it decides to make new ones).
  private val certsCurrent =
    fill(
      """D=@@CERTS@@
        |for f in server_ca.key.pem server_ca.cert.pem client_ca.key.pem client_ca.cert.pem client.cert-and-key.pem; do
        |  [ -s $D/$f ] || exit 1
        |done
        |openssl rsa -in $D/server_ca.key.pem -passin "pass:@@PASS@@" -noout 2>/dev/null || exit 1
        |openssl rsa -in $D/client_ca.key.pem -passin "pass:@@PASS@@" -noout 2>/dev/null || exit 1
        |exit 0""".stripMargin
    ).replace("@@PASS@@", caPassphrase.reveal)

  // The plug-in script and unit are kept with a stamp, so that a change is plugged in again.
  private val hmStamp = Resource.sha256(hmPortScript + hmUnitText)
  private val hmStampFile = s"$stateDir/hm.stamp"
  private val hmPlugScript =
    s"""$hmStartScript
       |echo $hmStamp > $hmStampFile""".stripMargin
  // True when o-hm0 is on br-int with the address and MAC in ids.env, the unit is enabled and active, and the
  // stamp is the one the last plug-in made.
  private val hmPlugged =
    fill(
      s"""[ "$$(cat $hmStampFile 2>/dev/null)" = "$hmStamp" ] && . @@STATE@@/ids.env && ip -4 -o addr show dev o-hm0 2>/dev/null | grep -q " $$HM_IP/$$HM_PREFIX " && [ "$$(cat /sys/class/net/o-hm0/address 2>/dev/null)" = "$$HM_MAC" ] && ovs-vsctl list-ports br-int 2>/dev/null | grep -qx o-hm0 && systemctl is-enabled --quiet $hmUnit && systemctl is-active --quiet $hmUnit"""
    )

  // ---- octavia.conf ----------------------------------------------------

  // Written with markers that the next task fills from ids.env (the helper's `plumbing` makes the network, the
  // security group and the port, whose ids are not known when this file is built).
  private val octaviaConfTemplate: String =
    s"""# Managed by Orphera (octavia_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/octavia
       |use_stderr = true
       |
       |[api_settings]
       |bind_host = 0.0.0.0
       |bind_port = $apiPort
       |auth_strategy = keystone
       |api_base_uri = http://$fqdn:$apiPort
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[health_manager]
       |bind_ip = @@HM_IP@@
       |bind_port = 5555
       |controller_ip_port_list = @@HM_IP@@:5555
       |heartbeat_key = ${heartbeatKey.reveal}
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = octavia
       |password = ${octaviaPassword.reveal}
       |cafile = /etc/octavia/ssl/ca.crt
       |
       |[service_auth]
       |auth_url = $ksUrl/v3
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = octavia
       |password = ${octaviaPassword.reveal}
       |cafile = /etc/octavia/ssl/ca.crt
       |region_name = RegionOne
       |
       |[nova]
       |cafile = /etc/octavia/ssl/ca.crt
       |region_name = RegionOne
       |
       |[neutron]
       |cafile = /etc/octavia/ssl/ca.crt
       |region_name = RegionOne
       |
       |[glance]
       |cafile = /etc/octavia/ssl/ca.crt
       |region_name = RegionOne
       |
       |[certificates]
       |cert_generator = local_cert_generator
       |ca_certificate = /etc/octavia/certs/server_ca.cert.pem
       |ca_private_key = /etc/octavia/certs/server_ca.key.pem
       |ca_private_key_passphrase = ${caPassphrase.reveal}
       |
       |[haproxy_amphora]
       |server_ca = /etc/octavia/certs/server_ca.cert.pem
       |client_cert = /etc/octavia/certs/client.cert-and-key.pem
       |
       |[controller_worker]
       |amp_image_tag = amphora
       |amp_flavor_id = $amphoraFlavorId
       |amp_boot_network_list = @@AMP_NET_ID@@
       |amp_secgroup_list = @@AMP_SG_ID@@
       |client_ca = /etc/octavia/certs/client_ca.cert.pem
       |network_driver = allowed_address_pairs_driver
       |compute_driver = compute_nova_driver
       |amphora_driver = amphora_haproxy_rest_driver
       |loadbalancer_topology = SINGLE
       |
       |[oslo_messaging]
       |topic = octavia_prov
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/octavia/tmp
       |""".stripMargin


  private val renderConfScript =
    fill(
      """set -e
        |. @@STATE@@/ids.env
        |sed -e "s|@@HM_IP@@|$HM_IP|g" -e "s|@@AMP_NET_ID@@|$AMP_NET_ID|g" -e "s|@@AMP_SG_ID@@|$AMP_SG_ID|g" @@SRC@@/octavia.conf.in > @@SRC@@/octavia.conf.new
        |if grep -q '@@' @@SRC@@/octavia.conf.new; then
        |  echo "octavia.conf still has unfilled markers" >&2
        |  exit 1
        |fi
        |chmod 600 @@SRC@@/octavia.conf.new
        |mv @@SRC@@/octavia.conf.new @@SRC@@/octavia.conf
        |echo "octavia.conf written (health manager $HM_IP, amphora network $AMP_NET_ID)"""".stripMargin
    )


  // Current when filling in the template from ids.env gives the installed octavia.conf.
  private val renderCurrent =
    fill(
      """. @@STATE@@/ids.env && T=$(mktemp) && sed -e "s|@@HM_IP@@|$HM_IP|g" -e "s|@@AMP_NET_ID@@|$AMP_NET_ID|g" -e "s|@@AMP_SG_ID@@|$AMP_SG_ID|g" @@SRC@@/octavia.conf.in > $T && cmp -s $T @@SRC@@/octavia.conf; R=$?; rm -f $T; exit $R"""
    )

  // The files every service's config.json copies in, and the ownership it sets. The command is the service's
  // own name.
  private def configJson(service: String): String =
    s"""{"command": "$service --config-file /etc/octavia/octavia.conf", "config_files": [
       |  {"source": "/var/lib/kolla/config_files/octavia.conf", "dest": "/etc/octavia/octavia.conf", "owner": "octavia", "perm": "0600"},
       |  {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/octavia/ssl/ca.crt", "owner": "octavia", "perm": "0644"},
       |  {"source": "/var/lib/kolla/config_files/server_ca.key.pem", "dest": "/etc/octavia/certs/server_ca.key.pem", "owner": "octavia", "perm": "0600"},
       |  {"source": "/var/lib/kolla/config_files/server_ca.cert.pem", "dest": "/etc/octavia/certs/server_ca.cert.pem", "owner": "octavia", "perm": "0644"},
       |  {"source": "/var/lib/kolla/config_files/client_ca.cert.pem", "dest": "/etc/octavia/certs/client_ca.cert.pem", "owner": "octavia", "perm": "0644"},
       |  {"source": "/var/lib/kolla/config_files/client.cert-and-key.pem", "dest": "/etc/octavia/certs/client.cert-and-key.pem", "owner": "octavia", "perm": "0600"}
       |], "permissions": [
       |  {"path": "/var/log/kolla/octavia", "owner": "octavia:kolla", "recurse": true},
       |  {"path": "/var/lib/octavia", "owner": "octavia:octavia", "recurse": true}
       |]}
       |""".stripMargin

  // The certificate files every container gets, with their modes.
  private val certFiles = List("server_ca.key.pem", "server_ca.cert.pem", "client_ca.cert.pem", "client.cert-and-key.pem")
  private def certsCopyCurrent(dir: String): String =
    s"""for f in ${certFiles.mkString(" ")}; do cmp -s $certDir/$$f $dir/$$f || exit 1; done"""
  private def certsCopyScript(dir: String): String =
    s"""set -e
       |for f in ${certFiles.mkString(" ")}; do
       |  case $$f in *.key.pem|*cert-and-key.pem) m=0600 ;; *) m=0644 ;; esac
       |  install -m $$m $certDir/$$f $dir/$$f
       |done
       |echo "certificates copied to $dir"""".stripMargin

  // octavia-db-manage runs in a one-off container with octavia.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    fill(
      s"""set -e
         |podman run --rm --network host --user root --entrypoint octavia-db-manage -v @@SRC@@/octavia.conf:/etc/octavia/octavia.conf:ro,z -v @@LOG@@:/var/log/kolla/octavia:z $apiImage --config-file /etc/octavia/octavia.conf upgrade head
         |echo "octavia database synced"""".stripMargin
    )

  // Runs a Python snippet in the octavia-api image, with its own interpreter (the one named on the first line of
  // octavia-db-manage), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`. Exit 0
  // when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v octavia-db-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbStamp = Resource.sha256(dbSyncScript + octaviaConfTemplate)
  // True when the octavia database, queried as the octavia user through the VIP, has the `load_balancer` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM load_balancer')")
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // ---- the amphora image ---------------------------------------------------

  // A step that runs for minutes with no output can lose its connection to the agent, so the download and the
  // upload each print a line every 20 seconds while they work.
  private val heartbeat =
    """heartbeat() { "$@" & P=$!; while kill -0 $P 2>/dev/null; do sleep 20; echo "still working ($(date +%H:%M:%S))"; done; wait $P; }"""

  private val amphoraFile = s"$stateDir/amphora.qcow2"
  private val fetchImageScript =
    s"""set -e
       |$heartbeat
       |IMG=$amphoraFile
       |if [ ! -s $$IMG ]; then
       |  if ! heartbeat curl -fsSL --retry 3 --connect-timeout 30 -o $$IMG.part $amphoraImageUrl; then
       |    rm -f $$IMG.part
       |    echo "cannot download $amphoraImageUrl - put the image file at $$IMG and run again" >&2
       |    exit 1
       |  fi
       |  mv $$IMG.part $$IMG
       |fi
       |MAGIC=$$(head -c 3 $$IMG)
       |if [ "$$MAGIC" != "QFI" ]; then
       |  echo "$$IMG is not a qcow2 file - delete it and run again" >&2
       |  exit 1
       |fi
       |ls -l $$IMG""".stripMargin
  private val imageFileOk = s"""[ "$$(head -c 3 $amphoraFile 2>/dev/null)" = "QFI" ]"""
  private val imagePresent = s"$helperPath image-present >/dev/null 2>&1"
  private val uploadImageScript =
    s"""set -e
       |$heartbeat
       |heartbeat $helperPath image-upload $amphoraFile""".stripMargin

  // ---- the helper ----------------------------------------------------------

  private val apiHelperPython =
    """#!/usr/bin/python3
import json, os, re, subprocess, shutil, ssl, sys, tempfile, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
OCTAVIA = '@@OCTAVIA@@'
NODE = '@@NODE@@'
ADMIN_PW = '@@ADMINPW@@'
OCTAVIA_PW = '@@OCTAVIAPW@@'
CTX = ssl.create_default_context(cafile='@@CAFILE@@')
STATE = '@@STATE@@'
MGMT_NET = 'lb-mgmt-net'
MGMT_SUBNET = 'lb-mgmt-subnet'
MGMT_CIDR = '192.168.200.0/24'
MGMT_SG = 'lb-mgmt-sec-grp'
HM_SG = 'lb-health-mgr-sec-grp'
HM_PORT = 'octavia-hm-' + NODE
FLAVOR_ID = '@@FLAVORID@@'
FLAVOR_NAME = 'orphera.amphora'
IMAGE_NAME = 'amphora-x64-haproxy-test'
IMAGE_TAG = 'amphora'
ROLES = ['load-balancer_admin', 'load-balancer_member', 'load-balancer_observer', 'load-balancer_global_observer', 'load-balancer_quota_admin']


def call(method, url, body=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=timeout)
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
    return token, parsed


def endpoint(parsed, svc_type):
    for c in parsed['token']['catalog']:
        if c['type'] == svc_type:
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    return e['url'].rstrip('/')
    die('no %s service in the catalog' % svc_type)


def versioned(url, suffix):
    return url if url.endswith(suffix) else url + suffix


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


def register():
    token, _ = admin()
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=octavia', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'octavia', 'domain_id': 'default', 'password': OCTAVIA_PW}}, token)
        if s != 201:
            die('creating user octavia failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        # A password change revokes the user's tokens, so change it only if the stored one does not work.
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'octavia', 'domain': {'name': 'Default'}, 'password': OCTAVIA_PW}}}}}
        s, _, _ = call('POST', KS + '/auth/tokens', body)
        if s != 201:
            call('PATCH', KS + '/users/' + user['id'], {'user': {'password': OCTAVIA_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to octavia failed: HTTP %s %s' % (s, p))
    for name in ROLES:
        ensure(token, 'roles', '/roles?name=' + name, 'roles', {'role': {'name': name}}, 'role')
    service = ensure(token, 'services', '/services?type=load-balancer', 'services', {'service': {'name': 'octavia', 'type': 'load-balancer'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': OCTAVIA, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != OCTAVIA:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': OCTAVIA}}, token)
    print('registered octavia in keystone (user octavia, service type load-balancer, 3 endpoints, %d roles)' % len(ROLES))


def unregister():
    token, _ = admin()
    service = find(token, '/services?type=load-balancer', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=octavia', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed octavia from keystone (service load-balancer, its endpoints, user octavia)')


def catalog():
    _, p = admin()
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


class Clients(object):
    def __init__(self):
        self.token, parsed = admin()
        self.neutron = versioned(endpoint(parsed, 'network'), '/v2.0')
        self.nova = versioned(endpoint(parsed, 'compute'), '/v2.1')
        self.glance = versioned(endpoint(parsed, 'image'), '/v2')

    def n(self, method, path, body=None):
        return call(method, self.neutron + path, body, self.token)

    def c(self, method, path, body=None):
        return call(method, self.nova + path, body, self.token)

    def g(self, method, path, body=None):
        return call(method, self.glance + path, body, self.token)

    def o(self, method, path, body=None):
        return call(method, OCTAVIA + '/v2' + path, body, self.token)


def service_project(cl):
    p = find(cl.token, '/projects?name=service', 'projects')
    if p is None:
        die('project service not found')
    return p['id']


def first(cl, path, key, what):
    s, p, _ = cl.n('GET', path)
    if s != 200:
        die('%s lookup failed: HTTP %s %s' % (what, s, p))
    items = p.get(key, [])
    return items[0] if items else None


def ensure_sg(cl, pid, name, rules):
    sg = first(cl, '/security-groups?name=%s&project_id=%s' % (name, pid), 'security_groups', 'security group')
    if sg is None:
        s, p, _ = cl.n('POST', '/security-groups', {'security_group': {'name': name, 'description': 'Octavia, managed by Orphera', 'project_id': pid}})
        if s != 201:
            die('creating security group %s failed: HTTP %s %s' % (name, s, p))
        sg = p['security_group']
    have = set((r.get('direction'), r.get('protocol'), r.get('port_range_min')) for r in sg.get('security_group_rules', []))
    for proto, port in rules:
        if ('ingress', proto, port) in have:
            continue
        rule = {'security_group_id': sg['id'], 'direction': 'ingress', 'ethertype': 'IPv4', 'protocol': proto, 'remote_ip_prefix': '0.0.0.0/0'}
        if port is not None:
            rule['port_range_min'] = port
            rule['port_range_max'] = port
        s, p, _ = cl.n('POST', '/security-group-rules', {'security_group_rule': rule})
        if s != 201:
            die('creating a rule in %s failed: HTTP %s %s' % (name, s, p))
    return sg['id']


def ovs_host(cl):
    s, p, _ = cl.n('GET', '/agents?binary=neutron-openvswitch-agent')
    if s != 200:
        die('agent list failed: HTTP %s %s' % (s, p))
    for a in p.get('agents', []):
        if a.get('host', '').split('.')[0] == NODE:
            if not a.get('alive'):
                die('the Open vSwitch agent on %s is not alive' % a.get('host'))
            return a['host']
    die('no Open vSwitch agent for %s (neutron_single_node.scala)' % NODE)


def plumbing():
    cl = Clients()
    pid = service_project(cl)
    net = first(cl, '/networks?name=%s&project_id=%s' % (MGMT_NET, pid), 'networks', 'network')
    if net is None:
        s, p, _ = cl.n('POST', '/networks', {'network': {'name': MGMT_NET, 'project_id': pid}})
        if s != 201:
            die('creating %s failed: HTTP %s %s' % (MGMT_NET, s, p))
        net = p['network']
    if net.get('provider:network_type') != 'vxlan':
        die('%s is %s, expected vxlan' % (MGMT_NET, net.get('provider:network_type')))
    sub = first(cl, '/subnets?network_id=' + net['id'], 'subnets', 'subnet')
    if sub is None:
        s, p, _ = cl.n('POST', '/subnets', {'subnet': {'network_id': net['id'], 'ip_version': 4, 'cidr': MGMT_CIDR, 'name': MGMT_SUBNET, 'project_id': pid, 'enable_dhcp': True, 'gateway_ip': None}})
        if s != 201:
            die('creating %s failed: HTTP %s %s' % (MGMT_SUBNET, s, p))
        sub = p['subnet']
    mgmt_sg = ensure_sg(cl, pid, MGMT_SG, [('tcp', 9443), ('icmp', None)])
    hm_sg = ensure_sg(cl, pid, HM_SG, [('udp', 5555), ('icmp', None)])
    s, p, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s == 404:
        s, p, _ = cl.c('POST', '/flavors', {'flavor': {'id': FLAVOR_ID, 'name': FLAVOR_NAME, 'ram': 1024, 'vcpus': 1, 'disk': 5, 'os-flavor-access:is_public': True}})
        if s not in (200, 201):
            die('creating flavor %s failed: HTTP %s %s' % (FLAVOR_ID, s, p))
    elif s != 200:
        die('flavor lookup failed: HTTP %s %s' % (s, p))
    host = ovs_host(cl)
    port = first(cl, '/ports?name=%s&network_id=%s' % (HM_PORT, net['id']), 'ports', 'port')
    if port is None:
        body = {'port': {'name': HM_PORT, 'network_id': net['id'], 'project_id': pid, 'device_owner': 'Octavia:health-mgr', 'device_id': HM_PORT, 'binding:host_id': host, 'security_groups': [hm_sg], 'fixed_ips': [{'subnet_id': sub['id']}]}}
        s, p, _ = cl.n('POST', '/ports', body)
        if s != 201:
            die('creating port %s failed: HTTP %s %s' % (HM_PORT, s, p))
        port = p['port']
    elif port.get('binding:host_id') != host or port.get('binding:vif_type') == 'binding_failed' or port.get('security_groups') != [hm_sg]:
        s, p, _ = cl.n('PUT', '/ports/' + port['id'], {'port': {'binding:host_id': host, 'security_groups': [hm_sg]}})
        if s != 200:
            die('updating port %s failed: HTTP %s %s' % (HM_PORT, s, p))
        port = p['port']
    ip = port['fixed_ips'][0]['ip_address']
    prefix = MGMT_CIDR.split('/')[1]
    os.makedirs(STATE, exist_ok=True)
    tmp = os.path.join(STATE, 'ids.env.new')
    with open(tmp, 'w') as fh:
        fh.write('AMP_NET_ID=%s\nAMP_SG_ID=%s\nHM_PORT_ID=%s\nHM_MAC=%s\nHM_IP=%s\nHM_PREFIX=%s\nHM_MTU=%s\n' % (net['id'], mgmt_sg, port['id'], port['mac_address'], ip, prefix, net.get('mtu') or 1450))
    os.replace(tmp, os.path.join(STATE, 'ids.env'))
    print('plumbing ready: network %s, security groups %s %s, flavor %s, health manager port %s %s on host %s' % (net['id'], mgmt_sg, hm_sg, FLAVOR_ID, port['id'], ip, host))


def wait_hm():
    cl = Clients()
    pid = service_project(cl)
    net = first(cl, '/networks?name=%s&project_id=%s' % (MGMT_NET, pid), 'networks', 'network')
    if net is None:
        die('network %s not found; run plumbing' % MGMT_NET)
    end = time.time() + 150
    port = None
    while time.time() < end:
        port = first(cl, '/ports?name=%s&network_id=%s' % (HM_PORT, net['id']), 'ports', 'port')
        if port is not None and port.get('status') == 'ACTIVE':
            print('health manager port %s is ACTIVE (%s)' % (port['id'], port['fixed_ips'][0]['ip_address']))
            return
        time.sleep(3)
    die('health manager port is not ACTIVE after 150 s: status %s, vif_type %s (check orphera-neutron-openvswitch-agent and ovs-vsctl show on this node)' % (port and port.get('status'), port and port.get('binding:vif_type')))


def amphora_images(cl):
    s, p, _ = cl.g('GET', '/images?tag=' + IMAGE_TAG)
    if s != 200:
        die('image list failed: HTTP %s %s' % (s, p))
    return p.get('images', [])


def image_present():
    cl = Clients()
    ok = [i for i in amphora_images(cl) if i.get('status') == 'active']
    if ok:
        print('amphora image %s (%s) is active in Glance' % (ok[0].get('name'), ok[0]['id']))
        return
    sys.exit(1)


def image_upload(path):
    cl = Clients()
    for i in amphora_images(cl):
        if i.get('status') != 'active':
            cl.g('DELETE', '/images/' + i['id'])
    body = {'name': IMAGE_NAME, 'disk_format': 'qcow2', 'container_format': 'bare', 'visibility': 'public', 'tags': [IMAGE_TAG]}
    s, p, _ = cl.g('POST', '/images', body)
    if s != 201:
        die('image create failed: HTTP %s %s' % (s, p))
    iid = p['id']
    size = os.path.getsize(path)
    with open(path, 'rb') as fh:
        req = urllib.request.Request(cl.glance + '/images/%s/file' % iid, data=fh, method='PUT')
        req.add_header('Content-Type', 'application/octet-stream')
        req.add_header('Content-Length', str(size))
        req.add_header('X-Auth-Token', cl.token)
        try:
            urllib.request.urlopen(req, timeout=1800).read()
        except urllib.error.HTTPError as e:
            die('image upload failed: HTTP %s %s' % (e.code, e.read().decode()[:300]))
    end = time.time() + 300
    status = None
    while time.time() < end:
        s, p, _ = cl.g('GET', '/images/' + iid)
        status = p.get('status')
        if status == 'active':
            print('amphora image uploaded: %s (%s), %d bytes' % (IMAGE_NAME, iid, size))
            return
        if status in ('killed', 'deleted'):
            break
        time.sleep(3)
    die('image %s did not become active (status %s)' % (iid, status))


def providers():
    cl = Clients()
    s, p, _ = cl.o('GET', '/lbaas/providers')
    if s != 200:
        die('provider list failed: HTTP %s %s' % (s, p))
    names = [x.get('name') for x in p.get('providers', [])]
    print('providers: ' + ' '.join(names))
    if 'amphora' not in names:
        die('the amphora provider is not listed')


def wait_lb(cl, lb_id, prov, oper, seconds):
    end = time.time() + seconds
    last = {}
    while time.time() < end:
        s, p, _ = cl.o('GET', '/lbaas/loadbalancers/' + lb_id)
        if s == 200:
            last = p['loadbalancer']
            if last.get('provisioning_status') == 'ERROR':
                die('load balancer %s went to ERROR (operating %s); see orphera-octavia-worker on this node (journalctl -u orphera-octavia-worker)' % (lb_id, last.get('operating_status')))
            if last.get('provisioning_status') == prov and (oper is None or last.get('operating_status') == oper):
                return last
        time.sleep(5)
    die('load balancer %s is %s/%s after %d s, wanted %s/%s' % (lb_id, last.get('provisioning_status'), last.get('operating_status'), seconds, prov, oper))


def netns_exists(name):
    r = subprocess.run(['ip', 'netns', 'list'], capture_output=True, text=True)
    return any(line.split(' ')[0] == name for line in r.stdout.splitlines())


def lb_cycle():
    cl = Clients()
    stamp = int(time.time())
    net_id = None
    lb_id = None
    backend = None
    tmpdir = tempfile.mkdtemp(prefix='orphera-octavia-')
    try:
        s, p, _ = cl.n('POST', '/networks', {'network': {'name': 'orphera-lb-net-%d' % stamp}})
        if s != 201:
            die('network create failed: HTTP %s %s' % (s, p))
        net_id = p['network']['id']
        s, p, _ = cl.n('POST', '/subnets', {'subnet': {'network_id': net_id, 'ip_version': 4, 'cidr': '192.168.78.0/24', 'name': 'orphera-lb-subnet-%d' % stamp, 'enable_dhcp': True}})
        if s != 201:
            die('subnet create failed: HTTP %s %s' % (s, p))
        sub_id = p['subnet']['id']
        end = time.time() + 90
        dhcp_ip = None
        while time.time() < end and dhcp_ip is None:
            s, p, _ = cl.n('GET', '/ports?network_id=%s&device_owner=network:dhcp' % net_id)
            for port in p.get('ports', []):
                if port.get('fixed_ips'):
                    dhcp_ip = port['fixed_ips'][0]['ip_address']
            if dhcp_ip is None:
                time.sleep(3)
        if dhcp_ip is None:
            die('no DHCP port with an address on the test network after 90 s (is neutron-dhcp-agent running?)')
        ns = 'qdhcp-' + net_id
        end = time.time() + 90
        while time.time() < end and not netns_exists(ns):
            time.sleep(3)
        if not netns_exists(ns):
            die('namespace %s does not exist on this node after 90 s' % ns)

        s, p, _ = cl.o('POST', '/lbaas/loadbalancers', {'loadbalancer': {'name': 'orphera-test-%d' % stamp, 'vip_subnet_id': sub_id}})
        if s != 201:
            die('load balancer create failed: HTTP %s %s' % (s, p))
        lb_id = p['loadbalancer']['id']
        lb = wait_lb(cl, lb_id, 'ACTIVE', None, 900)
        vip = lb['vip_address']
        s, p, _ = cl.o('POST', '/lbaas/listeners', {'listener': {'name': 'orphera-listener', 'protocol': 'HTTP', 'protocol_port': 80, 'loadbalancer_id': lb_id}})
        if s != 201:
            die('listener create failed: HTTP %s %s' % (s, p))
        listener_id = p['listener']['id']
        wait_lb(cl, lb_id, 'ACTIVE', None, 300)
        s, p, _ = cl.o('POST', '/lbaas/pools', {'pool': {'name': 'orphera-pool', 'protocol': 'HTTP', 'lb_algorithm': 'ROUND_ROBIN', 'listener_id': listener_id}})
        if s != 201:
            die('pool create failed: HTTP %s %s' % (s, p))
        pool_id = p['pool']['id']
        wait_lb(cl, lb_id, 'ACTIVE', None, 300)
        s, p, _ = cl.o('POST', '/lbaas/pools/%s/members' % pool_id, {'member': {'address': dhcp_ip, 'protocol_port': 8080, 'subnet_id': sub_id}})
        if s != 201:
            die('member create failed: HTTP %s %s' % (s, p))
        wait_lb(cl, lb_id, 'ACTIVE', None, 300)

        marker = 'octavia-backend-%d' % stamp
        with open(os.path.join(tmpdir, 'index.html'), 'w') as fh:
            fh.write(marker + '\n')
        backend = subprocess.Popen(['ip', 'netns', 'exec', ns, 'python3', '-m', 'http.server', '8080', '--bind', dhcp_ip, '--directory', tmpdir], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        end = time.time() + 120
        body = ''
        while time.time() < end:
            r = subprocess.run(['ip', 'netns', 'exec', ns, 'curl', '-s', '-m', '5', 'http://%s:80/' % vip], capture_output=True, text=True)
            body = r.stdout
            if marker in body:
                break
            time.sleep(3)
        if marker not in body:
            die('the load balancer address %s did not return the backend page within 120 s (last answer: %r)' % (vip, body[:80]))
        print('lb cycle ok: load balancer %s ACTIVE, %s answered with the backend page through the amphora, then deleted' % (lb_id, vip))
    finally:
        if backend is not None:
            backend.terminate()
        if lb_id is not None:
            cl.o('DELETE', '/lbaas/loadbalancers/%s?cascade=true' % lb_id)
            end = time.time() + 300
            while time.time() < end:
                s, _, _ = cl.o('GET', '/lbaas/loadbalancers/' + lb_id)
                if s == 404:
                    break
                time.sleep(5)
        if net_id is not None:
            end = time.time() + 180
            while time.time() < end:
                s, _, _ = cl.n('DELETE', '/networks/' + net_id)
                if s in (204, 404):
                    break
                time.sleep(5)
        shutil.rmtree(tmpdir, ignore_errors=True)


def teardown():
    cl = Clients()
    pid = service_project(cl)
    s, p, _ = cl.c('GET', '/servers/detail?all_tenants=1&name=amphora-')
    for srv in p.get('servers', []) if s == 200 else []:
        if srv.get('tenant_id') == pid or srv.get('name', '').startswith('amphora-'):
            cl.c('DELETE', '/servers/' + srv['id'])
    time.sleep(5)
    net = first(cl, '/networks?name=%s&project_id=%s' % (MGMT_NET, pid), 'networks', 'network')
    if net is not None:
        s, p, _ = cl.n('GET', '/ports?network_id=' + net['id'])
        for port in p.get('ports', []) if s == 200 else []:
            if port.get('device_owner') != 'network:dhcp':
                cl.n('DELETE', '/ports/' + port['id'])
        end = time.time() + 120
        while time.time() < end:
            s, _, _ = cl.n('DELETE', '/networks/' + net['id'])
            if s in (204, 404):
                break
            time.sleep(5)
    for name in (MGMT_SG, HM_SG):
        sg = first(cl, '/security-groups?name=%s&project_id=%s' % (name, pid), 'security_groups', 'security group')
        if sg is not None:
            cl.n('DELETE', '/security-groups/' + sg['id'])
    cl.c('DELETE', '/flavors/' + FLAVOR_ID)
    for i in amphora_images(cl):
        cl.g('DELETE', '/images/' + i['id'])
    unregister()
    print('removed the Octavia network, security groups, flavor, image and Keystone entries')


def registered():
    # Exit 0 only when everything register() makes is in place: the user octavia (able to log in with the stored
    # password) with the admin role on project service, the five load-balancer roles, and the service of type
    # load-balancer with its three endpoints. Makes no change.
    token, _ = admin()
    project = find(token, '/projects?name=service', 'projects')
    user = find(token, '/users?name=octavia', 'users')
    admin_role = find(token, '/roles?name=admin', 'roles')
    if project is None or user is None or admin_role is None:
        die('project service, user octavia or role admin is missing')
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'octavia', 'domain': {'name': 'Default'}, 'password': OCTAVIA_PW}}}}}
    s, _, _ = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('user octavia cannot log in with the stored password')
    s, _, _ = call('GET', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s != 204:
        die('user octavia has no admin role on project service (HTTP %s)' % s)
    for name in ROLES:
        if find(token, '/roles?name=' + name, 'roles') is None:
            die('role %s is missing' % name)
    service = find(token, '/services?type=load-balancer', 'services')
    if service is None:
        die('service of type load-balancer is missing')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != OCTAVIA:
            die('the %s endpoint is missing or differs' % iface)
    print('octavia is registered in keystone')


def sg_ok(cl, pid, name, rules):
    sg = first(cl, '/security-groups?name=%s&project_id=%s' % (name, pid), 'security_groups', 'security group')
    if sg is None:
        return None
    have = set((r.get('direction'), r.get('protocol'), r.get('port_range_min')) for r in sg.get('security_group_rules', []))
    for proto, port in rules:
        if ('ingress', proto, port) not in have:
            return None
    return sg['id']


def plumbing_ok():
    # Exit 0 only when everything plumbing() makes is in place and ids.env says what plumbing() would write:
    # the network (vxlan) and subnet, the two security groups with their rules, the flavor, and the health
    # manager's port bound to this host. Makes no change.
    cl = Clients()
    pid = service_project(cl)
    net = first(cl, '/networks?name=%s&project_id=%s' % (MGMT_NET, pid), 'networks', 'network')
    if net is None or net.get('provider:network_type') != 'vxlan':
        die('network %s is missing or not vxlan' % MGMT_NET)
    sub = first(cl, '/subnets?network_id=' + net['id'], 'subnets', 'subnet')
    if sub is None:
        die('the subnet of %s is missing' % MGMT_NET)
    mgmt_sg = sg_ok(cl, pid, MGMT_SG, [('tcp', 9443), ('icmp', None)])
    hm_sg = sg_ok(cl, pid, HM_SG, [('udp', 5555), ('icmp', None)])
    if mgmt_sg is None or hm_sg is None:
        die('a security group or one of its rules is missing')
    s, _, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s != 200:
        die('flavor %s is missing (HTTP %s)' % (FLAVOR_ID, s))
    host = ovs_host(cl)
    port = first(cl, '/ports?name=%s&network_id=%s' % (HM_PORT, net['id']), 'ports', 'port')
    if port is None or port.get('binding:host_id') != host or port.get('binding:vif_type') == 'binding_failed' or port.get('security_groups') != [hm_sg]:
        die('the health manager port is missing, not bound to %s or in the wrong group' % host)
    ip = port['fixed_ips'][0]['ip_address']
    prefix = MGMT_CIDR.split('/')[1]
    want = 'AMP_NET_ID=%s\nAMP_SG_ID=%s\nHM_PORT_ID=%s\nHM_MAC=%s\nHM_IP=%s\nHM_PREFIX=%s\nHM_MTU=%s\n' % (net['id'], mgmt_sg, port['id'], port['mac_address'], ip, prefix, net.get('mtu') or 1450)
    try:
        have = open(os.path.join(STATE, 'ids.env')).read()
    except IOError:
        die('%s/ids.env is missing' % STATE)
    if have != want:
        die('%s/ids.env differs from the ids Neutron has' % STATE)
    print('plumbing is in place')


def hm_active():
    # Exit 0 when the health manager's port is ACTIVE. Makes no change and does not wait.
    cl = Clients()
    pid = service_project(cl)
    net = first(cl, '/networks?name=%s&project_id=%s' % (MGMT_NET, pid), 'networks', 'network')
    if net is None:
        die('network %s not found' % MGMT_NET)
    port = first(cl, '/ports?name=%s&network_id=%s' % (HM_PORT, net['id']), 'ports', 'port')
    if port is None or port.get('status') != 'ACTIVE':
        die('the health manager port is not ACTIVE')
    print('health manager port %s is ACTIVE' % port['id'])


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
    elif cmd == 'image-present':
        image_present()
    elif cmd == 'image-upload' and len(sys.argv) > 2:
        image_upload(sys.argv[2])
    elif cmd == 'plumbing':
        plumbing()
    elif cmd == 'plumbing-ok':
        plumbing_ok()
    elif cmd == 'wait-hm':
        wait_hm()
    elif cmd == 'hm-active':
        hm_active()
    elif cmd == 'providers':
        providers()
    elif cmd == 'lb-cycle':
        lb_cycle()
    elif cmd == 'teardown':
        teardown()
    else:
        die('usage: orphera-octavia-api register | registered | unregister | catalog | image-present | image-upload FILE | plumbing | plumbing-ok | wait-hm | hm-active | providers | lb-cycle | teardown')
"""


  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@OCTAVIA@@", s"http://$fqdn:$apiPort")
      .replace("@@NODE@@", node)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@OCTAVIAPW@@", octaviaPassword.reveal)
      .replace("@@CAFILE@@", caCert)
      .replace("@@STATE@@", stateDir)
      .replace("@@FLAVORID@@", amphoraFlavorId)

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 36); do
       |  ( $healthy ) && exit 0
       |  sleep 5
       |done
       |echo "octavia-api does not answer on $apiPort - see 'journalctl -xeu orphera-octavia-api' and $logDir" >&2
       |exit 1""".stripMargin

  // The amphora provider appears once the API is up and the database is in place.
  private val providersListed = s"$helperPath providers >/dev/null 2>&1"
  private val waitProviders =
    s"""for i in $$(seq 1 36); do $helperPath providers && exit 0; sleep 5; done
       |echo "the amphora provider is not listed - see 'journalctl -xeu orphera-octavia-api'" >&2
       |exit 1""".stripMargin

  private val waitHmActive =
    s"""for i in $$(seq 1 60); do $helperPath hm-active && exit 0; sleep 3; done
       |echo "health manager port is not ACTIVE after 180 s (check orphera-neutron-openvswitch-agent and ovs-vsctl show on this node)" >&2
       |exit 1""".stripMargin
  private val hmActive = s"$helperPath hm-active >/dev/null 2>&1"

  // The load balancer test is made again when this changes: the configuration template, the plug-in script, the
  // image address, and the server CA (read when the guard runs).
  private val cycleStamp = Resource.sha256(octaviaConfTemplate + hmStamp + amphoraImageUrl)
  private val cycleStampFile = s"$stateDir/cycle.stamp"
  private val cycleValue =
    s"""$cycleStamp-$$(sha256sum $certDir/server_ca.cert.pem 2>/dev/null | cut -c1-16)"""
  private val cycleCurrent =
    s"""[ "$$(cat $cycleStampFile 2>/dev/null)" = "$cycleValue" ]"""
  private val cycleScript =
    s"""set -e
       |$heartbeat
       |heartbeat $helperPath lb-cycle
       |echo "$cycleValue" > $cycleStampFile""".stripMargin

  // One service: its resources (written before it starts), the ones that decide when it restarts.
  private final case class Svc(
      unit: ApplyResource,
      resources: List[ApplyResource],
      deps: List[ApplyResource]
  )

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val opensslPkg = tools(1)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Octavia images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)
    val commandsOk = command(
      "check that the commands are in the images",
      imageCheckScript,
      unless = imageCheckOk,
      timeout = 300
    ).after(pulled)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, opensslPkg)

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)
    val dataDirectory = directory(dataDir).after(stateDirectory)
    val tmpDirectory = directory(s"$dataDir/tmp").after(dataDirectory)
    val srcDirectory = directory(srcDir, mode = "0755")

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and octavia passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Octavia in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val fetchedImage = command(
      "download the amphora image",
      fetchImageScript,
      unless = s"$imagePresent || $imageFileOk",
      timeout = 1800
    ).after(stateDirectory, tools(2))
    val uploadedImage = command(
      "upload the amphora image to Glance",
      uploadImageScript,
      unless = imagePresent,
      timeout = 1800
    ).after(fetchedImage, helperFile, ca, registered)

    val plumbing = command(
      "make the amphora network, security groups, flavor and the health manager port",
      s"$helperPath plumbing",
      unless = s"$helperPath plumbing-ok >/dev/null 2>&1",
      timeout = 180
    ).after(helperFile, ca, stateDirectory, registered)

    val hmScriptFile = file(
      "/usr/local/sbin/orphera-octavia-hm-port",
      hmPortScript,
      mode = "0755",
      owner = "root",
      group = "root"
    )
    val hmUnitFile = file(
      s"/etc/systemd/system/$hmUnit",
      hmUnitText,
      mode = "0644",
      owner = "root",
      group = "root"
    )
    val plugged = command(
      "plug the health manager port into br-int",
      hmPlugScript,
      unless = hmPlugged,
      timeout = 120
    ).after(plumbing, hmScriptFile, hmUnitFile, tools(5), tools(6))
    val hmReady = command(
      "wait for the health manager port to be ACTIVE",
      waitHmActive,
      unless = hmActive,
      timeout = 200
    ).after(plugged)

    val certs = command(
      "make the certificates",
      certsScript,
      unless = certsCurrent,
      timeout = 180
    ).after(srcDirectory, opensslPkg)

    val confTemplate = secretFile(
      s"$srcDir/octavia.conf.in",
      Secret(octaviaConfTemplate), // it holds the database, message bus, service and heartbeat secrets
      mode = "0600",
      owner = "root",
      group = "root"
    ).after(srcDirectory)
    val rendered = command(
      "fill in octavia.conf",
      renderConfScript,
      unless = renderCurrent
    ).after(confTemplate, plumbing)

    // One config directory per container, mounted by Kolla at /var/lib/kolla/config_files.
    val svcs = octaviaServices.map { c =>
      val name = c.name.value
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = command(
        s"copy octavia.conf to the $name config",
        s"install -m 0600 $srcDir/octavia.conf $dir/octavia.conf",
        unless = s"cmp -s $srcDir/octavia.conf $dir/octavia.conf"
      ).after(rendered, dirRes)
      val kollaCa = command(
        s"copy Keystone's CA to the $name config",
        s"cp $caCert $dir/ca.crt",
        unless = s"cmp -s $caCert $dir/ca.crt"
      ).after(ca, dirRes)
      val certCopies = command(
        s"copy the certificates to the $name config",
        certsCopyScript(dir),
        unless = certsCopyCurrent(dir)
      ).after(certs, dirRes)
      val kollaJson = file(
        s"$dir/config.json",
        configJson(name),
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(dirRes)
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      Svc(
        unitService,
        List(dirRes, conf, kollaCa, certCopies, kollaJson, unitFile),
        List(conf, kollaCa, certCopies, kollaJson, unitFile)
      )
    }

    val dbSync = command(
      "octavia-db-manage upgrade head",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(commandsOk, rendered, logDirectory, stateDirectory)

    val running = svcs.map { s =>
      s.unit.after(
        (dbSync :: registered :: hmReady :: pulled :: commandsOk :: logDirectory :: dataDirectory :: tmpDirectory :: s.deps)*
      )
    }

    // A container reads its configuration and CA at start. So each unit is restarted when the hash of its config
    // directory and unit file differs from the one noted at its last restart here. The stamp is written after the
    // restart, so a change that came while the unit was skipped (an earlier step failed) is still picked up by the
    // next run. The first run restarts each unit once.
    val restarts = octaviaServices.zip(running).map { case (c, r) =>
      val dir = s"$confRoot/${c.name.value}"
      val stampFile = s"$stateDir/${c.name.value}.restart.stamp"
      val hash = s"cat $dir/* ${c.unitPath} 2>/dev/null | sha256sum | cut -c1-32"
      command(
        s"restart ${c.unitName} when its configuration or CA changed",
        s"""set -e
           |systemctl restart ${c.unitName}
           |$hash > $stampFile""".stripMargin,
        unless = s"""[ "$$($hash)" = "$$(cat $stampFile 2>/dev/null)" ]""",
        timeout = 180
      ).after(r)
    }

    val answering = command(
      "octavia-api answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 240
    ).after((running ++ restarts)*)
    val providerListed = command(
      "the amphora provider is listed",
      waitProviders,
      unless = providersListed,
      timeout = 240
    ).after((answering :: (running ++ restarts))*)
    val cycle = command(
      "make a load balancer, send an HTTP request through its amphora, delete it",
      cycleScript,
      unless = cycleCurrent,
      timeout = 1800
    ).after(providerListed, uploadedImage, stateDirectory)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      commandsOk,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory,
      dataDirectory,
      tmpDirectory,
      srcDirectory,
      helperFile,
      registered,
      fetchedImage,
      uploadedImage,
      plumbing,
      hmScriptFile,
      hmUnitFile,
      plugged,
      hmReady,
      certs,
      confTemplate,
      rendered
    ) ++ svcs.flatMap(_.resources) ++ List(dbSync) ++ running ++ restarts ++
      List(answering, providerListed, cycle)
