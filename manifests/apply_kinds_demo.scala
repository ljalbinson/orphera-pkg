// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Tries the step-2 resource kinds of declarative apply (docs/APPLY-DESIGN.md)
// on a node without touching anything it already runs: a package (tree), a
// group and a system user, a directory they own, and a oneshot systemd unit
// of its own that a change to its unit file restarts. Removal leaves the
// package, which the node may have had before.
//
//   orphera apply manifests/apply_kinds_demo.scala --check --nodes tst5
//   orphera apply manifests/apply_kinds_demo.scala --nodes tst5     # creates
//   orphera apply manifests/apply_kinds_demo.scala --nodes tst5     # changes nothing
//   DEMO_MESSAGE=again orphera apply manifests/apply_kinds_demo.scala --nodes tst5   # restarts the unit
//   DEMO_REMOVE=yes orphera apply manifests/apply_kinds_demo.scala --nodes tst5      # takes it all away
object apply_kinds_demo extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst5")

  private val unitPath = "/etc/systemd/system/orphera-demo.service"
  private val dir = "/var/lib/orphera-demo"

  private def unitFile(message: String): String =
    s"""# Managed by Orphera (apply_kinds_demo.scala).
       |[Unit]
       |Description=Orphera apply demo
       |
       |[Service]
       |Type=oneshot
       |RemainAfterExit=yes
       |User=orphera-demo
       |ExecStart=/bin/sh -c 'echo $message >> $dir/started'
       |
       |[Install]
       |WantedBy=multi-user.target
       |""".stripMargin

  def describe(node: Node): List[ApplyResource] =
    if sys.env.get("DEMO_REMOVE").contains("yes") then
      // Stop and disable it while its unit file is still there (a second removal finds nothing to do).
      val stop = command("stop orphera-demo", "systemctl disable --now orphera-demo.service", onlyIf = s"test -e $unitPath")
      val reload = command("reload systemd", "systemctl daemon-reload", onChangeOnly = true)
      val clear = command("remove the demo directory", s"rm -rf $dir", onlyIf = s"test -d $dir")
      val gone = absentUser("orphera-demo").after(stop, clear)
      List(
        stop,
        absentFile(unitPath).after(stop).notifies(reload),
        reload,
        clear,
        gone,
        absentGroup("orphera-demo").after(gone)
      )
    else
      val message = sys.env.getOrElse("DEMO_MESSAGE", "started")
      val unit = service("orphera-demo.service")
      List(
        pkg("tree"),
        group("orphera-demo", system = true),
        user("orphera-demo", group = "orphera-demo", shell = "/usr/sbin/nologin", system = true),
        directory(dir, mode = "0750", owner = "orphera-demo", group = "orphera-demo"),
        file(unitPath, unitFile(message), mode = "0644").notifies(unit),
        unit
      )
