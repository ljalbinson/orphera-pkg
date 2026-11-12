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
          case Some(_)                                       => problems += s"${r.id} notifies $t, which is not an onChangeOnly command"
      )
    }
    rs.collect { case c: ApplyResource.Command if c.onChangeOnly => c }.foreach(c =>
      if !rs.exists(_.triggers.contains(c.id)) then
        problems += s"${c.id} is onChangeOnly but nothing notifies it"
    )

    val found = problems.result()
    if found.nonEmpty then Left(found)
    else
      val needs: Map[Int, Set[Int]] =
        rs.indices.map(i => i -> rs(i).needs.flatMap(byId.get).toSet).toMap
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
      target: ApplyTarget
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
      observable = rs.zipWithIndex.collect {
        case (c: ApplyResource.Command, i) if c.guarded => (i, c: ApplyResource)
        case (f: ApplyResource.File, i)      => (i, f: ApplyResource)
        case (d: ApplyResource.Directory, i) => (i, d: ApplyResource)
        case (s: ApplyResource.Symlink, i)   => (i, s: ApplyResource)
      }.toList
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
                  val seen: Either[String, Observed] = r match
                    case c: ApplyResource.Command if !c.guarded => Right(Observed.Guard(needed = true, ""))
                    case _ =>
                      seenOrError match
                        case Left(err) => Left(s"could not read the node: ${Option(err.getMessage).getOrElse(err.toString)}")
                        case Right(m)  => m.get(i).toRight("the node did not say")
                  (seen, contents(i)) match
                    case (Left(e), _)                 => finish(ApplyOutcome.Failed(e))
                    case (_, Left(e))                 => finish(ApplyOutcome.Failed(s"its content: $e"))
                    case (Right(Observed.Guard(false, note)), _) => finish(ApplyOutcome.Unchanged(note))
                    case (Right(s), Right(content))   =>
                      val wanted = r match
                        case f: ApplyResource.File if f.content.nonEmpty => Some(content)
                        case _                                      => None
                      compare(r, s, wanted, target).attempt.flatMap {
                        case Left(err)       => finish(ApplyOutcome.Failed(Option(err.getMessage).getOrElse(err.toString)))
                        case Right(Left(e))  => finish(ApplyOutcome.Failed(e))
                        case Right(Right(d)) if d.changes.isEmpty => finish(ApplyOutcome.Unchanged(""))
                        case Right(Right(d)) if check => finish(ApplyOutcome.WouldChange(d.changes, d.detail))
                        case Right(Right(d)) =>
                          val timeout = r match
                            case c: ApplyResource.Command => c.timeout
                            case _                   => 120
                          d.actions.traverse_ {
                            case Action.Write(p, t, o, g, m) => target.write(p, t, o, g, m)
                            case Action.Shell(script)        => target.shell(r.id, script, timeout)
                          }.attempt.flatMap {
                            case Right(())  => finish(ApplyOutcome.Changed(d.changes, d.detail))
                            case Left(err)  => finish(ApplyOutcome.Failed(Option(err.getMessage).getOrElse(err.toString)))
                          }
                      }
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
    ("set +e" :: resources.map {
      case (i, f: ApplyResource.File)      => pathState(i, f.path)
      case (i, d: ApplyResource.Directory) => pathState(i, d.path)
      case (i, s: ApplyResource.Symlink)   => pathState(i, s.path)
      case (i, c: ApplyResource.Command)   => guard(i, c)
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
        else if j < b.size && (i == a.size || lcs(i)(j + 1) >= lcs(i + 1)(j)) then { ops += ('+' -> b(j)); j += 1 }
        else { ops += ('-' -> a(i)); i += 1 }
      val all = ops.result()
      val near = all.indices.filter(k => all(k)._1 != ' ').flatMap(k => (k - 2) to (k + 2)).toSet
      val shown = all.indices.filter(near.contains).map(k => s"${all(k)._1}${all(k)._2}").toList
      val body = if shown.size > MaxShown then shown.take(MaxShown) :+ s"... (${shown.size - MaxShown} more lines)" else shown
      s"--- $path (now)" :: s"+++ $path (wanted)" :: body
