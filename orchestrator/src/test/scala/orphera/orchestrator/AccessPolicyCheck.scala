// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** Checks for `orphera issue-policy`: reading access.yaml, which roles go to
  * which node, and the file each node gets. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.AccessPolicyCheck"`.
  */
object AccessPolicyCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name${if detail.nonEmpty then s" — $detail" else ""}")

  private val groups = List(
    Group("ceph", List("tst0", "tst1", "tst2")),
    Group("galera", List("tst3", "tst4", "tst5"))
  )
  private val names = List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5")

  private val good =
    """default_role: admin
      |roles:
      |  viewer:
      |    allow: [facts, version, uptime]
      |  deployer:
      |    allow: [facts, copy, packages]
      |    nodes: [group:ceph, tst3]
      |  admin:
      |    allow: all
      |revoked: ["1A2b", "0x0f"]
      |""".stripMargin

  private def rejects(text: String, part: String): Boolean =
    AccessPolicy.parse(text).left.exists(_.contains(part))

  def main(args: Array[String]): Unit =
    val spec = AccessPolicy.parse(good)
    check("a policy parses", spec.isRight, spec.toString)
    val s = spec.toOption.get
    check(
      "its roles, in order",
      s.roles.map(_.name) == List("viewer", "deployer", "admin") &&
        s.defaultRole == "admin"
    )
    check(
      "a role's nodes and operations",
      s.roles(1).nodes.contains(List("group:ceph", "tst3")) &&
        s.roles(1).allow == Set("facts", "copy", "packages")
    )
    check(
      "allow: all as a plain word",
      AccessPolicy
        .parse("roles:\n  admin:\n    allow: all\n")
        .exists(_.roles.head.allow == Set("all"))
    )
    check(
      "default_role defaults to admin",
      AccessPolicy
        .parse("roles:\n  admin:\n    allow: all\n")
        .exists(_.defaultRole == "admin")
    )
    check(
      "the default role must be a role",
      rejects(
        "default_role: boss\nroles:\n  admin:\n    allow: all\n",
        "not one of the roles"
      )
    )
    check(
      "an unknown operation is refused, naming the known ones",
      rejects(
        "roles:\n  admin:\n    allow: [all]\n  v:\n    allow: [lookatit]\n",
        "unknown operation(s) lookatit"
      )
    )
    check(
      "paths limit a role: parsed, and emitted as a paths line after its role line", {
        val text =
          "default_role: admin\nroles:\n  deployer:\n    allow: [copy, fetch]\n    paths: [/etc/ceph/, /srv/]\n  admin:\n    allow: all\n"
        AccessPolicy
          .parse(text)
          .flatMap(s => AccessPolicy.policyFor(s, "tst0", groups, names))
          .exists(p =>
            p.linesIterator.toList.containsSlice(
              List(
                "role deployer copy,fetch",
                "paths deployer /etc/ceph/,/srv/"
              )
            )
          )
      }
    )
    check(
      "paths must be absolute",
      rejects(
        "roles:\n  d:\n    allow: [copy]\n    paths: [etc/]\n",
        "absolute paths"
      )
    )
    check(
      "paths are refused for a role that can run commands",
      rejects(
        "roles:\n  d:\n    allow: [run, copy]\n    paths: [/etc/]\n",
        "run"
      )
    )
    check(
      "paths are refused for a role that allows no file operation",
      rejects(
        "roles:\n  d:\n    allow: [facts]\n    paths: [/etc/]\n",
        "allows none of them"
      )
    )
    check(
      "limitsPaths sees a paths line",
      AccessPolicy.limitsPaths(
        "default_role a\nrole a copy\npaths a /etc/\n"
      ) &&
        !AccessPolicy.limitsPaths("default_role a\nrole a all\n")
    )
    check(
      "only the Go agent from 0.1.212 can limit paths",
      AccessPolicy.agentCanLimitPaths("0.1.212+go") &&
        AccessPolicy.agentCanLimitPaths("0.1.230+go") &&
        !AccessPolicy.agentCanLimitPaths("0.1.211+go") &&
        !AccessPolicy.agentCanLimitPaths("0.1.212")
    )
    check(
      "the Scala agent's policy parser fails closed on a paths line",
      orphera.common.Policy
        .parse("default_role a\nrole a copy\npaths a /etc/\n")
        .isLeft
    )
    check(
      "parseHeld: no policy, a policy, and a cut-off answer",
      AccessPolicy.parseHeld(List("banner", "@@NONE@@")) == Right(None) &&
        AccessPolicy.parseHeld(
          List(
            "x",
            "@@POLICY@@",
            "default_role a",
            "@@REVOKED@@",
            "ab",
            "@@END@@"
          )
        ) == Right(Some(("default_role a\n", "ab\n"))) &&
        AccessPolicy.parseHeld(List("@@POLICY@@", "default_role a")).isLeft &&
        AccessPolicy.parseHeld(List("nothing useful")).isLeft
    )
    check(
      "compare: same, comments ignored; differing lines and revoked list named", {
        val want = "# made\ndefault_role a\nrole a all\n"
        AccessPolicy
          .compare(
            want,
            Set("ab"),
            Some(("default_role a\nrole a all # x\n", "ab\n"))
          )
          .isEmpty &&
        AccessPolicy
          .compare(want, Set.empty, None)
          .exists(_.contains("no policy")) &&
        AccessPolicy
          .compare(
            want,
            Set.empty,
            Some(("default_role a\nrole a facts\n", ""))
          )
          .exists(_.contains("missing: role a all")) &&
        AccessPolicy
          .compare(want, Set("ab"), Some((want, "")))
          .exists(_.contains("revoked list differs"))
      }
    )
    check(
      "an unknown key in a role is refused",
      rejects(
        "roles:\n  admin:\n    allow: all\n    colour: red\n",
        "unknown key"
      )
    )
    check(
      "an unknown top-level key is refused",
      rejects("rolez:\n  admin:\n    allow: all\n", "unknown key")
    )
    check(
      "a role with no allow is refused",
      rejects("roles:\n  admin: {}\n", "allow: is needed")
    )
    check(
      "a role with an empty allow is refused",
      rejects("roles:\n  admin:\n    allow: []\n", "allow: is empty")
    )
    check(
      "a role name must be safe",
      rejects("roles:\n  Admin:\n    allow: all\n", "not a usable name")
    )
    check(
      "a revoked entry must be a serial number",
      rejects(
        "roles:\n  admin:\n    allow: all\nrevoked: [nope!]\n",
        "not a serial number"
      )
    )
    check(
      "a serial written without quotes (YAML would make it a number) is refused",
      rejects(
        "roles:\n  admin:\n    allow: all\nrevoked: [0x0f]\n",
        "in quotes"
      )
    )
    check("not YAML", AccessPolicy.parse("a: [").isLeft)

    check(
      "node selectors are checked against the inventory",
      AccessPolicy.check(s, groups, names).isRight &&
        AccessPolicy
          .check(
            s.copy(roles =
              s.roles.map(r => r.copy(nodes = Some(List("group:cef"))))
            ),
            groups,
            names
          )
          .left
          .exists(_.contains("no node group 'cef'")) &&
        AccessPolicy
          .check(
            s.copy(roles =
              s.roles.map(r => r.copy(nodes = Some(List("tst99"))))
            ),
            groups,
            names
          )
          .left
          .exists(_.contains("no node 'tst99'"))
    )

    val onCeph = AccessPolicy.policyFor(s, "tst0", groups, names)
    check(
      "a node in the group gets the role limited to it",
      onCeph.exists(t =>
        t.contains("role deployer copy,facts,packages") &&
          t.contains("role viewer facts,uptime,version") &&
          t.contains("role admin all") &&
          t.contains("default_role admin")
      ),
      onCeph.toString
    )
    check(
      "a node named directly gets it too",
      AccessPolicy
        .policyFor(s, "tst3", groups, names)
        .exists(_.contains("role deployer"))
    )
    check(
      "another node does not get it, so there the role is refused",
      AccessPolicy
        .policyFor(s, "tst4", groups, names)
        .exists(t =>
          !t.contains("role deployer") && t.contains("role admin all")
        )
    )
    check(
      "the file is what the agent parses",
      names.forall(n =>
        AccessPolicy
          .policyFor(s, n, groups, names)
          .exists(t => orphera.common.Policy.parse(t).isRight)
      )
    )
    check(
      "the revoked list is normalised, sorted and commented",
      AccessPolicy.revokedText(s).linesIterator.toList.drop(1) == List(
        "1a2b",
        "f"
      ) &&
        orphera.common.Policy.parseRevoked(AccessPolicy.revokedText(s)) ==
        Set("1a2b", "f")
    )

    check(
      "agent versions: a plain and a Go one",
      AccessPolicy.versionOf("0.1.211") == Some((0, 1, 211)) &&
        AccessPolicy.versionOf("0.1.211+go") == Some((0, 1, 211)) &&
        AccessPolicy.versionOf("dev").isEmpty
    )
    check(
      "an agent older than the first to apply a policy is refused",
      !AccessPolicy.agentIsNewEnough("0.1.210+go") &&
        !AccessPolicy.agentIsNewEnough("0.1.210") &&
        !AccessPolicy.agentIsNewEnough("dev") &&
        AccessPolicy.agentIsNewEnough("0.1.211") &&
        AccessPolicy.agentIsNewEnough("0.1.211+go") &&
        AccessPolicy.agentIsNewEnough("0.2.0") &&
        AccessPolicy.agentIsNewEnough("0.1.1000")
    )

    check(
      "issue-policy parses",
      Cli.parse(
        List(
          "issue-policy",
          "--nodes",
          "tst0,tst1",
          "--ssh-user",
          "localadmin",
          "--dry-run"
        )
      ) ==
        Right(
          Command.IssuePolicy(
            Some(List("tst0", "tst1")),
            "localadmin",
            None,
            None,
            false,
            true
          )
        ) &&
        Cli.parse(List("issue-policy", "--remove", "--nodes", "tst0")) ==
        Right(
          Command.IssuePolicy(
            Some(List("tst0")),
            "root",
            None,
            None,
            true,
            false
          )
        )
    )
    check(
      "--remove and --dry-run, or --policy, do not go together",
      Cli.parse(List("issue-policy", "--remove", "--dry-run")).isLeft &&
        Cli
          .parse(List("issue-policy", "--remove", "--policy", "x.yaml"))
          .isLeft &&
        Cli.parse(List("issue-policy", "--bogus")).isLeft
    )
    check(
      "a placed policy is audited, a dry run is not",
      AuditLog.isAuditable(Command.IssuePolicy(None, "root", None)) &&
        !AuditLog.isAuditable(
          Command.IssuePolicy(None, "root", None, None, false, true)
        )
    )

    // Early refusals
    val op = orphera.common.Operator
    val early = AccessPolicy
      .parse(
        """roles:
        |  viewer:
        |    allow: [facts, version, uptime]
        |  deployer:
        |    allow: [facts, copy, packages]
        |    nodes: [group:ceph]
        |  admin:
        |    allow: all
        |""".stripMargin
      )
      .toOption
      .get
    def refusal(c: Command, who: orphera.common.Operator) =
      AccessPolicy.earlyRefusal(c, who, early, groups, names)
    val viewer = op("alice", "viewer")
    val deployer = op("bob", "deployer")
    val boss = op("carol", "admin")
    val runOn = (n: List[String]) =>
      Command.RunCommand(List("true"), Some(n), 60)
    check(
      "a viewer may read",
      refusal(Command.Facts(None), viewer).isEmpty &&
        refusal(Command.Uptime(Some(List("tst0"))), viewer).isEmpty
    )
    check(
      "a viewer may not run, and the refusal names who, what and the nodes",
      refusal(runOn(List("tst0", "tst1")), viewer).exists(m =>
        m.contains("alice (viewer) may not run commands") &&
          m.contains("tst0, tst1:") &&
          m.contains("operation run") &&
          m.contains("no node was contacted")
      ),
      refusal(runOn(List("tst0", "tst1")), viewer).toString
    )
    check(
      "a viewer may not apply, which runs commands on the node",
      refusal(Command.Apply("x.scala", false, None, false), viewer).isDefined
    )
    check(
      "apply --check makes only the ObserveNode call; a real apply runs commands too",
      AccessPolicy
        .callsOf(Command.Apply("x.yaml", true, None, false))
        .map(_._1) == Some(List("ObserveNode")) &&
        AccessPolicy
          .callsOf(Command.Apply("x.yaml", false, None, false))
          .exists(_._1.contains("ExecuteCommand"))
    )
    check(
      "a policy may name the observe operation",
      AccessPolicy
        .parse("roles:\n  v:\n    allow: [observe]\n  admin:\n    allow: all\n")
        .isRight &&
        orphera.common.Policy.allows(Set("observe"), "ObserveNode") &&
        !orphera.common.Policy.allows(Set("facts", "uptime"), "ObserveNode")
    )
    check(
      "a deployer may install on a Ceph node, not on a Galera node (where it has no role)",
      refusal(
        Command.Install(List("tree"), Some(List("tst0")), true),
        deployer
      ).isEmpty &&
        refusal(
          Command.Install(List("tree"), Some(List("tst3")), true),
          deployer
        )
          .exists(m =>
            m.contains("tst3:") && m.contains(
              "not in this node's access policy"
            )
          )
    )
    check(
      "a deployer may not run",
      refusal(runOn(List("tst0")), deployer).isDefined
    )
    check(
      "a command for every node is checked against every node",
      refusal(Command.Install(List("tree"), None, true), deployer).exists(m =>
        m.contains("tst3, tst4, tst5:") && !m.contains("tst0")
      )
    )
    check(
      "an admin may do all of it",
      refusal(runOn(names), boss).isEmpty &&
        refusal(Command.Reboot(None, 5, true, 300), boss).isEmpty
    )
    check(
      "a reboot that waits needs the calls it waits with",
      AccessPolicy
        .callsOf(Command.Reboot(None, 5, true, 300))
        .exists(_._1.contains("GetUptime")) &&
        AccessPolicy
          .callsOf(Command.Reboot(None, 5, false, 300))
          .exists(!_._1.contains("GetUptime"))
    )
    check(
      "commands over SSH, playbooks and reports are not checked early",
      List(
        Command.Bootstrap(None, None, "root", None, "/tmp/x.deb"),
        Command.IssueCerts(None, "root", None),
        Command.IssuePolicy(None, "root", None),
        Command.IssuePolicy(None, "root", None, check = true),
        Command.RunPlaybook("x.scala", false, None),
        Command.ShowAuditLog(20),
        Command.Help
      ).forall(c => AccessPolicy.callsOf(c).isEmpty)
    )
    check(
      "a node name that is not in the inventory is left to the normal error",
      refusal(runOn(List("nope")), viewer).isEmpty
    )

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"AccessPolicyCheck: all $passes checks passed")
