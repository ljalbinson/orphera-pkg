// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import cats.effect.*
import cats.syntax.all.*

import io.circe.Json
import io.circe.yaml.parser

import orphera.common.{Operator, Policy}

/** `orphera issue-policy`: what each role may do, from `access.yaml`, put on
  * each node as `/etc/orphera-agent/policy` (and `revoked`) over SSH. The agent
  * applies it to every call, by the role in the caller's client certificate
  * (see docs/ACCESS-CONTROL-DESIGN.md). A node with no policy file allows every
  * call, as before.
  *
  * {{{
  * default_role: admin            # for a certificate with no role in it
  * roles:
  *   viewer:
  *     allow: [facts, version, uptime, thread-info, check-file, fetch]
  *   deployer:
  *     allow: [facts, copy, packages]
  *     nodes: [group:ceph, tst7]  # only these nodes; else every node
  *     paths: ["/etc/ceph/"]      # copy, check-file and fetch only under these
  *   admin:
  *     allow: all
  * revoked: ["1a2b3c"]            # serial numbers, in quotes of certificates not to accept
  * }}}
  */
object AccessPolicy:

  /** The first agent version that applies a policy file. An older agent ignores
    * it and allows everything, so a policy is not placed on one.
    */
  val MinAgentVersion = "0.1.211"

  /** The first agent version that limits a role to `paths`. Only the Go agent
    * does; an agent that cannot read a `paths` line refuses every call, so a
    * policy with one is not placed on it.
    */
  val MinAgentForPaths = "0.1.212"

  final case class Role(
      name: String,
      allow: Set[String],
      nodes: Option[List[String]],
      paths: Option[List[String]] = None
  )

  final case class Spec(
      defaultRole: String,
      roles: List[Role],
      revoked: List[String]
  )

  private val RoleKeys = Set("allow", "nodes", "paths")

  /** The policy in `text` (YAML), or what is wrong with it. */
  def parse(text: String): Either[String, Spec] =
    parser
      .parse(text)
      .left
      .map(e => s"not valid YAML: ${e.getMessage}")
      .flatMap { json =>
        json.asObject.toRight("expected a mapping at the top").flatMap { top =>
          val unknown = top.keys.toList
            .filterNot(k => Set("default_role", "roles", "revoked").contains(k))
          if unknown.nonEmpty then
            Left(s"unknown key(s): ${unknown.mkString(", ")}")
          else
            for
              default <- top("default_role") match
                case None       => Right("admin")
                case Some(json) =>
                  json.asString
                    .filter(Operator.validName)
                    .toRight("default_role must be a role name")
              rolesJson <- top("roles")
                .flatMap(_.asObject)
                .toRight("roles: a mapping of role names is needed")
              roles <- rolesJson.toList.traverse { case (name, body) =>
                role(name, body)
              }
              revoked <- top("revoked") match
                case None       => Right(Nil)
                case Some(json) =>
                  json.asArray
                    .toRight("revoked must be a list of serial numbers")
                    .flatMap(
                      _.toList.traverse(j =>
                        j.asString
                          .filter(_.matches("(0x)?[0-9a-fA-F]+"))
                          .toRight(
                            s"revoked: not a serial number: $j (write each one in quotes, as hexadecimal)"
                          )
                      )
                    )
              _ <- Either.cond(
                roles.exists(_.name == default),
                (),
                s"default_role '$default' is not one of the roles; certificates with no role would be refused everywhere"
              )
              _ <- Either.cond(roles.nonEmpty, (), "roles: none given")
            yield Spec(default, roles, revoked)
        }
      }

  private def role(name: String, body: Json): Either[String, Role] =
    for
      _ <- Either.cond(
        Operator.validName(name),
        (),
        s"role '$name' is not a usable name (lower-case letters, digits, '.', '_' and '-')"
      )
      obj <- body.asObject.toRight(
        s"role $name: expected allow: and optionally nodes:"
      )
      _ <- obj.keys.toList.filterNot(RoleKeys.contains) match
        case Nil     => Right(())
        case unknown =>
          Left(s"role $name: unknown key(s): ${unknown.mkString(", ")}")
      allow <- obj("allow") match
        case None    => Left(s"role $name: allow: is needed")
        case Some(j) =>
          j.asString
            .map(List(_))
            .orElse(j.asArray.flatMap(_.toList.traverse(_.asString)))
            .toRight(s"role $name: allow: is a list of operations, or all")
      unknownOps = allow.filterNot(op =>
        op == Policy.All || Policy.Operations.contains(op)
      )
      _ <- Either.cond(
        unknownOps.isEmpty && allow.nonEmpty,
        (),
        if allow.isEmpty then s"role $name: allow: is empty"
        else
          s"role $name: unknown operation(s) ${unknownOps.mkString(", ")} " +
            s"(known: ${Policy.Operations.keys.toList.sorted.mkString(", ")}, all)"
      )
      nodes <- obj("nodes") match
        case None    => Right(None)
        case Some(j) =>
          j.asArray
            .flatMap(_.toList.traverse(_.asString))
            .filter(_.nonEmpty)
            .map(Some(_))
            .toRight(
              s"role $name: nodes: is a list of node names and group:<name>"
            )
      paths <- obj("paths") match
        case None    => Right(None)
        case Some(j) =>
          j.asArray
            .flatMap(_.toList.traverse(_.asString))
            .filter(l => l.nonEmpty && l.forall(validPath))
            .map(Some(_))
            .toRight(
              s"role $name: paths: is a list of absolute paths, each starting with / " +
                "and with no spaces, commas or #"
            )
      _ <- Either.cond(
        paths.isEmpty || !(allow.contains(Policy.All) || allow.contains("run")),
        (),
        s"role $name: paths limits copy, check-file and fetch, but a role that can " +
          "run commands can reach any path anyway; take paths or run out"
      )
      _ <- Either.cond(
        paths.isEmpty || allow.exists(op =>
          op == Policy.All || op == "copy" || op == "check-file" || op == "fetch"
        ),
        (),
        s"role $name: paths limits copy, check-file and fetch, and this role allows none of them"
      )
    yield Role(name, allow.toSet, nodes, paths)

  private def validPath(p: String): Boolean =
    p.startsWith("/") && !p.exists(c => c == ',' || c == '#' || c.isWhitespace)

  /** The node names a selector means: `group:<name>` or a node's own name. */
  def members(
      selector: String,
      groups: List[Group],
      nodeNames: List[String]
  ): Either[String, List[String]] =
    if selector.startsWith("group:") then
      val g = selector.stripPrefix("group:")
      groups
        .find(_.name == g)
        .map(_.members)
        .toRight(s"no node group '$g' in the inventory")
    else if nodeNames.contains(selector) then Right(List(selector))
    else Left(s"no node '$selector' in the inventory")

  /** Checks every `nodes:` selector against the inventory, so a typo is an
    * error here and not a role that silently applies nowhere.
    */
  def check(
      spec: Spec,
      groups: List[Group],
      nodeNames: List[String]
  ): Either[String, Unit] =
    spec.roles
      .flatMap(r => r.nodes.getOrElse(Nil).map(r.name -> _))
      .traverse { case (role, sel) =>
        members(sel, groups, nodeNames).left.map(e => s"role $role: $e")
      }
      .void

  /** The policy file for `node`: the roles that apply to it. A role limited to
    * other nodes is not listed, so on this node it is refused.
    */
  def policyFor(
      spec: Spec,
      node: String,
      groups: List[Group],
      nodeNames: List[String]
  ): Either[String, String] =
    spec.roles
      .traverse { r =>
        (r.nodes match
          case None      => Right(true)
          case Some(sel) =>
            sel
              .traverse(members(_, groups, nodeNames))
              .map(_.flatten.contains(node))
        ).map(applies => r -> applies)
      }
      .map(_.collect { case (r, true) => r })
      .map { applying =>
        val lines = applying.flatMap { r =>
          val ops =
            if r.allow.contains(Policy.All) then Policy.All
            else r.allow.toList.sorted.mkString(",")
          s"role ${r.name} $ops" ::
            r.paths.map(ps => s"paths ${r.name} ${ps.mkString(",")}").toList
        }
        (List(
          s"# Made by orphera issue-policy for $node from the access policy. Do not edit: it is replaced the next time.",
          s"default_role ${spec.defaultRole}"
        ) ++ lines).mkString("", "\n", "\n")
      }

  def revokedText(spec: Spec): String =
    spec.revoked
      .map(Policy.normaliseSerial(_))
      .distinct
      .sorted
      .mkString(
        "# Serial numbers (hexadecimal) of client certificates not accepted.\n",
        "\n",
        "\n"
      )

  /** The agent calls (bare method names) `command` makes on its nodes, which
    * nodes (None: every node), and what to call it in a message. None for a
    * command that makes no call the policy governs: one that goes over SSH
    * (`bootstrap`, `issue-certs`, `issue-policy`, `teardown`), one that runs on
    * this machine, and a playbook or pipeline, whose calls are only known as it
    * runs (the agent refuses those).
    */
  def callsOf(
      command: Command
  ): Option[(List[String], Option[List[String]], String)] =
    command match
      case Command.Install(_, nodes, _) =>
        Some((List("Install"), nodes, "install packages"))
      case Command.Remove(_, nodes, _) =>
        Some((List("Remove"), nodes, "remove packages"))
      case Command.AutoRemove(nodes, _) =>
        Some((List("RunAutoRemove"), nodes, "remove unused packages"))
      case Command.DistUpgrade(nodes, _) =>
        Some((List("RunDistUpgrade"), nodes, "upgrade packages"))
      case Command.Copy(_, _, nodes, _, _, _) =>
        Some((List("CheckFile", "CopyFile"), nodes, "copy files"))
      case Command.WriteFile(_, _, nodes, _, _, _) =>
        Some((List("CheckFile", "CopyFile"), nodes, "write files"))
      case Command.NetworkApply(nodes, _) =>
        Some(
          (
            List("ReloadNetwork", "ConfirmNetwork", "CheckFile"),
            nodes,
            "reload the network"
          )
        )
      case Command.DeployAgent(_, _, nodes, _, _, _) =>
        Some(
          (
            List("CheckFile", "CopyFile", "InstallDebPackage", "GetVersion"),
            nodes,
            "deploy the agent"
          )
        )
      case Command.Fetch(_, _, nodes) =>
        Some((List("RemoteFetchFile"), nodes, "fetch files"))
      case Command.Facts(nodes) =>
        Some((List("GatherFacts"), nodes, "read facts"))
      case Command.Version(nodes) =>
        Some((List("GetVersion"), nodes, "read versions"))
      case Command.Uptime(nodes) =>
        Some((List("GetUptime"), nodes, "read uptime"))
      case Command.Threads(nodes, _, _) =>
        Some((List("GetThreadInfo"), nodes, "read thread information"))
      case Command.Reboot(nodes, _, wait, _) =>
        Some(
          (
            List("TriggerReboot") ++
              (if wait then List("CheckFile", "GetUptime") else Nil),
            nodes,
            "reboot"
          )
        )
      case Command.Shutdown(nodes, _, _) =>
        Some((List("TriggerShutdown"), nodes, "shut down"))
      case Command.RunCommand(_, nodes, _, _) =>
        Some((List("ExecuteCommand"), nodes, "run commands"))
      // An apply reads the node with GatherFacts and ObserveNode, and changes
      // it with the typed calls its kinds need (ApplyFiles, ApplyAccess,
      // ApplySoftware); only a `command` resource, or a guard on one, runs
      // anything (ExecuteCommand). A description that cannot be read here (a
      // Scala one) is taken to need it.
      case Command.Apply(path, check, nodes, _) =>
        Some(
          (
            applyMethods(path, check),
            nodes,
            if check then "check descriptions" else "apply descriptions"
          )
        )
      case _ => None

  /** The agent calls applying the description at `path` makes (with `check`:
    * only the reading ones), from the kinds of its resources.
    */
  private[orchestrator] def applyMethods(
      path: String,
      check: Boolean
  ): List[String] =
    val reads = List("GatherFacts", "ObserveNode")
    ApplyYaml.load(path).toOption match
      case None => if check then reads else reads :+ "ExecuteCommand"
      case Some(d) =>
        import ApplyResource.*
        val rs = d.resources
        val runs = rs.exists {
          case c: Command => !check || c.guarded
          case _          => false
        }
        val files = !check && rs.exists {
          case _: File | _: Directory | _: Symlink | _: Sysctl |
              _: KernelModule | _: LineInFile =>
            true
          case _ => false
        }
        val access = !check && rs.exists {
          case _: User | _: Group | _: AuthorizedKey | _: Mount => true
          case _                                                => false
        }
        val software = !check && rs.exists {
          case _: Package | _: Service => true
          case _                       => false
        }
        reads ++ List(
          Option.when(files)("ApplyFiles"),
          Option.when(access)("ApplyAccess"),
          Option.when(software)("ApplySoftware"),
          Option.when(runs)("ExecuteCommand")
        ).flatten

  /** Why `operator` may not run `command`, by the policy `spec`, or None. The
    * same words as the agent's refusal, but before any node is contacted, and
    * for every node at once. Only advice: the agent is what enforces, and a
    * node may hold an older policy than `access.yaml`.
    */
  def earlyRefusal(
      command: Command,
      operator: Operator,
      spec: Spec,
      groups: List[Group],
      nodeNames: List[String]
  ): Option[String] =
    callsOf(command).flatMap { case (methods, nodes, what) =>
      val targets = nodes.getOrElse(nodeNames).filter(nodeNames.contains)
      val refused = targets.flatMap { node =>
        policyFor(spec, node, groups, nodeNames).toOption
          .flatMap(text => Policy.parse(text).toOption)
          .flatMap(p =>
            methods.flatMap(m => p.refusal(operator.role, m)).headOption
          )
          .map(node -> _)
      }
      if refused.isEmpty then None
      else
        val byReason =
          refused
            .map(_._2)
            .distinct
            .map(why => why -> refused.filter(_._2 == why).map(_._1))
        Some(
          s"${operator.name} (${operator.role}) may not $what (access.yaml); " +
            "no node was contacted:\n" +
            byReason
              .map { case (why, nodes) =>
                s"  ${nodes.mkString(", ")}: $why"
              }
              .mkString("\n")
        )
    }

  /** The early check for a command this orchestrator is about to run: only when
    * `ORPHERA_OPERATOR` is set (otherwise there is no role to check) and an
    * `access.yaml` is there; `ORPHERA_ACCESS_CHECK=off` turns it off. A policy
    * that cannot be read is a warning, not a refusal: the agent decides.
    */
  def early(command: Command): Option[String] =
    if sys.env.get("ORPHERA_ACCESS_CHECK").map(_.trim).contains("off") then None
    else
      CurrentOperator.current.flatMap { operator =>
        if !Files.isRegularFile(defaultFile) then None
        else
          read(defaultFile)
            .flatMap(spec =>
              check(spec, Inventory.groups, Inventory.all.map(_.name))
                .map(_ => spec)
            ) match
            case Left(err) =>
              Console.err.println(
                s"Warning: early access checks skipped: $err"
              )
              None
            case Right(spec) =>
              earlyRefusal(
                command,
                operator,
                spec,
                Inventory.groups,
                Inventory.all.map(_.name)
              )
      }

  /** `1.2.3` or `1.2.3+go` as numbers, for comparing. */
  def versionOf(text: String): Option[(Int, Int, Int)] =
    text.takeWhile(c => c.isDigit || c == '.').split('.').toList match
      case a :: b :: c :: Nil =>
        (a.toIntOption, b.toIntOption, c.toIntOption).tupled
      case _ => None

  private def atLeast(agentVersion: String, minimum: String): Boolean =
    (versionOf(agentVersion), versionOf(minimum)).tupled.exists {
      case (have, need) => Ordering[(Int, Int, Int)].gteq(have, need)
    }

  def agentIsNewEnough(agentVersion: String): Boolean =
    atLeast(agentVersion, MinAgentVersion)

  /** Whether `policy` (a node's policy file) limits any role to paths. */
  def limitsPaths(policy: String): Boolean =
    policy.linesIterator.exists(_.startsWith("paths "))

  /** The first agent version that knows each operation added after the policy
    * itself. An agent that does not know an operation refuses the whole policy
    * file (so every call), so a policy that names one is not placed on it.
    */
  val OperationSince: Map[String, String] = Map(
    "observe" -> "0.1.213",
    "apply-files" -> "0.1.214",
    "apply-access" -> "0.1.215",
    "apply-software" -> "0.1.216"
  )

  /** The operations in `policy` (a node's policy file) that an agent of
    * `agentVersion` does not know.
    */
  def unknownOperations(policy: String, agentVersion: String): List[String] =
    policy.linesIterator
      .filter(_.startsWith("role "))
      .flatMap(_.split("\\s+").drop(2).flatMap(_.split(",")))
      .toList
      .distinct
      .filter(op =>
        OperationSince.get(op).exists(since => !atLeast(agentVersion, since))
      )

  /** The Go agent from `MinAgentForPaths` on; the Scala agent never. */
  def agentCanLimitPaths(agentVersion: String): Boolean =
    agentVersion.contains("+go") && atLeast(agentVersion, MinAgentForPaths)

  // `orphera policy-info`: what a node holds, read over SSH as the files are
  // placed, between markers so that a login banner does not matter.
  private val readHeld: String =
    """sudo sh -c 'cd /etc/orphera-agent; if [ -f policy ]; then echo @@POLICY@@; cat policy; echo @@REVOKED@@; cat revoked 2>/dev/null; echo @@END@@; else echo @@NONE@@; fi'"""

  /** The policy and revoked list a node holds, from the lines `readHeld`
    * printed: None when it has no policy (every call is allowed).
    */
  def parseHeld(lines: List[String]): Either[String, Option[(String, String)]] =
    if lines.exists(_.trim == "@@NONE@@") then Right(None)
    else
      lines.dropWhile(_.trim != "@@POLICY@@") match
        case _ :: afterStart =>
          val (policy, rest) = afterStart.span(_.trim != "@@REVOKED@@")
          rest match
            case _ :: afterMarker =>
              val revoked = afterMarker.takeWhile(_.trim != "@@END@@")
              Right(
                Some(
                  (
                    policy.mkString("", "\n", "\n"),
                    revoked.mkString("", "\n", "\n")
                  )
                )
              )
            case Nil => Left("the node's answer was cut short")
        case Nil => Left("the node did not answer with its policy")

  private def significant(text: String): List[String] =
    text.linesIterator
      .map(_.takeWhile(_ != '#').trim)
      .filter(_.nonEmpty)
      .toList

  /** How what a node holds compares with what access.yaml gives it: no lines
    * when they are the same, else what differs.
    */
  def compare(
      expectedPolicy: String,
      expectedRevoked: Set[String],
      held: Option[(String, String)]
  ): List[String] =
    held match
      case None =>
        List(
          "no policy: every call is allowed (orphera issue-policy puts one on)"
        )
      case Some((policy, revoked)) =>
        val want = significant(expectedPolicy)
        val have = significant(policy)
        val missing = want.filterNot(have.contains)
        val extra = have.filterNot(want.contains)
        val policyLines =
          if want == have then Nil
          else
            List("policy differs from access.yaml") ++
              missing.map(l => s"  missing: $l") ++
              extra.map(l => s"  extra:   $l") ++
              (if missing.isEmpty && extra.isEmpty then
                 List("  the same lines, in another order")
               else Nil)
        val heldRevoked = Policy.parseRevoked(revoked)
        val revokedLines =
          if heldRevoked == expectedRevoked then Nil
          else
            List(
              s"revoked list differs from access.yaml: the node has ${heldRevoked.size} serial(s), access.yaml ${expectedRevoked.size}"
            )
        policyLines ++ revokedLines

  def heldOn(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String]
  ): IO[Either[String, Option[(String, String)]]] =
    Ref.of[IO, List[String]](Nil).flatMap { seen =>
      SshDeployer
        .sshRunWithInput(
          node,
          sshUser,
          sshKeyPath,
          readHeld,
          "",
          line => seen.update(line :: _)
        ) >> seen.get.map(newestFirst => parseHeld(newestFirst.reverse))
    }

  private val Marker = "@@REVOKED@@"

  /** Reads the policy and the revoked list from stdin (separated by a line
    * `@@REVOKED@@`) and puts each in place if it differs. It does not touch a
    * node that does not require client certificates, since the agent would have
    * no caller role to check. The agent reads the files on every call, so no
    * restart is needed.
    */
  private val install: String =
    """sudo sh -c 'set -e; d=/etc/orphera-agent; t=$(mktemp -d); trap "rm -rf $t" EXIT;
      |if [ ! -f $d/tls/ca.crt ]; then echo "this node does not require client certificates (run issue-certs --client-auth first); nothing changed"; exit 1; fi;
      |: > $t/policy; : > $t/revoked;
      |awk -v p=$t/policy -v r=$t/revoked "BEGIN{o=p} /^@@REVOKED@@$/{o=r;next} {print > o}";
      |changed=0;
      |for f in policy revoked; do if ! cmp -s $t/$f $d/$f; then install -m 644 -o root -g root $t/$f $d/$f.new; mv $d/$f.new $d/$f; changed=1; fi; done;
      |if [ $changed = 1 ]; then echo "policy placed; it applies from the next call"; else echo "policy unchanged"; fi'""".stripMargin
      .replace("\n", " ")

  private val remove: String =
    "sudo sh -c 'rm -f /etc/orphera-agent/policy /etc/orphera-agent/revoked; echo \"policy removed; every call is allowed again\"'"

  def placeOn(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      policy: String,
      revoked: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    SshDeployer.sshRunWithInput(
      node,
      sshUser,
      sshKeyPath,
      install,
      policy + Marker + "\n" + revoked,
      onLine
    )

  def removeFrom(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      onLine: String => IO[Unit]
  ): IO[Unit] =
    SshDeployer.sshRunWithInput(node, sshUser, sshKeyPath, remove, "", onLine)

  /** `access.yaml` beside the inventory (not in `config/`, which holds the VM
    * configs that the schema checks and the database tools read as such).
    */
  def defaultFile: Path =
    Paths.get(Locations.inventory).resolveSibling("access.yaml")

  def read(path: Path): Either[String, Spec] =
    scala.util
      .Try(Files.readString(path, UTF_8))
      .toEither
      .left
      .map(e => s"cannot read $path: ${e.getMessage}")
      .flatMap(parse(_).left.map(e => s"$path: $e"))
