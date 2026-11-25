// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// The Ceilometer compute agent on the compute nodes tst17 and tst18, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). It was a stage of telemetry_single_node.scala when that was a cluster
// playbook. Read that file's header first: it explains how the data flows and the order of the descriptions.
//
//   orphera apply manifests/telemetry_compute.scala --check
//   orphera apply manifests/telemetry_compute.scala
//
// On each node: podman, the cache mirror, the image, Keystone's CA, the configuration (the node's own host
// name is in it), the polling file, the Kolla config and the unit `orphera-ceilometer-compute`. The agent
// reads libvirt through the host's socket (nova_compute.scala opens it to all users) and runs as root in the
// container, as the nova-compute container does. Its exporter answers on 9101; Prometheus scrapes it
// (telemetry_prometheus.scala).
//
// Prerequisites, not checked here: nova_compute.scala has run on both nodes; telemetry_rabbitmq.scala has run
// (the `ceilometer` vhost and user); the ceilometer-compute image is available (tst21 or quay.io).
object telemetry_compute extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst17", "tst18")

  private val ksFqdn = "tst6.ljalbinson.com"
  private val exporterPort = 9101
  private val rabbitIp = "10.10.5.22"
  private val ceilometerRabbitPassword = Secrets.secret("ceilometer/rabbit")
  private val ceilometerPassword = Secrets.secret("ceilometer/service")

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val confRoot = "/etc/kolla"
  private val caDir = "/etc/kolla/telemetry-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ceilometerLogDir = "/var/log/kolla/ceilometer"
  private val stateDir = "/var/lib/orphera/telemetry"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val dir = s"$confRoot/ceilometer-compute"

  private val computeContainer = Container(
    name = ContainerName("ceilometer-compute"),
    image =
      Image("ceilometer-compute", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(dir, "/var/lib/kolla/config_files"),
      Mount(ceilometerLogDir, "/var/log/kolla/ceilometer", Access.ReadWrite),
      Mount(s"$stateDir/ceilometer", "/var/lib/ceilometer", Access.ReadWrite),
      Mount("/run/libvirt", "/run/libvirt", Access.Unlabelled)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    user = Some("root"),
    after = List("libvirtd.service"),
    wants = List("libvirtd.service")
  )

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  private val image = computeContainer.image.ref

  private def credentials(user: String, password: String, ca: String): String =
    s"""auth_type = password
       |auth_url = $ksUrl
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = $user
       |password = $password
       |region_name = RegionOne
       |interface = public
       |cafile = $ca""".stripMargin

  // Keep in step with telemetry_single_node.scala (the same file, with the node's own host name).
  private def ceilometerConf(host: String): String =
    s"""# Managed by Orphera (telemetry_compute.scala)
       |[DEFAULT]
       |transport_url = rabbit://ceilometer:${ceilometerRabbitPassword.reveal}@$rabbitIp:5672/ceilometer
       |log_dir = /var/log/kolla/ceilometer
       |use_stderr = true
       |host = $host
       |
       |[service_credentials]
       |${credentials(
        "ceilometer",
        ceilometerPassword.reveal,
        "/etc/ceilometer/ssl/ca.crt"
      )}
       |
       |[polling]
       |enable_prometheus_exporter = true
       |prometheus_listen_addresses = 0.0.0.0:$exporterPort
       |enable_notifications = false
       |batch_size = 500
       |
       |[compute]
       |instance_discovery_method = libvirt_metadata
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/ceilometer/tmp
       |""".stripMargin

  private val computePolling: String =
    """---
      |sources:
      |  - name: compute_pollsters
      |    interval: 60
      |    meters:
      |      - cpu
      |      - vcpus
      |      - memory.usage
      |      - network.incoming.bytes
      |      - network.outgoing.bytes
      |      - disk.device.read.bytes
      |      - disk.device.write.bytes
      |""".stripMargin

  private val configJson: String =
    """{"command": "ceilometer-polling --polling-namespaces compute --config-file /etc/ceilometer/ceilometer.conf", "config_files": [{"source": "/var/lib/kolla/config_files/ceilometer.conf", "dest": "/etc/ceilometer/ceilometer.conf", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/polling.yaml", "dest": "/etc/ceilometer/polling.yaml", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/ceilometer/ssl/ca.crt", "owner": "ceilometer", "perm": "0644"}], "permissions": [{"path": "/var/log/kolla/ceilometer", "owner": "ceilometer:kolla", "recurse": true}, {"path": "/var/lib/ceilometer", "owner": "ceilometer:ceilometer", "recurse": true}]}
      |""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = pullImages(
      "pull the Ceilometer compute image",
      List(image),
      hint = "the Image(...) values in telemetry_compute.scala",
      timeout = 2400
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = serverCa(
      "fetch Keystone's CA from its TLS handshake",
      ksFqdn,
      5000,
      caCert,
      hint = "apply keystone_single_node.scala (it must serve server-chain.crt)"
    ).after(caDirectory, tools(1))

    val unit = service(computeContainer.unitName)
    val logDirectory = directory(ceilometerLogDir)
    val stateDirectory = directory(stateDir)
    val ceilometerState =
      directory(s"$stateDir/ceilometer").after(stateDirectory)
    val ceilometerTmp =
      directory(s"$stateDir/ceilometer/tmp").after(ceilometerState)
    val dirRes = directory(dir, mode = "0755")
    val conf = secretFile(
      s"$dir/ceilometer.conf",
      Secret(
        ceilometerConf(target.name)
      ), // it holds the message bus and service passwords
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(dirRes).notifies(unit)
    val polling = file(
      s"$dir/polling.yaml",
      computePolling,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(dirRes).notifies(unit)
    val kollaCa = fileCopy(s"$dir/ca.crt", caCert, mode = "0644")
      .after(ca, dirRes)
      .notifies(unit)
    val json = file(
      s"$dir/config.json",
      configJson,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(dirRes).notifies(unit)
    val unitFile = file(
      computeContainer.unitPath,
      computeContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unit)

    val running = unit.after(
      pulled,
      logDirectory,
      ceilometerTmp,
      dirRes,
      conf,
      polling,
      kollaCa,
      json,
      unitFile
    )
    // The polling agent's exporter answers once the first poll cycle has started.
    val answering = waitForHttp(
      "the exporter answers on its port",
      s"http://localhost:$exporterPort/metrics",
      timeout = 180,
      hint = s"see 'journalctl -xeu orphera-ceilometer-compute' and $ceilometerLogDir"
    ).after(running)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory,
      ceilometerState,
      ceilometerTmp,
      dirRes,
      conf,
      polling,
      kollaCa,
      json,
      unitFile,
      running,
      answering
    )
