// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.kolla

import orphera.orchestrator.Task

/** Checks for Container, with no test library (none is a dependency of this
  * project yet). Run:
  *
  * sbt "orchestrator/Test/runMain orphera.orchestrator.kolla.ContainerCheck"
  *
  * The expected text is the placement unit as placement_single_node.scala wrote
  * it before it used Container, so a pass means the refactor changed nothing on
  * the node.
  */
object ContainerCheck:
  private var failures = 0
  private var passes = 0
  // PASS lines only with CHECK_VERBOSE=1, so `make release` output stays short.
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val placement = Container(
    name = ContainerName("placement-api"),
    image = Image("placement-api", Release.R2025_1, Base.UbuntuNoble),
    mounts = List(
      Mount("/etc/kolla/placement-api", "/var/lib/kolla/config_files"),
      Mount(
        "/var/log/kolla/placement",
        "/var/log/kolla/placement",
        Access.ReadWrite
      )
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )

  private val expectedUnit =
    """[Unit]
      |Description=placement-api (Kolla container) managed by Orphera
      |After=network-online.target
      |Wants=network-online.target
      |
      |[Service]
      |ExecStartPre=-/usr/bin/podman rm -f placement-api
      |ExecStart=/usr/bin/podman run --rm --name placement-api --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla/placement-api:/var/lib/kolla/config_files:ro,z -v /var/log/kolla/placement:/var/log/kolla/placement:z quay.io/openstack.kolla/placement-api:2025.1-ubuntu-noble
      |ExecStop=/usr/bin/podman stop -t 30 placement-api
      |Restart=on-failure
      |RestartSec=5
      |TimeoutStartSec=300
      |
      |[Install]
      |WantedBy=multi-user.target
      |""".stripMargin

  def main(args: Array[String]): Unit =
    check(
      "the placement unit is byte for byte what the manifest wrote",
      placement.render == expectedUnit,
      s"\n--- got ---\n${placement.render}"
    )
    check(
      "image reference",
      placement.image.ref == "quay.io/openstack.kolla/placement-api:2025.1-ubuntu-noble",
      placement.image.ref
    )
    check(
      "unit name and path",
      placement.unitName == "orphera-placement-api.service" && placement.unitPath == "/etc/systemd/system/orphera-placement-api.service"
    )
    check(
      "mount flags",
      Mount("/a", "/b").flag == "-v /a:/b:ro,z" && Mount(
        "/a",
        "/b",
        Access.ReadWrite
      ).flag == "-v /a:/b:z"
    )
    check(
      "env comes before mounts, the image last",
      placement.runArguments.startsWith(
        "-e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla"
      ) && placement.runArguments.endsWith("2025.1-ubuntu-noble")
    )
    check(
      "a different release or base changes the image",
      Image(
        "nova-api",
        Release.R2025_1,
        Base.UbuntuNoble,
        "cache.local:5000"
      ).ref == "cache.local:5000/nova-api:2025.1-ubuntu-noble"
    )

    placement.writeUnit match
      case Task.WriteFile(content, dest, owner, group, mode) =>
        check(
          "writeUnit writes the rendered unit as root, mode 0644",
          content == expectedUnit && dest == placement.unitPath && owner == "root" && group == "root" && mode == 420
        )
      case other => check("writeUnit is a WriteFile", false, other.toString)

    placement.start match
      case Task.RunCommand(List("sh", "-c", script), timeout) =>
        check(
          "start has an explicit timeout above its settle time",
          timeout == 300
        )
        check(
          "start reloads, enables and restarts the unit",
          script.contains("systemctl daemon-reload") && script.contains(
            "systemctl enable orphera-placement-api"
          ) && script.contains("systemctl restart orphera-placement-api")
        )
        check(
          "a failed start says which journal to read and where the logs are",
          script.contains(
            "journalctl -xeu orphera-placement-api' and /var/log/kolla/placement"
          ),
          script
        )
        check(
          "start prints the service name on success",
          script.endsWith("echo \"placement-api running\""),
          script.takeRight(60)
        )
      case other =>
        check("start is a RunCommand through sh -c", false, other.toString)

    check(
      "a name with a space or capital is rejected",
      scala.util.Try(ContainerName("Bad Name")).isFailure && ContainerName
        .parse("ok-name-1")
        .isRight && ContainerName.parse("-x").isLeft
    )
    check(
      "a container without a read-write mount gives no log hint",
      scala.util.Try(placement.copy(mounts = Nil).start).toOption.exists {
        case Task.RunCommand(List(_, _, s), _) => !s.contains(" and /");
        case _                                 => false
      }
    )

    // glance_single_node.scala: three mounts, the last two read-write. The expected text is the unit
    // the manifest wrote before it used Container.
    val glance = Container(
      name = ContainerName("glance-api"),
      image = Image("glance-api", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/glance-api", "/var/lib/kolla/config_files"),
        Mount(
          "/var/log/kolla/glance",
          "/var/log/kolla/glance",
          Access.ReadWrite
        ),
        Mount("/var/lib/orphera/glance", "/var/lib/glance", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
    val glanceExecStart =
      "ExecStart=/usr/bin/podman run --rm --name glance-api --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla/glance-api:/var/lib/kolla/config_files:ro,z -v /var/log/kolla/glance:/var/log/kolla/glance:z -v /var/lib/orphera/glance:/var/lib/glance:z quay.io/openstack.kolla/glance-api:2025.1-ubuntu-noble"
    check(
      "the glance unit has the three mounts in order and the image last",
      glance.render.linesIterator.contains(glanceExecStart),
      glance.render
    )
    check("glance unit name", glance.unitName == "orphera-glance-api.service")

    // nova_single_node.scala: four containers that differ only in name and config directory.
    def nova(service: String) = Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount(s"/etc/kolla/$service", "/var/lib/kolla/config_files"),
        Mount("/var/log/kolla/nova", "/var/log/kolla/nova", Access.ReadWrite),
        Mount("/var/lib/orphera/nova", "/var/lib/nova", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
    val novaConductorExecStart =
      "ExecStart=/usr/bin/podman run --rm --name nova-conductor --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla/nova-conductor:/var/lib/kolla/config_files:ro,z -v /var/log/kolla/nova:/var/log/kolla/nova:z -v /var/lib/orphera/nova:/var/lib/nova:z quay.io/openstack.kolla/nova-conductor:2025.1-ubuntu-noble"
    check(
      "the nova-conductor unit has its own config directory and the shared log and state mounts",
      nova("nova-conductor").render.linesIterator
        .contains(novaConductorExecStart),
      nova("nova-conductor").render
    )
    check(
      "the four nova units have distinct names",
      List("nova-api", "nova-scheduler", "nova-conductor", "nova-novncproxy")
        .map(nova(_).unitName)
        .distinct
        .size == 4
    )

    // neutron_single_node.scala: the agents are privileged, share the host's process table and
    // mount the OVS socket directory, /run/netns (shared both ways) and the kernel modules.
    val netnsPre =
      "/bin/sh -c 'mkdir -p /run/netns && (mountpoint -q /run/netns || mount --bind /run/netns /run/netns) && mount --make-rshared /run/netns'"
    val ovsAgent = Container(
      name = ContainerName("neutron-openvswitch-agent"),
      image =
        Image("neutron-openvswitch-agent", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount(
          "/etc/kolla/neutron-openvswitch-agent",
          "/var/lib/kolla/config_files"
        ),
        Mount(
          "/var/log/kolla/neutron",
          "/var/log/kolla/neutron",
          Access.ReadWrite
        ),
        Mount("/var/lib/orphera/neutron", "/var/lib/neutron", Access.ReadWrite),
        Mount("/run/openvswitch", "/run/openvswitch", Access.Unlabelled),
        Mount("/run/netns", "/run/netns", Access.Shared),
        Mount("/lib/modules", "/lib/modules", Access.ReadOnlyUnlabelled)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      privileged = true,
      hostPid = true,
      after = List("openvswitch-switch.service"),
      preStart = List(netnsPre),
      settleSeconds = 12
    )
    val ovsUnit = ovsAgent.render
    check(
      "the agent unit waits for Open vSwitch as well as the network",
      ovsUnit.linesIterator.contains(
        "After=network-online.target openvswitch-switch.service"
      ),
      ovsUnit
    )
    check(
      "the namespace setup runs before the old container is removed",
      ovsUnit.indexOf("ExecStartPre=" + netnsPre) >= 0 && ovsUnit.indexOf(
        "ExecStartPre=" + netnsPre
      ) < ovsUnit.indexOf("ExecStartPre=-/usr/bin/podman rm -f"),
      ovsUnit
    )
    check(
      "the agent runs privileged in the host's process table",
      ovsUnit.contains(
        "--network host --privileged --pid host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS"
      ),
      ovsUnit
    )
    check(
      "the agent mounts have the right flags",
      ovsUnit.contains(
        "-v /run/openvswitch:/run/openvswitch -v /run/netns:/run/netns:rshared -v /lib/modules:/lib/modules:ro quay.io/openstack.kolla/neutron-openvswitch-agent:2025.1-ubuntu-noble"
      ),
      ovsUnit
    )
    check(
      "a plain container has no privilege flags and no extra pre-start line",
      !placement.render.contains("--privileged") && !placement.render.contains(
        "--pid"
      ) && placement.render.linesIterator.count(
        _.startsWith("ExecStartPre=")
      ) == 1
    )

    val asRoot = placement.copy(user = Some("root"))
    check(
      "a container with a user is started with --user, and one without is not",
      asRoot.render.contains("--network host --user root -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS") &&
        !placement.render.contains("--user")
    )

    // keystone_single_node.scala: one container whose state is two key directories.
    val keystone = Container(
      name = ContainerName("keystone"),
      image = Image("keystone", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/keystone", "/var/lib/kolla/config_files"),
        Mount(
          "/var/lib/orphera/keystone/fernet-keys",
          "/etc/keystone/fernet-keys",
          Access.ReadWrite
        ),
        Mount(
          "/var/lib/orphera/keystone/credential-keys",
          "/etc/keystone/credential-keys",
          Access.ReadWrite
        ),
        Mount(
          "/var/log/kolla/keystone",
          "/var/log/kolla/keystone",
          Access.ReadWrite
        )
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      settleSeconds = 5
    )
    check(
      "the keystone unit mounts the config read-only and both key directories read-write",
      keystone.render.linesIterator.contains(
        "ExecStart=/usr/bin/podman run --rm --name keystone --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla/keystone:/var/lib/kolla/config_files:ro,z -v /var/lib/orphera/keystone/fernet-keys:/etc/keystone/fernet-keys:z -v /var/lib/orphera/keystone/credential-keys:/etc/keystone/credential-keys:z -v /var/log/kolla/keystone:/var/log/kolla/keystone:z quay.io/openstack.kolla/keystone:2025.1-ubuntu-noble"
      ),
      keystone.render
    )
    keystone.start match
      case Task.RunCommand(List(_, _, script), _) =>
        check(
          "a failed start prints the last journal lines before the hint",
          script.contains(
            "journalctl -u orphera-keystone -n 40 --no-pager >&2"
          ) && script.contains("sleep 5"),
          script
        )
      case other =>
        check("keystone start is a RunCommand", false, other.toString)

    // cinder_single_node.scala: the volume service adds three capabilities; RabbitMQ is not a Kolla image.
    val cinderVolume = Container(
      name = ContainerName("cinder-volume"),
      image = Image("cinder-volume", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/cinder-volume", "/var/lib/kolla/config_files"),
        Mount(
          "/var/log/kolla/cinder",
          "/var/log/kolla/cinder",
          Access.ReadWrite
        ),
        Mount("/var/lib/orphera/cinder", "/var/lib/cinder", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      capabilities = List("SYS_ADMIN", "NET_ADMIN", "DAC_READ_SEARCH"),
      after = List("orphera-rabbitmq.service")
    )
    check(
      "the volume service adds its three capabilities before the environment",
      cinderVolume.render.contains(
        "--network host --cap-add=SYS_ADMIN --cap-add=NET_ADMIN --cap-add=DAC_READ_SEARCH -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS"
      ),
      cinderVolume.render
    )
    check(
      "the cinder units start after RabbitMQ",
      cinderVolume.render.linesIterator.contains(
        "After=network-online.target orphera-rabbitmq.service"
      )
    )
    val rabbit = Container(
      name = ContainerName("rabbitmq"),
      image = ExternalImage("docker.io/library/rabbitmq:3.13"),
      mounts = List(
        Mount(
          "/var/lib/orphera/rabbitmq",
          "/var/lib/rabbitmq",
          Access.ReadWrite
        )
      ),
      env = List(
        "RABBITMQ_DEFAULT_USER" -> "openstack",
        "RABBITMQ_DEFAULT_PASS" -> "pw"
      ),
      description = Some("RabbitMQ for OpenStack managed by Orphera")
    )
    check(
      "rabbitmq runs a plain image with its own description",
      rabbit.render.contains(
        "Description=RabbitMQ for OpenStack managed by Orphera"
      ) && rabbit.render.contains(
        "-e RABBITMQ_DEFAULT_USER=openstack -e RABBITMQ_DEFAULT_PASS=pw -v /var/lib/orphera/rabbitmq:/var/lib/rabbitmq:z docker.io/library/rabbitmq:3.13"
      ),
      rabbit.render
    )
    check(
      "a container with no capabilities has no --cap-add",
      !placement.render.contains("--cap-add")
    )

    // horizon_single_node.scala: its config directory is horizon-web, not horizon.
    val horizon = Container(
      name = ContainerName("horizon"),
      image = Image("horizon", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/horizon-web", "/var/lib/kolla/config_files"),
        Mount(
          "/var/log/kolla/horizon",
          "/var/log/kolla/horizon",
          Access.ReadWrite
        )
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      settleSeconds = 10
    )
    check(
      "the horizon unit mounts horizon-web as its config",
      horizon.render.contains(
        "-v /etc/kolla/horizon-web:/var/lib/kolla/config_files:ro,z -v /var/log/kolla/horizon:/var/log/kolla/horizon:z quay.io/openstack.kolla/horizon:2025.1-ubuntu-noble"
      ),
      horizon.render
    )

    // kolla_cache.scala: a plain registry that publishes a port instead of using the host network.
    val cache = Container(
      name = ContainerName("kolla-cache"),
      image = ExternalImage("docker.io/library/registry:2"),
      mounts = List(
        Mount(
          "/var/lib/orphera/kolla-cache",
          "/var/lib/registry",
          Access.ReadWrite
        )
      ),
      env = List(
        "REGISTRY_PROXY_REMOTEURL" -> "https://quay.io",
        "REGISTRY_PROXY_TTL" -> "8760h"
      ),
      network = Network.Published,
      ports = List(Port(5000, 5000)),
      description = Some(
        "Kolla image pull-through cache (registry proxying quay.io) managed by Orphera"
      )
    )
    check(
      "the cache unit publishes its port and does not use the host network",
      cache.render.linesIterator.contains(
        "ExecStart=/usr/bin/podman run --rm --name kolla-cache -p 5000:5000 -e REGISTRY_PROXY_REMOTEURL=https://quay.io -e REGISTRY_PROXY_TTL=8760h -v /var/lib/orphera/kolla-cache:/var/lib/registry:z docker.io/library/registry:2"
      ),
      cache.render
    )
    check(
      "published ports without Network.Published are rejected",
      scala.util.Try(placement.copy(ports = List(Port(1, 2)))).isFailure
    )
    check(
      "Network.Published without a port is rejected",
      scala.util.Try(placement.copy(network = Network.Published)).isFailure
    )

    // nova_compute.scala: privileged, wants libvirtd, the host's /dev and the libvirt and OVS sockets.
    val compute = Container(
      name = ContainerName("nova-compute"),
      image = Image("nova-compute", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/nova-compute", "/var/lib/kolla/config_files"),
        Mount("/var/log/kolla/nova", "/var/log/kolla/nova", Access.ReadWrite),
        Mount("/var/lib/nova", "/var/lib/nova", Access.Unlabelled),
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
    val computeUnit = compute.render
    check(
      "the compute unit starts after and wants libvirtd",
      computeUnit.linesIterator.contains(
        "After=network-online.target openvswitch-switch.service libvirtd.service"
      ) && computeUnit.linesIterator.contains(
        "Wants=network-online.target libvirtd.service"
      ),
      computeUnit
    )
    check(
      "the compute unit stops with a 60 second grace",
      computeUnit.linesIterator.contains(
        "ExecStop=/usr/bin/podman stop -t 60 nova-compute"
      ),
      computeUnit
    )
    check(
      "the compute mounts keep their flags, /dev included",
      computeUnit.contains(
        "-v /var/lib/nova:/var/lib/nova -v /run/libvirt:/run/libvirt -v /run/openvswitch:/run/openvswitch -v /lib/modules:/lib/modules:ro -v /dev:/dev quay.io/openstack.kolla/nova-compute:2025.1-ubuntu-noble"
      ),
      computeUnit
    )
    {
      import orphera.orchestrator.{Drift, Resource, Task}
      // Not `import Resource.*`: its `check` extension would clash with this object's own `check`.
      val r = summon[Resource[Container]]
      val written = r.write(placement)
      check(
        "Container is a Resource: write is its writeUnit",
        written == placement.writeUnit
      )
      val checked = r.check(placement) match
        case Task.RunCommand(List("sh", "-c", script), _) => script
        case other                                        => other.toString
      check(
        "the check command diffs the unit path against the rendered unit",
        checked.contains(
          s"diff -u --label \"node: ${placement.unitPath}\""
        ) && checked.contains(placement.render)
      )
      check(
        "the check does not write or restart anything",
        !checked.contains("systemctl") && !checked.contains(
          s"> ${placement.unitPath}"
        ) && !checked.contains(s"tee ${placement.unitPath}")
      )
      check(
        "the check says MISSING with exit 2 and DRIFT with exit 1",
        checked.contains("exit 2") && checked.contains("exit 1")
      )
      check(
        "Drift: same text is in sync, no file is missing",
        Drift.compare("a\nb\n", Some("a\nb\n")) == Drift.InSync && Drift
          .compare("a", None) == Drift.Missing
      )
      check(
        "Drift: names the first differing line",
        Drift.compare("a\nb\nc\n", Some("a\nX\nc\n")) == Drift.Differs(
          "line 2: expected `b`, found `X`"
        )
      )
      check(
        "a resource containing the here-document marker is rejected",
        scala.util
          .Try(Resource.checkTask("x", "/p", "ORPHERA_EXPECTED_EOF"))
          .isFailure
      )
    }
    check(
      "ports outside 1 to 65535 are rejected",
      List(0, 65536, -1).forall(n =>
        scala.util.Try(TcpPort(n)).isFailure
      ) && scala.util.Try(Port(70000, 1)).isFailure && TcpPort
        .parse(65535)
        .isRight
    )
    check(
      "Port renders from checked numbers",
      Port(8080, 80).flag == "-p 8080:80" && Port(
        TcpPort(8080),
        TcpPort(80)
      ).flag == "-p 8080:80"
    )
    {
      // Run the generated function against a fake podman that fails a set number of times.
      import java.nio.file.{Files, Path}
      import scala.sys.process.*
      val dir: Path = Files.createTempDirectory("pullcheck")
      val podman = dir.resolve("podman")
      Files.writeString(
        podman,
        "#!/bin/sh\nc=$(cat \"$COUNT\" 2>/dev/null || echo 0); c=$((c+1)); echo $c > \"$COUNT\"\n[ \"$c\" -ge \"$FAIL_UNTIL\" ]\n"
      )
      podman.toFile.setExecutable(true)
      def run(failUntil: Int): (Int, String, Int) =
        val count = dir.resolve(s"count-$failUntil").toString
        val out = new StringBuilder
        val script = Pull.shellFunction(
          "the Image in x.scala",
          0
        ) + "\npull_retry --tls-verify=false img || exit 1\n"
        val code = Process(
          List("sh", "-c", script),
          None,
          "PATH" -> s"${dir.toString}:${sys.env.getOrElse("PATH", "/usr/bin:/bin")}",
          "COUNT" -> count,
          "FAIL_UNTIL" -> failUntil.toString
        ) ! ProcessLogger(
          l => out.append(l).append('\n'),
          l => out.append(l).append('\n')
        )
        val calls = scala.util
          .Try(Files.readString(dir.resolve(s"count-$failUntil")).trim.toInt)
          .getOrElse(0)
        (code, out.toString, calls)
      val (okCode, _, okCalls) = run(1)
      check(
        "a pull that works first time is tried once",
        okCode == 0 && okCalls == 1
      )
      val (retryCode, _, retryCalls) = run(3)
      check(
        "a pull that fails twice is tried a third time and succeeds",
        retryCode == 0 && retryCalls == 3
      )
      val (failCode, failOut, failCalls) = run(99)
      check(
        "a pull that always fails gives up after three tries with a message",
        failCode == 1 && failCalls == 3 && failOut.contains(
          "could not pull --tls-verify=false img after 3 attempts"
        ) && failOut.contains("the Image in x.scala"),
        failOut
      )
      check(
        "a hint containing a quote, backtick or dollar is rejected",
        List("a\"b", "a`b", "a$b").forall(h =>
          scala.util.Try(Pull.shellFunction(h)).isFailure
        )
      )
    }
    check(
      "a container with no wants has only network-online.target",
      placement.render.linesIterator.contains("Wants=network-online.target")
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"ContainerCheck: all $passes checks passed")
