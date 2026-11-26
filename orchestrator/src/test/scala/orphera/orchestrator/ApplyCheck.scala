// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import orphera.common.Event

/** Checks for declarative apply (Apply, docs/APPLY-DESIGN.md).
  *
  * Part 1 uses a fake node held in memory: plan checks and order, a first run
  * that creates, a second that changes nothing, check mode, diffs, secrets,
  * guards, notifications, failures and what they skip.
  *
  * Part 2 runs the real node side (TaskExecutor.applyOn and its shell scripts)
  * against a temporary directory on this machine: a stand-in task runner runs
  * each `sh -c` step here and writes files here, so the observe script, its
  * parsing and the change commands are the ones a node gets.
  *
  * Run with `sbt "orchestrator/Test/runMain orphera.orchestrator.ApplyCheck"`.
  */
object ApplyCheck:
  import Resources.*

  private var failures = 0
  private var passes = 0

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then passes += 1
    else
      failures += 1
      println(s"FAIL: $name ${detail}")

  // ---- a node in memory ---------------------------------------------------

  private enum Entry:
    case F(content: String, mode: Int, owner: String, group: String)
    case D(mode: Int, owner: String, group: String)
    case L(target: String)

  private class FakeNode(
      var fs: Map[String, Entry],
      guards: Map[String, Boolean] = Map.empty,
      var failing: Set[String] = Set.empty,
      var pkgs: Map[String, String] = Map.empty,
      var units: Map[String, (String, String)] = Map.empty,
      var users: Map[String, Observed.Account] = Map.empty,
      var groupsDb: Map[String, Int] = Map.empty
  ) extends ApplyTarget:
    var ran: Vector[String] = Vector.empty
    var observed: Int = 0
    def node = "fake"

    // Notifications still owed, as a node keeps them between runs.
    var owed: Set[String] = Set.empty
    var kept: Int = 0
    override def pendingNotices(plan: String): IO[Set[String]] = IO(owed)
    override def keepPendingNotices(plan: String, ids: Set[String]): IO[Unit] =
      IO { kept += 1; owed = ids }

    private def sha(t: String) =
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(t.getBytes(StandardCharsets.UTF_8))
        .map(b => f"${b & 0xff}%02x")
        .mkString

    def observe(rs: List[(Int, ApplyResource)]): IO[Map[Int, Observed]] = IO {
      observed += 1
      rs.map { (i, r) =>
        val seen = r match
          case c: ApplyResource.Command =>
            val g = if c.onlyIf.nonEmpty then guards.getOrElse(c.onlyIf, true)
            else if c.unless.nonEmpty then !guards.getOrElse(c.unless, false)
            else !fs.contains(c.creates)
            Observed.Guard(g, if g then "" else "guard says no")
          case p: ApplyResource.Package =>
            Observed.Pkg("apt", pkgs.contains(p.name), pkgs.get(p.name).toList)
          case u: ApplyResource.Service =>
            val (e, a) = units.getOrElse(u.unit, ("not-found", "inactive"))
            Observed.ServiceState(e, a)
          case u: ApplyResource.User => users.getOrElse(u.name, Observed.Absent)
          case g: ApplyResource.Group =>
            groupsDb.get(g.name).fold(Observed.Absent)(Observed.GroupEntry(_))
          case other =>
            val p = other match
              case f: ApplyResource.File      => f.path
              case d: ApplyResource.Directory => d.path
              case s: ApplyResource.Symlink   => s.path
              case _                          => ""
            fs.get(p) match
              case None                      => Observed.Absent
              case Some(Entry.F(t, m, o, g)) =>
                Observed.RegularFile(m, o, g, sha(t))
              case Some(Entry.D(m, o, g)) => Observed.Dir(m, o, g)
              case Some(Entry.L(t))       => Observed.Link(t)
        i -> seen
      }.toMap
    }

    def readText(path: String): IO[Option[String]] = IO(
      fs.get(path).collect { case Entry.F(t, _, _, _) => t }
    )

    def source(path: String, vars: Map[String, String]): IO[String] =
      IO(Templating.render(s"source $path for {{name}}", vars))

    def render(text: String, vars: Map[String, String]): IO[String] =
      IO(
        Templating.render(
          text,
          Map("node" -> node, "default_interface" -> "eth9") ++ vars
        )
      )

    def write(
        path: String,
        content: String,
        owner: String,
        group: String,
        mode: Int
    ): IO[Unit] = IO {
      ran :+= s"write $path"
      val old = fs.get(path).collect { case e: Entry.F => e }
      fs += path -> Entry.F(
        content,
        if mode > 0 then mode else old.fold(0x1a4 /* 0644 */ )(_.mode),
        if owner.nonEmpty then owner else old.fold("root")(_.owner),
        if group.nonEmpty then group else old.fold("root")(_.group)
      )
    }

    private val Q = "'((?:[^']|'\\\\'')*)'"
    private def unq(s: String) = s.replace("'\\''", "'")
    private val Rm = s"rm -f -- $Q".r
    private val Mkdir = s"mkdir -p -- $Q.*".r
    private val Rmdir = s"rmdir -- $Q".r
    private val Ln = s"ln -sfn -- $Q $Q".r
    private val Chmod = s"chmod ([0-7]+) $Q".r
    private val AptInstall = s".*apt-get install .* $Q".r
    private val AptRemove = s".*apt-get remove .* $Q".r
    private val Systemctl = s"systemctl ([a-z-]+) $Q".r
    private val UserAdd = s"useradd (.*)$Q".r
    private val UserMod = s"usermod (.*)$Q".r
    private val UserDel = s"userdel $Q".r
    private val GroupAdd = s"groupadd (.*)$Q".r
    private val GroupDel = s"groupdel $Q".r
    private def flag(flags: String, f: String): Option[String] =
      s"$f (?:$Q|(\\S+))".r
        .findFirstMatchIn(flags)
        .map(m => Option(m.group(1)).map(unq).getOrElse(m.group(2)))

    def shell(label: String, script: String, timeout: Int): IO[Unit] =
      IO {
        ran :+= s"$label: $script"
        if failing.contains(label) then
          throw new RuntimeException(s"$label failed on purpose")
        script.split(" && ").toList.foreach {
          case Rm(p)    => fs -= unq(p)
          case Mkdir(p) =>
            fs += unq(p) -> Entry.D(0x1ed /* 0755 */, "root", "root")
          case Rmdir(p)    => fs -= unq(p)
          case Ln(t, p)    => fs += unq(p) -> Entry.L(unq(t))
          case Chmod(m, p) =>
            val mode = Integer.parseInt(m, 8)
            fs = fs.updatedWith(unq(p)) {
              case Some(e: Entry.F) => Some(e.copy(mode = mode))
              case Some(e: Entry.D) => Some(e.copy(mode = mode))
              case other            => other
            }
          case AptInstall(spec) =>
            unq(spec).split("=", 2) match
              case Array(n, v) => pkgs += n -> v
              case other       => pkgs += other.mkString -> "1.0-1"
          case AptRemove(n)       => pkgs -= unq(n)
          case Systemctl(verb, u) =>
            val (e, a) = units.getOrElse(unq(u), ("not-found", "inactive"))
            if e == "not-found" then
              throw new RuntimeException(s"no unit ${unq(u)}")
            units += unq(u) -> (verb match
              case "enable"            => ("enabled", a)
              case "disable"           => ("disabled", a)
              case "start" | "restart" => (e, "active")
              case "stop"              => (e, "inactive")
              case _                   => (e, a))
          case UserAdd(flags, n) =>
            val name = unq(n)
            users += name -> Observed.Account(
              flag(flags, "-u").flatMap(_.toIntOption).getOrElse(1001),
              flag(flags, "-g").getOrElse(name),
              flag(flags, "-G").fold(List.empty[String])(_.split(",").toList),
              flag(flags, "-d").getOrElse(s"/home/$name"),
              flag(flags, "-s").getOrElse("/bin/sh")
            )
          case UserMod(flags, n) =>
            users = users.updatedWith(unq(n))(_.map { a =>
              a.copy(
                shell = flag(flags, "-s").getOrElse(a.shell),
                groups = a.groups ++ flag(flags, "-a -G")
                  .fold(List.empty[String])(_.split(",").toList)
              )
            })
          case UserDel(n)         => users -= unq(n)
          case GroupAdd(flags, n) =>
            groupsDb += unq(n) -> flag(flags, "-g")
              .flatMap(_.toIntOption)
              .getOrElse(1001)
          case GroupDel(n) => groupsDb -= unq(n)
          case _           => () // a command: recorded in `ran`
        }
      }

  // A run's report and the lines it printed.
  private final case class Ran(report: ApplyReport, lines: Vector[String])

  private def applyTo(
      t: ApplyTarget,
      rs: List[ApplyResource],
      check: Boolean = false,
      stop: Boolean = false
  ): Ran =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      p <- Apply.plan("t", rs)
      r <- Apply.run(p, t, check, s => lines.update(_ :+ s), stop)
      ls <- lines.get
    yield Ran(r, ls)).unsafeRunSync()

  private def outcome(r: ApplyReport, id: String): Option[ApplyOutcome] =
    r.results.find(_.id == id).map(_.outcome)

  private def problems(rs: List[ApplyResource]): List[String] =
    ApplyPlan("t", rs).left.getOrElse(Nil)

  private def planChecks(): Unit =
    val restart = command("restart", "systemctl restart x", onChangeOnly = true)
    check(
      "a good description has no problems",
      problems(List(file("/etc/x", "x").notifies(restart), restart)).isEmpty,
      problems(List(file("/etc/x", "x").notifies(restart), restart)).toString
    )
    check(
      "a name used twice",
      problems(List(file("/etc/x", "a"), file("/etc/x", "b")))
        .exists(_.contains("file[/etc/x] is described 2 times"))
    )
    check(
      "a relative path",
      problems(List(file("etc/x", "a"))).exists(_.contains("must be absolute"))
    )
    check(
      "a bad mode",
      problems(List(file("/etc/x", "a", mode = "0999")))
        .exists(_.contains("not an octal mode"))
    )
    check(
      "an unguarded command",
      problems(List(command("go", "true")))
        .exists(_.contains("would run every time"))
    )
    check(
      "two guards",
      problems(List(command("go", "true", onlyIf = "a", unless = "b")))
        .exists(_.contains("one of onlyIf"))
    )
    check(
      "a gate with no guard",
      problems(List(gate("g"))).exists(_.contains("a gate needs onlyIf")),
      problems(List(gate("g"))).toString
    )
    check(
      "a gate with a guard, and a file after it, is a good description",
      problems(
        List(
          gate("g", onlyIf = "test -e /x"),
          file("/etc/x", "a").afterIds("command[g]")
        )
      ).isEmpty
    )
    check(
      "after something not described",
      problems(List(file("/etc/x", "a").afterIds("file[/nope]")))
        .exists(_.contains("which is not described"))
    )
    check(
      "notifying a file",
      problems(List(file("/a", "a").notifiesIds("file[/b]"), file("/b", "b")))
        .exists(_.contains("not a service or an onChangeOnly command"))
    )
    check(
      "onChangeOnly with nothing notifying it",
      problems(List(restart)).exists(_.contains("nothing notifies it"))
    )
    val a = file("/a", "a")
    val b = file("/b", "b").after(a)
    check(
      "a cycle",
      problems(List(a.afterIds("file[/b]"), b))
        .exists(_.contains("wait for each other"))
    )
    // Order: declaration order, except what `after` and notification require.
    val plan = ApplyPlan(
      "t",
      List(
        restart,
        file("/z", "z").after(file("/y", "y")),
        file("/y", "y"),
        file("/x", "x").notifies(restart)
      )
    )
    check(
      "order: declaration order unless after/notify say otherwise",
      plan.toOption
        .map(p => p.order.map(p.resources(_).id).toList)
        .contains(List("file[/y]", "file[/z]", "file[/x]", "command[restart]")),
      plan.toString
    )

  private def runChecks(): Unit =
    val clean = command("clean", "dnf clean metadata -q", onChangeOnly = true)
    def desc = List(
      directory("/etc/app", mode = "0750"),
      file("/etc/app/app.conf", "a = 1\nb = 2\n", mode = "0640")
        .after(directory("/etc/app"))
        .notifies(clean),
      symlink("/etc/app/current", "/etc/app/app.conf"),
      clean
    )
    val n = new FakeNode(Map.empty)
    val ranFirst = applyTo(n, desc)
    val first = ranFirst.report
    val lines1 = ranFirst.lines
    check(
      "first run: everything changed",
      first.results.forall(_.changed) && !first.failed,
      first.toString
    )
    check(
      "first run: one read, then each resource again once something changed",
      n.observed == 3,
      n.observed.toString
    )
    check(
      "first run: the directory got its mode",
      n.fs.get("/etc/app").contains(Entry.D(0x1e8, "root", "root")),
      n.fs.toString
    )
    check(
      "first run: the notified command ran last",
      n.ran.lastOption.exists(_.contains("dnf clean")),
      n.ran.toString
    )
    check(
      "first run: the plan line names who notified",
      lines1.exists(l =>
        l.contains("command[clean]") && l.contains(
          "ran (notified by file[/etc/app/app.conf])"
        )
      ),
      lines1.mkString("\n")
    )
    check(
      "first run: summary",
      lines1.lastOption.contains("4 changed, 0 unchanged"),
      lines1.lastOption.toString
    )

    n.ran = Vector.empty
    val ranSecond = applyTo(n, desc)
    val second = ranSecond.report
    val lines2 = ranSecond.lines
    check(
      "second run: nothing changed",
      !second.changed && n.ran.isEmpty,
      second.toString + n.ran
    )
    check(
      "second run: the command was not notified",
      outcome(second, "command[clean]").contains(ApplyOutcome.NotNotified)
    )
    check(
      "second run: summary",
      lines2.lastOption.contains("0 changed, 4 unchanged"),
      lines2.lastOption.toString
    )

    // A changed content: check mode shows it and changes nothing.
    val changed = desc.updated(
      1,
      file("/etc/app/app.conf", "a = 1\nb = 3\n", mode = "0640")
        .after(directory("/etc/app"))
        .notifies(clean)
    )
    val before = n.fs
    val ranPlan = applyTo(n, changed, check = true)
    val plan = ranPlan.report
    val lines3 = ranPlan.lines
    check(
      "check: nothing changed on the node",
      n.fs == before && n.ran.isEmpty,
      n.ran.toString
    )
    check(
      "check: the file would change",
      outcome(plan, "file[/etc/app/app.conf]").exists(
        _.isInstanceOf[ApplyOutcome.WouldChange]
      )
    )
    check(
      "check: the command would run",
      lines3.exists(
        _.contains("would run (notified by file[/etc/app/app.conf])")
      ),
      lines3.mkString("\n")
    )
    check(
      "check: a diff of the content",
      lines3.contains("  -b = 2") && lines3.contains("  +b = 3"),
      lines3.mkString("\n")
    )
    check(
      "check: summary",
      lines3.lastOption.contains(
        "2 to change, 2 unchanged (check: nothing changed)"
      ),
      lines3.lastOption.toString
    )

    // Only the mode differs: chmod, not a rewrite.
    n.fs = n.fs.updated(
      "/etc/app/app.conf",
      Entry.F("a = 1\nb = 2\n", 0x1a4, "root", "root")
    )
    n.ran = Vector.empty
    val modeOnly = applyTo(n, desc).report
    check(
      "mode only: chmod, not a write",
      n.ran.exists(_.contains("chmod 0640")) && !n.ran.exists(
        _.startsWith("write")
      ),
      n.ran.toString
    )
    check(
      "mode only: says what",
      outcome(modeOnly, "file[/etc/app/app.conf]").contains(
        ApplyOutcome.Changed(List("mode 0644 -> 0640"), Nil)
      ),
      modeOnly.toString
    )

    // seedFile: written when missing, never replaced.
    val seedNew = new FakeNode(Map.empty)
    applyTo(seedNew, List(seedFile("/etc/app/list.txt", "a\nb\n", mode = "0644")))
    check(
      "seedFile: written when missing",
      seedNew.ran.contains("write /etc/app/list.txt"),
      seedNew.ran.toString
    )
    val seedOld = new FakeNode(
      Map("/etc/app/list.txt" -> Entry.F("mine\n", 0x1a4, "root", "root"))
    )
    val seedKept = applyTo(
      seedOld,
      List(seedFile("/etc/app/list.txt", "a\nb\n", mode = "0644"))
    ).report
    check(
      "seedFile: an existing file's content is left alone",
      !seedOld.ran.exists(_.startsWith("write")) &&
        outcome(seedKept, "file[/etc/app/list.txt]").exists(
          _.isInstanceOf[ApplyOutcome.Unchanged]
        ),
      seedOld.ran.toString
    )

    // A secret is never shown.
    val sn = new FakeNode(
      Map("/etc/key" -> Entry.F("old secret", 0x180, "root", "root"))
    )
    val slines = applyTo(
      sn,
      List(secretFile("/etc/key", new Secret("new secret"))),
      check = true
    ).lines
    check(
      "secret: content not shown",
      slines.exists(_.contains("content (secret: not shown)")) && !slines
        .exists(l => l.contains("new secret") || l.contains("old secret")),
      slines.mkString("\n")
    )

    // Guards.
    val gn = new FakeNode(
      Map("/opt/done" -> Entry.F("", 0x1a4, "root", "root")),
      guards = Map("test -e /x" -> false)
    )
    val guarded = applyTo(
      gn,
      List(
        command("only if", "echo a", onlyIf = "test -e /x"),
        command("creates", "echo b", creates = "/opt/done"),
        command("unless", "echo c", unless = "test -e /x")
      )
    ).report
    check(
      "guards: onlyIf false and creates existing leave it unchanged",
      outcome(guarded, "command[only if]").exists(
        _.isInstanceOf[ApplyOutcome.Unchanged]
      ) && outcome(guarded, "command[creates]").exists(
        _.isInstanceOf[ApplyOutcome.Unchanged]
      ),
      guarded.toString
    )
    check(
      "guards: unless false runs it",
      outcome(guarded, "command[unless]").exists(
        _.isInstanceOf[ApplyOutcome.Changed]
      ) && gn.ran.exists(_.contains("echo c")),
      gn.ran.toString
    )

    // Gates: what comes after a gate that answers no is skipped, the rest runs.
    val yesNode = new FakeNode(Map.empty, guards = Map("is-resolved" -> true))
    val yes = applyTo(
      yesNode,
      List(
        gate("resolved", onlyIf = "is-resolved"),
        file("/etc/resolved.conf", "a").afterIds("command[resolved]")
      )
    ).report
    check(
      "gate: a yes leaves the gate unchanged and applies what follows",
      outcome(yes, "command[resolved]").contains(ApplyOutcome.Unchanged(""))
        && outcome(yes, "file[/etc/resolved.conf]").exists(
          _.isInstanceOf[ApplyOutcome.Changed]
        ) && yesNode.fs.contains("/etc/resolved.conf")
        && yesNode.ran == Vector("write /etc/resolved.conf"),
      yes.toString + yesNode.ran.toString
    )
    val noNode = new FakeNode(Map.empty, guards = Map("is-resolved" -> false))
    val unitRestart = service("resolved", Some(false), Some(true))
    val no = applyTo(
      noNode,
      List(
        gate("resolved", onlyIf = "is-resolved"),
        file("/etc/resolved.conf", "a")
          .afterIds("command[resolved]")
          .notifies(unitRestart),
        unitRestart,
        file("/etc/always.conf", "b")
      )
    )
    check(
      "gate: a no skips the gate and what follows it, and what it notifies",
      outcome(no.report, "command[resolved]").contains(
        ApplyOutcome.Skipped("not for this node (guard says no)")
      ) && outcome(no.report, "file[/etc/resolved.conf]").contains(
        ApplyOutcome.Skipped("command[resolved] was skipped")
      ) && outcome(no.report, "service[resolved]").contains(
        ApplyOutcome.Skipped("file[/etc/resolved.conf] was skipped")
      ),
      no.report.toString
    )
    check(
      "gate: a no changes nothing, the rest still runs, and it is not a failure",
      !noNode.fs.contains("/etc/resolved.conf") && noNode.fs.contains(
        "/etc/always.conf"
      ) && !no.report.failed
        && noNode.ran == Vector("write /etc/always.conf")
        && no.lines.lastOption.contains("1 changed, 0 unchanged, 3 skipped"),
      no.lines.mkString("\n")
    )
    val unlessNode =
      new FakeNode(Map.empty, guards = Map("has-unit" -> true))
    val unlessRun = applyTo(
      unlessNode,
      List(
        gate("no unit", unless = "has-unit"),
        file("/etc/static.conf", "s").afterIds("command[no unit]")
      )
    ).report
    check(
      "gate: unless true (the test passes) is a no",
      outcome(unlessRun, "file[/etc/static.conf]").contains(
        ApplyOutcome.Skipped("command[no unit] was skipped")
      ) && !unlessNode.fs.contains("/etc/static.conf"),
      unlessRun.toString
    )
    val checkRun = applyTo(
      new FakeNode(Map.empty, guards = Map("is-resolved" -> false)),
      List(
        gate("resolved", onlyIf = "is-resolved"),
        file("/etc/resolved.conf", "a").afterIds("command[resolved]")
      ),
      check = true
    ).report
    check(
      "gate: with check, a no also skips what follows",
      outcome(checkRun, "file[/etc/resolved.conf]").contains(
        ApplyOutcome.Skipped("command[resolved] was skipped")
      ),
      checkRun.toString
    )

    // Failures: a directory where a file should be; what comes after it is skipped, the rest runs.
    val restart =
      command("restart", "systemctl restart app", onChangeOnly = true)
    val fn = new FakeNode(
      Map("/etc/app.conf" -> Entry.D(0x1ed, "root", "root"))
    )
    val ranFailed = applyTo(
      fn,
      List(
        file("/etc/app.conf", "x").notifies(restart),
        file("/etc/other", "y").afterIds("file[/etc/app.conf]"),
        file("/etc/third", "z"),
        restart
      )
    )
    val failed = ranFailed.report
    val flines = ranFailed.lines
    check(
      "failure: the file fails",
      outcome(failed, "file[/etc/app.conf]").exists(
        _.isInstanceOf[ApplyOutcome.Failed]
      ) && failed.failed,
      failed.toString
    )
    check(
      "failure: what comes after it is skipped",
      outcome(failed, "file[/etc/other]").contains(
        ApplyOutcome.Skipped("file[/etc/app.conf] failed")
      ),
      failed.toString
    )
    check(
      "failure: what it notifies is skipped",
      outcome(failed, "command[restart]").contains(
        ApplyOutcome.Skipped("file[/etc/app.conf] failed")
      ),
      failed.toString
    )
    check(
      "failure: the rest still runs",
      outcome(failed, "file[/etc/third]").exists(
        _.isInstanceOf[ApplyOutcome.Changed]
      ),
      failed.toString
    )
    check(
      "failure: summary counts it",
      flines.lastOption.contains("1 changed, 0 unchanged, 2 skipped, 1 failed"),
      flines.lastOption.toString
    )
    val sn2 = new FakeNode(
      Map("/etc/app.conf" -> Entry.D(0x1ed, "root", "root"))
    )
    val stopped = applyTo(
      sn2,
      List(file("/etc/app.conf", "x"), file("/etc/third", "z")),
      stop = true
    ).report
    check(
      "stop on failure: the rest is skipped",
      outcome(stopped, "file[/etc/third]").contains(
        ApplyOutcome.Skipped("stopped after file[/etc/app.conf] failed")
      ),
      stopped.toString
    )

    // A change that fails.
    val cn = new FakeNode(Map.empty, failing = Set("command[boom]"))
    val boom = applyTo(
      cn,
      List(
        command("boom", "false", creates = "/nope"),
        file("/after", "x").afterIds("command[boom]")
      )
    ).report
    check(
      "a failing change: failed, and what follows skipped",
      outcome(boom, "command[boom]").exists(
        _.isInstanceOf[ApplyOutcome.Failed]
      ) && outcome(boom, "file[/after]").exists(
        _.isInstanceOf[ApplyOutcome.Skipped]
      ),
      boom.toString
    )

    // Removal.
    val rn = new FakeNode(
      Map(
        "/old" -> Entry.F("x", 0x1a4, "root", "root"),
        "/oldlink" -> Entry.L("/x"),
        "/olddir" -> Entry.D(0x1ed, "root", "root")
      )
    )
    val removed = applyTo(
      rn,
      List(
        absentFile("/old"),
        absentSymlink("/oldlink"),
        absentDirectory("/olddir"),
        absentFile("/never")
      )
    ).report
    check(
      "removal: removed, and absent left alone",
      rn.fs.isEmpty && outcome(removed, "file[/never]").exists(
        _.isInstanceOf[ApplyOutcome.Unchanged]
      ),
      rn.fs.toString + removed
    )
    check(
      "removal: a directory is not removed as a file",
      applyTo(
        new FakeNode(Map("/d" -> Entry.D(0x1ed, "r", "r"))),
        List(absentFile("/d"))
      ).report.failed
    )

    // A template source is rendered with its vars.
    val tn = new FakeNode(Map.empty)
    applyTo(tn, List(fileFrom("/etc/t", "t.mustache", Map("name" -> "tst8"))))
    check(
      "fileFrom: rendered with vars",
      tn.fs.get("/etc/t").exists {
        case Entry.F(t, _, _, _) => t == "source t.mustache for tst8";
        case _                   => false
      },
      tn.fs.toString
    )

    // Inline template text is rendered with the node's vars, then compared as
    // content, so a second run changes nothing.
    val tt = new FakeNode(Map.empty)
    val tplPlan = List(
      fileTemplate(
        "/etc/k.conf",
        "iface {{default_interface}} on {{node}} peer {{peer}}",
        Map("peer" -> "10.0.0.4"),
        mode = "0644"
      )
    )
    val tplFirst = applyTo(tt, tplPlan)
    check(
      "fileTemplate: rendered with the node's vars",
      tt.fs.get("/etc/k.conf").exists {
        case Entry.F(t, _, _, _) => t == "iface eth9 on fake peer 10.0.0.4"
        case _                   => false
      },
      tt.fs.toString
    )
    check("fileTemplate: first run changed", tplFirst.report.changed)
    check(
      "fileTemplate: second run changed nothing",
      !applyTo(tt, tplPlan).report.changed
    )

  // A file changes and the service it restarts is skipped because a step before the service fails:
  // the restart is owed, kept, and delivered by the next run although the file no longer differs.
  private def owedChecks(): Unit =
    def desc(conf: String) =
      val prep = command("prep", "prep-script", unless = "prep-done")
      List(
        file("/etc/app.conf", conf).notifiesIds("service[app.service]"),
        prep,
        service("app.service").after(prep)
      )
    val n = new FakeNode(
      Map.empty,
      failing = Set("command[prep]"),
      units = Map("app.service" -> ("enabled", "active"))
    )
    val first = applyTo(n, desc("a\n"))
    check(
      "owed: the service is skipped when its step fails",
      outcome(first.report, "service[app.service]").contains(
        ApplyOutcome.Skipped("command[prep] failed")
      ),
      first.lines.mkString("\n")
    )
    check(
      "owed: the skipped restart is kept and said",
      n.owed == Set("service[app.service]") && first.lines.exists(
        _.contains("still owed a notification")
      ),
      n.owed.toString + first.lines.mkString("\n")
    )
    n.failing = Set.empty
    val keptBefore = n.kept
    val planned = applyTo(n, desc("a\n"), check = true)
    check(
      "owed: check says who notifies and records nothing",
      planned.lines.exists(l =>
        l.contains("service[app.service]") && l.contains(
          "restarted (notified by an earlier run)"
        )
      ) && n.kept == keptBefore && n.owed == Set("service[app.service]"),
      planned.lines.mkString("\n")
    )
    n.ran = Vector.empty
    val third = applyTo(n, desc("a\n"))
    check(
      "owed: the next run restarts it, though the file is unchanged",
      outcome(third.report, "file[/etc/app.conf]").exists(
        _.isInstanceOf[ApplyOutcome.Unchanged]
      ) && third.lines.exists(l =>
        l.contains("service[app.service]") && l.contains(
          "restarted (notified by an earlier run)"
        )
      ) && n.ran.exists(_.contains("systemctl restart 'app.service'")),
      third.lines.mkString("\n") + n.ran
    )
    check("owed: and the record is cleared", n.owed.isEmpty, n.owed.toString)
    n.ran = Vector.empty
    val fourth = applyTo(n, desc("a\n"))
    check(
      "owed: after that nothing is restarted",
      !n.ran.exists(_.contains("systemctl restart")) && !fourth.lines.exists(
        _.contains("restarted")
      ),
      fourth.lines.mkString("\n") + n.ran
    )

  private def kindChecks(): Unit =
    // Plans.
    check(
      "a bad package name",
      problems(List(pkg("no spaces"))).exists(_.contains("not a package name"))
    )
    check(
      "a bad user name",
      problems(List(user("Bad Name"))).exists(_.contains("not a user name"))
    )
    check(
      "a service with nothing to do",
      problems(List(service("x.service", enabled = None, running = None)))
        .exists(_.contains("says nothing about it"))
    )
    check(
      "a service may be notified",
      problems(
        List(
          file("/etc/x", "x").notifiesIds("service[x.service]"),
          service("x.service")
        )
      ).isEmpty
    )
    val implied = ApplyPlan(
      "t",
      List(
        file("/srv/f", "x", owner = "app", group = "app"),
        user("app", group = "app"),
        group("app")
      )
    )
    check(
      "order: a group before its users, a user before the files it owns",
      implied.toOption
        .map(p => p.order.map(p.resources(_).id).toList)
        .contains(List("group[app]", "user[app]", "file[/srv/f]")),
      implied.toString
    )

    // A package, its config, its service, restarted on a config change.
    def desc(conf: String) = List(
      group("app", gid = 990, system = true),
      user(
        "app",
        group = "app",
        groups = List("adm"),
        shell = "/usr/sbin/nologin",
        system = true
      ),
      pkg("app-server", updateCache = true),
      file("/etc/app.conf", conf, owner = "app")
        .after(pkg("app-server"))
        .notifiesIds("service[app.service]"),
      service("app.service").after(pkg("app-server"))
    )
    val n = new FakeNode(
      Map.empty,
      users = Map(
        "root" -> Observed.Account(0, "root", Nil, "/root", "/bin/bash")
      )
    )
    val planned = applyTo(n, desc("a\n"), check = true)
    check(
      "kinds check: all would change",
      planned.report.results.forall(_.changed),
      planned.lines.mkString("\n")
    )
    check(
      "kinds check: a unit that a package will bring",
      planned.lines.exists(l =>
        l.contains("service[app.service]") && l.contains(
          "the unit does not exist yet"
        )
      ),
      planned.lines.mkString("\n")
    )
    // Installing the package brings its unit, disabled and stopped.
    val withUnit = new FakeNode(Map.empty) {
      override def shell(
          label: String,
          script: String,
          timeout: Int
      ): IO[Unit] =
        super.shell(label, script, timeout) >> IO {
          if script.contains("apt-get install") then
            units += "app.service" -> ("disabled", "inactive")
        }
    }
    val first = applyTo(withUnit, desc("a\n"))
    check(
      "kinds first run: nothing failed",
      !first.report.failed,
      first.lines.mkString("\n")
    )
    check(
      "kinds first run: group then user",
      withUnit.groupsDb.get("app").contains(990) && withUnit.users
        .get("app")
        .exists(a => a.group == "app" && a.groups == List("adm")),
      withUnit.users.toString
    )
    check(
      "kinds first run: apt-get update then install",
      withUnit.ran.exists(r =>
        r.contains("apt-get update -q && ") && r.contains("'app-server'")
      ),
      withUnit.ran.toString
    )
    check(
      "kinds first run: the service enabled and started, not restarted",
      withUnit.units
        .get("app.service")
        .contains(("enabled", "active")) && first.lines.exists(l =>
        l.contains("service[app.service]") && l.contains(
          "enabled, started"
        ) && !l.contains("restarted")
      ),
      first.lines.mkString("\n")
    )
    withUnit.ran = Vector.empty
    val second = applyTo(withUnit, desc("a\n"))
    check(
      "kinds second run: nothing changed",
      !second.report.changed && withUnit.ran.isEmpty,
      second.lines.mkString("\n") + withUnit.ran
    )
    val third = applyTo(withUnit, desc("b\n"))
    check(
      "kinds config change: restarted",
      third.lines.exists(
        _.contains("restarted (notified by file[/etc/app.conf])")
      ) && withUnit.ran.exists(_.contains("systemctl restart 'app.service'")),
      third.lines.mkString("\n")
    )
    val pinned = applyTo(
      withUnit,
      List(pkg("app-server", version = "2.0-1")),
      check = true
    )
    check(
      "a version differs",
      pinned.lines.exists(_.contains("version 1.0-1 -> 2.0-1")),
      pinned.lines.mkString("\n")
    )
    check(
      "a version without its release matches",
      Apply.versionMatches("2.0-1.el9", "2.0") && !Apply.versionMatches(
        "2.01",
        "2.0"
      )
    )
    val more =
      applyTo(withUnit, List(user("app", groups = List("adm", "ceph"))))
    check(
      "supplementary groups are added, not replaced",
      withUnit.users
        .get("app")
        .exists(_.groups == List("adm", "ceph")) && more.lines.exists(
        _.contains("added to ceph")
      ),
      more.lines.mkString("\n")
    )
    val gone = applyTo(
      withUnit,
      List(
        service("app.service", enabled = Some(false), running = Some(false)),
        absentPkg("app-server"),
        absentUser("app"),
        absentGroup("app")
      )
    )
    check(
      "removal of the four kinds",
      withUnit.units
        .get("app.service")
        .contains(
          ("disabled", "inactive")
        ) && withUnit.pkgs.isEmpty && withUnit.users.isEmpty && withUnit.groupsDb.isEmpty,
      gone.lines.mkString("\n")
    )
    val masked = new FakeNode(
      Map.empty,
      units = Map("m.service" -> ("masked", "inactive"))
    )
    check(
      "a masked unit fails",
      applyTo(masked, List(service("m.service"))).report.failures
        .exists(_.contains("masked"))
    )
    val missing = new FakeNode(Map.empty)
    check(
      "a unit that does not exist fails in a real run",
      applyTo(missing, List(service("none.service"))).report.failures
        .exists(_.contains("no such unit"))
    )

  private def newKindChecks(): Unit =
    check(
      "new kinds: a good description has no problems",
      problems(
        List(
          kernelModule("br_netfilter"),
          sysctl("net.bridge.bridge-nf-call-iptables", "1")
            .afterIds("kernel_module[br_netfilter]"),
          lineInFile(
            "/etc/ssh/sshd_config",
            "PermitRootLogin no",
            "^PermitRootLogin"
          ),
          user("alice"),
          authorizedKey("alice", "ssh-ed25519 AAAAC3Nza alice@x"),
          mount("/mnt/data", "UUID=abc", "ext4")
        )
      ).isEmpty
    )
    check(
      "new kinds: removing a key, then its user and group, is a valid order", {
        val key = absentAuthorizedKey("alice", "ssh-ed25519 AAAAC3Nza alice@x")
        val gone = absentUser("alice").after(key)
        problems(List(key, gone, absentGroup("alice").after(gone))).isEmpty
      }
    )
    check(
      "new kinds: bad sysctl key, module, path, key and mount are named",
      problems(
        List(
          sysctl("bad key", "1"),
          sysctl("net.x", ""),
          kernelModule("a b"),
          lineInFile("relative", "x"),
          lineInFile("/etc/x", "a\nb"),
          authorizedKey("alice", "justonefield"),
          mount("/mnt/x", "", "ext4"),
          mount("/mnt/y", "/dev/sdb 1", "ext4")
        )
      ).size >= 8
    )
    val parsed = yaml(
      """name: kinds
        |resources:
        |  - kernel_module: br_netfilter
        |  - sysctl: net.ipv4.ip_forward
        |    value: 1
        |    requires: ["kernel_module[br_netfilter]"]
        |  - line: "PermitRootLogin no"
        |    path: /etc/ssh/sshd_config
        |    match: "^#?PermitRootLogin"
        |  - line: "old"
        |    path: /etc/x
        |    state: absent
        |  - user: alice
        |  - authorized_key: alice
        |    key: ssh-ed25519 AAAAC3Nza alice@x
        |  - mount: /mnt/data
        |    device: UUID=abc
        |    fstype: ext4
        |    options: noatime
        |    mounted: any
        |  - mount: /mnt/old
        |    state: absent
        |""".stripMargin
    )
    check(
      "new kinds: YAML reads them",
      parsed.map(_.resources.map(_.id)) == Right(
        List(
          "kernel_module[br_netfilter]",
          "sysctl[net.ipv4.ip_forward]",
          "line[/etc/ssh/sshd_config: PermitRootLogin no]",
          "line[/etc/x: old]",
          "user[alice]",
          "authorized_key[alice: ssh-ed25519 ...AAAC3Nza alice@x]",
          "mount[/mnt/data]",
          "mount[/mnt/old]"
        )
      ),
      parsed.toString
    )
    check(
      "new kinds: YAML mount options and mounted: any",
      parsed.exists(
        _.resources.contains(
          ApplyResource.Mount(
            "/mnt/data",
            "UUID=abc",
            "ext4",
            "noatime",
            None,
            present = true
          )
        )
      )
    )
    check(
      "new kinds: sysctl in YAML needs a value; state absent is refused",
      yamlError("name: x\nresources:\n  - sysctl: a.b\n").contains("value") &&
        yamlError(
          "name: x\nresources:\n  - sysctl: a.b\n    value: 1\n    state: absent\n"
        ).contains("absent") &&
        yamlError("name: x\nresources:\n  - line: x\n").contains("path")
    )

  private def yaml(text: String): Either[String, ApplyDescription] =
    io.circe.yaml.parser
      .parse(text)
      .left
      .map(_.getMessage)
      .flatMap(ApplyYaml.decode)

  private def yamlError(text: String): String = yaml(text).left.getOrElse("")

  private def yamlChecks(): Unit =
    val full = yaml(
      """name: all-kinds
        |nodes: [tst5, tst6]
        |resources:
        |  - file: /etc/a.conf
        |    content: "a = 1\n"
        |    mode: "0640"
        |    owner: app
        |    group: app
        |    notify: ["service[app.service]"]
        |  - file: /etc/b.conf
        |    source: files/b.conf.mustache
        |    vars: {port: 8080}
        |  - file: /etc/old
        |    state: absent
        |  - directory: /srv/app
        |    mode: "0750"
        |  - symlink: /srv/current
        |    target: /srv/app
        |  - package: app-server
        |    version: 2.0
        |    update_cache: true
        |  - package: telnet
        |    state: absent
        |  - service: app.service
        |    running: any
        |  - user: app
        |    group: app
        |    groups: [adm]
        |    system: true
        |  - group: app
        |    gid: 990
        |  - command: reload
        |    run: systemctl daemon-reload
        |    unless: test -e /x
        |    requires: ["file[/etc/a.conf]", "directory[/srv/app]"]
        |""".stripMargin
    )
    val expected = List(
      file(
        "/etc/a.conf",
        "a = 1\n",
        mode = "0640",
        owner = "app",
        group = "app"
      ).notifiesIds("service[app.service]"),
      fileFrom("/etc/b.conf", "files/b.conf.mustache", Map("port" -> "8080")),
      absentFile("/etc/old"),
      directory("/srv/app", mode = "0750"),
      symlink("/srv/current", "/srv/app"),
      pkg("app-server", version = "2.0", updateCache = true),
      absentPkg("telnet"),
      service("app.service", running = None),
      user("app", group = "app", groups = List("adm"), system = true),
      group("app", gid = 990),
      command("reload", "systemctl daemon-reload", unless = "test -e /x")
        .afterIds("file[/etc/a.conf]", "directory[/srv/app]")
    )
    check(
      "yaml: every kind reads into the same resources as Scala",
      full.map(_.resources) == Right(expected),
      full.toString
    )
    check(
      "yaml: name and nodes",
      full.exists(d =>
        d.name == "all-kinds" && d.nodes == List("tst5", "tst6")
      ),
      full.toString
    )
    check(
      "yaml: the description is a valid plan",
      full.toOption.exists(d => ApplyPlan(d.name, d.resources).isRight),
      full.map(d => ApplyPlan(d.name, d.resources)).toString
    )
    check(
      "yaml: group as an option is not a second kind",
      yaml("name: x\nresources:\n  - directory: /d\n    group: app\n").isRight
    )
    check(
      "yaml: an unknown option",
      yamlError(
        "name: x\nresources:\n  - file: /a\n    content: x\n    mod: \"0644\"\n"
      ).contains("unknown option(s) mod")
    )
    check(
      "yaml: two kinds",
      yamlError("name: x\nresources:\n  - file: /a\n    package: b\n").contains(
        "more than one kind"
      )
    )
    check(
      "yaml: no kind",
      yamlError("name: x\nresources:\n  - path: /a\n").contains("no kind")
    )
    check(
      "yaml: an unquoted mode",
      yamlError(
        "name: x\nresources:\n  - file: /a\n    content: x\n    mode: 0644\n"
      ).contains("quote it")
    )
    check(
      "yaml: content and source",
      yamlError(
        "name: x\nresources:\n  - file: /a\n    content: x\n    source: y\n"
      ).contains("not both")
    )
    check(
      "yaml: absent with options",
      yamlError(
        "name: x\nresources:\n  - file: /a\n    state: absent\n    mode: \"0644\"\n"
      ).contains("takes no mode")
    )
    check(
      "yaml: a bad state",
      yamlError("name: x\nresources:\n  - file: /a\n    state: gone\n")
        .contains("present or absent")
    )
    check(
      "yaml: an unknown top-level key",
      yamlError("name: x\nresource: []\n").contains("unknown key(s) resource")
    )
    check(
      "yaml: the error names the resource",
      yamlError(
        "name: x\nresources:\n  - file: /a\n    content: x\n  - symlink: /b\n"
      ).startsWith("resource 2: symlink: target is needed"),
      yamlError(
        "name: x\nresources:\n  - file: /a\n    content: x\n  - symlink: /b\n"
      )
    )
    // The demo descriptions in manifests/ read and plan (sbt may run this from the repository or from orchestrator/).
    List("apply_demo.yaml", "apply_demo_remove.yaml").foreach { f =>
      List(s"manifests/$f", s"../manifests/$f").find(p =>
        Files.exists(Paths.get(p))
      ) match
        case None    => check(s"yaml: $f found", false)
        case Some(p) =>
          val d = ApplyYaml.load(p)
          check(
            s"yaml: $f reads and plans",
            d.exists(x => ApplyPlan(x.name, x.resources).isRight),
            d.toString
          )
    }
    // A YAML description applies as the Scala one does.
    val desc = yaml(
      """name: d
        |resources:
        |  - directory: /etc/app
        |  - file: /etc/app/app.conf
        |    content: "x\n"
        |    notify: ["command[clean]"]
        |  - command: clean
        |    run: dnf clean metadata
        |    on_change_only: true
        |""".stripMargin
    ).map(_.resources).getOrElse(Nil)
    val n = new FakeNode(Map.empty)
    val first = applyTo(n, desc).report
    val second = applyTo(n, desc).report
    check(
      "yaml: applied, then unchanged",
      desc.size == 3 && first.results.forall(_.changed) && !second.changed,
      first.toString + second
    )

  private def totalChecks(): Unit =
    def report(node: String, o: ApplyOutcome) =
      ApplyReport(node, "t", check = false, List(ResourceResult("file[/x]", o)))
    val rs = Map(
      "a" -> report("a", ApplyOutcome.Changed(Nil, Nil)),
      "b" -> report("b", ApplyOutcome.Unchanged(""))
    )
    check(
      "total: changed, unchanged, and a node without a report counted as failed",
      OrpheraApply.total(
        List("a", "b", "c"),
        rs,
        check = false
      ) == "All 3 nodes: 1 changed, 1 unchanged, 1 failed (c)",
      OrpheraApply.total(List("a", "b", "c"), rs, check = false)
    )
    check(
      "total: check mode says to change, and no failures part when none failed",
      OrpheraApply.total(
        List("a", "b"),
        rs,
        check = true
      ) == "All 2 nodes: 1 to change, 1 unchanged"
    )

  private def diffChecks(): Unit =
    val d =
      TextDiff.unified("a\nb\nc\nd\ne\nf\ng\n", "a\nb\nc\nX\ne\nf\ng\n", "/f")
    check(
      "diff: header and the change",
      d.take(2) == List("--- /f (now)", "+++ /f (wanted)") && d.contains(
        "-d"
      ) && d.contains("+X"),
      d.toString
    )
    check(
      "diff: the removed line before the added one",
      d.indexOf("-d") >= 0 && d.indexOf("-d") < d.indexOf("+X"),
      d.toString
    )
    check(
      "diff: two lines of context, not more",
      d.contains(" b") && d.contains(" f") && !d.contains(" a") && !d.contains(
        " g"
      ),
      d.toString
    )

  // ---- the real node side, on this machine ------------------------------

  private def localRunner(
      nt: NamedTask,
      node: Node,
      env: TaskExecutor.Env
  ): IO[Boolean] =
    def ev(kind: Event.Kind, msg: String, exit: Int = 0, ok: Boolean = true) =
      env.output.event(node, nt.name, Event(kind, msg, exit, ok))
    nt.task match
      case Task.RunCommand(command, _, _) =>
        IO.blocking {
          val p =
            new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
          val out =
            new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
          (out.linesIterator.toList, p.waitFor())
        }.flatMap((out, code) =>
          out.traverse_(l => ev(Event.Kind.OUTPUT, l)) >> ev(
            Event.Kind.RESULT,
            "",
            code,
            code == 0
          ) >>
            IO.raiseWhen(code != 0)(new TaskExecutor.RemoteFailure("FAILED"))
              .as(true)
        )
      case Task.WriteFile(content, dest, _, _, mode) =>
        IO.blocking {
          val path = Paths.get(dest)
          val same = Files.exists(path) && new String(
            Files.readAllBytes(path),
            StandardCharsets.UTF_8
          ) == content
          if !same then
            Files.write(path, content.getBytes(StandardCharsets.UTF_8))
          if mode > 0 then
            Files.setPosixFilePermissions(
              path,
              PosixFilePermissions.fromString(rwx(mode))
            )
          same
        }.flatMap(same =>
          ev(
            Event.Kind.RESULT,
            if same then "Skipped: unchanged" else "",
            0,
            true
          ).as(!same)
        )
      case _ => IO.pure(false)

  private def rwx(mode: Int): String =
    (0 until 9)
      .map(i => if (mode & (1 << (8 - i))) != 0 then "rwx" (i % 3) else '-')
      .mkString

  private def localChecks(): Unit =
    val dir: Path = Files.createTempDirectory("orphera-apply-check")
    val base = dir.toString
    val node = Node("local", "localhost")
    val restart =
      command("touch the marker", s"touch $base/notified", onChangeOnly = true)
    def desc = List(
      directory(s"$base/conf.d", mode = "0750"),
      file(s"$base/conf.d/it's.conf", "x = 1\ny = 2\n", mode = "0640")
        .after(directory(s"$base/conf.d"))
        .notifies(restart),
      symlink(s"$base/current", s"$base/conf.d/it's.conf"),
      command(
        "make the state file",
        s"echo made > $base/state",
        creates = s"$base/state"
      ),
      restart
    )
    def run(rs: List[ApplyResource], check: Boolean): Ran =
      (for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        setFacts <- SetFacts.empty
        env = TaskExecutor.Env(
          ClusterContext(Map.empty),
          setFacts,
          TaskExecutor.Output(
            (_, _, _) => IO.unit,
            (_, _, _) => IO.unit,
            (_, _, _) => IO.unit
          ),
          templating = false,
          perform = localRunner,
          // No agent here: ObserveNode and ApplyFiles are unimplemented, so the
          // engine reads and changes the node with scripts, as it does on a
          // Scala agent.
          observe = (_, _) =>
            IO.raiseError(
              _root_.io.grpc.Status.UNIMPLEMENTED.asRuntimeException()
            ),
          applyFiles = (_, _) =>
            IO.raiseError(
              _root_.io.grpc.Status.UNIMPLEMENTED.asRuntimeException()
            ),
          applyAccess = (_, _) =>
            IO.raiseError(
              _root_.io.grpc.Status.UNIMPLEMENTED.asRuntimeException()
            ),
          applySoftware = (_, _) =>
            IO.raiseError(
              _root_.io.grpc.Status.UNIMPLEMENTED.asRuntimeException()
            )
        )
        plan <- Apply.plan("local", rs)
        report <- TaskExecutor.applyOn(
          plan,
          node,
          env,
          check,
          stopOnFailure = false,
          s => lines.update(_ :+ s)
        )
        ls <- lines.get
      yield Ran(report, ls)).unsafeRunSync()

    try
      val planned = run(desc, check = true).report
      check(
        "local check: all would change, nothing made",
        planned.results.count(_.changed) == 5 && !Files.exists(
          Paths.get(s"$base/conf.d")
        ),
        planned.toString
      )
      val ranFirst = run(desc, check = false)
      val first = ranFirst.report
      val l1 = ranFirst.lines
      check("local first run: no failures", !first.failed, l1.mkString("\n"))
      check(
        "local first run: made as described",
        Files.isDirectory(Paths.get(s"$base/conf.d")) &&
          Files.readString(
            Paths.get(s"$base/conf.d/it's.conf")
          ) == "x = 1\ny = 2\n" &&
          Files
            .readSymbolicLink(Paths.get(s"$base/current"))
            .toString == s"$base/conf.d/it's.conf" &&
          Files.exists(Paths.get(s"$base/state")) && Files.exists(
            Paths.get(s"$base/notified")
          ),
        l1.mkString("\n")
      )
      check(
        "local first run: modes",
        PosixFilePermissions.toString(
          Files.getPosixFilePermissions(Paths.get(s"$base/conf.d"))
        ) == "rwxr-x---" &&
          PosixFilePermissions.toString(
            Files.getPosixFilePermissions(Paths.get(s"$base/conf.d/it's.conf"))
          ) == "rw-r-----",
        l1.mkString("\n")
      )
      Files.delete(Paths.get(s"$base/notified"))
      val ranSecond = run(desc, check = false)
      val second = ranSecond.report
      val l2 = ranSecond.lines
      check(
        "local second run: nothing changed (read back by the real observe script)",
        !second.changed && !Files.exists(Paths.get(s"$base/notified")),
        l2.mkString("\n")
      )
      val edited = desc.updated(
        1,
        file(s"$base/conf.d/it's.conf", "x = 1\ny = 3\n", mode = "0640")
          .after(directory(s"$base/conf.d"))
          .notifies(restart)
      )
      val ranDiff = run(edited, check = true)
      val diff = ranDiff.report
      val l3 = ranDiff.lines
      check(
        "local check after an edit: a real diff",
        diff.changed && l3.contains("  -y = 2") && l3.contains("  +y = 3"),
        l3.mkString("\n")
      )
      val clash = List(file(s"$base/conf.d", "not a dir"))
      check(
        "local: a directory where a file is wanted fails",
        run(clash, check = false).report.failed
      )
      // The four kinds, read by the real observe script on this machine (check mode only: nothing changes).
      val kinds = run(
        List(
          pkg("bash"),
          absentPkg("orphera-no-such-package"),
          service("orphera-no-such-unit.service"),
          user("root"),
          absentUser("orphera-nobody"),
          group("root"),
          absentGroup("orphera-nogroup"),
          group("orphera-new-group")
        ),
        check = true
      )
      val kl = kinds.lines.mkString("\n")
      def kindOutcome(id: String) = outcome(kinds.report, id)
      check(
        "local: an installed package (bash) is unchanged",
        kindOutcome("package[bash]").exists(
          _.isInstanceOf[ApplyOutcome.Unchanged]
        ),
        kl
      )
      check(
        "local: an absent package stays absent",
        kindOutcome("package[orphera-no-such-package]").exists(
          _.isInstanceOf[ApplyOutcome.Unchanged]
        ),
        kl
      )
      check(
        "local: a unit that does not exist would change in check mode",
        kindOutcome("service[orphera-no-such-unit.service]").exists(
          _.isInstanceOf[ApplyOutcome.WouldChange]
        ),
        kl
      )
      check(
        "local: root exists",
        kindOutcome("user[root]")
          .exists(_.isInstanceOf[ApplyOutcome.Unchanged]) && kindOutcome(
          "group[root]"
        ).exists(_.isInstanceOf[ApplyOutcome.Unchanged]),
        kl
      )
      check(
        "local: absent user and group",
        kindOutcome("user[orphera-nobody]")
          .exists(_.isInstanceOf[ApplyOutcome.Unchanged]) && kindOutcome(
          "group[orphera-nogroup]"
        ).exists(_.isInstanceOf[ApplyOutcome.Unchanged]),
        kl
      )
      check(
        "local: a new group would be created",
        kindOutcome("group[orphera-new-group]").contains(
          ApplyOutcome.WouldChange(List("created"), Nil)
        ),
        kl
      )

      // Lines and keys, edited by the real scripts on this machine.
      val cfg = s"$base/sshd_config"
      Files.writeString(
        Paths.get(cfg),
        "Port 22\n#PermitRootLogin yes\nPermitRootLogin prohibit-password\nUseDNS yes"
      )
      val wantLine = lineInFile(cfg, "PermitRootLogin no", "^#?PermitRootLogin")
      val lineRun1 = run(List(wantLine), check = true)
      check(
        "local line: check mode would replace and changes nothing",
        lineRun1.report.changed && Files
          .readString(Paths.get(cfg))
          .contains("prohibit-password"),
        lineRun1.lines.mkString("\n")
      )
      val lineRun2 = run(List(wantLine), check = false)
      check(
        "local line: replaces the first match and drops the others",
        !lineRun2.report.failed &&
          Files.readString(
            Paths.get(cfg)
          ) == "Port 22\nPermitRootLogin no\nUseDNS yes\n",
        lineRun2.lines.mkString("\n") + Files.readString(Paths.get(cfg))
      )
      check(
        "local line: second run changes nothing",
        !run(List(wantLine), check = false).report.changed
      )
      val addLine = lineInFile(cfg, "it's here")
      check(
        "local line: appends a line with a quote in it, once",
        !run(List(addLine), check = false).report.failed &&
          !run(List(addLine), check = false).report.changed &&
          Files.readString(Paths.get(cfg)).endsWith("UseDNS yes\nit's here\n"),
        Files.readString(Paths.get(cfg))
      )
      check(
        "local line: removes it",
        !run(List(absentLine(cfg, "it's here")), check = false).report.failed &&
          !Files.readString(Paths.get(cfg)).contains("it's here") &&
          !run(List(absentLine(cfg, "it's here")), check = false).report.changed
      )
      val fresh = s"$base/new.conf"
      check(
        "local line: a missing file is created",
        !run(List(lineInFile(fresh, "a = 1")), check = false).report.failed &&
          Files.readString(Paths.get(fresh)) == "a = 1\n"
      )
      // Read by the real observe script (check mode): what this machine has, and what it has not.
      val others = run(
        List(
          sysctl("orphera.no.such.key", "1"),
          kernelModule("orphera_no_such_module"),
          authorizedKey("orphera-nobody", "ssh-ed25519 AAAA x"),
          mount("/orphera-no-such-mount", "/dev/orphera", "ext4"),
          absentMount("/orphera-no-such-mount-either")
        ),
        check = true
      )
      val ol = others.lines.mkString("\n")
      check(
        "local: a sysctl key the node lacks would change; so does a key for a user that does not exist yet",
        outcome(others.report, "sysctl[orphera.no.such.key]")
          .exists(_.isInstanceOf[ApplyOutcome.WouldChange]) &&
          outcome(
            others.report,
            "authorized_key[orphera-nobody: ssh-ed25519 ...AAAA x]"
          ).exists(_.isInstanceOf[ApplyOutcome.WouldChange]),
        ol
      )
      check(
        "local: a missing module and mount would change; an absent mount is unchanged",
        outcome(others.report, "kernel_module[orphera_no_such_module]")
          .exists(_.isInstanceOf[ApplyOutcome.WouldChange]) &&
          outcome(others.report, "mount[/orphera-no-such-mount]")
            .exists(_.isInstanceOf[ApplyOutcome.WouldChange]) &&
          outcome(others.report, "mount[/orphera-no-such-mount-either]")
            .exists(_.isInstanceOf[ApplyOutcome.Unchanged]),
        ol
      )
      val goneKey = run(
        List(absentAuthorizedKey("orphera-nobody", "ssh-ed25519 AAAA x")),
        check = true
      )
      check(
        "local: removing a key of a user that does not exist is not a failure",
        outcome(
          goneKey.report,
          "authorized_key[orphera-nobody: ssh-ed25519 ...AAAA x]"
        ).exists(_.isInstanceOf[ApplyOutcome.Unchanged]),
        goneKey.lines.mkString("\n")
      )
    finally
      new ProcessBuilder("rm", "-rf", base).start().waitFor()
      ()

  // ---- what the agent's ObserveNode reads -----------------------------------

  private def observeChecks(): Unit =
    val line = lineInFile("/etc/x.conf", "beta = 3", "^beta")
    val kinds = List(
      file("/etc/a", "x") -> Apply.Probe("path", "/etc/a"),
      directory("/opt/d") -> Apply.Probe("path", "/opt/d"),
      symlink("/opt/l", "/opt/d") -> Apply.Probe("path", "/opt/l"),
      pkg("tree") -> Apply.Probe("package", "tree"),
      service("sshd") -> Apply.Probe("unit", "sshd"),
      user("bob") -> Apply.Probe("user", "bob"),
      group("grp") -> Apply.Probe("group", "grp"),
      sysctl("net.ipv4.ip_forward", "1") -> Apply.Probe(
        "sysctl",
        "net.ipv4.ip_forward",
        "/etc/sysctl.d/60-orphera-net.ipv4.ip_forward.conf"
      ),
      kernelModule("overlay") -> Apply.Probe(
        "module",
        "overlay",
        "/etc/modules-load.d/orphera-overlay.conf"
      ),
      line -> Apply.Probe("file_text", "/etc/x.conf"),
      authorizedKey("bob", "ssh-ed25519 AAAA x") -> Apply.Probe(
        "key",
        "bob",
        "ssh-ed25519 AAAA"
      ),
      mount("/opt/ram", "tmpfs", "tmpfs") -> Apply.Probe("mount", "/opt/ram")
    )
    kinds.foreach { (r, p) =>
      check(
        s"probe of ${r.id}",
        Apply.probe(r) == Some(p),
        s"${Apply.probe(r)}"
      )
    }
    check(
      "a command's guard has no typed read",
      Apply.probe(command("c", "true")).isEmpty
    )

    def one(r: ApplyResource, kv: Map[String, String]): Option[Observed] =
      Apply.observedFromAgent(List(0 -> r), Map(0 -> kv)).get(0)
    check(
      "a file as the agent reads it",
      one(
        file("/etc/a", "x"),
        Map(
          "type" -> "file",
          "mode" -> "644",
          "owner" -> "root",
          "group" -> "root",
          "sha256" -> "ab"
        )
      ).contains(
        Observed.RegularFile(Integer.parseInt("644", 8), "root", "root", "ab")
      )
    )
    check(
      "an absent path, a link and a directory",
      one(file("/e", "x"), Map("type" -> "absent")).contains(Observed.Absent) &&
        one(symlink("/l", "/t"), Map("type" -> "link", "target" -> "/t"))
          .contains(Observed.Link("/t")) &&
        one(
          directory("/d"),
          Map(
            "type" -> "directory",
            "mode" -> "750",
            "owner" -> "a",
            "group" -> "b"
          )
        ).contains(Observed.Dir(Integer.parseInt("750", 8), "a", "b"))
    )
    check(
      "a package and a unit",
      one(
        pkg("tree"),
        Map("manager" -> "apt", "installed" -> "yes", "versions" -> "2.1-1")
      ).contains(Observed.Pkg("apt", true, List("2.1-1"))) &&
        one(service("sshd"), Map("enabled" -> "enabled", "active" -> "active"))
          .contains(Observed.ServiceState("enabled", "active"))
    )
    check(
      "a user, an absent user and a group",
      one(
        user("bob"),
        Map(
          "exists" -> "yes",
          "uid" -> "1001",
          "home" -> "/home/bob",
          "shell" -> "/bin/sh",
          "group" -> "bob",
          "groups" -> "bob,adm"
        )
      ).contains(
        Observed
          .Account(1001, "bob", List("bob", "adm"), "/home/bob", "/bin/sh")
      ) &&
        one(user("bob"), Map("exists" -> "no")).contains(Observed.Absent) &&
        one(group("g"), Map("exists" -> "yes", "gid" -> "50"))
          .contains(Observed.GroupEntry(50))
    )
    check(
      "a line resource: the counts are worked out from the file's text",
      one(
        line,
        Map(
          "exists" -> "yes",
          "truncated" -> "no",
          "content" -> "alpha = 1\nbeta = 2\nbeta = 3\n"
        )
      ).contains(
        Observed.Facts(
          Map(
            "exists" -> "yes",
            "exact" -> "1",
            "matches" -> "2",
            "content" -> "alpha = 1\nbeta = 2\nbeta = 3\n"
          )
        )
      ) &&
        one(line, Map("exists" -> "no"))
          .contains(Observed.Facts(Map("exists" -> "no", "content" -> "")))
    )
    check(
      "a file too large to read, or a bad regular expression, is not read at all",
      one(
        line,
        Map("exists" -> "yes", "truncated" -> "yes", "content" -> "")
      ).isEmpty &&
        one(
          lineInFile("/etc/x", "a", "("),
          Map("exists" -> "yes", "truncated" -> "no", "content" -> "a\n")
        ).isEmpty
    )
    check(
      "a resource the agent could not read is missing from the answer",
      Apply.observedFromAgent(List(0 -> pkg("tree")), Map.empty).isEmpty
    )
    check(
      "sysctl, module, key and mount values pass through as facts",
      one(
        sysctl("k", "1"),
        Map("found" -> "yes", "runtime" -> "1", "persisted" -> "")
      )
        .contains(
          Observed.Facts(
            Map("found" -> "yes", "runtime" -> "1", "persisted" -> "")
          )
        ) &&
        one(
          mount("/m", "tmpfs", "tmpfs"),
          Map("fstab" -> "no", "mounted" -> "no")
        )
          .contains(Observed.Facts(Map("fstab" -> "no", "mounted" -> "no")))
    )

  // ---- typed changes ---------------------------------------------------------

  // A node as the agent's ObserveNode reports it (values by what is read), that
  // records the typed changes asked of it and refuses to run a script.
  private class TypedTarget(values: Map[String, Map[String, String]])
      extends ApplyTarget:
    var seen: Vector[FileChange] = Vector.empty
    var written: Vector[String] = Vector.empty
    var access: Vector[AccessChange] = Vector.empty
    var scripts: Vector[String] = Vector.empty
    var software: Vector[SoftwareChange] = Vector.empty
    def node = "typed"
    def observe(rs: List[(Int, ApplyResource)]): IO[Map[Int, Observed]] =
      IO.pure(
        rs.flatMap { (i, r) =>
          Apply
            .probe(r)
            .flatMap(p => values.get(p.target))
            .flatMap(kv =>
              Apply.observedFromAgent(List(i -> r), Map(i -> kv)).get(i)
            )
            .map(i -> _)
        }.toMap
      )
    def readText(path: String): IO[Option[String]] = IO.pure(None)
    def source(path: String, vars: Map[String, String]): IO[String] =
      IO.pure("")
    def render(text: String, vars: Map[String, String]): IO[String] =
      IO.pure(text)
    def write(
        path: String,
        content: String,
        owner: String,
        group: String,
        mode: Int
    ): IO[Unit] = IO { written :+= path }
    def shell(label: String, script: String, timeout: Int): IO[Unit] =
      IO.raiseError(new RuntimeException(s"a script ran: $script"))
    override def typed(
        label: String,
        changes: List[FileChange],
        fallback: String,
        timeout: Int
    ): IO[Unit] = IO { seen ++= changes }
    override def software(
        label: String,
        changes: List[SoftwareChange],
        fallback: String,
        timeout: Int
    ): IO[Unit] = IO { software ++= changes }
    override def access(
        label: String,
        changes: List[AccessChange],
        fallback: String,
        timeout: Int
    ): IO[Unit] = IO { access ++= changes }

  private def shaOf(t: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(t.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def typedChecks(): Unit =
    def seenFor(
        values: Map[String, Map[String, String]],
        r: ApplyResource
    ): (Vector[FileChange], Ran) =
      val t = new TypedTarget(values)
      val ran = applyTo(t, List(r))
      (t.seen, ran)

    val old = "alpha = 1\nbeta = 2\n"
    val fileOf = (c: String) =>
      Map("exists" -> "yes", "truncated" -> "no", "content" -> c)
    val (put, ran) = seenFor(
      Map("/etc/x.conf" -> fileOf(old)),
      lineInFile("/etc/x.conf", "beta = 3", "^beta")
    )
    check(
      "a line replaced: the new text is worked out here and sent with the old hash",
      put == Vector(
        FileChange.ReplaceText(
          "/etc/x.conf",
          "alpha = 1\nbeta = 3\n",
          shaOf(old),
          "0644"
        )
      ) && !ran.report.failed,
      s"$put ${ran.lines}"
    )
    check(
      "a line in a file that is not there: the file is created",
      seenFor(
        Map("/etc/x.conf" -> Map("exists" -> "no")),
        lineInFile("/etc/x.conf", "alpha = 1")
      )._1 == Vector(
        FileChange.ReplaceText("/etc/x.conf", "alpha = 1\n", "", "0644")
      )
    )
    check(
      "a line removed: it and the lines that match go",
      seenFor(
        Map("/f" -> fileOf("a\nb\nc\n")),
        absentLine("/f", "b")
      )._1 == Vector(
        FileChange.ReplaceText("/f", "a\nc\n", shaOf("a\nb\nc\n"), "0644")
      )
    )
    check(
      "a line already there changes nothing",
      seenFor(
        Map("/f" -> fileOf("a\nb\n")),
        lineInFile("/f", "b", "^b")
      )._1.isEmpty
    )
    check(
      "a directory that is not there is made with its mode, owner and group",
      seenFor(
        Map("/opt/d" -> Map("type" -> "absent")),
        directory("/opt/d", mode = "0750")
      )._1 == Vector(FileChange.MakeDirectory("/opt/d", "0750", "", ""))
    )
    def fileKv(sum: String, mode: String = "644") =
      Map(
        "type" -> "file",
        "mode" -> mode,
        "owner" -> "root",
        "group" -> "root",
        "sha256" -> sum
      )
    check(
      "fileCopy: the destination is not there, so the agent copies the file on the node",
      seenFor(
        Map("/src/ca" -> fileKv("aa"), "/dst/ca" -> Map("type" -> "absent")),
        fileCopy("/dst/ca", "/src/ca", mode = "0644")
      )._1 == Vector(
        FileChange.ReplaceText("/dst/ca", "", "", "0644", false, "/src/ca"),
        FileChange.SetAttributes("/dst/ca", "0644", "", "")
      )
    )
    check(
      "fileCopy: a different hash is replaced, guarded by the hash it replaces",
      seenFor(
        Map("/src/ca" -> fileKv("aa"), "/dst/ca" -> fileKv("bb")),
        fileCopy("/dst/ca", "/src/ca")
      )._1 == Vector(
        FileChange.ReplaceText("/dst/ca", "", "bb", "0644", false, "/src/ca")
      )
    )
    check(
      "fileCopy: the same hash changes nothing",
      seenFor(
        Map("/src/ca" -> fileKv("aa"), "/dst/ca" -> fileKv("aa")),
        fileCopy("/dst/ca", "/src/ca", mode = "0644")
      )._1.isEmpty
    )
    check(
      "fileCopy: the same hash but the wrong mode sets only the mode",
      seenFor(
        Map("/src/ca" -> fileKv("aa"), "/dst/ca" -> fileKv("aa", "600")),
        fileCopy("/dst/ca", "/src/ca", mode = "0644")
      )._1 == Vector(FileChange.SetAttributes("/dst/ca", "0644", "", ""))
    )
    check(
      "fileCopy: a source that is not on the node fails and nothing is written", {
        val t = new TypedTarget(
          Map(
            "/src/ca" -> Map("type" -> "absent"),
            "/dst/ca" -> Map("type" -> "absent")
          )
        )
        val ran = applyTo(t, List(fileCopy("/dst/ca", "/src/ca")))
        ran.report.failed && t.seen.isEmpty && ran.lines.exists(
          _.contains("is not on the node")
        )
      }
    )
    check(
      "a directory with the wrong mode: only the mode is set",
      seenFor(
        Map(
          "/opt/d" -> Map(
            "type" -> "directory",
            "mode" -> "755",
            "owner" -> "root",
            "group" -> "root"
          )
        ),
        directory("/opt/d", mode = "0750")
      )._1 == Vector(FileChange.SetAttributes("/opt/d", "0750", "", ""))
    )
    check(
      "a link to somewhere else is made again; a file is removed",
      seenFor(
        Map("/l" -> Map("type" -> "link", "target" -> "/old")),
        symlink("/l", "/new")
      )._1 == Vector(FileChange.MakeSymlink("/l", "/new")) &&
        seenFor(
          Map(
            "/f" -> Map(
              "type" -> "file",
              "mode" -> "644",
              "owner" -> "root",
              "group" -> "root",
              "sha256" -> "ab"
            )
          ),
          absentFile("/f")
        )._1 == Vector(FileChange.RemovePath("/f", false))
    )
    check(
      "a directory removed with its contents: one typed recursive removal",
      seenFor(
        Map(
          "/srv/t" -> Map(
            "type" -> "directory",
            "mode" -> "755",
            "owner" -> "root",
            "group" -> "root"
          )
        ),
        absentDirectory("/srv/t", recursive = true)
      )._1 == Vector(FileChange.RemovePath("/srv/t", true, true))
    )
    check(
      "absentDirectory(recursive) refuses a path near the root or not clean", {
        def refused(p: String) =
          scala.util.Try(absentDirectory(p, recursive = true)).isFailure
        refused("/") && refused("/srv") && refused("/srv/") &&
        refused("/srv/../etc") && refused("srv/x") &&
        scala.util.Try(absentDirectory("/srv/x", recursive = true)).isSuccess
      }
    )
    check(
      "a sysctl that differs is set through the agent, and persisted as a file write", {
        val t = new TypedTarget(
          Map(
            "net.ipv4.ip_forward" -> Map(
              "found" -> "yes",
              "runtime" -> "0",
              "persisted" -> ""
            )
          )
        )
        applyTo(t, List(sysctl("net.ipv4.ip_forward", "1")))
        t.seen == Vector(FileChange.SetSysctl("net.ipv4.ip_forward", "1")) &&
        t.written == Vector(
          "/etc/sysctl.d/60-orphera-net.ipv4.ip_forward.conf"
        )
      }
    )
    check(
      "a module that is not loaded is loaded through the agent",
      seenFor(
        Map("overlay" -> Map("loaded" -> "no", "persisted" -> "overlay")),
        kernelModule("overlay")
      )._1 == Vector(FileChange.LoadModule("overlay"))
    )

    def edited(l: ApplyResource, text: String): String = l match
      case x: ApplyResource.LineInFile => Apply.editedText(x, text)
      case _                           => ""
    check(
      "the text a line resource makes: replace the first match, drop the others, or add",
      edited(lineInFile("/f", "k = 1", "^k"), "a\nk = 0\nb\nk = 9\n") ==
        "a\nk = 1\nb\n" &&
        edited(lineInFile("/f", "k = 1", "^k"), "a\nb\n") == "a\nb\nk = 1\n" &&
        edited(lineInFile("/f", "k = 1"), "a\n") == "a\nk = 1\n" &&
        edited(absentLine("/f", "x", "^y"), "x\ny1\nz\n") == "z\n" &&
        edited(absentLine("/f", "z"), "z\n") == "" &&
        edited(lineInFile("/f", "n"), "a") == "a\nn\n"
    )
    check(
      "records are split on newline only, as awk does",
      Apply.records("a\r\nb\n") == List("a\r", "b") &&
        Apply.records("a\nb") == List("a", "b") &&
        Apply.records("") == Nil &&
        Apply.records("\n") == List("")
    )

  // Packages and units go through ApplySoftware.
  private def softwareChecks(): Unit =
    def softFor(
        values: Map[String, Map[String, String]],
        r: ApplyResource
    ): (Vector[SoftwareChange], Ran) =
      val t = new TypedTarget(values)
      val ran = applyTo(t, List(r))
      (t.software, ran)

    val absentPkg = Map("manager" -> "apt", "installed" -> "no")
    val (inst, r1) = softFor(
      Map("curl" -> absentPkg),
      ApplyResource.Package("curl", "7.1-2", true, true)
    )
    check(
      "typed software: a missing package is installed at its version",
      inst == Vector(SoftwareChange.InstallPackage("curl", "7.1-2", true)) &&
        !r1.report.failed,
      s"$inst ${r1.lines}"
    )
    val (up, _) = softFor(
      Map(
        "curl" -> Map(
          "manager" -> "dnf",
          "installed" -> "yes",
          "versions" -> "7.0-1"
        )
      ),
      ApplyResource.Package("curl", "7.1-2", false, true)
    )
    check(
      "typed software: another installed version is replaced",
      up == Vector(SoftwareChange.InstallPackage("curl", "7.1-2", false)),
      s"$up"
    )
    val (rm, _) = softFor(
      Map(
        "curl" -> Map(
          "manager" -> "apt",
          "installed" -> "yes",
          "versions" -> "7.0-1"
        )
      ),
      ApplyResource.Package("curl", "", false, false)
    )
    check(
      "typed software: an installed package is removed",
      rm == Vector(SoftwareChange.RemovePackage("curl")),
      s"$rm"
    )
    val (same, _) = softFor(
      Map(
        "curl" -> Map(
          "manager" -> "apt",
          "installed" -> "yes",
          "versions" -> "7.1-2"
        )
      ),
      ApplyResource.Package("curl", "7.1-2", false, true)
    )
    check("typed software: the right version is left", same.isEmpty, s"$same")
    val (unit, _) = softFor(
      Map(
        "ssh.service" -> Map("enabled" -> "disabled", "active" -> "inactive")
      ),
      ApplyResource.Service("ssh.service", Some(true), Some(true))
    )
    check(
      "typed software: a unit is reloaded, enabled and started",
      unit == Vector(
        SoftwareChange.ReloadUnits,
        SoftwareChange.ControlUnit("ssh.service", "enable"),
        SoftwareChange.ControlUnit("ssh.service", "start")
      ),
      s"$unit"
    )
    val (steady, _) = softFor(
      Map("ssh.service" -> Map("enabled" -> "enabled", "active" -> "active")),
      ApplyResource.Service("ssh.service", Some(true), Some(true))
    )
    check(
      "typed software: a unit as wanted is left",
      steady.isEmpty,
      s"$steady"
    )
    check(
      "typed write: the replacement, then mode, owner and group",
      Apply.writeChanges("/etc/a", "x", "root", "", 416, "abc", false) ==
        List(
          FileChange.ReplaceText("/etc/a", "x", "abc", "0640", false),
          FileChange.SetAttributes("/etc/a", "0640", "root", "")
        ) &&
        Apply.writeChanges("/etc/s", "k = 1\n", "", "", 420, "", true) ==
        List(
          FileChange.ReplaceText("/etc/s", "k = 1\n", "", "0644", true),
          FileChange.SetAttributes("/etc/s", "0644", "", "")
        ) &&
        Apply.writeChanges("/etc/a", "x", "", "", 0, "", false) ==
        List(FileChange.ReplaceText("/etc/a", "x", "", "0644", false))
    )

  // Accounts, keys and mounts go through ApplyAccess, with fstab worked out here.
  private def accessChecks(): Unit =
    def accessFor(
        values: Map[String, Map[String, String]],
        r: ApplyResource
    ): (Vector[AccessChange], Ran) =
      val t = new TypedTarget(values)
      val ran = applyTo(t, List(r))
      (t.access, ran)

    val (made, r1) = accessFor(
      Map("svc" -> Map("exists" -> "no")),
      ApplyResource.User("svc", 501, "", List("adm"), "", "/bin/sh", true, true)
    )
    check(
      "typed access: a missing user is created by the agent",
      made == Vector(
        AccessChange.CreateUser(
          "svc",
          "501",
          "",
          List("adm"),
          "",
          "/bin/sh",
          true,
          false
        )
      ) && !r1.report.failed,
      s"$made ${r1.lines}"
    )
    val (mod, _) = accessFor(
      Map(
        "svc" -> Map(
          "exists" -> "yes",
          "uid" -> "501",
          "group" -> "svc",
          "groups" -> "svc",
          "home" -> "/home/svc",
          "shell" -> "/bin/bash"
        )
      ),
      ApplyResource.User("svc", -1, "", List("adm"), "", "/bin/sh", false, true)
    )
    check(
      "typed access: only what differs is modified",
      mod == Vector(
        AccessChange.ModifyUser("svc", "", "", List("adm"), "", "/bin/sh")
      ),
      s"$mod"
    )
    val (gone, _) = accessFor(
      Map("svc" -> Map("exists" -> "no")),
      ApplyResource.Group("svc", 700, false, true)
    )
    check(
      "typed access: a group is created with its gid",
      gone == Vector(AccessChange.CreateGroup("svc", "700", false)),
      s"$gone"
    )
    val (key, _) = accessFor(
      Map("alice" -> Map("exists" -> "yes", "count" -> "0")),
      ApplyResource.AuthorizedKey("alice", "ssh-ed25519 AAAA c", true)
    )
    check(
      "typed access: a key is added",
      key == Vector(
        AccessChange.AddAuthorizedKey("alice", "ssh-ed25519 AAAA c")
      ),
      s"$key"
    )
    val (unkey, _) = accessFor(
      Map("alice" -> Map("exists" -> "yes", "count" -> "1")),
      ApplyResource.AuthorizedKey("alice", "ssh-ed25519 AAAA c", false)
    )
    check(
      "typed access: a key is removed by its id",
      unkey == Vector(
        AccessChange.RemoveAuthorizedKey("alice", "ssh-ed25519 AAAA")
      ),
      s"$unkey"
    )
    val tab = "# c\n/dev/sda1 / ext4 defaults 0 1\n"
    val mnt =
      ApplyResource.Mount(
        "/mnt/x",
        "tmpfs",
        "tmpfs",
        "size=8m",
        Some(true),
        true
      )
    val (mounted, _) = accessFor(
      Map(
        "/mnt/x" -> Map(
          "fstab" -> "no",
          "mounted" -> "no",
          "fstab_text" -> tab
        )
      ),
      mnt
    )
    check(
      "typed access: fstab text is worked out here, hash-guarded, then mounted",
      mounted == Vector(
        AccessChange.ReplaceFstab(
          tab + "tmpfs /mnt/x tmpfs size=8m 0 0\n",
          shaOf(tab)
        ),
        AccessChange.MountPath("/mnt/x", true)
      ),
      s"$mounted"
    )
    val (unmounted, _) = accessFor(
      Map(
        "/mnt/x" -> Map(
          "fstab" -> "yes",
          "mounted" -> "yes",
          "dev" -> "tmpfs",
          "fstype" -> "tmpfs",
          "opts" -> "size=8m",
          "fstab_text" -> (tab + "tmpfs /mnt/x tmpfs size=8m 0 0\n")
        )
      ),
      mnt.copy(present = false)
    )
    check(
      "typed access: a mount is unmounted and its fstab line dropped",
      unmounted == Vector(
        AccessChange.Unmount("/mnt/x"),
        AccessChange.ReplaceFstab(
          tab,
          shaOf(tab + "tmpfs /mnt/x tmpfs size=8m 0 0\n")
        )
      ),
      s"$unmounted"
    )
    check(
      "fstab edits: first match replaced, duplicates and comments handled",
      Apply.fstabEdited(
        "a /m x 0 0\n#b /m y\nc /m z\nd /o e\n",
        "/m",
        Some("N /m t o 0 0")
      ) ==
        "N /m t o 0 0\n#b /m y\nd /o e\n" &&
        Apply.fstabEdited("a /m x\nb /m y\n", "/m", None) == "" &&
        Apply.fstabEdited("a /o x", "/m", Some("N")) == "a /o x\nN\n"
    )
    // No fstab text read (a Scala agent): the script edits the file.
    val sh = new TypedTarget(
      Map("/mnt/x" -> Map("fstab" -> "no", "mounted" -> "yes"))
    ):
      override def shell(l: String, script: String, t: Int): IO[Unit] =
        IO { scripts :+= script }
    val r = applyTo(sh, List(mnt))
    check(
      "no fstab text: the fstab edit is the script",
      sh.scripts.exists(
        _.contains("/etc/fstab")
      ) && sh.access.isEmpty && !r.report.failed,
      s"${sh.scripts} ${r.lines}"
    )

  // ---- waitForHttp and waitForPort: the checks they generate, run against a server on this machine ----

  private def runShell(script: String): (Int, String) =
    val pb = new ProcessBuilder("sh", "-c", script)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (proc.waitFor(), out.trim)

  private def rabbitChecks(): Unit =
    val dir = Files.createTempDirectory("rabbit")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val calls = dir.resolve("calls")
    // A stand-in podman: it answers from files in the test directory and records the commands that change things.
    val fake = bin.resolve("podman")
    Files.writeString(
      fake,
      s"""#!/bin/sh
         |case "$$*" in
         |  "ps "*) [ -e "$dir/up" ] && echo abc123; exit 0 ;;
         |  *list_vhosts*) cat "$dir/vhosts" 2>/dev/null; exit 0 ;;
         |  *list_users*) cat "$dir/users" 2>/dev/null; exit 0 ;;
         |  *authenticate_user*) [ -e "$dir/authok" ] ;;
         |  *list_permissions*) cat "$dir/perms" 2>/dev/null; exit 0 ;;
         |  *) echo "$$*" >> "$calls"; exit 0 ;;
         |esac
         |""".stripMargin
    )
    check("rabbit: the fake podman is executable", fake.toFile.setExecutable(true))
    def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
    def put(n: String, text: String): Unit = { val _ = Files.writeString(dir.resolve(n), text) }
    def gone(n: String): Unit = { val _ = Files.deleteIfExists(dir.resolve(n)) }
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val acct = rabbitAccount("acct", "svc", "svcuser", Secret("pa'ss"))
    put("vhosts", "/\n")
    put("users", "guest\t[administrator]\n")
    check("rabbitAccount: no vhost means it is needed", sh(part(acct, _.unless))._1 != 0)
    put("vhosts", "/\nsvc\n")
    put("users", "guest\t[administrator]\nsvcuser\t[]\n")
    put("authok", "")
    put("perms", "svcuser\t.*\t.*\t.*\n")
    check("rabbitAccount: vhost, login and permissions mean unchanged", sh(part(acct, _.unless))._1 == 0)
    put("perms", "svcuser\t.*\t.*\t\n")
    check("rabbitAccount: lesser permissions mean it is needed", sh(part(acct, _.unless))._1 != 0)
    gone("up")
    val down = sh(part(acct, _.run))
    check(
      "rabbitAccount: no running broker fails and says so",
      down._1 != 0 && down._2.contains("apply rabbitmq_single_node.scala first"),
      down.toString
    )
    put("up", "")
    put("vhosts", "/\n")
    put("users", "guest\t[administrator]\n")
    gone("authok")
    put("calls", "")
    val made = sh(part(acct, _.run))
    val c1 = Files.readString(calls)
    check("rabbitAccount: a new vhost and user are made", made._1 == 0 && c1.contains("add_vhost svc") && c1.contains("add_user svcuser pa'ss"), made.toString + c1)
    check("rabbitAccount: and given full permissions", c1.contains("set_permissions -p svc svcuser .* .* .*"), c1)
    put("vhosts", "/\nsvc\n")
    put("users", "svcuser\t[]\n")
    put("calls", "")
    sh(part(acct, _.run))
    val c2 = Files.readString(calls)
    check("rabbitAccount: an existing user gets the password set, no vhost is added", c2.contains("change_password svcuser") && !c2.contains("add_vhost") && !c2.contains("add_user"), c2)
    val root = rabbitAccount("root", "/", "svcuser", Secret("x"))
    check("rabbitAccount: the default vhost is not made", !part(root, _.run).contains("add_vhost") && !part(root, _.unless).contains("list_vhosts"))
    check("rabbitAccount: a bad name is refused", scala.util.Try(rabbitAccount("x", "a b", "u", Secret("x"))).isFailure)
    val drop = absentRabbitAccount("drop", "svc", "svcuser")
    put("vhosts", "/\n")
    put("users", "guest\t[administrator]\n")
    check("absentRabbitAccount: nothing there means nothing to do", sh(part(drop, _.onlyIf))._1 != 0)
    put("users", "guest\t[administrator]\nsvcuser\t[]\n")
    check("absentRabbitAccount: the user there means it runs", sh(part(drop, _.onlyIf))._1 == 0)
    gone("up")
    check("absentRabbitAccount: no running broker means nothing to do", sh(part(drop, _.onlyIf))._1 != 0)
    put("calls", "")
    sh(part(drop, _.run))
    val c3 = Files.readString(calls)
    check("absentRabbitAccount: the vhost and the user are deleted", c3.contains("delete_vhost svc") && c3.contains("delete_user svcuser"), c3)
    check("absentRabbitAccount: the default vhost is refused", scala.util.Try(absentRabbitAccount("x", "/", "u")).isFailure)

  private def serverCaChecks(): Unit =
    val dir = Files.createTempDirectory("serverca")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val calls = dir.resolve("calls")
    val realOpenssl = runShell("command -v openssl")
    check("serverCa: openssl is installed", realOpenssl._1 == 0, realOpenssl.toString)
    if realOpenssl._1 == 0 then
      // A CA, a certificate it signed, and the chain a server would present.
      val made = runShell(s"""cd $dir
        |openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -subj /CN=testca -days 2 >/dev/null 2>&1
        |openssl req -newkey rsa:2048 -nodes -keyout leaf.key -out leaf.csr -subj /CN=leaf >/dev/null 2>&1
        |openssl x509 -req -in leaf.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out leaf.crt -days 2 >/dev/null 2>&1
        |cat leaf.crt ca.crt > chain.pem""".stripMargin)
      check("serverCa: the test certificates are made", made._1 == 0 && Files.exists(dir.resolve("chain.pem")), made.toString)
      // A stand-in openssl answers s_client from a file (with text after the certificates that differs on each
      // call, as a real handshake's does) and passes everything else to the real one.
      val fakeOpenssl = bin.resolve("openssl")
      Files.writeString(
        fakeOpenssl,
        s"""#!/bin/sh
           |if [ "$$1" = s_client ]; then cat "$dir/served"; echo "read bytes $$(date +%s%N)"; exit 0; fi
           |exec ${realOpenssl._2} "$$@"
           |""".stripMargin
      )
      val fakeUpdate = bin.resolve("update-ca-certificates")
      Files.writeString(fakeUpdate, s"""#!/bin/sh
          |echo update >> "$calls"
          |""".stripMargin)
      check("serverCa: the stand-ins are executable", fakeOpenssl.toFile.setExecutable(true) && fakeUpdate.toFile.setExecutable(true))
      def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
      def serve(file: String): Unit = { val _ = Files.writeString(dir.resolve("served"), "Certificate chain\n" + Files.readString(dir.resolve(file))) }
      def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
        case c: ApplyResource.Command => pick(c)
        case other                    => s"not a command: $other"
      val caFile = s"$dir/out/ca.crt"
      val anchor = s"$dir/anchors/orphera-test-ca.crt"
      val step = serverCa("ca", "ks.example.com", 5000, caFile, hint = "apply keystone_single_node.scala", anchorsDeb = s"$dir/anchors", anchorsRpm = s"$dir/rpm")
      serve("chain.pem")
      Files.createDirectory(dir.resolve("anchors"))
      check("serverCa: nothing installed means it is needed", sh(part(step, _.unless))._1 != 0)
      val ran = sh(part(step, _.run))
      check("serverCa: the run succeeds and says what it installed", ran._1 == 0 && ran._2.contains("installed: CN"), ran.toString)
      check("serverCa: the CA is saved, in a directory the run made", sh(s"cmp $dir/ca.crt $caFile")._1 == 0)
      check("serverCa: and put in the trust store, which is refreshed", sh(s"cmp $dir/ca.crt $anchor")._1 == 0 && Files.exists(calls))
      check("serverCa: the installed CA means unchanged, whatever the handshake adds", sh(part(step, _.unless))._1 == 0 && sh(part(step, _.unless))._1 == 0)
      Files.delete(dir.resolve("anchors/orphera-test-ca.crt"))
      check("serverCa: a CA missing from the trust store means it is needed", sh(part(step, _.unless))._1 != 0)
      sh(part(step, _.run))
      check("serverCa: and the run puts it back", sh(part(step, _.unless))._1 == 0)
      val leafOnly = sh(s"cp $dir/leaf.crt $dir/leafonly.pem")
      check("serverCa: leaf copy made", leafOnly._1 == 0, leafOnly.toString)
      serve("leafonly.pem")
      check("serverCa: a server presenting one certificate leaves it needed", sh(part(step, _.unless))._1 != 0)
      val one = sh(part(step, _.run))
      check("serverCa: and the run fails with the count and the hint", one._1 != 0 && one._2.contains("presented 1 certificate") && one._2.contains("apply keystone_single_node.scala"), one.toString)
      sh(s"cat $dir/leaf.crt $dir/leaf.crt > $dir/twoleaves.pem")
      serve("twoleaves.pem")
      check("serverCa: a last certificate that is not self-signed fails", sh(part(step, _.run))._1 != 0)
      val noTrust = serverCa("ca", "ks.example.com", 5000, s"$dir/img/ca.crt", trust = false, anchorsDeb = s"$dir/anchors2")
      serve("chain.pem")
      check("serverCa: trust = false: needed at first", sh(part(noTrust, _.unless))._1 != 0)
      check("serverCa: trust = false: the run succeeds", sh(part(noTrust, _.run))._1 == 0)
      check("serverCa: trust = false: then unchanged, with no trust store file made", sh(part(noTrust, _.unless))._1 == 0 && !Files.exists(dir.resolve("anchors2")))
      check("serverCa: a bad host is refused", scala.util.Try(serverCa("x", "a b", 5000, "/x/ca.crt")).isFailure)
      check("serverCa: a bad port is refused", scala.util.Try(serverCa("x", "h", 0, "/x/ca.crt")).isFailure)
      check("serverCa: a relative file is refused", scala.util.Try(serverCa("x", "h", 5000, "ca.crt")).isFailure)
      check("serverCa: a hint with a quote is refused", scala.util.Try(serverCa("x", "h", 5000, "/x/ca.crt", hint = "say \"no\"")).isFailure)

  private def aptRepositoryChecks(): Unit =
    val dir = Files.createTempDirectory("aptrepo")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val calls = dir.resolve("calls")
    def stand(name: String, body: String): Unit =
      val f = bin.resolve(name)
      Files.writeString(f, s"#!/bin/sh\n$body\n")
      check(s"aptRepository: the stand-in $name is executable", f.toFile.setExecutable(true))
    // curl gives a key unless told otherwise, gpg --dearmor copies it to the -o file, apt-get records its
    // arguments, and apt-cache answers from a file.
    stand("curl", s"""[ -e "$dir/nokey" ] && exit 22; echo "KEYDATA $$*"; exit 0""")
    stand("gpg", """while [ $# -gt 0 ]; do [ "$1" = -o ] && OUT="$2"; shift; done; cat > "$OUT"; exit 0""")
    stand("apt-get", s"""echo "apt-get $$*" >> "$calls"; exit 0""")
    stand("apt-cache", s"""cat "$dir/policy" 2>/dev/null; exit 0""")
    def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val keyring = s"$dir/keys/repo.gpg"
    val list = s"$dir/lists/repo.list"
    val repo = "https://apt.example.com/deb stable main"
    val step = aptRepository("repo", "https://apt.example.com/key.asc", keyring, list, repo, offers = "foo", update = true)
    check("aptRepository: nothing configured means it is needed", sh(part(step, _.unless))._1 != 0)
    Files.writeString(dir.resolve("policy"), "foo:\n  Candidate: 1\n     500 https://other.example.org stable/main Packages\n")
    val bad = sh(part(step, _.run))
    check("aptRepository: a package the repository does not offer fails the run, and says so", bad._1 != 0 && bad._2.contains("foo is not offered by apt.example.com"), bad.toString)
    Files.writeString(dir.resolve("policy"), "foo:\n  Candidate: 1\n     500 https://apt.example.com/deb stable/main Packages\n")
    val ran = sh(part(step, _.run))
    check("aptRepository: the run succeeds", ran._1 == 0 && ran._2.contains("apt repository apt.example.com configured"), ran.toString)
    check("aptRepository: the key is in the keyring, in a directory the run made", Files.readString(Path.of(keyring)).startsWith("KEYDATA"))
    check("aptRepository: the sources file holds the one line", Files.readString(Path.of(list)) == s"deb [signed-by=$keyring] $repo\n", Files.readString(Path.of(list)))
    check("aptRepository: with update, only this source's lists are refreshed", Files.readString(calls).contains(s"update -o Dir::Etc::sourcelist=$list -o Dir::Etc::sourceparts=-"), Files.readString(calls))
    check("aptRepository: configured and offered means unchanged", sh(part(step, _.unless))._1 == 0)
    Files.writeString(dir.resolve("policy"), "foo:\n  Candidate: (none)\n")
    check("aptRepository: a package no longer offered means it is needed", sh(part(step, _.unless))._1 != 0)
    val other = aptRepository("repo", "https://apt.example.com/key.asc", keyring, list, "https://apt.example.com/deb stable contrib")
    check("aptRepository: a different line means it is needed", sh(part(other, _.unless))._1 != 0)
    sh(part(other, _.run))
    check("aptRepository: and the run rewrites the line", Files.readString(Path.of(list)).contains("stable contrib") && sh(part(other, _.unless))._1 == 0)
    check("aptRepository: without update there is no apt-get call", !part(other, _.run).contains("apt-get"))
    Files.delete(Path.of(keyring))
    Files.writeString(dir.resolve("nokey"), "")
    val noKey = sh(part(other, _.run))
    check("aptRepository: a key that cannot be fetched fails the run", noKey._1 != 0, noKey.toString)
    check("aptRepository: and leaves it needed", sh(part(other, _.unless))._1 != 0)
    check("aptRepository: a key URL that is not https is refused", scala.util.Try(aptRepository("x", "http://k/x", keyring, list, repo)).isFailure)
    check("aptRepository: a relative keyring is refused", scala.util.Try(aptRepository("x", "https://k/x", "k.gpg", list, repo)).isFailure)
    check("aptRepository: a repository with a quote is refused", scala.util.Try(aptRepository("x", "https://k/x", keyring, list, "https://r/ 'x'")).isFailure)
    check("aptRepository: a bad package name is refused", scala.util.Try(aptRepository("x", "https://k/x", keyring, list, repo, offers = "a b")).isFailure)

  private def downloadChecks(): Unit =
    val dir = Files.createTempDirectory("download")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val curl = bin.resolve("curl")
    // A stand-in curl: it copies the file `served` to the -o file, or fails when `fail` exists.
    Files.writeString(
      curl,
      s"""#!/bin/sh
         |while [ $$# -gt 0 ]; do [ "$$1" = -o ] && OUT="$$2"; shift; done
         |[ -e "$dir/fail" ] && exit 22
         |cat "$dir/served" > "$$OUT"
         |""".stripMargin
    )
    check("download: the stand-in curl is executable", curl.toFile.setExecutable(true))
    def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
    def serve(text: String): Unit = { val _ = Files.writeString(dir.resolve("served"), text) }
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val img = s"$dir/img/a.qcow2"
    val step = download("img", "https://example.com/a.qcow2", img, magic = "QFI")
    serve("QFIdata")
    check("download: no file means it is needed", sh(part(step, _.unless))._1 != 0)
    val ran = sh(part(step, _.run))
    check("download: the run succeeds and puts the file in place", ran._1 == 0 && Files.readString(Path.of(img)) == "QFIdata", ran.toString)
    check("download: and leaves no .part file", !Files.exists(Path.of(img + ".part")))
    check("download: a file that passes the checks means unchanged", sh(part(step, _.unless))._1 == 0)
    Files.writeString(Path.of(img), "XXXdata")
    check("download: a file with the wrong magic means it is needed", sh(part(step, _.unless))._1 != 0)
    sh(part(step, _.run))
    check("download: and the run replaces it", Files.readString(Path.of(img)) == "QFIdata")
    Files.delete(Path.of(img))
    serve("XXXdata")
    val wrong = sh(part(step, _.run))
    check("download: a download with the wrong magic fails, says so and leaves nothing", wrong._1 != 0 && wrong._2.contains("not what was expected") && !Files.exists(Path.of(img)) && !Files.exists(Path.of(img + ".part")), wrong.toString)
    Files.writeString(dir.resolve("fail"), "")
    val failed = sh(part(step, _.run))
    check("download: a failing curl fails the run and names the file to put there", failed._1 != 0 && failed._2.contains(s"put the file at $img"), failed.toString)
    Files.delete(dir.resolve("fail"))
    val skipped = download("img", "https://example.com/a.qcow2", img, magic = "QFI", skipIf = "true")
    check("download: skipIf true means unchanged without the file", sh(part(skipped, _.unless))._1 == 0)
    val notSkipped = download("img", "https://example.com/a.qcow2", img, magic = "QFI", skipIf = "false")
    check("download: skipIf false means the checks decide", sh(part(notSkipped, _.unless))._1 != 0)
    val sum = sh("printf QFIdata | sha256sum | cut -d' ' -f1")._2
    val summed = download("img", "https://example.com/a.qcow2", img, sha256 = sum)
    serve("QFIdata")
    check("download: a matching checksum is accepted", sh(part(summed, _.run))._1 == 0 && sh(part(summed, _.unless))._1 == 0)
    val badSum = download("img", "https://example.com/a.qcow2", img, sha256 = "0" * 64)
    check("download: a file with another checksum means it is needed", sh(part(badSum, _.unless))._1 != 0)
    check("download: a bad checksum is refused", scala.util.Try(download("x", "https://e/x", img, sha256 = "abc")).isFailure)
    check("download: a relative path is refused", scala.util.Try(download("x", "https://e/x", "a.qcow2")).isFailure)
    check("download: a path with .. is refused", scala.util.Try(download("x", "https://e/x", "/a/../b")).isFailure)
    check("download: a URL with a quote is refused", scala.util.Try(download("x", "https://e/'x", img)).isFailure)

  private def archiveFilesChecks(): Unit =
    val dir = Files.createTempDirectory("archive")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val curl = bin.resolve("curl")
    Files.writeString(
      curl,
      s"""#!/bin/sh
         |while [ $$# -gt 0 ]; do [ "$$1" = -o ] && OUT="$$2"; shift; done
         |cat "$dir/served.tar.gz" > "$$OUT"
         |""".stripMargin
    )
    check("archiveFiles: the stand-in curl is executable", curl.toFile.setExecutable(true))
    def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val made = sh(s"""cd $dir && mkdir -p pkg-1/sub && printf 'one' > pkg-1/one && printf 'two' > pkg-1/sub/two && tar czf served.tar.gz pkg-1""")
    check("archiveFiles: the test archive is made", made._1 == 0, made.toString)
    val t1 = s"$dir/out/bin/one"
    val t2 = s"$dir/out/lib/two"
    val step = archiveFiles("tools", "https://example.com/pkg.tar.gz", List("pkg-1/one" -> t1, "pkg-1/sub/two" -> t2))
    check("archiveFiles: nothing installed means it is needed", sh(part(step, _.unless))._1 != 0)
    val ran = sh(part(step, _.run))
    check("archiveFiles: the run succeeds", ran._1 == 0, ran.toString)
    check("archiveFiles: both files are in place, with their directories made", Files.readString(Path.of(t1)) == "one" && Files.readString(Path.of(t2)) == "two")
    check("archiveFiles: and executable", Files.isExecutable(Path.of(t1)) && Files.isExecutable(Path.of(t2)))
    check("archiveFiles: everything there means unchanged", sh(part(step, _.unless))._1 == 0)
    Files.delete(Path.of(t2))
    check("archiveFiles: one missing means it is needed", sh(part(step, _.unless))._1 != 0)
    val missing = archiveFiles("tools", "https://example.com/pkg.tar.gz", List("pkg-1/nothing" -> s"$dir/out/x"))
    val bad = sh(part(missing, _.run))
    check("archiveFiles: a file not in the archive fails the run and says so", bad._1 != 0 && bad._2.contains("pkg-1/nothing is not in the archive"), bad.toString)
    val data = archiveFiles("data", "https://example.com/pkg.tar.gz", List("pkg-1/one" -> s"$dir/out/data"), mode = "0644")
    sh(part(data, _.run))
    check("archiveFiles: mode 0644 gives a file that is not executable", Files.exists(Path.of(s"$dir/out/data")) && !Files.isExecutable(Path.of(s"$dir/out/data")) && sh(part(data, _.unless))._1 == 0)
    check("archiveFiles: a path with .. inside is refused", scala.util.Try(archiveFiles("x", "https://e/x", List("../one" -> t1))).isFailure)
    check("archiveFiles: a relative target is refused", scala.util.Try(archiveFiles("x", "https://e/x", List("one" -> "bin/one"))).isFailure)
    check("archiveFiles: a bad mode is refused", scala.util.Try(archiveFiles("x", "https://e/x", List("one" -> t1), mode = "755")).isFailure)
    check("archiveFiles: no files is refused", scala.util.Try(archiveFiles("x", "https://e/x", Nil)).isFailure)

  // A small Keystone in Python (projects, users, roles, services, endpoints and tokens in memory), for the helper
  // that keystoneEntries writes.
  private val fakeKeystone: String =
    """import json, sys, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
DB = {'projects': [{'id':'p-admin','name':'admin'}], 'users':[{'id':'u-admin','name':'admin','password':'adminpw'}], 'roles':[{'id':'r-admin','name':'admin'}], 'services':[], 'endpoints':[], 'grants':[], 'domains':[]}
class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def send(self,code,obj=None,headers=None):
        b=json.dumps(obj).encode() if obj is not None else b''
        self.send_response(code)
        for k,v in (headers or {}).items(): self.send_header(k,v)
        self.send_header('Content-Length',str(len(b))); self.end_headers(); self.wfile.write(b)
    def body(self):
        n=int(self.headers.get('Content-Length') or 0); return json.loads(self.rfile.read(n)) if n else None
    def do_GET(self):
        u=urlparse(self.path); q={k:v[0] for k,v in parse_qs(u.query).items()}; coll=u.path.strip('/').split('/')[-1]
        if coll=='dump': return self.send(200,DB)
        items=[x for x in DB.get(coll,[]) if all(str(x.get(k))==v for k,v in q.items())]
        self.send(200,{coll:[{k:v for k,v in x.items() if k!='password'} for x in items]})
    def do_POST(self):
        u=urlparse(self.path); b=self.body()
        if u.path.endswith('/auth/tokens'):
            us=b['auth']['identity']['password']['user']; dn=us['domain']['name']
            did='default' if dn=='Default' else next((d['id'] for d in DB['domains'] if d['name']==dn),None)
            usr=next((x for x in DB['users'] if x['name']==us['name'] and x['password']==us['password'] and x.get('domain_id','default')==did),None)
            if not usr: return self.send(401,{})
            if 'scope' not in b['auth']: return self.send(201,{'token':{}},{'X-Subject-Token':'tok'})
            proj=b['auth']['scope']['project']['name']
            if proj not in [p['name'] for p in DB['projects']]: return self.send(401,{})
            if us['name']!='admin' and not any(g[0]==usr['id'] and g[1]==next(p['id'] for p in DB['projects'] if p['name']==proj) for g in DB['grants']): return self.send(401,{})
            return self.send(201,{'token':{}},{'X-Subject-Token':'tok'})
        coll=u.path.strip('/').split('/')[-1]; key=coll[:-1]; obj=dict(b[key]); obj['id']=str(uuid.uuid4()); DB[coll].append(obj); self.send(201,{key:{k:v for k,v in obj.items() if k!='password'}})
    def do_PATCH(self):
        u=urlparse(self.path); b=self.body(); parts=u.path.strip('/').split('/'); coll,i=parts[-2],parts[-1]; key=coll[:-1]
        for x in DB[coll]:
            if x['id']==i: x.update(b[key])
        self.send(200,{})
    def do_HEAD(self):
        p=urlparse(self.path).path.strip('/').split('/')
        self.send(204 if (p[-3],p[-5],p[-1]) in [tuple(g) for g in DB['grants']] else 404)
    def do_PUT(self):
        p=urlparse(self.path).path.strip('/').split('/')
        DB['grants'].append((p[-3],p[-5],p[-1])); self.send(204)
    def do_DELETE(self):
        parts=urlparse(self.path).path.strip('/').split('/'); coll,i=parts[-2],parts[-1]
        if coll=='domains' and any(x['id']==i and x.get('enabled',True) for x in DB[coll]): return self.send(403,{})
        DB[coll]=[x for x in DB[coll] if x['id']!=i]
        if coll=='services': DB['endpoints']=[e for e in DB['endpoints'] if e['service_id']!=i]
        self.send(204)
HTTPServer(('127.0.0.1',int(sys.argv[1])),H).serve_forever()
"""

  private def keystoneChecks(): Unit =
    val dir = Files.createTempDirectory("keystone")
    val server = dir.resolve("fake_ks.py")
    Files.writeString(server, fakeKeystone)
    val port =
      val sock = new java.net.ServerSocket(0)
      try sock.getLocalPort
      finally sock.close()
    val proc = new ProcessBuilder("python3", server.toString, port.toString).redirectErrorStream(true).start()
    try
      def up: Boolean = scala.util.Try(new java.net.Socket("127.0.0.1", port).close()).isSuccess
      var tries = 0
      while !up && tries < 50 do
        Thread.sleep(100)
        tries += 1
      check("keystone: the fake Keystone is up", up)
      val url = s"http://127.0.0.1:$port"
      def fileText(r: ApplyResource): String = r match
        case f: ApplyResource.File =>
          f.content match
            case Some(ApplyResource.Content.Hidden(secret)) => secret.reveal
            case other                                      => s"not hidden text: $other"
        case other => s"not a file: $other"
      def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
        case c: ApplyResource.Command => pick(c)
        case other                    => s"not a command: $other"
      def helperRun(r: ApplyResource, text: String, args: String*): (Int, String) =
        val script = dir.resolve(s"helper-${r.id.hashCode.abs}.py")
        Files.writeString(script, text)
        runShell(s"python3 -I ${script} ${args.mkString(" ")}")
      def entries(password: String, urlPath: String) =
        keystoneEntries(
          "register placement",
          "placement",
          Secret(password),
          List(KeystoneService("placement", "placement", s"http://tst13:8780$urlPath")),
          url,
          Secret("adminpw"),
          "/no/ca.crt",
          roles = List("creator")
        )
      val (helper, ensure) = entries("p\"w\\1", "")
      check("keystone: the helper is a root-only secret file named for the service", helper.id == "file[/usr/local/sbin/orphera-keystone-placement]")
      check("keystone: the command runs the helper and is guarded by its check", part(ensure, _.run).contains("orphera-keystone-placement' ensure") && part(ensure, _.unless).contains("check"), part(ensure, _.run))
      val text = fileText(helper)
      def run(arg: String): (Int, String) = helperRun(helper, text, arg)
      check("keystone: nothing registered fails the check", run("check")._1 != 0)
      check("keystone: and exists finds nothing", run("exists")._1 != 0)
      val made = run("ensure")
      check("keystone: ensure succeeds", made._1 == 0 && made._2.contains("registered placement"), made.toString)
      check("keystone: then the check passes (a password with a quote and a backslash works)", run("check")._1 == 0, run("check").toString)
      check("keystone: and exists finds the entries", run("exists")._1 == 0)
      check("keystone: ensure again changes nothing and succeeds", run("ensure")._1 == 0 && run("check")._1 == 0)
      val (urlHelper, _) = entries("p\"w\\1", "/v2")
      val urlText = fileText(urlHelper)
      check("keystone: another endpoint URL fails the check", helperRun(urlHelper, urlText, "check")._1 != 0)
      check("keystone: ensure puts the URL right", helperRun(urlHelper, urlText, "ensure")._1 == 0 && helperRun(urlHelper, urlText, "check")._1 == 0)
      val (pwHelper, _) = entries("another", "/v2")
      val pwText = fileText(pwHelper)
      check("keystone: another password fails the check", helperRun(pwHelper, pwText, "check")._1 != 0)
      check("keystone: ensure sets the password", helperRun(pwHelper, pwText, "ensure")._1 == 0 && helperRun(pwHelper, pwText, "check")._1 == 0)
      val gone = absentKeystoneEntries("remove placement", "placement")
      check("keystone: removal is guarded by the helper being there and an entry existing", part(gone, _.onlyIf).contains("orphera-keystone-placement' exists"), part(gone, _.onlyIf))
      val removed = helperRun(pwHelper, pwText, "remove")
      check("keystone: remove succeeds", removed._1 == 0 && removed._2.contains("removed placement"), removed.toString)
      check("keystone: and nothing is left", helperRun(pwHelper, pwText, "exists")._1 != 0 && helperRun(pwHelper, pwText, "check")._1 != 0)
      check("keystone: removing again succeeds", helperRun(pwHelper, pwText, "remove")._1 == 0)
      check("keystone: a name with a space is refused", scala.util.Try(keystoneEntries("x", "a b", Secret("x"), List(KeystoneService("a", "a", "http://h")), url, Secret("x"), "/ca")).isFailure)
      val (userHelper, userEnsure) = keystoneEntries("register a user", "plain", Secret("pw"), Nil, url, Secret("adminpw"), "/no/ca.crt")
      val userText = fileText(userHelper)
      def userRun(arg: String): (Int, String) = helperRun(userHelper, userText, arg)
      check("keystone: a user without services is allowed and has the check guard", part(userEnsure, _.unless).contains("orphera-keystone-plain' check"))
      check("keystone: a user without services fails the check before it exists", userRun("check")._1 != 0 && userRun("exists")._1 != 0)
      check("keystone: ensure makes only the user, and the check then passes", userRun("ensure")._1 == 0 && userRun("check")._1 == 0 && userRun("exists")._1 == 0, userRun("check").toString)
      val userRemoved = userRun("remove")
      check("keystone: remove takes the user away", userRemoved._1 == 0 && userRun("exists")._1 != 0, userRemoved.toString)
      // A domain with its admin (Heat's stack domain, Magnum's trustee domain), and a service on top.
      def dumpDb: String =
        scala.util.Using.resource(java.net.URI.create(s"$url/dump").toURL.openStream())(in => new String(in.readAllBytes(), "UTF-8"))
      def domainEntries(password: String) =
        keystoneEntries(
          "register heat",
          "heat",
          Secret("heatpw"),
          List(
            KeystoneService("heat", "orchestration", "http://tst20:8004/v1/%(tenant_id)s"),
            KeystoneService("heat-cfn", "cloudformation", "http://tst20:8000/v1")
          ),
          url,
          Secret("adminpw"),
          "/no/ca.crt",
          roles = List("heat_stack_owner", "heat_stack_user"),
          domain = Some(KeystoneDomain("heat", "Owns stack users", "heat_domain_admin", Secret(password)))
        )
      val (domHelper, _) = domainEntries("dom\"pw")
      val domText = fileText(domHelper)
      def domRun(arg: String): (Int, String) = helperRun(domHelper, domText, arg)
      check("keystone: a domain registration fails the check before it exists", domRun("check")._1 != 0 && domRun("exists")._1 != 0)
      val domMade = domRun("ensure")
      check("keystone: ensure makes the domain, its admin and the services", domMade._1 == 0 && domMade._2.contains("domain heat") && domMade._2.contains("orchestration cloudformation"), domMade.toString)
      check("keystone: the domain registration then passes the check", domRun("check")._1 == 0, domRun("check").toString)
      check("keystone: and ensure again changes nothing", domRun("ensure")._1 == 0 && domRun("check")._1 == 0 && dumpDb.split("\"name\": \"heat_domain_admin\"").length == 2)
      val (domPwHelper, _) = domainEntries("another")
      val domPwText = fileText(domPwHelper)
      check("keystone: another domain admin password fails the check", helperRun(domPwHelper, domPwText, "check")._1 != 0)
      check("keystone: ensure sets it", helperRun(domPwHelper, domPwText, "ensure")._1 == 0 && helperRun(domPwHelper, domPwText, "check")._1 == 0)
      // The service makes a user and a project of its own in the domain, as Heat does for stacks.
      val domainId = "\"name\": \"heat\", \"description\": \"Owns stack users\", \"id\": \"([^\"]+)\"".r.findFirstMatchIn(dumpDb).map(_.group(1)).getOrElse("none")
      check("keystone: the domain is in the database", domainId != "none", dumpDb)
      val seeded = runShell(
        s"""python3 -I -c "
import json, urllib.request
def post(path, body):
    r = urllib.request.Request('$url/v3/' + path, data=json.dumps(body).encode(), method='POST')
    urllib.request.urlopen(r)
post('users', {'user': {'name': 'stack-user', 'domain_id': '$domainId', 'password': 'x'}})
post('projects', {'project': {'name': 'stack-project', 'domain_id': '$domainId'}})
" """
      )
      check("keystone: a stack user and project are made in the domain", seeded._1 == 0 && dumpDb.contains("stack-user") && dumpDb.contains("stack-project"), seeded.toString)
      val domRemoved = helperRun(domPwHelper, domPwText, "remove")
      check("keystone: remove succeeds and says what went", domRemoved._1 == 0 && domRemoved._2.contains("domain heat"), domRemoved.toString)
      check("keystone: the domain, its users and projects, and the services are all gone", domRun("exists")._1 != 0 && !dumpDb.contains("stack-user") && !dumpDb.contains("stack-project") && !dumpDb.contains("heat_domain_admin"), dumpDb)
      check("keystone: removing a domain registration again succeeds", domRun("remove")._1 == 0)
      check("keystone: a domain name with a space is refused", scala.util.Try(keystoneEntries("x", "a", Secret("x"), Nil, url, Secret("x"), "/ca", domain = Some(KeystoneDomain("a b", "d", "adm", Secret("x"))))).isFailure)
      check("keystone: an endpoint that is not a URL is refused", scala.util.Try(keystoneEntries("x", "a", Secret("x"), List(KeystoneService("a", "a", "tst13:80")), url, Secret("x"), "/ca")).isFailure)
      check("keystone: a password with a non-ASCII character is refused", scala.util.Try(keystoneEntries("x", "a", Secret("pä"), List(KeystoneService("a", "a", "http://h")), url, Secret("x"), "/ca")).isFailure)
      // Swift: the admin endpoint has no project in its URL, the other two have.
      val swiftPublic = "http://rgw:8080/swift/v1/AUTH_%(project_id)s"
      val swiftAdmin = "http://rgw:8080/swift/v1"
      def swiftEntries(adminUrl: String) =
        keystoneEntries(
          "register object storage",
          "rgw",
          Secret("rgwpw"),
          List(
            KeystoneService("swift", "object-store", swiftPublic, Some(adminUrl)),
            KeystoneService("s3", "s3", "http://rgw:8080")
          ),
          url,
          Secret("adminpw"),
          "/no/ca.crt",
          roles = List("member")
        )
      val (swiftHelper, _) = swiftEntries(swiftAdmin)
      val swiftText = fileText(swiftHelper)
      def swiftRun(arg: String): (Int, String) = helperRun(swiftHelper, swiftText, arg)
      check("keystone: object storage fails the check before it exists", swiftRun("check")._1 != 0)
      check("keystone: ensure makes both services", swiftRun("ensure")._1 == 0 && swiftRun("check")._1 == 0, swiftRun("check").toString)
      val swiftDb = dumpDb
      check(
        "keystone: the admin endpoint has its own URL and the other two the project one",
        swiftDb.split(java.util.regex.Pattern.quote("\"url\": \"" + swiftPublic + "\"")).length == 3 &&
          swiftDb.split(java.util.regex.Pattern.quote("\"url\": \"" + swiftAdmin + "\"")).length == 2,
        swiftDb
      )
      val (swiftOtherHelper, _) = swiftEntries("http://rgw:8080/swift/v2")
      val swiftOtherText = fileText(swiftOtherHelper)
      check("keystone: another admin URL fails the check", helperRun(swiftOtherHelper, swiftOtherText, "check")._1 != 0)
      check("keystone: ensure puts the admin URL right and leaves the others", helperRun(swiftOtherHelper, swiftOtherText, "ensure")._1 == 0 && helperRun(swiftOtherHelper, swiftOtherText, "check")._1 == 0 && swiftRun("check")._1 != 0)
      check("keystone: an admin URL that is not a URL is refused", scala.util.Try(swiftEntries("rgw:8080")).isFailure)
      check("keystone: object storage is removed with its user", helperRun(swiftOtherHelper, swiftOtherText, "remove")._1 == 0 && helperRun(swiftOtherHelper, swiftOtherText, "exists")._1 != 0)
    finally proc.destroy()

  // A small Keystone with Glance and Nova in Python (a token with a catalogue, images, flavors), for the
  // openstackImage and openstackFlavor helpers.
  private val fakeOpenstack: String =
    """import json, sys, re, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
PORT = int(sys.argv[1])
DB = {'images': {}, 'flavors': {}, 'uploads': {}}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj=None, headers=None):
        b = json.dumps(obj).encode() if obj is not None else b''
        self.send_response(code)
        self.send_header('Content-Length', str(len(b)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(b)
    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n)
    def route(self, method):
        u = urlparse(self.path); path = u.path; q = parse_qs(u.query)
        raw = self.body()
        if path == '/v3/auth/tokens' and method == 'POST':
            b = json.loads(raw)
            if b['auth']['identity']['password']['user']['password'] != 'adminpw': return self.send(401, {})
            base = 'http://127.0.0.1:%d' % PORT
            cat = [{'type': 'image', 'endpoints': [{'interface': 'public', 'url': base}]}, {'type': 'compute', 'endpoints': [{'interface': 'public', 'url': base + '/v2.1'}]}]
            return self.send(201, {'token': {'catalog': cat}}, {'X-Subject-Token': 'tok'})
        if self.headers.get('X-Auth-Token') != 'tok': return self.send(401, {})
        if path == '/dump': return self.send(200, DB)
        if path == '/v2/images' and method == 'GET':
            imgs = [i for i in DB['images'].values() if 'name' not in q or i['name'] == q['name'][0]]
            return self.send(200, {'images': imgs})
        if path == '/v2/images' and method == 'POST':
            b = json.loads(raw); i = dict(b); i['id'] = str(uuid.uuid4()); i['status'] = 'queued'
            DB['images'][i['id']] = i; return self.send(201, i)
        m = re.match(r'/v2/images/([^/]+)(/file)?$', path)
        if m:
            i = DB['images'].get(m.group(1))
            if i is None: return self.send(404, {})
            if m.group(2) and method == 'PUT':
                DB['uploads'][i['id']] = len(raw); i['status'] = 'active'; return self.send(204)
            if method == 'GET': return self.send(200, i)
            if method == 'DELETE': del DB['images'][i['id']]; return self.send(204)
        m = re.match(r'/v2\.1/flavors/([^/]+)$', path)
        if m:
            f = DB['flavors'].get(m.group(1))
            if method == 'GET': return self.send(200, {'flavor': f}) if f else self.send(404, {})
            if method == 'DELETE':
                if f: del DB['flavors'][m.group(1)]; return self.send(202)
                return self.send(404, {})
        if path == '/v2.1/flavors' and method == 'POST':
            f = json.loads(raw)['flavor']; DB['flavors'][f['id']] = f; return self.send(200, {'flavor': f})
        self.send(404, {})
    def do_GET(self): self.route('GET')
    def do_POST(self): self.route('POST')
    def do_PUT(self): self.route('PUT')
    def do_DELETE(self): self.route('DELETE')
HTTPServer(('127.0.0.1', PORT), H).serve_forever()
"""

  private def openstackChecks(): Unit =
    val dir = Files.createTempDirectory("openstack")
    val server = dir.resolve("fake_os.py")
    Files.writeString(server, fakeOpenstack)
    val port =
      val sock = new java.net.ServerSocket(0)
      try sock.getLocalPort
      finally sock.close()
    val proc = new ProcessBuilder("python3", server.toString, port.toString).redirectErrorStream(true).start()
    try
      def up: Boolean = scala.util.Try(new java.net.Socket("127.0.0.1", port).close()).isSuccess
      var tries = 0
      while !up && tries < 50 do
        Thread.sleep(100)
        tries += 1
      check("openstack: the fake Keystone, Glance and Nova are up", up)
      val url = s"http://127.0.0.1:$port"
      val imageFile = dir.resolve("disk.qcow2")
      Files.write(imageFile, ("QFI" + "0" * 1000).getBytes("UTF-8"))
      def fileText(r: ApplyResource): String = r match
        case f: ApplyResource.File =>
          f.content match
            case Some(ApplyResource.Content.Hidden(secret)) => secret.reveal
            case other                                      => s"not hidden text: $other"
        case other => s"not a file: $other"
      def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
        case c: ApplyResource.Command => pick(c)
        case other                    => s"not a command: $other"
      def helperRun(r: ApplyResource, text: String, args: String*): (Int, String) =
        val script = dir.resolve(s"helper-${r.id.hashCode.abs}.py")
        Files.writeString(script, text)
        runShell(s"python3 -I ${script} ${args.mkString(" ")}")
      def images(props: Map[String, String], replace: Boolean = false, file: String = imageFile.toString) =
        openstackImage(
          "upload the guest image",
          "guest-image",
          file,
          url,
          Secret("adminpw"),
          "/no/ca.crt",
          tags = List("guest", "mysql"),
          properties = props,
          replace = replace
        )
      val (imageHelper, imageEnsure) = images(Map("hw_rng_model" -> "virtio"))
      check("openstack: the image helper is a root-only secret file named for the image", imageHelper.id == "file[/usr/local/sbin/orphera-image-guest-image]", imageHelper.id)
      check(
        "openstack: the command runs the helper under a heartbeat and is guarded by its check",
        part(imageEnsure, _.run).contains("heartbeat '/usr/local/sbin/orphera-image-guest-image' ensure") && part(imageEnsure, _.unless).contains("check"),
        part(imageEnsure, _.run)
      )
      val imageText = fileText(imageHelper)
      def run(arg: String*): (Int, String) = helperRun(imageHelper, imageText, arg*)
      check("openstack: no image fails the check and exists", run("check")._1 != 0 && run("exists")._1 != 0)
      val uploaded = run("ensure")
      check("openstack: ensure uploads the file", uploaded._1 == 0 && uploaded._2.contains("image uploaded: guest-image") && uploaded._2.contains("1003 bytes"), uploaded.toString)
      check("openstack: then the check passes and exists finds it", run("check")._1 == 0 && run("exists")._1 == 0, run("check").toString)
      val again = run("ensure")
      check("openstack: ensure again uploads nothing", again._1 == 0 && again._2.contains("already active"), again.toString)
      val replaced = run("ensure", "--replace")
      check("openstack: ensure --replace uploads again and leaves one image", replaced._1 == 0 && replaced._2.contains("image uploaded") && run("remove")._2.contains("removed 1 image(s)"), replaced.toString)
      check("openstack: after the removal nothing is left", run("exists")._1 != 0 && run("check")._1 != 0 && run("remove")._2.contains("removed 0 image(s)"))
      val (otherHelper, _) = images(Map("hw_rng_model" -> "none"))
      val otherText = fileText(otherHelper)
      check("openstack: a new image is made for the properties", helperRun(otherHelper, otherText, "ensure")._1 == 0)
      val (changedHelper, _) = images(Map("hw_rng_model" -> "virtio"))
      val changedText = fileText(changedHelper)
      check("openstack: another property value fails the check", helperRun(changedHelper, changedText, "check")._1 != 0)
      check("openstack: ensure replaces the image and the check then passes", helperRun(changedHelper, changedText, "ensure")._1 == 0 && helperRun(changedHelper, changedText, "check")._1 == 0 && helperRun(changedHelper, changedText, "remove")._2.contains("removed 1 image(s)"))
      val (missingHelper, _) = images(Map.empty, file = dir.resolve("none.qcow2").toString)
      val missing = helperRun(missingHelper, fileText(missingHelper), "ensure")
      check("openstack: a missing image file fails with its name", missing._1 != 0 && missing._2.contains("none.qcow2"), missing.toString)
      val (_, replaceEnsure) = images(Map.empty, replace = true)
      check("openstack: with replace the command always runs and passes --replace", part(replaceEnsure, _.run).contains("ensure --replace") && part(replaceEnsure, _.unless) == "false", part(replaceEnsure, _.run))
      val gone = absentOpenstackImage("remove the guest image", "guest-image")
      check("openstack: image removal is guarded by the helper and an image existing", part(gone, _.onlyIf).contains("orphera-image-guest-image' exists"), part(gone, _.onlyIf))
      check("openstack: an image name with a space is refused", scala.util.Try(openstackImage("x", "a b", "/f", url, Secret("x"), "/ca")).isFailure)
      check("openstack: a relative image file is refused", scala.util.Try(openstackImage("x", "a", "f", url, Secret("x"), "/ca")).isFailure)
      check("openstack: a property value that is not ASCII is refused", scala.util.Try(openstackImage("x", "a", "/f", url, Secret("x"), "/ca", properties = Map("p" -> "pä"))).isFailure)

      def flavors(ram: Int) =
        openstackFlavor("make the flavor", "guest-flavor", "guest.flavor", ram, 1, 10, url, Secret("adminpw"), "/no/ca.crt")
      val (flavorHelper, flavorEnsure) = flavors(2048)
      check("openstack: the flavor helper is named for the id", flavorHelper.id == "file[/usr/local/sbin/orphera-flavor-guest-flavor]", flavorHelper.id)
      check("openstack: the flavor command runs the helper and is guarded by its check", part(flavorEnsure, _.run).contains("orphera-flavor-guest-flavor' ensure") && part(flavorEnsure, _.unless).contains("check"))
      val flavorText = fileText(flavorHelper)
      def flavorRun(arg: String): (Int, String) = helperRun(flavorHelper, flavorText, arg)
      check("openstack: no flavor fails the check and exists", flavorRun("check")._1 != 0 && flavorRun("exists")._1 != 0)
      val made = flavorRun("ensure")
      check("openstack: ensure makes the flavor", made._1 == 0 && made._2.contains("made (1 vCPU, 2048 MB, 10 GB)"), made.toString)
      check("openstack: then the check passes and ensure again changes nothing", flavorRun("check")._1 == 0 && flavorRun("exists")._1 == 0 && flavorRun("ensure")._2.contains("exists"))
      val (biggerHelper, _) = flavors(4096)
      val biggerText = fileText(biggerHelper)
      check("openstack: other memory fails the check", helperRun(biggerHelper, biggerText, "check")._1 != 0)
      val bigger = helperRun(biggerHelper, biggerText, "ensure")
      check("openstack: ensure replaces the flavor", bigger._1 == 0 && bigger._2.contains("4096 MB") && helperRun(biggerHelper, biggerText, "check")._1 == 0, bigger.toString)
      val flavorGone = absentOpenstackFlavor("remove the flavor", "guest-flavor")
      check("openstack: flavor removal is guarded by the helper and the flavor existing", part(flavorGone, _.onlyIf).contains("orphera-flavor-guest-flavor' exists"), part(flavorGone, _.onlyIf))
      check("openstack: remove deletes the flavor and nothing is left", helperRun(biggerHelper, biggerText, "remove")._1 == 0 && helperRun(biggerHelper, biggerText, "exists")._1 != 0)
      check("openstack: a flavor with no memory is refused", scala.util.Try(openstackFlavor("x", "a", "a", 0, 1, 1, url, Secret("x"), "/ca")).isFailure)
    finally proc.destroy()

  // A stand-in `cephadm shell` for the Ceph helpers: pools, cephx users and settings in a JSON file, and `dump` to
  // read it back. FAKE_CEPH_DOWN makes every command fail, as a cluster that cannot be reached does.
  private val fakeCephadm: String =
    """#!/usr/bin/python3
import json, os, sys
S = os.environ['FAKE_CEPH']
if os.environ.get('FAKE_CEPH_DOWN'):
    sys.exit(1)
a = sys.argv[1:]
assert a[0] == 'shell'
a = a[1:]
mount = None
if a[0] == '--mount':
    mount = a[1]
    a = a[2:]
assert a[0] == '--'
a = a[1:]
st = json.load(open(S)) if os.path.exists(S) else {'pools': [], 'users': {}, 'conf': {}, 'log': []}
for k in ('fs', 'nfs'):
    st.setdefault(k, [])
def save():
    json.dump(st, open(S, 'w'))
c = ' '.join(a)
if a[0] == 'dump':
    print(json.dumps(st))
elif c == 'ceph osd pool ls':
    print('\n'.join(st['pools']))
elif c.startswith('ceph osd pool create '):
    st['pools'].append(a[4]); st['log'].append(c); save()
elif c.startswith('rbd pool init '):
    if a[3] not in st['pools']:
        sys.exit(2)
    st['log'].append(c); save()
elif c.startswith('ceph osd pool application enable '):
    st['log'].append(c); save()
elif c.startswith('ceph osd pool delete '):
    if st['conf'].get('mon_allow_pool_delete') != 'true' or a[4] != a[5] or a[4] not in st['pools']:
        sys.exit(1)
    st['pools'].remove(a[4]); st['log'].append(c); save()
elif c.startswith('ceph config set mon '):
    st['conf'][a[4]] = a[5]; save()
elif c.startswith('ceph mon set auth_allowed_ciphers '):
    st['log'].append(c); save()
elif a[:3] == ['ceph', 'auth', 'get-key']:
    if a[3] not in st['users']:
        sys.exit(2)
    print(st['users'][a[3]]['key'])
elif a[:3] == ['ceph', 'auth', 'get']:
    u = st['users'].get(a[3])
    if u is None:
        sys.exit(2)
    print('[%s]\n\tkey = %s' % (a[3], u['key']))
    for k, v in u['caps'].items():
        print('\tcaps %s = "%s"' % (k, v))
elif a[:3] == ['ceph', 'auth', 'import']:
    assert mount is not None and a[4] == '/mnt/' + os.path.basename(mount), (mount, a)
    name = None
    for line in open(mount).read().splitlines():
        line = line.strip()
        if line.startswith('['):
            name = line.strip('[]'); st['users'][name] = {'key': '', 'caps': {}}
        elif line.startswith('key = '):
            st['users'][name]['key'] = line[6:]
        elif line.startswith('caps '):
            k, v = line[5:].split(' = ', 1)
            st['users'][name]['caps'][k] = v.strip('"')
    st['log'].append('import ' + name); save()
elif a[:3] == ['ceph', 'auth', 'del']:
    if a[3] not in st['users']:
        sys.exit(2)
    del st['users'][a[3]]; save()
elif c == 'ceph fs ls':
    for n in st['fs']:
        print('name: %s, metadata pool: %s_meta, data pools: [%s_data ]' % (n, n, n))
    if not st['fs']:
        print('No filesystems enabled')
elif c.startswith('ceph fs volume create '):
    st['fs'].append(a[4]); st['log'].append(c); save()
elif c.startswith('ceph fs volume rm '):
    if st['conf'].get('mon_allow_pool_delete') != 'true' or a[4] not in st['fs'] or a[5] != '--yes-i-really-mean-it':
        sys.exit(1)
    st['fs'].remove(a[4]); st['log'].append(c); save()
elif c == 'ceph mds stat':
    print('%s:1 {0=a=up:active}' % st['fs'][0] if st['fs'] and not os.environ.get('FAKE_NO_MDS') else 'cephfs:0')
elif c == 'ceph orch host ls --format json':
    print(json.dumps([{'hostname': 'tst0.example', 'addr': '10.10.5.12'}, {'hostname': 'tst1.example', 'addr': '10.10.5.13'}]))
elif c == 'ceph nfs cluster ls':
    print(json.dumps(st['nfs']))
elif c.startswith('ceph nfs cluster create '):
    st['nfs'].append(a[4]); st['log'].append(c); save()
elif c.startswith('ceph nfs cluster rm '):
    if a[4] not in st['nfs']:
        sys.exit(2)
    st['nfs'].remove(a[4]); st['log'].append(c); save()
elif a[:3] == ['ceph', 'auth', 'ls']:
    print('installed auth entries:\n')
    for n in st['users']:
        print(n)
else:
    sys.stderr.write('fake cephadm: unknown ' + c + '\n'); sys.exit(3)
"""

  // A whole cephx key of the given type (1 = aes, 2 = aes256k) over 16 bytes of `seed`.
  private def cephKeyText(keyType: Int, seed: Int): String =
    val b = java.nio.ByteBuffer.allocate(28).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.putShort(keyType.toShort).putInt(1700000000).putInt(0).putShort(16.toShort)
    (0 until 16).foreach(i => b.put((seed + i).toByte))
    java.util.Base64.getEncoder.encodeToString(b.array())

  private def cephChecks(): Unit =
    val dir = Files.createTempDirectory("ceph")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val fake = bin.resolve("cephadm")
    Files.writeString(fake, fakeCephadm)
    check("ceph: the fake cephadm is executable", fake.toFile.setExecutable(true))
    val state = dir.resolve("state")
    def sh(script: String, down: Boolean = false): (Int, String) =
      val env = if down then "FAKE_CEPH_DOWN=1\nexport FAKE_CEPH_DOWN\n" else ""
      runShell(s"PATH=$bin:$$PATH\nFAKE_CEPH=$state\nexport FAKE_CEPH\n$env$script")
    def dump: String = sh("cephadm shell -- dump")._2
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    // pools
    val pool = cephPool("the volumes pool", "volumes")
    check("cephPool: no pool means it is needed", sh(part(pool, _.unless))._1 != 0)
    val made = sh(part(pool, _.run))
    check("cephPool: it makes the pool and initialises it for rbd", made._1 == 0 && made._2.contains("pool volumes ready") && dump.contains("rbd pool init volumes"), made.toString + dump)
    check("cephPool: the pool then means unchanged", sh(part(pool, _.unless))._1 == 0)
    check("cephPool: a pool with a similar name does not count", sh(part(cephPool("x", "vol"), _.unless))._1 != 0)
    val fs = cephPool("the data pool", "cephfs_data", application = "cephfs")
    sh(part(fs, _.run))
    check("cephPool: another application is enabled instead of rbd init", dump.contains("pool application enable cephfs_data cephfs") && !dump.contains("rbd pool init cephfs_data"), dump)
    check("cephPool: a bad pool name is refused", scala.util.Try(cephPool("x", "a b")).isFailure)
    // users
    val aes = cephKeyText(1, 10)
    val user = cephUser("client.cinder", "client.cinder", Secret(aes), List("mon" -> "profile rbd", "osd" -> "profile rbd pool=volumes"))
    check("cephUser: no user means it is needed", sh(part(user, _.unless))._1 != 0)
    val imported = sh(part(user, _.run))
    check("cephUser: it imports the user and says so", imported._1 == 0 && imported._2.contains("client.cinder ready"), imported.toString)
    check("cephUser: an aes key first allows aes", dump.contains("auth_allowed_ciphers aes,aes256k"), dump)
    check("cephUser: the key and both capabilities are in Ceph", dump.contains(aes) && dump.contains("profile rbd pool=volumes") && dump.contains("\"mon\": \"profile rbd\""), dump)
    check("cephUser: the keyring file is gone afterwards", !Files.exists(Path.of("/tmp/orphera-client.cinder.keyring")))
    check("cephUser: it then means unchanged", sh(part(user, _.unless))._1 == 0)
    val rotated = cephUser("rotated", "client.cinder", Secret(cephKeyText(1, 40)), List("mon" -> "profile rbd", "osd" -> "profile rbd pool=volumes"))
    check("cephUser: another key means it is needed", sh(part(rotated, _.unless))._1 != 0)
    sh(part(rotated, _.run))
    check("cephUser: running it puts the new key right", sh(part(rotated, _.unless))._1 == 0 && sh(part(user, _.unless))._1 != 0)
    val widerCaps = cephUser("wider", "client.cinder", Secret(cephKeyText(1, 40)), List("mon" -> "profile rbd", "osd" -> "profile rbd pool=images"))
    check("cephUser: a different capability means it is needed", sh(part(widerCaps, _.unless))._1 != 0)
    val newer = cephKeyText(2, 70)
    val modern = cephUser("modern", "client.glance", Secret(newer), List("mon" -> "allow r", "mgr" -> "allow rw"))
    val before = dump
    val m = sh(part(modern, _.run))
    check("cephUser: an aes256k key leaves the cipher setting alone and says so", m._1 == 0 && m._2.contains("client.glance has an aes256k key") && dump.split("auth_allowed_ciphers").length == before.split("auth_allowed_ciphers").length, m.toString)
    check("cephUser: and the user is made", sh(part(modern, _.unless))._1 == 0)
    check("cephUser: a name without a type is refused", scala.util.Try(cephUser("x", "cinder", Secret(aes), List("mon" -> "profile rbd"))).isFailure)
    check("cephUser: text that is not a cephx key is refused", scala.util.Try(cephUser("x", "client.a", Secret("not a key"), List("mon" -> "profile rbd"))).isFailure)
    check("cephUser: a capability with a quote is refused", scala.util.Try(cephUser("x", "client.a", Secret(aes), List("mon" -> "profile \"rbd\""))).isFailure)
    check("cephUser: an unknown capability type is refused", scala.util.Try(cephUser("x", "client.a", Secret(aes), List("net" -> "allow"))).isFailure)
    check("cephUser: no capabilities is refused", scala.util.Try(cephUser("x", "client.a", Secret(aes), Nil)).isFailure)
    // removal
    val noUser = absentCephUser("remove client.cinder", "client.cinder")
    check("absentCephUser: it is wanted while the user is listed", sh(part(noUser, _.onlyIf))._1 == 0)
    check("absentCephUser: and also when Ceph cannot be queried, so the failure is shown", sh(part(noUser, _.onlyIf), down = true)._1 == 0)
    val removedUser = sh(part(noUser, _.run))
    check("absentCephUser: it removes the user", removedUser._1 == 0 && removedUser._2.contains("client.cinder removed") && !dump.contains("\"client.cinder\""), removedUser.toString + dump)
    check("absentCephUser: nothing to do afterwards", sh(part(noUser, _.onlyIf))._1 != 0)
    check("absentCephUser: a cluster that is down fails the removal", sh(part(noUser, _.run), down = true)._1 != 0)
    val noPool = absentCephPool("remove the volumes pool", "volumes")
    check("absentCephPool: it is wanted while the pool is listed", sh(part(noPool, _.onlyIf))._1 == 0)
    check("absentCephPool: and when Ceph cannot be queried", sh(part(noPool, _.onlyIf), down = true)._1 == 0)
    val removedPool = sh(part(noPool, _.run))
    check("absentCephPool: it deletes the pool", removedPool._1 == 0 && removedPool._2.contains("pool volumes removed") && !dump.contains("\"volumes\""), removedPool.toString + dump)
    check("absentCephPool: and sets pool deletion back to false", dump.contains("\"mon_allow_pool_delete\": \"false\""), dump)
    check("absentCephPool: nothing to do afterwards", sh(part(noPool, _.onlyIf))._1 != 0)
    check("absentCephPool: a pool that is not there fails the removal", sh(part(noPool, _.run))._1 != 0)
    // filesystem and NFS cluster
    val fsRes = cephFilesystem("the filesystem", "cephfs")
    check("cephFilesystem: no filesystem means it is needed", sh(part(fsRes, _.unless))._1 != 0)
    val fsMade = sh(part(fsRes, _.run))
    check("cephFilesystem: it creates the filesystem and sees an active MDS", fsMade._1 == 0 && fsMade._2.contains("filesystem cephfs created") && fsMade._2.contains("an MDS is active"), fsMade.toString)
    check("cephFilesystem: it then means unchanged", sh(part(fsRes, _.unless))._1 == 0)
    val fsAgain = sh(part(fsRes, _.run))
    check("cephFilesystem: running it again does not create it twice", fsAgain._1 == 0 && dump.split("fs volume create").length == 2, fsAgain.toString + dump)
    check("cephFilesystem: a filesystem with a similar name does not count", sh(part(cephFilesystem("x", "ceph"), _.unless))._1 != 0)
    check("cephFilesystem: a bad name is refused", scala.util.Try(cephFilesystem("x", "a b")).isFailure)
    val noFs = absentCephFilesystem("remove the filesystem", "cephfs")
    check("absentCephFilesystem: it is wanted while the filesystem is listed, and when Ceph is down", sh(part(noFs, _.onlyIf))._1 == 0 && sh(part(noFs, _.onlyIf), down = true)._1 == 0)
    val fsGone = sh(part(noFs, _.run))
    check("absentCephFilesystem: it removes it and puts pool deletion back to false", fsGone._1 == 0 && fsGone._2.contains("filesystem cephfs removed") && dump.contains("\"fs\": []") && dump.contains("\"mon_allow_pool_delete\": \"false\""), fsGone.toString + dump)
    check("absentCephFilesystem: nothing to do afterwards", sh(part(noFs, _.onlyIf))._1 != 0)
    check("absentCephFilesystem: a cluster that is down fails the removal", sh(part(noFs, _.run), down = true)._1 != 0)
    val listener = new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
    try
      val nfsPort = listener.getLocalPort
      val nfsRes = cephNfsCluster("the NFS cluster", "manila", "10.10.5.12", "tst0", nfsPort)
      val nfsLocal = cephNfsCluster("the NFS cluster", "manila", "127.0.0.1", "tst0", nfsPort)
      check("cephNfsCluster: no cluster means it is needed", sh(part(nfsLocal, _.unless))._1 != 0)
      val nfsMade = sh(part(nfsLocal, _.run))
      check("cephNfsCluster: it creates the cluster on the matching host and waits for the port", nfsMade._1 == 0 && nfsMade._2.contains("NFS cluster manila created on tst0.example") && nfsMade._2.contains("answers on") , nfsMade.toString)
      check("cephNfsCluster: it then means unchanged", sh(part(nfsLocal, _.unless))._1 == 0)
      val byName = cephNfsCluster("x", "other", "10.9.9.9", "tst1", nfsPort)
      val viaName = sh(part(byName, _.run).replace("10.9.9.9", "127.0.0.1"))
      check("cephNfsCluster: the host is found by its short name too", viaName._1 == 0 && viaName._2.contains("created on tst1.example"), viaName.toString)
      val nobody = cephNfsCluster("x", "third", "10.9.9.9", "nowhere", nfsPort)
      val noHost = sh(part(nobody, _.run))
      check("cephNfsCluster: an address and name that match no host fail with a message", noHost._1 != 0 && noHost._2.contains("no Ceph orchestrator host"), noHost.toString)
      check("cephNfsCluster: a listed cluster whose port does not answer is still needed", sh(part(nfsRes, _.unless).replace("10.10.5.12", "127.0.0.1").replace(nfsPort.toString, "1"))._1 != 0)
      check("cephNfsCluster: a bad address is refused", scala.util.Try(cephNfsCluster("x", "a", "1.2.3.4; id", "h")).isFailure)
      val noNfs = absentCephNfsCluster("remove the NFS cluster", "manila")
      check("absentCephNfsCluster: it is wanted while the cluster is listed, and when Ceph is down", sh(part(noNfs, _.onlyIf))._1 == 0 && sh(part(noNfs, _.onlyIf), down = true)._1 == 0)
      val nfsGone = sh(part(noNfs, _.run))
      check("absentCephNfsCluster: it removes it", nfsGone._1 == 0 && nfsGone._2.contains("NFS cluster manila removed"), nfsGone.toString)
      check("absentCephNfsCluster: nothing to do afterwards", sh(part(noNfs, _.onlyIf))._1 != 0)
    finally listener.close()

  // Stand-ins for `ovs-vsctl` and `ip` that keep Open vSwitch bridges, their ports and the manager in a JSON file;
  // `ovs-vsctl dump` prints it. A port on a bridge that does not exist fails, as it does for real.
  private val fakeOvs: String =
    """#!/usr/bin/python3
import json, os, sys
S = os.environ['FAKE_OVS']
st = json.load(open(S)) if os.path.exists(S) else {'bridges': {}, 'manager': ''}
def save():
    json.dump(st, open(S, 'w'))
a = sys.argv[1:]
prog = os.path.basename(sys.argv[0])
if prog == 'ip':
    if a[:2] == ['link', 'set']:
        if a[2] not in st['bridges']:
            print('Cannot find device "%s"' % a[2], file=sys.stderr); sys.exit(1)
        st['bridges'][a[2]]['up'] = (a[3] == 'up'); save(); sys.exit(0)
    if a[:3] == ['-o', 'link', 'show']:
        b = st['bridges'].get(a[3])
        if b is None:
            print('Device "%s" does not exist.' % a[3], file=sys.stderr); sys.exit(1)
        print('7: %s: <BROADCAST,MULTICAST%s> mtu 1500 state UNKNOWN' % (a[3], ',UP,LOWER_UP' if b['up'] else ''))
        sys.exit(0)
    sys.exit(2)
flag = ''
if a and a[0] in ('--may-exist', '--if-exists'):
    flag = a.pop(0)
cmd = a[0]
def need(name):
    if name not in st['bridges']:
        print('ovs-vsctl: no bridge named %s' % name, file=sys.stderr); sys.exit(1)
if cmd == 'dump':
    print(json.dumps(st, sort_keys=True))
elif cmd == 'br-exists':
    sys.exit(0 if a[1] in st['bridges'] else 2)
elif cmd == 'add-br':
    if a[1] in st['bridges'] and flag != '--may-exist':
        print('ovs-vsctl: bridge %s already exists' % a[1], file=sys.stderr); sys.exit(1)
    st['bridges'].setdefault(a[1], {'ports': [], 'up': False}); save()
elif cmd == 'add-port':
    need(a[1])
    if a[2] in st['bridges'][a[1]]['ports'] and flag != '--may-exist':
        sys.exit(1)
    if a[2] not in st['bridges'][a[1]]['ports']:
        st['bridges'][a[1]]['ports'].append(a[2])
    save()
elif cmd == 'list-ports':
    need(a[1])
    print('\n'.join(st['bridges'][a[1]]['ports']))
elif cmd == 'del-br':
    if a[1] not in st['bridges'] and flag != '--if-exists':
        need(a[1])
    st['bridges'].pop(a[1], None); save()
elif cmd == 'del-port':
    need(a[1])
    if a[2] in st['bridges'][a[1]]['ports']:
        st['bridges'][a[1]]['ports'].remove(a[2])
    elif flag != '--if-exists':
        sys.exit(1)
    save()
elif cmd == 'set-manager':
    st['manager'] = a[1]; save()
elif cmd == 'get-manager':
    print(st['manager'])
else:
    sys.exit(2)
"""

  private def ovsChecks(): Unit =
    val dir = Files.createTempDirectory("ovs")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val real = bin.resolve("ovs-vsctl")
    Files.writeString(real, fakeOvs)
    check("ovs: the fake ovs-vsctl is executable", real.toFile.setExecutable(true))
    Files.createSymbolicLink(bin.resolve("ip"), real)
    val state = dir.resolve("state.json")
    def sh(script: String, installed: Boolean = true): (Int, String) =
      val path = if installed then s"$bin:$$PATH" else "/nonexistent"
      runShell(s"PATH=$path\nFAKE_OVS=$state\nexport FAKE_OVS\n$script")
    def dump: String = sh("ovs-vsctl dump")._2
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    // bridge
    val bridge = ovsBridge("br-provider with enp2s0", "br-provider", List("enp2s0"))
    check("ovsBridge: no bridge means it is needed", sh(part(bridge, _.unless))._1 != 0)
    val made = sh(part(bridge, _.run))
    check("ovsBridge: it makes the bridge, its port and brings it up", made._1 == 0 && made._2.contains("br-provider has enp2s0") && dump.contains("\"ports\": [\"enp2s0\"]") && dump.contains("\"up\": true"), made.toString + dump)
    check("ovsBridge: it then means unchanged", sh(part(bridge, _.unless))._1 == 0)
    sh("ovs-vsctl del-port br-provider enp2s0")
    check("ovsBridge: a missing port means it is needed", sh(part(bridge, _.unless))._1 != 0)
    sh(part(bridge, _.run))
    check("ovsBridge: running it puts the port back", sh(part(bridge, _.unless))._1 == 0)
    sh("ip link set br-provider down")
    check("ovsBridge: a bridge whose link is down means it is needed", sh(part(bridge, _.unless))._1 != 0)
    sh(part(bridge, _.run))
    check("ovsBridge: running it brings the link up", sh(part(bridge, _.unless))._1 == 0)
    check("ovsBridge: a port whose name only starts the same does not count", sh(part(ovsBridge("x", "br-provider", List("enp2s")), _.unless))._1 != 0)
    val bare = ovsBridge("a bridge with no ports", "br-int")
    check("ovsBridge: a bridge with no ports is made and says so", sh(part(bare, _.run))._2.contains("br-int has no ports") && sh(part(bare, _.unless))._1 == 0)
    check("ovsBridge: a name with a space is refused", scala.util.Try(ovsBridge("x", "br x")).isFailure)
    check("ovsBridge: a port name that is too long is refused", scala.util.Try(ovsBridge("x", "br0", List("a" * 16))).isFailure)
    // manager
    val manager = ovsManager("loopback manager", "ptcp:6640:127.0.0.1")
    check("ovsManager: no manager means it is needed", sh(part(manager, _.unless))._1 != 0)
    val set = sh(part(manager, _.run))
    check("ovsManager: it sets the manager", set._1 == 0 && dump.contains("\"manager\": \"ptcp:6640:127.0.0.1\""), set.toString + dump)
    check("ovsManager: it then means unchanged", sh(part(manager, _.unless))._1 == 0)
    check("ovsManager: another manager means it is needed", sh(part(ovsManager("x", "ptcp:6640:127.0.0.2"), _.unless))._1 != 0)
    check("ovsManager: a target with a quote is refused", scala.util.Try(ovsManager("x", "a'b")).isFailure)
    // port removal
    sh("ovs-vsctl add-port br-int o-hm0")
    val port = absentOvsPort("remove o-hm0", "br-int", "o-hm0")
    check("absentOvsPort: it is wanted while the bridge has the port", sh(part(port, _.onlyIf))._1 == 0)
    check("absentOvsPort: it is not wanted without Open vSwitch", sh(part(port, _.onlyIf), installed = false)._1 != 0)
    val unplugged = sh(part(port, _.run))
    check("absentOvsPort: it removes the port and says so", unplugged._1 == 0 && unplugged._2.contains("o-hm0 removed from br-int") && !dump.contains("o-hm0"), unplugged.toString + dump)
    check("absentOvsPort: nothing to do afterwards", sh(part(port, _.onlyIf))._1 != 0)
    check("absentOvsPort: nor when the bridge is gone", sh(part(absentOvsPort("x", "br-gone", "o-hm0"), _.onlyIf))._1 != 0)
    check("absentOvsPort: removing again still succeeds", sh(part(port, _.run))._1 == 0)
    // bridge removal
    val gone = absentOvsBridge("delete the bridges", List("br-provider", "br-int"))
    check("absentOvsBridge: it is wanted while either bridge exists", sh(part(gone, _.onlyIf))._1 == 0 && sh(part(absentOvsBridge("x", List("br-gone", "br-int")), _.onlyIf))._1 == 0)
    check("absentOvsBridge: it is not wanted without Open vSwitch", sh(part(gone, _.onlyIf), installed = false)._1 != 0)
    val removed = sh(part(gone, _.run))
    check("absentOvsBridge: it removes both and says so", removed._1 == 0 && removed._2.contains("br-provider br-int removed") && dump.contains("\"bridges\": {}"), removed.toString + dump)
    check("absentOvsBridge: nothing to do afterwards", sh(part(gone, _.onlyIf))._1 != 0)
    check("absentOvsBridge: removing bridges that are gone still succeeds", sh(part(gone, _.run))._1 == 0)
    check("absentOvsBridge: no bridge is refused", scala.util.Try(absentOvsBridge("x", Nil)).isFailure)

  private def sqlChecks(): Unit =
    val dir = Files.createTempDirectory("sqldb")
    val bin = dir.resolve("bin")
    Files.createDirectory(bin)
    val log = dir.resolve("log")
    // A stand-in mariadb: it records its arguments and the password variable, and answers as the test says.
    val fake = bin.resolve("mariadb")
    Files.writeString(
      fake,
      s"""#!/bin/sh
         |echo "pw=$$MYSQL_PWD args=$$*" >> "$log"
         |case "$$*" in
         |  *information_schema*) [ -e "$dir/exists" ] && echo 1; [ -e "$dir/down" ] && exit 1; exit 0 ;;
         |  *"SELECT 1"*) [ -e "$dir/login" ] ;;
         |  *) exit 0 ;;
         |esac
         |""".stripMargin
    )
    check("sql: the fake mariadb is executable", fake.toFile.setExecutable(true))
    def sh(script: String): Int = runShell(s"PATH=$bin:$$PATH\n$script")._1
    def touch(n: String): Unit = { val _ = Files.writeString(dir.resolve(n), "") }
    def gone(n: String): Unit = { val _ = Files.deleteIfExists(dir.resolve(n)) }
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val pw = Secret("p'a\"s$s")
    val db = sqlDatabase("db", List("alpha", "beta"), "owner", pw, "10.0.0.5", charset = "utf8mb4", collation = "utf8mb4_unicode_ci")
    val run = part(db, _.run)
    check(
      "sqlDatabase: each database is created with the character set",
      run.contains("CREATE DATABASE IF NOT EXISTS alpha DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS beta"),
      run
    )
    check(
      "sqlDatabase: the user is created, set and granted on each database",
      run.contains("CREATE USER IF NOT EXISTS 'owner'@'%'") && run.contains("ALTER USER 'owner'@'%'") &&
        run.contains("GRANT ALL PRIVILEGES ON alpha.* TO 'owner'@'%'") &&
        run.contains("GRANT ALL PRIVILEGES ON beta.* TO 'owner'@'%'") && run.endsWith("FLUSH PRIVILEGES;\"")
    )
    gone("login")
    check("sqlDatabase: no login means it is needed", sh(part(db, _.unless)) != 0)
    touch("login")
    check("sqlDatabase: a login on every database means unchanged", sh(part(db, _.unless)) == 0)
    val seen = Files.readString(log)
    check(
      "sqlDatabase: it logs in as the user through the address, with the password",
      seen.contains("pw=p'a\"s$s args=-N -h 10.0.0.5 -u owner alpha -e SELECT 1") && seen.contains("owner beta -e"),
      seen
    )
    Files.writeString(log, "")
    check("sqlDatabase: the run is accepted by sh", sh(run) == 0)
    val ran = Files.readString(log)
    check(
      "sqlDatabase: the password reaches the SQL with its quote escaped",
      ran.contains("IDENTIFIED BY 'p\\'a\"s$s'"),
      ran
    )
    check("sqlDatabase: a name with a quote is refused", scala.util.Try(sqlDatabase("x", List("a'b"), "u", pw, "h")).isFailure)
    check("sqlDatabase: no databases is refused", scala.util.Try(sqlDatabase("x", Nil, "u", pw, "h")).isFailure)
    check("sqlDatabase: a bad address is refused", scala.util.Try(sqlDatabase("x", List("a"), "u", pw, "a b")).isFailure)
    val drop = absentSqlDatabase("drop", List("alpha", "beta"), "owner")
    check(
      "absentSqlDatabase: both databases and the user are dropped",
      part(drop, _.run) == "mariadb -N -e \"DROP DATABASE IF EXISTS alpha; DROP DATABASE IF EXISTS beta; DROP USER IF EXISTS 'owner'@'%'; FLUSH PRIVILEGES;\"",
      part(drop, _.run)
    )
    gone("exists")
    check("absentSqlDatabase: nothing there means nothing to do", sh(part(drop, _.onlyIf)) != 0)
    touch("exists")
    check("absentSqlDatabase: something there means it runs", sh(part(drop, _.onlyIf)) == 0)
    gone("exists")
    touch("down")
    check("absentSqlDatabase: a server that cannot be queried means it runs", sh(part(drop, _.onlyIf)) == 0)
    check("absentSqlDatabase: no databases is refused", scala.util.Try(absentSqlDatabase("x", Nil, "u")).isFailure)

  private def absentContainerUnitsChecks(): Unit =
    val rs = absentContainerUnits(List("orphera-a" -> "a", "orphera-b" -> "b"))
    check("absentContainerUnits: three resources a unit and a reload", rs.size == 7, rs.map(_.id).toString)
    check("absentContainerUnits: the description is valid", ApplyPlan("gone", rs).isRight, ApplyPlan("gone", rs).toString)
    check(
      "absentContainerUnits: the units are stopped and disabled",
      rs.contains(service("orphera-a", Some(false), Some(false))) &&
        rs.contains(service("orphera-b", Some(false), Some(false)))
    )
    check(
      "absentContainerUnits: the unit files go",
      rs.exists(_.id == "file[/etc/systemd/system/orphera-a.service]") &&
        rs.exists(_.id == "file[/etc/systemd/system/orphera-b.service]")
    )
    val removal = rs.collectFirst { case c: ApplyResource.Command if c.label == "remove the a container" => c }
    check(
      "absentContainerUnits: a container is removed only when podman has it",
      removal.exists(c => c.run == "podman rm -f 'a'" && c.onlyIf.contains("podman container exists 'a'")),
      removal.toString
    )
    check(
      "absentContainerUnits: systemd reloads once, when notified",
      rs.collect { case c: ApplyResource.Command if c.onChangeOnly => c }.size == 1
    )
    check(
      "absentContainerUnits: no units is refused",
      scala.util.Try(absentContainerUnits(Nil)).isFailure
    )
    check(
      "absentContainerUnits: a space in a name is refused",
      scala.util.Try(absentContainerUnits(List("a b" -> "c"))).isFailure
    )

  private def pullImagesChecks(): Unit =
    val dir = java.nio.file.Files.createTempDirectory("pullimages")
    val bin = dir.resolve("bin")
    java.nio.file.Files.createDirectory(bin)
    val state = dir.resolve("state")
    java.nio.file.Files.createDirectory(state)
    val fake = bin.resolve("podman")
    java.nio.file.Files.writeString(
      fake,
      s"""#!/bin/sh
         |case "$$1" in
         |  image) [ -e "${state}/$$(echo "$$3" | tr / _)" ] ;;
         |  pull) echo "$$2" >> "${state}/log"; : > "${state}/$$(echo "$$2" | tr / _)" ;;
         |esac
         |""".stripMargin
    )
    check("pullImages: the fake podman is executable", fake.toFile.setExecutable(true))
    def sh(script: String): (Int, String) = runShell(s"PATH=$bin:$$PATH\n$script")
    def part(r: ApplyResource, pick: ApplyResource.Command => String): String = r match
      case c: ApplyResource.Command => pick(c)
      case other                    => s"not a command: $other"
    val r = pullImages("images", List("quay.io/a/one:1", "quay.io/a/two:2"), hint = "the manifest")
    check("pullImages: missing images are not present", sh(part(r, _.unless))._1 != 0)
    val first = sh(part(r, _.run))
    check("pullImages: the run pulls them", first._1 == 0, first.toString)
    check(
      "pullImages: both were pulled once",
      java.nio.file.Files.readAllLines(state.resolve("log")).toArray.toList == List("quay.io/a/one:1", "quay.io/a/two:2")
    )
    check("pullImages: present images are not missing", sh(part(r, _.unless))._1 == 0)
    sh(part(r, _.run))
    check(
      "pullImages: a run with them present pulls nothing more",
      java.nio.file.Files.readAllLines(state.resolve("log")).size == 2
    )
    val more = pullImages("images", List("quay.io/a/one:1", "quay.io/a/three:3"))
    sh(part(more, _.run))
    check(
      "pullImages: only the missing one is pulled",
      java.nio.file.Files.readAllLines(state.resolve("log")).toArray.toList.last == "quay.io/a/three:3" &&
        java.nio.file.Files.readAllLines(state.resolve("log")).size == 3
    )
    check("pullImages: no images is refused", scala.util.Try(pullImages("x", Nil)).isFailure)
    check("pullImages: a space in a reference is refused", scala.util.Try(pullImages("x", List("a b"))).isFailure)

  private def waitForChecks(): Unit =
    val server = com.sun.net.httpserver.HttpServer
      .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    def reply(path: String, code: Int, text: String): Unit =
      server.createContext(
        path,
        exchange =>
          val bytes = text.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(code, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
    reply("/ok", 200, "{\"version\": \"v3\"}")
    reply("/multi", 300, "several versions")
    reply("/down", 503, "not ready")
    server.start()
    val port = server.getAddress.getPort
    val base = s"http://127.0.0.1:$port"
    def guard(r: ApplyResource): String = r match
      case c: ApplyResource.Command => c.unless
      case other                    => s"not a command: $other"
    def body(r: ApplyResource): String = r match
      case c: ApplyResource.Command => c.run
      case other                    => s"not a command: $other"
    try
      check(
        "waitForHttp: a 200 answers",
        runShell(guard(waitForHttp("ok", s"$base/ok")))._1 == 0
      )
      check(
        "waitForHttp: a 300 is not 200",
        runShell(guard(waitForHttp("multi", s"$base/multi")))._1 != 0
      )
      check(
        "waitForHttp: a 300 counts when listed",
        runShell(guard(waitForHttp("multi", s"$base/multi", status = List(200, 300))))._1 == 0
      )
      check(
        "waitForHttp: a 503 is not an answer",
        runShell(guard(waitForHttp("down", s"$base/down")))._1 != 0
      )
      check(
        "waitForHttp: the body text is found",
        runShell(guard(waitForHttp("v", s"$base/ok", contains = "\"version\"")))._1 == 0
      )
      check(
        "waitForHttp: the body text is missing",
        runShell(guard(waitForHttp("v", s"$base/ok", contains = "nothing like this")))._1 != 0
      )
      check(
        "waitForHttp: a text with a quote is passed safely",
        runShell(guard(waitForHttp("q", s"$base/ok", contains = "it's")))._1 != 0
      )
      val waited = runShell(body(waitForHttp("the api", s"$base/down", timeout = 2, interval = 1, hint = "see the log")))
      check(
        "waitForHttp: gives up with the URL and the hint",
        waited._1 == 1 && waited._2.contains(s"the api: no answer from $base/down after 2 s - see the log"),
        waited.toString
      )
      val soon = runShell(body(waitForHttp("the api", s"$base/ok", timeout = 5, interval = 1)))
      check("waitForHttp: the script exits 0 at once when it answers", soon._1 == 0 && soon._2.isEmpty, soon.toString)
      check(
        "waitForHttp: a name is resolved to this node",
        runShell(
          guard(
            waitForHttp(
              "r",
              s"http://named.invalid:$port/ok",
              resolve = s"named.invalid:$port:127.0.0.1"
            )
          )
        )._1 == 0
      )
      check(
        "waitForHttp: without the mapping the name does not resolve",
        runShell(guard(waitForHttp("r", s"http://named.invalid:$port/ok")))._1 != 0
      )
      check(
        "waitForPort: an open port answers",
        runShell(guard(waitForPort("port", "127.0.0.1", port)))._1 == 0
      )
      server.stop(0)
      check(
        "waitForPort: a closed port does not",
        runShell(guard(waitForPort("port", "127.0.0.1", port)))._1 != 0
      )
      val closed = runShell(body(waitForPort("the port", "127.0.0.1", port, timeout = 2, interval = 1)))
      check(
        "waitForPort: gives up with the address",
        closed._1 == 1 && closed._2.contains(s"the port: no answer from 127.0.0.1:$port after 2 s"),
        closed.toString
      )
    finally server.stop(0)
    check("waitForHttp: no status codes is refused", scala.util.Try(waitForHttp("x", "http://h/", status = Nil)).isFailure)
    check("waitForHttp: a timeout below the interval is refused", scala.util.Try(waitForHttp("x", "http://h/", timeout = 1, interval = 5)).isFailure)
    check("waitForPort: a host with a space is refused", scala.util.Try(waitForPort("x", "a b", 80)).isFailure)
    check("waitForPort: port 0 is refused", scala.util.Try(waitForPort("x", "h", 0)).isFailure)
    val http = waitForHttp("l", "http://h/", ca = "/etc/ca.crt")
    check(
      "waitForHttp: the CA is passed to curl",
      guard(http).contains("--cacert '/etc/ca.crt'"),
      guard(http)
    )

  def main(args: Array[String]): Unit =
    typedChecks()
    accessChecks()
    softwareChecks()
    observeChecks()
    planChecks()
    runChecks()
    owedChecks()
    waitForChecks()
    pullImagesChecks()
    absentContainerUnitsChecks()
    sqlChecks()
    rabbitChecks()
    serverCaChecks()
    aptRepositoryChecks()
    downloadChecks()
    archiveFilesChecks()
    keystoneChecks()
    openstackChecks()
    cephChecks()
    ovsChecks()
    kindChecks()
    yamlChecks()
    newKindChecks()
    totalChecks()
    diffChecks()
    localChecks()
    println(s"ApplyCheck: $passes passed, $failures failed")
    if failures > 0 then sys.exit(1)
