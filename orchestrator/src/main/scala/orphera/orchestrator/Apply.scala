// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.util.Try

import cats.effect.{IO, Ref}
import cats.syntax.all.*

/** Declarative apply (docs/APPLY-DESIGN.md): a description of how a node should
  * be, as resources. Each resource is observed (read from the node), compared
  * with what is wanted, and changed only where it differs; with `check`, only
  * observed and compared.
  *
  * A resource is named by its kind and a name unique on the node
  * (`file[/etc/chrony.conf]`). `after` makes it wait for others (and be skipped
  * if one of them fails); `notifies` makes a change to it run a command marked
  * `onChangeOnly`, once, after everything that notifies it.
  */
sealed trait ApplyResource:
  def id: String
  /** Ids of the resources this one comes after. */
  def needs: List[String]
  /** Ids of the commands a change to this one runs. */
  def triggers: List[String]
  protected def relinked(needs: List[String], triggers: List[String]): ApplyResource

  /** This resource after `others` (wherever they stand in the list). */
  def after(others: ApplyResource*): ApplyResource = relinked(needs ++ others.map(_.id), triggers)

  /** As `after`, by id (`"file[/etc/x]"`). */
  def afterIds(ids: String*): ApplyResource = relinked(needs ++ ids, triggers)

  /** When this resource changes, `commands` (each `onChangeOnly`) run. */
  def notifies(commands: ApplyResource*): ApplyResource = relinked(needs, triggers ++ commands.map(_.id))

  def notifiesIds(ids: String*): ApplyResource = relinked(needs, triggers ++ ids)

object ApplyResource:

  /** A file's wanted content: text, secret text (never shown), or a local
    * file (a `.mustache` one rendered with `vars` and the node's facts and
    * vars, as `copy` does).
    */
  enum Content:
    case Text(text: String)
    case Hidden(secret: Secret)
    case Source(path: String, vars: Map[String, String])

  final case class File(
      path: String,
      content: Option[Content],
      mode: String,
      owner: String,
      group: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"file[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  final case class Directory(
      path: String,
      mode: String,
      owner: String,
      group: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"directory[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  final case class Symlink(
      path: String,
      target: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"symlink[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  /** A shell command (`sh -c`). It runs when its guard says it is needed
    * (`onlyIf` exits 0, `unless` exits non-zero, `creates` does not exist)
    * and, with `onChangeOnly`, only when a resource that notifies it changed.
    */
  final case class Command(
      label: String,
      run: String,
      onlyIf: String = "",
      unless: String = "",
      creates: String = "",
      onChangeOnly: Boolean = false,
      timeout: Int = 300,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"command[$label]"
    def guarded: Boolean = onlyIf.nonEmpty || unless.nonEmpty || creates.nonEmpty
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  /** A package, through apt (Debian, Ubuntu) or dnf (Rocky, RHEL), whichever
    * the node has. `version`, if given, is the version as the package manager
    * names it, and the installed one must equal it. `updateCache` runs
    * `apt-get update` before installing (dnf refreshes by itself).
    */
  final case class Package(
      name: String,
      version: String,
      updateCache: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"package[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  /** A systemd unit, enabled or not and running or not (None: left as it
    * is). A resource that notifies it restarts it, once, unless this run
    * starts it anyway.
    */
  final case class Service(
      unit: String,
      enabled: Option[Boolean],
      running: Option[Boolean],
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"service[$unit]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  /** A local user. `uid` -1, and an empty `group`, `home` or `shell`, are
    * left as they are; `groups` are supplementary groups it must be in (others
    * it is in are left alone). Removing it leaves its home directory.
    */
  final case class User(
      name: String,
      uid: Int,
      group: String,
      groups: List[String],
      home: String,
      shell: String,
      system: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"user[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

  /** A local group (`gid` -1: any). */
  final case class Group(
      name: String,
      gid: Int,
      system: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"group[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource = copy(needs = n, triggers = t)

/** The functions a description is written with. `mode` is octal text
  * ("0644"); an empty `mode`, `owner` or `group` is left as it is.
  */
object Resources:
  import ApplyResource.*

  def file(path: String, content: String, mode: String = "", owner: String = "", group: String = ""): ApplyResource =
    File(path, Some(Content.Text(content)), mode, owner, group, present = true)

  /** A file whose content is secret: it is never shown in a plan or diff. */
  def secretFile(path: String, content: Secret, mode: String = "0600", owner: String = "", group: String = ""): ApplyResource =
    File(path, Some(Content.Hidden(content)), mode, owner, group, present = true)

  /** A file whose content is a local file (`.mustache`: rendered). */
  def fileFrom(
      path: String,
      source: String,
      vars: Map[String, String] = Map.empty,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(path, Some(Content.Source(source, vars)), mode, owner, group, present = true)

  def absentFile(path: String): ApplyResource = File(path, None, "", "", "", present = false)

  def directory(path: String, mode: String = "", owner: String = "", group: String = ""): ApplyResource =
    Directory(path, mode, owner, group, present = true)

  /** Removed if empty; a directory with something in it fails. */
  def absentDirectory(path: String): ApplyResource = Directory(path, "", "", "", present = false)

  def symlink(path: String, target: String): ApplyResource = Symlink(path, target, present = true)

  def absentSymlink(path: String): ApplyResource = Symlink(path, "", present = false)

  /** A package installed (`package` is a word Scala keeps for itself). */
  def pkg(name: String, version: String = "", updateCache: Boolean = false): ApplyResource =
    Package(name, version, updateCache, present = true)

  def absentPkg(name: String): ApplyResource = Package(name, "", updateCache = false, present = false)

  /** A systemd unit: by default enabled and running; None leaves either as it is. */
  def service(unit: String, enabled: Option[Boolean] = Some(true), running: Option[Boolean] = Some(true)): ApplyResource =
    Service(unit, enabled, running)

  def user(
      name: String,
      uid: Int = -1,
      group: String = "",
      groups: List[String] = Nil,
      home: String = "",
      shell: String = "",
      system: Boolean = false
  ): ApplyResource =
    User(name, uid, group, groups, home, shell, system, present = true)

  def absentUser(name: String): ApplyResource = User(name, -1, "", Nil, "", "", system = false, present = false)

  def group(name: String, gid: Int = -1, system: Boolean = false): ApplyResource =
    Group(name, gid, system, present = true)

  def absentGroup(name: String): ApplyResource = Group(name, -1, system = false, present = false)

  def command(
      label: String,
      run: String,
      onlyIf: String = "",
      unless: String = "",
      creates: String = "",
      onChangeOnly: Boolean = false,
      timeout: Int = 300
  ): ApplyResource =
    Command(label, run, onlyIf, unless, creates, onChangeOnly, timeout)

/** A checked description: its resources and the order to apply them in. */
final case class ApplyPlan private (
    name: String,
    resources: Vector[ApplyResource],
    order: Vector[Int],
    // index -> the indices it comes after (by `after`)
    needs: Map[Int, Set[Int]],
    // index -> the indices that notify it
    notifiers: Map[Int, Set[Int]]
):
  def index(id: String): Option[Int] = resources.indexWhere(_.id == id) match
    case -1 => None
    case i  => Some(i)

object ApplyPlan:

  /** Checks a description and works out its order, or lists every problem:
    * a name used twice, a relative path, a bad mode, a command with no guard
    * and not `onChangeOnly`, a reference to a resource that is not described,
    * a notification of something that is not an `onChangeOnly` command, a
    * cycle.
    */
  def apply(name: String, resources: List[ApplyResource]): Either[List[String], ApplyPlan] =
    val rs = resources.toVector
    val ids = rs.map(_.id)
    val byId = ids.zipWithIndex.toMap
    val problems = List.newBuilder[String]

    ids.groupBy(identity).collect { case (id, xs) if xs.size > 1 => id }.toList.sorted.foreach(id =>
      problems += s"$id is described ${ids.count(_ == id)} times"
    )
    def checkPath(id: String, path: String): Unit =
      if !path.startsWith("/") then problems += s"$id: the path must be absolute"
    def checkMode(id: String, mode: String): Unit =
      if mode.nonEmpty && !mode.matches("[0-7]{3,4}") then
        problems += s"$id: mode '$mode' is not an octal mode such as \"0644\""
    rs.foreach {
      case f: ApplyResource.File =>
        checkPath(f.id, f.path); checkMode(f.id, f.mode)
      case d: ApplyResource.Directory =>
        checkPath(d.id, d.path); checkMode(d.id, d.mode)
      case s: ApplyResource.Symlink =>
        checkPath(s.id, s.path)
        if s.present && s.target.isEmpty then problems += s"${s.id}: no target"
      case p: ApplyResource.Package =>
        if !p.name.matches("[A-Za-z0-9][A-Za-z0-9.+_:-]*") then problems += s"${p.id}: not a package name"
      case u: ApplyResource.Service =>
        if !u.unit.matches("[A-Za-z0-9@._:-]+") then problems += s"${u.id}: not a unit name"
        if u.enabled.isEmpty && u.running.isEmpty && !rs.exists(_.triggers.contains(u.id)) then
          problems += s"${u.id}: says nothing about it (enabled, running) and nothing notifies it"
      case u: ApplyResource.User =>
        if !u.name.matches("[a-z_][a-z0-9_-]*[$]?") then problems += s"${u.id}: not a user name"
        if u.home.nonEmpty && !u.home.startsWith("/") then problems += s"${u.id}: home must be an absolute path"
      case g: ApplyResource.Group =>
        if !g.name.matches("[a-z_][a-z0-9_-]*[$]?") then problems += s"${g.id}: not a group name"
      case c: ApplyResource.Command =>
        if c.label.trim.isEmpty then problems += "a command has no label"
        if c.run.trim.isEmpty then problems += s"${c.id}: nothing to run"
        if List(c.onlyIf, c.unless, c.creates).count(_.nonEmpty) > 1 then
          problems += s"${c.id}: give one of onlyIf, unless and creates"
        if !c.guarded && !c.onChangeOnly then
          problems += s"${c.id}: says neither when it is needed (onlyIf, unless, creates) nor onChangeOnly, " +
            "so it would run every time"
        if c.creates.nonEmpty && !c.creates.startsWith("/") then
          problems += s"${c.id}: creates must be an absolute path"
    }
    rs.foreach { r =>
      r.needs.foreach(n =>
        if !byId.contains(n) then problems += s"${r.id} comes after $n, which is not described"
        else if n == r.id then problems += s"${r.id} comes after itself"
      )
      r.triggers.foreach(t =>
        byId.get(t).map(rs(_)) match
          case None                                          => problems += s"${r.id} notifies $t, which is not described"
          case Some(c: ApplyResource.Command) if c.onChangeOnly   => ()
          case Some(_: ApplyResource.Service)                => ()
          case Some(_)                                       => problems += s"${r.id} notifies $t, which is not a service or an onChangeOnly command"
      )
    }
    rs.collect { case c: ApplyResource.Command if c.onChangeOnly => c }.foreach(c =>
      if !rs.exists(_.triggers.contains(c.id)) then
        problems += s"${c.id} is onChangeOnly but nothing notifies it"
    )

    val found = problems.result()
    if found.nonEmpty then Left(found)
    else
      // Besides `after`: a user comes after the groups it names, and a file or directory after the user
      // and group that own it, when those are described too.
      def implied(r: ApplyResource): List[String] = r match
        case u: ApplyResource.User      => (u.group :: u.groups).filter(_.nonEmpty).map(g => s"group[$g]")
        case f: ApplyResource.File      => List(f.owner).filter(_.nonEmpty).map(o => s"user[$o]") ++ List(f.group).filter(_.nonEmpty).map(g => s"group[$g]")
        case d: ApplyResource.Directory => List(d.owner).filter(_.nonEmpty).map(o => s"user[$o]") ++ List(d.group).filter(_.nonEmpty).map(g => s"group[$g]")
        case _                          => Nil
      val needs: Map[Int, Set[Int]] =
        rs.indices.map(i => i -> (rs(i).needs ++ implied(rs(i)).filter(id => byId.get(id).exists(_ != i))).flatMap(byId.get).toSet).toMap
      val notifiers: Map[Int, Set[Int]] =
        rs.indices.map(i => i -> rs.indices.filter(j => rs(j).triggers.contains(rs(i).id)).toSet).toMap
      // Before i: what it needs and what notifies it. Declaration order otherwise.
      val before: Map[Int, Set[Int]] = rs.indices.map(i => i -> (needs(i) ++ notifiers(i))).toMap
      @annotation.tailrec
      def sort(done: Vector[Int], left: Set[Int]): Either[List[String], Vector[Int]] =
        if left.isEmpty then Right(done)
        else
          left.filter(i => before(i).forall(done.contains)).minOption match
            case Some(next) => sort(done :+ next, left - next)
            case None       =>
              Left(List(s"these resources wait for each other: ${left.toList.sorted.map(rs(_).id).mkString(", ")}"))
      sort(Vector.empty, rs.indices.toSet).map(order => new ApplyPlan(name, rs, order, needs, notifiers))

/** What a resource's observation found on the node. */
enum Observed:
  case Absent
  case RegularFile(mode: Int, owner: String, group: String, sha256: String)
  case Dir(mode: Int, owner: String, group: String)
  case Link(target: String)
  case OtherType
  /** A command's guard: whether the command is needed. */
  case Guard(needed: Boolean, note: String)
  /** A package: the node's package manager ("apt", "dnf", "none"), and the installed versions. */
  case Pkg(manager: String, installed: Boolean, versions: List[String])
  /** A systemd unit, as `systemctl is-enabled` and `is-active` name it. */
  case ServiceState(enabled: String, active: String)
  /** A user that exists. */
  case Account(uid: Int, group: String, groups: List[String], home: String, shell: String)
  /** A group that exists. */
  case GroupEntry(gid: Int)
  case Unreadable(text: String)

/** How a resource ended. */
enum ApplyOutcome:
  case Unchanged(note: String)
  case NotNotified
  case WouldChange(changes: List[String], detail: List[String])
  case Changed(changes: List[String], detail: List[String])
  case Skipped(reason: String)
  case Failed(error: String)

final case class ResourceResult(id: String, outcome: ApplyOutcome):
  def changed: Boolean = outcome match
    case _: ApplyOutcome.Changed | _: ApplyOutcome.WouldChange => true
    case _                                                     => false
  def failed: Boolean = outcome match
    case _: ApplyOutcome.Failed => true
    case _                      => false
  def skipped: Boolean = outcome match
    case _: ApplyOutcome.Skipped => true
    case _                       => false
  def unchanged: Boolean = outcome match
    case _: ApplyOutcome.Unchanged | ApplyOutcome.NotNotified => true
    case _                                                    => false

/** What applying a description to one node did (or, with `check`, would do). */
final case class ApplyReport(node: String, name: String, check: Boolean, results: List[ResourceResult]):
  /** Whether anything changed (with `check`: would change). */
  def changed: Boolean = results.exists(_.changed)
  def changed(id: String): Boolean = results.exists(r => r.id == id && r.changed)
  def failed: Boolean = results.exists(_.failed)
  def failures: List[String] = results.collect { case ResourceResult(id, ApplyOutcome.Failed(e)) => s"$id: $e" }

  def summary: String =
    val skipped = results.count(_.skipped)
    val failed = results.count(_.failed)
    val parts = List(
      s"${results.count(_.changed)} ${if check then "to change" else "changed"}",
      s"${results.count(_.unchanged)} unchanged"
    ) ++ Option.when(skipped > 0)(s"$skipped skipped") ++ Option.when(failed > 0)(s"$failed failed")
    if check then s"${parts.mkString(", ")} (check: nothing changed)" else parts.mkString(", ")

/** One node, as the engine sees it: how to read it and change it. The real
  * one runs shell commands and writes files through the agent; checks use a
  * fake.
  */
trait ApplyTarget:
  def node: String
  /** The state of these resources (keyed by index); a resource missing from
    * the answer could not be read.
    */
  def observe(resources: List[(Int, ApplyResource)]): IO[Map[Int, Observed]]
  /** A file's text, for a diff (None: cannot be shown). */
  def readText(path: String): IO[Option[String]]
  /** A `fileFrom` source as text, rendered if it is a `.mustache` file. */
  def source(path: String, vars: Map[String, String]): IO[String]
  def write(path: String, content: String, owner: String, group: String, mode: Int): IO[Unit]
  /** Runs a change; raises, with what it printed, when it fails. */
  def shell(label: String, script: String, timeout: Int): IO[Unit]

object Apply:

  /** The checked plan for `resources`, or an error naming every problem. */
  def plan(name: String, resources: List[ApplyResource]): IO[ApplyPlan] =
    IO.fromEither(
      ApplyPlan(name, resources).left.map(problems =>
        new IllegalArgumentException(s"description '$name': ${problems.mkString("; ")}")
      )
    )

  private def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  private[orchestrator] def octal(mode: String): Int = if mode.isEmpty then 0 else Integer.parseInt(mode, 8)

  private def modeText(m: Int): String = if m < 0 then "?" else "0" + Integer.toOctalString(m)

  def quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  // A change: a file written through the agent, or a shell command.
  private enum Action:
    case Write(path: String, content: String, owner: String, group: String, mode: Int)
    case Shell(script: String)

  // What it takes to bring a resource to its wanted state: what differs (for
  // the plan), the actions, and the lines of a content diff.
  private final case class Difference(changes: List[String], actions: List[Action], detail: List[String])

  private val NoDifference = Difference(Nil, Nil, Nil)

  // What differs in a path's mode, owner and group, and the commands that fix it.
  private final case class Attrs(changes: List[String], commands: List[String])

  private def attrs(path: String, mode: String, owner: String, group: String, m: Int, o: String, g: String): Attrs =
    val wanted = List(
      Option.when(mode.nonEmpty && octal(mode) != m)(s"mode ${modeText(m)} -> ${modeText(octal(mode))}" -> s"chmod $mode ${quote(path)}"),
      Option.when(owner.nonEmpty && owner != o)(s"owner $o -> $owner" -> s"chown ${quote(owner)} ${quote(path)}"),
      Option.when(group.nonEmpty && group != g)(s"group $g -> $group" -> s"chgrp ${quote(group)} ${quote(path)}")
    ).flatten
    Attrs(wanted.map(_._1), wanted.map(_._2))

  // The difference for one resource, or why it cannot be brought there.
  private def compare(
      r: ApplyResource,
      seen: Observed,
      content: Option[(String, Boolean)], // the wanted text, and whether it is secret
      target: ApplyTarget,
      check: Boolean,
      notifiedBy: List[String]
  ): IO[Either[String, Difference]] =
    import ApplyResource.*
    val path = r match
      case f: File      => f.path
      case d: Directory => d.path
      case s: Symlink   => s.path
      case _            => ""
    val q = quote(path)
    seen match
      case Observed.Unreadable(text) => IO.pure(Left(s"could not read it on the node: $text"))
      case _                         =>
        r match
          case f: File if !f.present =>
            IO.pure(seen match
              case Observed.Absent                         => Right(NoDifference)
              case _: Observed.RegularFile | _: Observed.Link => Right(Difference(List("removed"), List(Action.Shell(s"rm -f -- $q")), Nil))
              case Observed.Dir(_, _, _)                   => Left(s"$path is a directory: not removed")
              case _                                       => Left(s"$path is not a file: not removed"))
          case f: File =>
            val wanted = content.getOrElse(("", false))
            val text = wanted._1
            val secret = wanted._2
            val write = Action.Write(f.path, text, f.owner, f.group, octal(f.mode))
            seen match
              case Observed.Absent =>
                IO.pure(Right(Difference(List("created"), List(write), Nil)))
              case Observed.RegularFile(m, o, g, sum) =>
                val fix = attrs(f.path, f.mode, f.owner, f.group, m, o, g)
                if f.content.nonEmpty && sum != sha256(text) then
                  val diff =
                    if secret then IO.pure(List("(secret: not shown)"))
                    else target.readText(f.path).map(_.fold(List("(cannot be shown)"))(now => TextDiff.unified(now, text, f.path)))
                  diff.map(d => Right(Difference(List(if secret then "content (secret: not shown)" else "content") ++ fix.changes, List(write), if secret then Nil else d)))
                else IO.pure(Right(Difference(fix.changes, fix.commands.map(Action.Shell(_)), Nil)))
              case Observed.Dir(_, _, _) => IO.pure(Left(s"$path is a directory: not replaced by a file"))
              case Observed.Link(t)      => IO.pure(Left(s"$path is a symlink (to $t): not replaced by a file"))
              case _                     => IO.pure(Left(s"$path is not a regular file: not replaced"))
          case d: Directory if !d.present =>
            IO.pure(seen match
              case Observed.Absent       => Right(NoDifference)
              case Observed.Dir(_, _, _) => Right(Difference(List("removed"), List(Action.Shell(s"rmdir -- $q")), Nil))
              case _                     => Left(s"$path is not a directory: not removed"))
          case d: Directory =>
            IO.pure(seen match
              case Observed.Absent =>
                val fix = attrs(d.path, d.mode, d.owner, d.group, -1, "", "")
                Right(Difference(List("created"), List(Action.Shell((s"mkdir -p -- $q" :: fix.commands).mkString(" && "))), Nil))
              case Observed.Dir(m, o, g) =>
                val fix = attrs(d.path, d.mode, d.owner, d.group, m, o, g)
                Right(Difference(fix.changes, fix.commands.map(Action.Shell(_)), Nil))
              case _ => Left(s"$path exists and is not a directory: not replaced"))
          case s: Symlink if !s.present =>
            IO.pure(seen match
              case Observed.Absent  => Right(NoDifference)
              case Observed.Link(_) => Right(Difference(List("removed"), List(Action.Shell(s"rm -f -- $q")), Nil))
              case _                => Left(s"$path is not a symlink: not removed"))
          case s: Symlink =>
            val link = Action.Shell(s"ln -sfn -- ${quote(s.target)} $q")
            IO.pure(seen match
              case Observed.Absent                    => Right(Difference(List(s"created -> ${s.target}"), List(link), Nil))
              case Observed.Link(t) if t == s.target  => Right(NoDifference)
              case Observed.Link(t)                   => Right(Difference(List(s"target $t -> ${s.target}"), List(link), Nil))
              case _: Observed.RegularFile            => Right(Difference(List(s"a file replaced by a link to ${s.target}"), List(link), Nil))
              case _                                  => Left(s"$path is a directory or other: not replaced by a symlink"))
          case c: Command =>
            IO.pure(Right(Difference(List("run"), List(Action.Shell(c.run)), Nil)))
          case p: Package   => IO.pure(comparePackage(p, seen))
          case u: Service   => IO.pure(compareService(u, seen, check, notifiedBy))
          case u: User      => IO.pure(compareUser(u, seen))
          case g: Group     => IO.pure(compareGroup(g, seen))

  private def shellOf(changes: List[String], script: String): Either[String, Difference] =
    Right(Difference(changes, List(Action.Shell(script)), Nil))

  /** Whether an installed `version` is the wanted one: equal, or the wanted
    * one without its release part ("1.2.3" matches "1.2.3-1.el9").
    */
  private[orchestrator] def versionMatches(installed: String, wanted: String): Boolean =
    installed == wanted || installed.startsWith(wanted + "-")

  private def comparePackage(p: ApplyResource.Package, seen: Observed): Either[String, Difference] =
    val n = quote(p.name)
    seen match
      case Observed.Pkg("none", _, _) => Left("neither apt nor dnf on this node")
      case Observed.Pkg(manager, installed, versions) =>
        val apt = manager == "apt"
        val spec = if p.version.isEmpty then n else if apt then quote(s"${p.name}=${p.version}") else quote(s"${p.name}-${p.version}")
        val install =
          if apt then
            val update = if p.updateCache then "apt-get update -q && " else ""
            s"${update}DEBIAN_FRONTEND=noninteractive apt-get install -y -q -o DPkg::Lock::Timeout=300 --allow-downgrades $spec"
          else s"dnf -y -q install $spec || dnf -y -q downgrade $spec"
        if !p.present then
          if !installed then Right(NoDifference)
          else if apt then shellOf(List("removed"), s"DEBIAN_FRONTEND=noninteractive apt-get remove -y -q -o DPkg::Lock::Timeout=300 $n")
          else shellOf(List("removed"), s"dnf -y -q remove $n")
        else if !installed then shellOf(List(if p.version.isEmpty then "installed" else s"installed ${p.version}"), install)
        else if p.version.nonEmpty && !versions.exists(versionMatches(_, p.version)) then
          shellOf(List(s"version ${versions.mkString(",")} -> ${p.version}"), install)
        else Right(NoDifference)
      case other => Left(s"unexpected state: $other")

  private val Enabledish = Set("enabled", "enabled-runtime", "static", "indirect", "generated", "transient", "alias", "linked", "linked-runtime")
  private val Activeish = Set("active", "activating", "reloading")

  private def compareService(u: ApplyResource.Service, seen: Observed, check: Boolean, notifiedBy: List[String]): Either[String, Difference] =
    val n = quote(u.unit)
    seen match
      case Observed.ServiceState(enabled, active) =>
        val missing = enabled == "not-found" || enabled.isEmpty
        val masked = enabled.startsWith("masked")
        val enable: Option[Either[String, (String, String)]] = u.enabled.flatMap {
          case true if missing  => Some(Left("no such unit"))
          case true if masked   => Some(Left("the unit is masked"))
          case true if !Enabledish(enabled) => Some(Right("enabled" -> s"systemctl enable $n"))
          case false if enabled == "enabled" || enabled == "enabled-runtime" => Some(Right("disabled" -> s"systemctl disable $n"))
          case _ => None
        }
        val running = Activeish(active)
        val run: Option[Either[String, (String, String)]] = u.running.flatMap {
          case true if missing  => Some(Left("no such unit"))
          case true if masked   => Some(Left("the unit is masked"))
          case true if !running => Some(Right("started" -> s"systemctl start $n"))
          case false if running => Some(Right("stopped" -> s"systemctl stop $n"))
          case _ => None
        }
        val starting = run.exists(_.exists(_._1 == "started"))
        val restart: Option[Either[String, (String, String)]] =
          if notifiedBy.isEmpty || starting || u.running.contains(false) then None
          else
            val by = s"(notified by ${notifiedBy.mkString(", ")})"
            u.running match
              case Some(true) => Some(Right(s"restarted $by" -> s"systemctl restart $n"))
              case _          => Option.when(running)(Right(s"restarted $by" -> s"systemctl try-restart $n"))
        val steps = List(enable, run, restart).flatten
        steps.collectFirst { case Left(e) => e } match
          case Some(e) if check && e == "no such unit" =>
            // An earlier resource (a package, a unit file) may yet bring it.
            Right(Difference(List("the unit does not exist yet"), Nil, Nil))
          case Some(e) => Left(e)
          case None =>
            val ok = steps.collect { case Right(x) => x }
            if ok.isEmpty then Right(NoDifference)
            else shellOf(ok.map(_._1), ("systemctl daemon-reload" :: ok.map(_._2)).mkString(" && "))
      case other => Left(s"unexpected state: $other")

  private def compareUser(u: ApplyResource.User, seen: Observed): Either[String, Difference] =
    val n = quote(u.name)
    seen match
      case Observed.Absent if !u.present => Right(NoDifference)
      case Observed.Absent =>
        val flags = List(
          Option.when(u.uid >= 0)(s"-u ${u.uid}"),
          Option.when(u.group.nonEmpty)(s"-g ${quote(u.group)}"),
          Option.when(u.groups.nonEmpty)(s"-G ${quote(u.groups.mkString(","))}"),
          Option.when(u.home.nonEmpty)(s"-d ${quote(u.home)}"),
          Option.when(!u.system || u.home.nonEmpty)("-m"),
          Option.when(u.shell.nonEmpty)(s"-s ${quote(u.shell)}"),
          Option.when(u.system)("-r")
        ).flatten
        shellOf(List("created"), (("useradd" :: flags) :+ n).mkString(" "))
      case _: Observed.Account if !u.present => shellOf(List("removed"), s"userdel $n")
      case Observed.Account(uid, group, groups, home, shell) =>
        val missing = u.groups.filterNot(groups.contains)
        val wanted = List(
          Option.when(u.uid >= 0 && u.uid != uid)(s"uid $uid -> ${u.uid}" -> s"-u ${u.uid}"),
          Option.when(u.group.nonEmpty && u.group != group)(s"group $group -> ${u.group}" -> s"-g ${quote(u.group)}"),
          Option.when(missing.nonEmpty)(s"added to ${missing.mkString(", ")}" -> s"-a -G ${quote(missing.mkString(","))}"),
          Option.when(u.home.nonEmpty && u.home != home)(s"home $home -> ${u.home}" -> s"-d ${quote(u.home)}"),
          Option.when(u.shell.nonEmpty && u.shell != shell)(s"shell $shell -> ${u.shell}" -> s"-s ${quote(u.shell)}")
        ).flatten
        if wanted.isEmpty then Right(NoDifference)
        else shellOf(wanted.map(_._1), (("usermod" :: wanted.map(_._2)) :+ n).mkString(" "))
      case other => Left(s"unexpected state: $other")

  private def compareGroup(g: ApplyResource.Group, seen: Observed): Either[String, Difference] =
    val n = quote(g.name)
    seen match
      case Observed.Absent if !g.present => Right(NoDifference)
      case Observed.Absent =>
        val flags = List(Option.when(g.gid >= 0)(s"-g ${g.gid}"), Option.when(g.system)("-r")).flatten
        shellOf(List("created"), (("groupadd" :: flags) :+ n).mkString(" "))
      case _: Observed.GroupEntry if !g.present => shellOf(List("removed"), s"groupdel $n")
      case Observed.GroupEntry(gid) if g.gid >= 0 && g.gid != gid => shellOf(List(s"gid $gid -> ${g.gid}"), s"groupmod -g ${g.gid} $n")
      case _: Observed.GroupEntry => Right(NoDifference)
      case other => Left(s"unexpected state: $other")

  /** Applies `plan` to `target` (with `check`: only reads and compares),
    * printing each resource's line through `say`, and returns the report. A
    * resource whose `after` or notifying resource failed is skipped; with
    * `stopOnFailure`, everything after the first failure is.
    */
  def run(
      plan: ApplyPlan,
      target: ApplyTarget,
      check: Boolean,
      say: String => IO[Unit],
      stopOnFailure: Boolean = false
  ): IO[ApplyReport] =
    val rs = plan.resources
    val width = rs.map(_.id.length).foldLeft(0)(_ max _) min 60
    def line(id: String, text: String): IO[Unit] = say(s"${id.padTo(width, ' ')}  $text")

    for
      // The wanted content of each file: (text, secret), or why it cannot be had.
      contents <- rs.zipWithIndex.toList.traverse {
        case (f: ApplyResource.File, i) =>
          (f.content match
            case Some(ApplyResource.Content.Text(t))         => IO.pure((t, false))
            case Some(ApplyResource.Content.Hidden(s))       => IO.pure((s.reveal, true))
            case Some(ApplyResource.Content.Source(p, vars)) => target.source(p, vars).map((_, false))
            case None                                   => IO.pure(("", false))
          ).attempt.map(e => i -> e.leftMap(err => Option(err.getMessage).getOrElse(err.toString)))
        case (_, i) => IO.pure(i -> Right(("", false)))
      }.map(_.toMap)
      observable = rs.zipWithIndex.toList.filter {
        case (c: ApplyResource.Command, _) => c.guarded
        case _                             => true
      }.map(_.swap)
      observableIds = observable.map(_._1).toSet
      seenOrError <- target.observe(observable).attempt
      outcomes <- Ref.of[IO, Map[Int, ApplyOutcome]](Map.empty)
      _ <- plan.order.toList.traverse_ { i =>
        val r = rs(i)
        def finish(o: ApplyOutcome): IO[Unit] =
          outcomes.update(_ + (i -> o)) >> (o match
            case ApplyOutcome.Unchanged(note)        => line(r.id, if note.isEmpty then "unchanged" else s"unchanged ($note)")
            case ApplyOutcome.NotNotified            => line(r.id, "not notified")
            case ApplyOutcome.WouldChange(ch, det)   => line(r.id, describe(r, ch, would = true, plan, i)) >> det.traverse_(d => say(s"  $d"))
            case ApplyOutcome.Changed(ch, det)       => line(r.id, describe(r, ch, would = false, plan, i)) >> det.traverse_(d => say(s"  $d"))
            case ApplyOutcome.Skipped(reason)        => line(r.id, s"skipped: $reason")
            case ApplyOutcome.Failed(error)          => line(r.id, s"FAILED: $error"))
        outcomes.get.flatMap { done =>
          def badly(j: Int): Boolean = done.get(j).exists {
            case _: ApplyOutcome.Failed  => true
            case ApplyOutcome.Skipped(_) => true
            case _                  => false
          }
          val firstFailure = done.collectFirst { case (j, _: ApplyOutcome.Failed) => rs(j).id }
          val blocker = (plan.needs(i) ++ plan.notifiers(i)).toList.sorted.find(badly)
          if stopOnFailure && firstFailure.isDefined then
            finish(ApplyOutcome.Skipped(s"stopped after ${firstFailure.getOrElse("")} failed"))
          else blocker match
            case Some(j) =>
              val why = done(j) match
                case _: ApplyOutcome.Failed => "failed"
                case _                 => "was skipped"
              finish(ApplyOutcome.Skipped(s"${rs(j).id} $why"))
            case None =>
              val notifiedBy = plan.notifiers(i).toList.sorted.filter(j => done.get(j).exists {
                case _: ApplyOutcome.Changed | _: ApplyOutcome.WouldChange => true
                case _                                           => false
              })
              r match
                case c: ApplyResource.Command if c.onChangeOnly && notifiedBy.isEmpty => finish(ApplyOutcome.NotNotified)
                case _ =>
                  // After a real change, what the node holds may have moved (a package brings a unit,
                  // a user owns a file): read this resource again rather than trust the first read.
                  val again = !check && observableIds(i) && done.values.exists {
                    case _: ApplyOutcome.Changed => true
                    case _                       => false
                  }
                  def from(m: Either[Throwable, Map[Int, Observed]]): Either[String, Observed] = m match
                    case Left(err) => Left(s"could not read the node: ${Option(err.getMessage).getOrElse(err.toString)}")
                    case Right(found) => found.get(i).toRight("the node did not say")
                  val seenIO: IO[Either[String, Observed]] = r match
                    case c: ApplyResource.Command if !c.guarded => IO.pure(Right(Observed.Guard(needed = true, "")))
                    case _ if again => target.observe(List(i -> r)).attempt.map(from)
                    case _          => IO.pure(from(seenOrError))
                  seenIO.flatMap(seen => (seen, contents(i)) match
                    case (Left(e), _)                 => finish(ApplyOutcome.Failed(e))
                    case (_, Left(e))                 => finish(ApplyOutcome.Failed(s"its content: $e"))
                    case (Right(Observed.Guard(false, note)), _) => finish(ApplyOutcome.Unchanged(note))
                    case (Right(s), Right(content))   =>
                      val wanted = r match
                        case f: ApplyResource.File if f.content.nonEmpty => Some(content)
                        case _                                      => None
                      compare(r, s, wanted, target, check, notifiedBy.map(rs(_).id)).attempt.flatMap {
                        case Left(err)       => finish(ApplyOutcome.Failed(Option(err.getMessage).getOrElse(err.toString)))
                        case Right(Left(e))  => finish(ApplyOutcome.Failed(e))
                        case Right(Right(d)) if d.changes.isEmpty => finish(ApplyOutcome.Unchanged(""))
                        case Right(Right(d)) if check => finish(ApplyOutcome.WouldChange(d.changes, d.detail))
                        case Right(Right(d)) =>
                          val timeout = r match
                            case c: ApplyResource.Command => c.timeout
                            case _: ApplyResource.Package => 900
                            case _                   => 120
                          d.actions.traverse_ {
                            case Action.Write(p, t, o, g, m) => target.write(p, t, o, g, m)
                            case Action.Shell(script)        => target.shell(r.id, script, timeout)
                          }.attempt.flatMap {
                            case Right(())  => finish(ApplyOutcome.Changed(d.changes, d.detail))
                            case Left(err)  => finish(ApplyOutcome.Failed(Option(err.getMessage).getOrElse(err.toString)))
                          }
                      }
                  )
        }
      }
      done <- outcomes.get
      report = ApplyReport(target.node, plan.name, check, plan.order.toList.map(i => ResourceResult(rs(i).id, done(i))))
      _ <- say(report.summary)
    yield report

  private def describe(r: ApplyResource, changes: List[String], would: Boolean, plan: ApplyPlan, i: Int): String =
    r match
      case _: ApplyResource.Command =>
        val by = plan.notifiers(i).toList.sorted.map(plan.resources(_).id)
        val verb = if would then "would run" else "ran"
        if by.isEmpty then verb else s"$verb (notified by ${by.mkString(", ")})"
      case _ => s"${if would then "would change" else "changed"}: ${changes.mkString(", ")}"

  // ---- the shell side of a real node --------------------------------------

  private val Marker = "@@orphera-apply "

  /** One shell script that reports every resource's state, each after a
    * marker line, so that a node is read in one call.
    */
  def observeScript(resources: List[(Int, ApplyResource)]): String =
    def pathState(i: Int, path: String): String =
      s"""echo '$Marker$i'
         |p=${quote(path)}
         |if [ -L "$$p" ]; then echo type=link; echo "target=$$(readlink "$$p")"
         |elif [ -f "$$p" ]; then echo type=file; stat -c 'mode=%a owner=%U group=%G' "$$p"; echo "sha256=$$(sha256sum "$$p" | cut -d' ' -f1)"
         |elif [ -d "$$p" ]; then echo type=directory; stat -c 'mode=%a owner=%U group=%G' "$$p"
         |elif [ -e "$$p" ]; then echo type=other
         |else echo type=absent; fi""".stripMargin
    def guard(i: Int, c: ApplyResource.Command): String =
      val test =
        if c.onlyIf.nonEmpty then s"sh -c ${quote(c.onlyIf)} >/dev/null 2>&1"
        else if c.unless.nonEmpty then s"! sh -c ${quote(c.unless)} >/dev/null 2>&1"
        else s"[ ! -e ${quote(c.creates)} ]"
      s"""echo '$Marker$i'
         |if $test; then echo guard=yes; else echo guard=no; fi""".stripMargin
    def pkg(i: Int, p: ApplyResource.Package): String =
      val n = quote(p.name)
      s"""echo '$Marker$i'
         |if command -v dpkg-query >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then echo manager=apt
         |  s=$$(dpkg-query -W -f='$${db:Status-Abbrev}|$${Version}' $n 2>/dev/null)
         |  case "$$s" in ?i*) echo installed=yes; echo "versions=$${s#*|}";; *) echo installed=no;; esac
         |elif command -v rpm >/dev/null 2>&1; then echo manager=dnf
         |  if v=$$(rpm -q --qf '%{VERSION}-%{RELEASE} ' $n 2>/dev/null); then echo installed=yes; echo "versions=$$(echo $$v | tr ' ' ',')"; else echo installed=no; fi
         |else echo manager=none; fi""".stripMargin
    def unit(i: Int, u: ApplyResource.Service): String =
      val n = quote(u.unit)
      s"""echo '$Marker$i'
         |e=$$(systemctl is-enabled $n 2>/dev/null); [ -n "$$e" ] || e=not-found
         |if [ "$$e" = not-found ] && systemctl cat $n >/dev/null 2>&1; then e=static; fi
         |echo "enabled=$$e"; echo "active=$$(systemctl is-active $n 2>/dev/null)"""".stripMargin
    def user(i: Int, u: ApplyResource.User): String =
      val n = quote(u.name)
      s"""echo '$Marker$i'
         |if e=$$(getent passwd $n); then echo exists=yes
         |  echo "uid=$$(echo "$$e" | cut -d: -f3)"; echo "home=$$(echo "$$e" | cut -d: -f6)"; echo "shell=$$(echo "$$e" | cut -d: -f7)"
         |  echo "group=$$(id -gn $n)"; echo "groups=$$(id -Gn $n | tr ' ' ',')"
         |else echo exists=no; fi""".stripMargin
    def group(i: Int, g: ApplyResource.Group): String =
      val n = quote(g.name)
      s"""echo '$Marker$i'
         |if e=$$(getent group $n); then echo exists=yes; echo "gid=$$(echo "$$e" | cut -d: -f3)"; else echo exists=no; fi""".stripMargin
    ("set +e" :: resources.map {
      case (i, f: ApplyResource.File)      => pathState(i, f.path)
      case (i, d: ApplyResource.Directory) => pathState(i, d.path)
      case (i, s: ApplyResource.Symlink)   => pathState(i, s.path)
      case (i, c: ApplyResource.Command)   => guard(i, c)
      case (i, p: ApplyResource.Package)   => pkg(i, p)
      case (i, u: ApplyResource.Service)   => unit(i, u)
      case (i, u: ApplyResource.User)      => user(i, u)
      case (i, g: ApplyResource.Group)     => group(i, g)
    }).mkString("\n")

  /** The states in `observeScript`'s output. */
  def parseObserved(resources: List[(Int, ApplyResource)], lines: Seq[String]): Map[Int, Observed] =
    val blocks: Map[Int, Map[String, String]] =
      lines.foldLeft((Option.empty[Int], Map.empty[Int, Map[String, String]])) { case ((current, acc), raw) =>
        val l = raw.stripLineEnd
        if l.startsWith(Marker) then
          val i = l.drop(Marker.length).trim.toIntOption
          (i, i.fold(acc)(n => acc + (n -> Map.empty)))
        else
          current match
            case Some(i) =>
              val kvs = l.trim.split("\\s+(?=[a-z0-9]+=)").toList.flatMap { kv =>
                kv.split("=", 2) match
                  case Array(k, v) => Some(k -> v)
                  case _           => None
              }
              (current, acc.updated(i, acc(i) ++ kvs))
            case None => (current, acc)
      }._2
    resources.flatMap { (i, r) =>
      blocks.get(i).map { kv =>
        def mode = kv.get("mode").flatMap(m => Try(Integer.parseInt(m, 8)).toOption).getOrElse(-1)
        val seen = r match
          case c: ApplyResource.Command =>
            val note =
              if c.onlyIf.nonEmpty then "onlyIf: false"
              else if c.unless.nonEmpty then "unless: true"
              else s"${c.creates} exists"
            kv.get("guard") match
              case Some("yes") => Observed.Guard(needed = true, "")
              case Some("no")  => Observed.Guard(needed = false, note)
              case _           => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Package =>
            kv.get("manager") match
              case Some(m) =>
                Observed.Pkg(m, kv.get("installed").contains("yes"), kv.getOrElse("versions", "").split(",").toList.filter(_.nonEmpty))
              case None => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Service =>
            kv.get("enabled") match
              case Some(e) => Observed.ServiceState(e, kv.getOrElse("active", ""))
              case None    => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.User =>
            kv.get("exists") match
              case Some("no")  => Observed.Absent
              case Some("yes") =>
                Observed.Account(
                  kv.get("uid").flatMap(_.toIntOption).getOrElse(-1),
                  kv.getOrElse("group", ""),
                  kv.getOrElse("groups", "").split(",").toList.filter(_.nonEmpty),
                  kv.getOrElse("home", ""),
                  kv.getOrElse("shell", "")
                )
              case _ => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Group =>
            kv.get("exists") match
              case Some("no")  => Observed.Absent
              case Some("yes") => Observed.GroupEntry(kv.get("gid").flatMap(_.toIntOption).getOrElse(-1))
              case _           => Observed.Unreadable(kv.mkString(" "))
          case _ =>
            kv.get("type") match
              case Some("absent")    => Observed.Absent
              case Some("file")      => Observed.RegularFile(mode, kv.getOrElse("owner", ""), kv.getOrElse("group", ""), kv.getOrElse("sha256", ""))
              case Some("directory") => Observed.Dir(mode, kv.getOrElse("owner", ""), kv.getOrElse("group", ""))
              case Some("link")      => Observed.Link(kv.getOrElse("target", ""))
              case Some("other")     => Observed.OtherType
              case _                 => Observed.Unreadable(kv.mkString(" "))
        i -> seen
      }
    }.toMap

/** A unified-style line diff, for showing a file's change. */
object TextDiff:
  private val MaxLines = 4000
  private val MaxShown = 200

  /** `---`/`+++` header and the removed (`-`) and added (`+`) lines, with up to
    * two unchanged lines of context around each change.
    */
  def unified(before: String, after: String, path: String): List[String] =
    val a = before.linesIterator.toVector
    val b = after.linesIterator.toVector
    if a.size > MaxLines || b.size > MaxLines then List(s"(too long to show: ${a.size} -> ${b.size} lines)")
    else
      // LCS table, then walk it.
      val lcs = Array.ofDim[Int](a.size + 1, b.size + 1)
      for i <- a.indices.reverse; j <- b.indices.reverse do
        lcs(i)(j) = if a(i) == b(j) then lcs(i + 1)(j + 1) + 1 else lcs(i + 1)(j) max lcs(i)(j + 1)
      val ops = Vector.newBuilder[(Char, String)]
      var i = 0
      var j = 0
      while i < a.size || j < b.size do
        if i < a.size && j < b.size && a(i) == b(j) then { ops += (' ' -> a(i)); i += 1; j += 1 }
        // On a tie the removed line comes first, as in diff -u.
        else if i < a.size && (j == b.size || lcs(i + 1)(j) >= lcs(i)(j + 1)) then { ops += ('-' -> a(i)); i += 1 }
        else { ops += ('+' -> b(j)); j += 1 }
      val all = ops.result()
      val near = all.indices.filter(k => all(k)._1 != ' ').flatMap(k => (k - 2) to (k + 2)).toSet
      val shown = all.indices.filter(near.contains).map(k => s"${all(k)._1}${all(k)._2}").toList
      val body = if shown.size > MaxShown then shown.take(MaxShown) :+ s"... (${shown.size - MaxShown} more lines)" else shown
      s"--- $path (now)" :: s"+++ $path (wanted)" :: body
