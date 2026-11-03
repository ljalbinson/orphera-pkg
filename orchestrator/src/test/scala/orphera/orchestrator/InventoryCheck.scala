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
    def expanded(args: String*) =
      Cli.expandNodeGroups(args.toList, grp, List("a", "b", "c", "d"))
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
      "--exclude-nodes alone means all nodes but those, for a command that defaults to all",
      expanded("deploy-agent", "--agent", "x.deb", "--exclude-nodes", "b") ==
        Right(List("deploy-agent", "--agent", "x.deb", "--nodes", "a,c,d"))
    )
    check(
      "--exclude-nodes alone is refused for shutdown, reboot, teardown and bootstrap",
      List("shutdown", "reboot", "teardown", "bootstrap").forall(verb =>
        expanded(verb, "--exclude-nodes", "a", "--yes").isLeft
      )
    )
    check(
      "excluding a node that is not in the inventory is refused",
      expanded("uptime", "--exclude-nodes", "zzz").swap.exists(_.contains("not selected"))
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

    // orphera nodes: the same expansion, printed
    val all4 = List("a", "b", "c", "d")
    check("nodes with no selection is every node", ListNodes.select(Nil, grp, all4) == Right(all4))
    check(
      "nodes expands a group",
      ListNodes.select(List("--node-groups", "osds"), grp, all4) == Right(List("c", "d"))
    )
    check(
      "nodes with a group and an exclusion",
      ListNodes.select(List("--node-groups", "mons,osds", "--exclude-nodes", "a"), grp, all4) ==
        Right(List("b", "c", "d"))
    )
    check(
      "nodes --exclude-nodes alone is every node but those",
      ListNodes.select(List("--exclude-nodes", "a,b"), grp, all4) == Right(List("c", "d"))
    )
    check(
      "nodes refuses a node name that is not in the inventory",
      ListNodes.select(List("--nodes", "a,zz"), grp, all4).swap.exists(_.contains("zz"))
    )
    check(
      "nodes refuses an unknown group",
      ListNodes.select(List("--node-groups", "nope"), grp, all4).swap.exists(_.contains("Unknown node group"))
    )
    check("nodes refuses a stray argument", ListNodes.select(List("a"), grp, all4).isLeft)

    val bad = Files.createTempFile("inventory-check", ".yaml")
    Files.writeString(bad, "nodes:\n  - name: x\n    host: x\n    never-reboot: sometimes\n")
    check("a value that is not true or false is refused", InventoryYaml.load(bad.toString).isLeft)
    Files.delete(bad)

    // The agent package option: --agent, with --file still accepted
    def agentOf(args: String*): Option[String] = Cli.parse(args.toList) match
      case Right(Command.DeployAgent(f, _, _, _, _, _)) => f
      case Right(Command.Bootstrap(f, _, _, _, _, _)) => f
      case _                                          => None
    check(
      "deploy-agent takes --agent",
      agentOf("deploy-agent", "--agent", "x.deb").contains("x.deb")
    )
    check(
      "bootstrap takes --agent",
      agentOf("bootstrap", "--agent", "x.rpm", "--nodes", "a").contains("x.rpm")
    )
    check(
      "--file is still accepted for the same thing",
      agentOf("deploy-agent", "--file", "x.deb").contains("x.deb") &&
        agentOf("bootstrap", "--file", "x.rpm", "--nodes", "a").contains("x.rpm")
    )

    // The Go agent is the default; --scala selects the Scala agent, never together with --agent
    check(
      "deploy-agent --scala selects the Scala agent",
      Cli.parse(List("deploy-agent", "--scala", "--nodes", "a")) match
        case Right(Command.DeployAgent(None, _, _, true, _, _)) => true
        case _                                                  => false
    )
    check(
      "deploy-agent without --scala uses the default (Go) agent",
      Cli.parse(List("deploy-agent")) match
        case Right(Command.DeployAgent(None, _, _, false, _, _)) => true
        case _                                                   => false
    )
    check(
      "--go is still accepted and means the default",
      Cli.parse(List("deploy-agent", "--go")) match
        case Right(Command.DeployAgent(None, _, _, false, _, _)) => true
        case _                                                   => false
    )
    check(
      "--scala and --agent together are refused",
      Cli.parse(List("deploy-agent", "--scala", "--agent", "x.deb")).isLeft &&
        Cli.parse(List("deploy-agent", "--agent", "x.deb", "--scala")).isLeft
    )
    check(
      "with no Go package built the default says where it looked and how to use Scala",
      AgentPackages.resolve(None) match
        case Left(m)  => m.contains("agent-go") && m.contains("--scala")
        case Right(_) => java.io.File(AgentPackages.goDir).isDirectory
    )
    check(
      "an explicit --agent is used as given",
      AgentPackages.resolve(Some("x.deb")) == Right(AgentPackages(Some("x.deb"), None))
    )
    check(
      "deploy-agent --force and --parallel are parsed",
      Cli.parse(List("deploy-agent", "--force", "--parallel", "3")) match
        case Right(Command.DeployAgent(_, _, _, _, true, Some(3))) => true
        case _                                                     => false
    )
    check(
      "deploy-agent defaults: no force, no parallel limit given",
      Cli.parse(List("deploy-agent")) match
        case Right(Command.DeployAgent(_, _, _, _, false, None)) => true
        case _                                                   => false
    )
    check(
      "deploy-agent --parallel needs a positive number",
      Cli.parse(List("deploy-agent", "--parallel", "0")).isLeft &&
        Cli.parse(List("deploy-agent", "--parallel", "x")).isLeft
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"InventoryCheck: all $passes checks passed")
