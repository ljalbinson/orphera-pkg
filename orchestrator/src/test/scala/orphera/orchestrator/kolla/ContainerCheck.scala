// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.kolla

import orphera.orchestrator.Task

/** Checks for Container, with no test library (none is a dependency of this
  * project yet). Run:
  *
  *   sbt "orchestrator/Test/runMain orphera.orchestrator.kolla.ContainerCheck"
  *
  * The expected text is the placement unit as placement_single_node.scala wrote
  * it before it used Container, so a pass means the refactor changed nothing on
  * the node.
  */
object ContainerCheck:
  private var failures = 0

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val placement = Container(
    name = "placement-api",
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
    check("the placement unit is byte for byte what the manifest wrote", placement.render == expectedUnit, s"\n--- got ---\n${placement.render}")
    check("image reference", placement.image.ref == "quay.io/openstack.kolla/placement-api:2025.1-ubuntu-noble", placement.image.ref)
    check("unit name and path", placement.unitName == "orphera-placement-api.service" && placement.unitPath == "/etc/systemd/system/orphera-placement-api.service")
    check("mount flags", Mount("/a", "/b").flag == "-v /a:/b:ro,z" && Mount("/a", "/b", Access.ReadWrite).flag == "-v /a:/b:z")
    check("env comes before mounts, the image last", placement.runArguments.startsWith("-e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla") && placement.runArguments.endsWith("2025.1-ubuntu-noble"))
    check("a different release or base changes the image", Image("nova-api", Release.R2025_1, Base.UbuntuNoble, "cache.local:5000").ref == "cache.local:5000/nova-api:2025.1-ubuntu-noble")

    placement.writeUnit match
      case Task.WriteFile(content, dest, owner, group, mode) =>
        check("writeUnit writes the rendered unit as root, mode 0644", content == expectedUnit && dest == placement.unitPath && owner == "root" && group == "root" && mode == 420)
      case other => check("writeUnit is a WriteFile", false, other.toString)

    placement.start match
      case Task.RunCommand(List("sh", "-c", script), timeout) =>
        check("start has an explicit timeout above its settle time", timeout == 300)
        check("start reloads, enables and restarts the unit", script.contains("systemctl daemon-reload") && script.contains("systemctl enable orphera-placement-api") && script.contains("systemctl restart orphera-placement-api"))
        check("a failed start says which journal to read and where the logs are", script.contains("journalctl -xeu orphera-placement-api' and /var/log/kolla/placement"), script)
        check("start prints the service name on success", script.endsWith("echo \"placement-api running\""), script.takeRight(60))
      case other => check("start is a RunCommand through sh -c", false, other.toString)

    check("a name with a space or capital is rejected", scala.util.Try(placement.copy(name = "Bad Name")).isFailure)
    check("a container without a read-write mount gives no log hint", scala.util.Try(placement.copy(mounts = Nil).start).toOption.exists { case Task.RunCommand(List(_, _, s), _) => !s.contains(" and /"); case _ => false })

    // glance_single_node.scala: three mounts, the last two read-write. The expected text is the unit
    // the manifest wrote before it used Container.
    val glance = Container(
      name = "glance-api",
      image = Image("glance-api", Release.R2025_1, Base.UbuntuNoble),
      mounts = List(
        Mount("/etc/kolla/glance-api", "/var/lib/kolla/config_files"),
        Mount("/var/log/kolla/glance", "/var/log/kolla/glance", Access.ReadWrite),
        Mount("/var/lib/orphera/glance", "/var/lib/glance", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
    val glanceExecStart =
      "ExecStart=/usr/bin/podman run --rm --name glance-api --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v /etc/kolla/glance-api:/var/lib/kolla/config_files:ro,z -v /var/log/kolla/glance:/var/log/kolla/glance:z -v /var/lib/orphera/glance:/var/lib/glance:z quay.io/openstack.kolla/glance-api:2025.1-ubuntu-noble"
    check("the glance unit has the three mounts in order and the image last", glance.render.linesIterator.contains(glanceExecStart), glance.render)
    check("glance unit name", glance.unitName == "orphera-glance-api.service")

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println("all checks passed")
