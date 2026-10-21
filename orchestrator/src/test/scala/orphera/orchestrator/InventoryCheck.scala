// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global

/** Checks for the inventory's `never-reboot` property: reading it from the
  * file, and the rule that a node is protected by its own setting or its
  * group's. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.InventoryCheck"`.
  */
object InventoryCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val yaml =
    """nodes:
      |  - name: a
      |    host: a.example
      |    never-reboot: true
      |  - name: b
      |    host: b.example
      |  - name: c
      |    host: c.example
      |    never-reboot: false
      |  - name: d
      |    host: d.example
      |groups:
      |  - name: hypervisors
      |    members: [d]
      |    never-reboot: true
      |  - name: others
      |    members: [b, c]
      |""".stripMargin

  def main(args: Array[String]): Unit =
    val file = Files.createTempFile("inventory-check", ".yaml")
    Files.writeString(file, yaml)
    val loaded = InventoryYaml.load(file.toString)
    Files.delete(file)

    check("the inventory loads", loaded.isRight, loaded.toString)
    val (nodes, groups) = loaded.getOrElse((Nil, Nil))
    def node(name: String): Node =
      nodes.find(_.name == name).getOrElse(Node(name, name))
    def protectedNode(name: String): Boolean =
      Inventory.neverReboot(node(name), groups)

    check("never-reboot: true on a node is read", node("a").neverReboot)
    check("a node without it is not protected", !node("b").neverReboot)
    check("never-reboot: false is read", !node("c").neverReboot)
    check("never-reboot: true on a group is read", groups.exists(g => g.name == "hypervisors" && g.neverReboot))
    check("a group without it is not protected", groups.exists(g => g.name == "others" && !g.neverReboot))
    check("a node's own setting protects it", protectedNode("a"))
    check("a node in a protected group is protected", protectedNode("d"))
    check("a node in an unprotected group is not protected", !protectedNode("b"))
    check("an explicit false does not protect", !protectedNode("c"))

    // shutdown must refuse a protected node before it opens any connection, and the
    // orchestrator must skip it and carry on (an empty remainder here, so nothing is contacted)
    val guarded = Node("guarded-host", "guarded-host.invalid", neverReboot = true)
    val refused = NodeClient.shutdown(guarded, 0, _ => IO.unit).attempt.unsafeRunSync()
    check(
      "shutdown refuses a never-reboot node without contacting it",
      refused.left.exists(e => e.isInstanceOf[IllegalStateException] && e.getMessage.contains("never-reboot")),
      refused.toString
    )
    val skipped = Orchestrator.shutdown(List(guarded)).attempt.unsafeRunSync()
    check("Orchestrator.shutdown skips a never-reboot node instead of failing", skipped.isRight, skipped.toString)

    // --exclude-nodes: a group with some members left out
    val grp = List(
      Group("mons", List("a", "b", "c")),
      Group("osds", List("c", "d"))
    )
    def expanded(args: String*) = Cli.expandNodeGroups(args.toList, grp)
    check(
      "a group with a member excluded",
      expanded("uptime", "--node-groups", "mons", "--exclude-nodes", "b") ==
        Right(List("uptime", "--nodes", "a,c"))
    )
    check(
      "an exclusion applies to the union of --nodes and groups",
      expanded("uptime", "--nodes", "z", "--node-groups", "mons,osds", "--exclude-nodes", "c,z") ==
        Right(List("uptime", "--nodes", "a,b,d"))
    )
    check(
      "--exclude-nodes with --nodes alone",
      expanded("uptime", "--nodes", "a,b", "--exclude-nodes", "a") ==
        Right(List("uptime", "--nodes", "b"))
    )
    check(
      "--exclude-nodes alone is refused, not read as everything except",
      expanded("shutdown", "--exclude-nodes", "a", "--yes").isLeft
    )
    check(
      "excluding a node that was not selected is refused",
      expanded("uptime", "--node-groups", "mons", "--exclude-nodes", "d").swap
        .exists(e => e.contains("d") && e.contains("not selected"))
    )
    check(
      "excluding every selected node is refused",
      expanded("uptime", "--node-groups", "osds", "--exclude-nodes", "c,d").isLeft
    )
    check(
      "args without the options pass through unchanged",
      expanded("uptime", "--nodes", "a") == Right(List("uptime", "--nodes", "a"))
    )
    check(
      "an unknown group is still refused with an exclusion present",
      expanded("uptime", "--node-groups", "nope", "--exclude-nodes", "a").swap.exists(_.contains("Unknown node group"))
    )
    check(
      "a pipeline step may not give --exclude-nodes",
      Cli.parse(List("pipeline", "uptime --exclude-nodes a", "--nodes", "a,b")).isLeft
    )

    val bad = Files.createTempFile("inventory-check", ".yaml")
    Files.writeString(bad, "nodes:\n  - name: x\n    host: x\n    never-reboot: sometimes\n")
    check("a value that is not true or false is refused", InventoryYaml.load(bad.toString).isLeft)
    Files.delete(bad)

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"InventoryCheck: all $passes checks passed")
