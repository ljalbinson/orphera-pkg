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
  * Part 2 runs the real node side (TaskExecutor.applyOn and its shell
  * scripts) against a temporary directory on this machine: a stand-in task
  * runner runs each `sh -c` step here and writes files here, so the observe
  * script, its parsing and the change commands are the ones a node gets.
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
      failing: Set[String] = Set.empty,
      var pkgs: Map[String, String] = Map.empty,
      var units: Map[String, (String, String)] = Map.empty,
      var users: Map[String, Observed.Account] = Map.empty,
      var groupsDb: Map[String, Int] = Map.empty
  ) extends ApplyTarget:
    var ran: Vector[String] = Vector.empty
    var observed: Int = 0
    def node = "fake"

    private def sha(t: String) =
      java.security.MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

    def observe(rs: List[(Int, ApplyResource)]): IO[Map[Int, Observed]] = IO {
      observed += 1
      rs.map { (i, r) =>
        val seen = r match
          case c: ApplyResource.Command =>
            val g = if c.onlyIf.nonEmpty then guards.getOrElse(c.onlyIf, true)
            else if c.unless.nonEmpty then !guards.getOrElse(c.unless, false)
            else !fs.contains(c.creates)
            Observed.Guard(g, if g then "" else "guard says no")
          case p: ApplyResource.Package => Observed.Pkg("apt", pkgs.contains(p.name), pkgs.get(p.name).toList)
          case u: ApplyResource.Service =>
            val (e, a) = units.getOrElse(u.unit, ("not-found", "inactive"))
            Observed.ServiceState(e, a)
          case u: ApplyResource.User  => users.getOrElse(u.name, Observed.Absent)
          case g: ApplyResource.Group => groupsDb.get(g.name).fold(Observed.Absent)(Observed.GroupEntry(_))
          case other =>
            val p = other match
              case f: ApplyResource.File      => f.path
              case d: ApplyResource.Directory => d.path
              case s: ApplyResource.Symlink   => s.path
              case _                     => ""
            fs.get(p) match
              case None                         => Observed.Absent
              case Some(Entry.F(t, m, o, g))    => Observed.RegularFile(m, o, g, sha(t))
              case Some(Entry.D(m, o, g))       => Observed.Dir(m, o, g)
              case Some(Entry.L(t))             => Observed.Link(t)
        i -> seen
      }.toMap
    }

    def readText(path: String): IO[Option[String]] = IO(fs.get(path).collect { case Entry.F(t, _, _, _) => t })

    def source(path: String, vars: Map[String, String]): IO[String] =
      IO(Templating.render(s"source $path for {{name}}", vars))

    def write(path: String, content: String, owner: String, group: String, mode: Int): IO[Unit] = IO {
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
      s"$f (?:$Q|(\\S+))".r.findFirstMatchIn(flags).map(m => Option(m.group(1)).map(unq).getOrElse(m.group(2)))

    def shell(label: String, script: String, timeout: Int): IO[Unit] =
      IO {
        ran :+= s"$label: $script"
        if failing.contains(label) then throw new RuntimeException(s"$label failed on purpose")
        script.split(" && ").toList.foreach {
          case Rm(p)       => fs -= unq(p)
          case Mkdir(p)    => fs += unq(p) -> Entry.D(0x1ed /* 0755 */, "root", "root")
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
          case AptRemove(n) => pkgs -= unq(n)
          case Systemctl(verb, u) =>
            val (e, a) = units.getOrElse(unq(u), ("not-found", "inactive"))
            if e == "not-found" then throw new RuntimeException(s"no unit ${unq(u)}")
            units += unq(u) -> (verb match
              case "enable"                    => ("enabled", a)
              case "disable"                   => ("disabled", a)
              case "start" | "restart"         => (e, "active")
              case "stop"                      => (e, "inactive")
              case _                           => (e, a))
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
                groups = a.groups ++ flag(flags, "-a -G").fold(List.empty[String])(_.split(",").toList)
              )
            })
          case UserDel(n) => users -= unq(n)
          case GroupAdd(flags, n) => groupsDb += unq(n) -> flag(flags, "-g").flatMap(_.toIntOption).getOrElse(1001)
          case GroupDel(n) => groupsDb -= unq(n)
          case _ => () // a command: recorded in `ran`
        }
      }

  // A run's report and the lines it printed.
  private final case class Ran(report: ApplyReport, lines: Vector[String])

  private def applyTo(t: ApplyTarget, rs: List[ApplyResource], check: Boolean = false, stop: Boolean = false): Ran =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      p <- Apply.plan("t", rs)
      r <- Apply.run(p, t, check, s => lines.update(_ :+ s), stop)
      ls <- lines.get
    yield Ran(r, ls)).unsafeRunSync()

  private def outcome(r: ApplyReport, id: String): Option[ApplyOutcome] = r.results.find(_.id == id).map(_.outcome)

  private def problems(rs: List[ApplyResource]): List[String] = ApplyPlan("t", rs).left.getOrElse(Nil)

  private def planChecks(): Unit =
    val restart = command("restart", "systemctl restart x", onChangeOnly = true)
    check("a good description has no problems", problems(List(file("/etc/x", "x").notifies(restart), restart)).isEmpty, problems(List(file("/etc/x", "x").notifies(restart), restart)).toString)
    check("a name used twice", problems(List(file("/etc/x", "a"), file("/etc/x", "b"))).exists(_.contains("file[/etc/x] is described 2 times")))
    check("a relative path", problems(List(file("etc/x", "a"))).exists(_.contains("must be absolute")))
    check("a bad mode", problems(List(file("/etc/x", "a", mode = "0999"))).exists(_.contains("not an octal mode")))
    check("an unguarded command", problems(List(command("go", "true"))).exists(_.contains("would run every time")))
    check("two guards", problems(List(command("go", "true", onlyIf = "a", unless = "b"))).exists(_.contains("one of onlyIf")))
    check("after something not described", problems(List(file("/etc/x", "a").afterIds("file[/nope]"))).exists(_.contains("which is not described")))
    check("notifying a file", problems(List(file("/a", "a").notifiesIds("file[/b]"), file("/b", "b"))).exists(_.contains("not a service or an onChangeOnly command")))
    check("onChangeOnly with nothing notifying it", problems(List(restart)).exists(_.contains("nothing notifies it")))
    val a = file("/a", "a")
    val b = file("/b", "b").after(a)
    check("a cycle", problems(List(a.afterIds("file[/b]"), b)).exists(_.contains("wait for each other")))
    // Order: declaration order, except what `after` and notification require.
    val plan = ApplyPlan("t", List(restart, file("/z", "z").after(file("/y", "y")), file("/y", "y"), file("/x", "x").notifies(restart)))
    check(
      "order: declaration order unless after/notify say otherwise",
      plan.toOption.map(p => p.order.map(p.resources(_).id).toList).contains(List("file[/y]", "file[/z]", "file[/x]", "command[restart]")),
      plan.toString
    )

  private def runChecks(): Unit =
    val clean = command("clean", "dnf clean metadata -q", onChangeOnly = true)
    def desc = List(
      directory("/etc/app", mode = "0750"),
      file("/etc/app/app.conf", "a = 1\nb = 2\n", mode = "0640").after(directory("/etc/app")).notifies(clean),
      symlink("/etc/app/current", "/etc/app/app.conf"),
      clean
    )
    val n = new FakeNode(Map.empty)
    val ranFirst = applyTo(n, desc)
    val first = ranFirst.report
    val lines1 = ranFirst.lines
    check("first run: everything changed", first.results.forall(_.changed) && !first.failed, first.toString)
    check("first run: one read, then each resource again once something changed", n.observed == 3, n.observed.toString)
    check("first run: the directory got its mode", n.fs.get("/etc/app").contains(Entry.D(0x1e8, "root", "root")), n.fs.toString)
    check("first run: the notified command ran last", n.ran.lastOption.exists(_.contains("dnf clean")), n.ran.toString)
    check("first run: the plan line names who notified", lines1.exists(l => l.contains("command[clean]") && l.contains("ran (notified by file[/etc/app/app.conf])")), lines1.mkString("\n"))
    check("first run: summary", lines1.lastOption.contains("4 changed, 0 unchanged"), lines1.lastOption.toString)

    n.ran = Vector.empty
    val ranSecond = applyTo(n, desc)
    val second = ranSecond.report
    val lines2 = ranSecond.lines
    check("second run: nothing changed", !second.changed && n.ran.isEmpty, second.toString + n.ran)
    check("second run: the command was not notified", outcome(second, "command[clean]").contains(ApplyOutcome.NotNotified))
    check("second run: summary", lines2.lastOption.contains("0 changed, 4 unchanged"), lines2.lastOption.toString)

    // A changed content: check mode shows it and changes nothing.
    val changed = desc.updated(1, file("/etc/app/app.conf", "a = 1\nb = 3\n", mode = "0640").after(directory("/etc/app")).notifies(clean))
    val before = n.fs
    val ranPlan = applyTo(n, changed, check = true)
    val plan = ranPlan.report
    val lines3 = ranPlan.lines
    check("check: nothing changed on the node", n.fs == before && n.ran.isEmpty, n.ran.toString)
    check("check: the file would change", outcome(plan, "file[/etc/app/app.conf]").exists(_.isInstanceOf[ApplyOutcome.WouldChange]))
    check("check: the command would run", lines3.exists(_.contains("would run (notified by file[/etc/app/app.conf])")), lines3.mkString("\n"))
    check("check: a diff of the content", lines3.contains("  -b = 2") && lines3.contains("  +b = 3"), lines3.mkString("\n"))
    check("check: summary", lines3.lastOption.contains("2 to change, 2 unchanged (check: nothing changed)"), lines3.lastOption.toString)

    // Only the mode differs: chmod, not a rewrite.
    n.fs = n.fs.updated("/etc/app/app.conf", Entry.F("a = 1\nb = 2\n", 0x1a4, "root", "root"))
    n.ran = Vector.empty
    val modeOnly = applyTo(n, desc).report
    check("mode only: chmod, not a write", n.ran.exists(_.contains("chmod 0640")) && !n.ran.exists(_.startsWith("write")), n.ran.toString)
    check("mode only: says what", outcome(modeOnly, "file[/etc/app/app.conf]").contains(ApplyOutcome.Changed(List("mode 0644 -> 0640"), Nil)), modeOnly.toString)

    // A secret is never shown.
    val sn = new FakeNode(Map("/etc/key" -> Entry.F("old secret", 0x180, "root", "root")))
    val slines = applyTo(sn, List(secretFile("/etc/key", new Secret("new secret"))), check = true).lines
    check("secret: content not shown", slines.exists(_.contains("content (secret: not shown)")) && !slines.exists(l => l.contains("new secret") || l.contains("old secret")), slines.mkString("\n"))

    // Guards.
    val gn = new FakeNode(Map("/opt/done" -> Entry.F("", 0x1a4, "root", "root")), guards = Map("test -e /x" -> false))
    val guarded = applyTo(gn, List(
      command("only if", "echo a", onlyIf = "test -e /x"),
      command("creates", "echo b", creates = "/opt/done"),
      command("unless", "echo c", unless = "test -e /x")
    )).report
    check("guards: onlyIf false and creates existing leave it unchanged", outcome(guarded, "command[only if]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]) && outcome(guarded, "command[creates]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), guarded.toString)
    check("guards: unless false runs it", outcome(guarded, "command[unless]").exists(_.isInstanceOf[ApplyOutcome.Changed]) && gn.ran.exists(_.contains("echo c")), gn.ran.toString)

    // Failures: a directory where a file should be; what comes after it is skipped, the rest runs.
    val restart = command("restart", "systemctl restart app", onChangeOnly = true)
    val fn = new FakeNode(Map("/etc/app.conf" -> Entry.D(0x1ed, "root", "root")))
    val ranFailed = applyTo(fn, List(
      file("/etc/app.conf", "x").notifies(restart),
      file("/etc/other", "y").afterIds("file[/etc/app.conf]"),
      file("/etc/third", "z"),
      restart
    ))
    val failed = ranFailed.report
    val flines = ranFailed.lines
    check("failure: the file fails", outcome(failed, "file[/etc/app.conf]").exists(_.isInstanceOf[ApplyOutcome.Failed]) && failed.failed, failed.toString)
    check("failure: what comes after it is skipped", outcome(failed, "file[/etc/other]").contains(ApplyOutcome.Skipped("file[/etc/app.conf] failed")), failed.toString)
    check("failure: what it notifies is skipped", outcome(failed, "command[restart]").contains(ApplyOutcome.Skipped("file[/etc/app.conf] failed")), failed.toString)
    check("failure: the rest still runs", outcome(failed, "file[/etc/third]").exists(_.isInstanceOf[ApplyOutcome.Changed]), failed.toString)
    check("failure: summary counts it", flines.lastOption.contains("1 changed, 0 unchanged, 2 skipped, 1 failed"), flines.lastOption.toString)
    val sn2 = new FakeNode(Map("/etc/app.conf" -> Entry.D(0x1ed, "root", "root")))
    val stopped = applyTo(sn2, List(file("/etc/app.conf", "x"), file("/etc/third", "z")), stop = true).report
    check("stop on failure: the rest is skipped", outcome(stopped, "file[/etc/third]").contains(ApplyOutcome.Skipped("stopped after file[/etc/app.conf] failed")), stopped.toString)

    // A change that fails.
    val cn = new FakeNode(Map.empty, failing = Set("command[boom]"))
    val boom = applyTo(cn, List(command("boom", "false", creates = "/nope"), file("/after", "x").afterIds("command[boom]"))).report
    check("a failing change: failed, and what follows skipped", outcome(boom, "command[boom]").exists(_.isInstanceOf[ApplyOutcome.Failed]) && outcome(boom, "file[/after]").exists(_.isInstanceOf[ApplyOutcome.Skipped]), boom.toString)

    // Removal.
    val rn = new FakeNode(Map("/old" -> Entry.F("x", 0x1a4, "root", "root"), "/oldlink" -> Entry.L("/x"), "/olddir" -> Entry.D(0x1ed, "root", "root")))
    val removed = applyTo(rn, List(absentFile("/old"), absentSymlink("/oldlink"), absentDirectory("/olddir"), absentFile("/never"))).report
    check("removal: removed, and absent left alone", rn.fs.isEmpty && outcome(removed, "file[/never]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), rn.fs.toString + removed)
    check("removal: a directory is not removed as a file", applyTo(new FakeNode(Map("/d" -> Entry.D(0x1ed, "r", "r"))), List(absentFile("/d"))).report.failed)

    // A template source is rendered with its vars.
    val tn = new FakeNode(Map.empty)
    applyTo(tn, List(fileFrom("/etc/t", "t.mustache", Map("name" -> "tst8"))))
    check("fileFrom: rendered with vars", tn.fs.get("/etc/t").exists { case Entry.F(t, _, _, _) => t == "source t.mustache for tst8"; case _ => false }, tn.fs.toString)

  private def kindChecks(): Unit =
    // Plans.
    check("a bad package name", problems(List(pkg("no spaces"))).exists(_.contains("not a package name")))
    check("a bad user name", problems(List(user("Bad Name"))).exists(_.contains("not a user name")))
    check("a service with nothing to do", problems(List(service("x.service", enabled = None, running = None))).exists(_.contains("says nothing about it")))
    check("a service may be notified", problems(List(file("/etc/x", "x").notifiesIds("service[x.service]"), service("x.service"))).isEmpty)
    val implied = ApplyPlan("t", List(file("/srv/f", "x", owner = "app", group = "app"), user("app", group = "app"), group("app")))
    check(
      "order: a group before its users, a user before the files it owns",
      implied.toOption.map(p => p.order.map(p.resources(_).id).toList).contains(List("group[app]", "user[app]", "file[/srv/f]")),
      implied.toString
    )

    // A package, its config, its service, restarted on a config change.
    def desc(conf: String) = List(
      group("app", gid = 990, system = true),
      user("app", group = "app", groups = List("adm"), shell = "/usr/sbin/nologin", system = true),
      pkg("app-server", updateCache = true),
      file("/etc/app.conf", conf, owner = "app").after(pkg("app-server")).notifiesIds("service[app.service]"),
      service("app.service").after(pkg("app-server"))
    )
    val n = new FakeNode(Map.empty, users = Map("root" -> Observed.Account(0, "root", Nil, "/root", "/bin/bash")))
    val planned = applyTo(n, desc("a\n"), check = true)
    check("kinds check: all would change", planned.report.results.forall(_.changed), planned.lines.mkString("\n"))
    check("kinds check: a unit that a package will bring", planned.lines.exists(l => l.contains("service[app.service]") && l.contains("the unit does not exist yet")), planned.lines.mkString("\n"))
    // Installing the package brings its unit, disabled and stopped.
    val withUnit = new FakeNode(Map.empty) {
      override def shell(label: String, script: String, timeout: Int): IO[Unit] =
        super.shell(label, script, timeout) >> IO { if script.contains("apt-get install") then units += "app.service" -> ("disabled", "inactive") }
    }
    val first = applyTo(withUnit, desc("a\n"))
    check("kinds first run: nothing failed", !first.report.failed, first.lines.mkString("\n"))
    check("kinds first run: group then user", withUnit.groupsDb.get("app").contains(990) && withUnit.users.get("app").exists(a => a.group == "app" && a.groups == List("adm")), withUnit.users.toString)
    check("kinds first run: apt-get update then install", withUnit.ran.exists(r => r.contains("apt-get update -q && ") && r.contains("'app-server'")), withUnit.ran.toString)
    check("kinds first run: the service enabled and started, not restarted", withUnit.units.get("app.service").contains(("enabled", "active")) && first.lines.exists(l => l.contains("service[app.service]") && l.contains("enabled, started") && !l.contains("restarted")), first.lines.mkString("\n"))
    withUnit.ran = Vector.empty
    val second = applyTo(withUnit, desc("a\n"))
    check("kinds second run: nothing changed", !second.report.changed && withUnit.ran.isEmpty, second.lines.mkString("\n") + withUnit.ran)
    val third = applyTo(withUnit, desc("b\n"))
    check("kinds config change: restarted", third.lines.exists(_.contains("restarted (notified by file[/etc/app.conf])")) && withUnit.ran.exists(_.contains("systemctl restart 'app.service'")), third.lines.mkString("\n"))
    val pinned = applyTo(withUnit, List(pkg("app-server", version = "2.0-1")), check = true)
    check("a version differs", pinned.lines.exists(_.contains("version 1.0-1 -> 2.0-1")), pinned.lines.mkString("\n"))
    check("a version without its release matches", Apply.versionMatches("2.0-1.el9", "2.0") && !Apply.versionMatches("2.01", "2.0"))
    val more = applyTo(withUnit, List(user("app", groups = List("adm", "ceph"))))
    check("supplementary groups are added, not replaced", withUnit.users.get("app").exists(_.groups == List("adm", "ceph")) && more.lines.exists(_.contains("added to ceph")), more.lines.mkString("\n"))
    val gone = applyTo(withUnit, List(service("app.service", enabled = Some(false), running = Some(false)), absentPkg("app-server"), absentUser("app"), absentGroup("app")))
    check("removal of the four kinds", withUnit.units.get("app.service").contains(("disabled", "inactive")) && withUnit.pkgs.isEmpty && withUnit.users.isEmpty && withUnit.groupsDb.isEmpty, gone.lines.mkString("\n"))
    val masked = new FakeNode(Map.empty, units = Map("m.service" -> ("masked", "inactive")))
    check("a masked unit fails", applyTo(masked, List(service("m.service"))).report.failures.exists(_.contains("masked")))
    val missing = new FakeNode(Map.empty)
    check("a unit that does not exist fails in a real run", applyTo(missing, List(service("none.service"))).report.failures.exists(_.contains("no such unit")))

  private def yaml(text: String): Either[String, ApplyDescription] =
    io.circe.yaml.parser.parse(text).left.map(_.getMessage).flatMap(ApplyYaml.decode)

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
      file("/etc/a.conf", "a = 1\n", mode = "0640", owner = "app", group = "app").notifiesIds("service[app.service]"),
      fileFrom("/etc/b.conf", "files/b.conf.mustache", Map("port" -> "8080")),
      absentFile("/etc/old"),
      directory("/srv/app", mode = "0750"),
      symlink("/srv/current", "/srv/app"),
      pkg("app-server", version = "2.0", updateCache = true),
      absentPkg("telnet"),
      service("app.service", running = None),
      user("app", group = "app", groups = List("adm"), system = true),
      group("app", gid = 990),
      command("reload", "systemctl daemon-reload", unless = "test -e /x").afterIds("file[/etc/a.conf]", "directory[/srv/app]")
    )
    check("yaml: every kind reads into the same resources as Scala", full.map(_.resources) == Right(expected), full.toString)
    check("yaml: name and nodes", full.exists(d => d.name == "all-kinds" && d.nodes == List("tst5", "tst6")), full.toString)
    check("yaml: the description is a valid plan", full.toOption.exists(d => ApplyPlan(d.name, d.resources).isRight), full.map(d => ApplyPlan(d.name, d.resources)).toString)
    check("yaml: group as an option is not a second kind", yaml("name: x\nresources:\n  - directory: /d\n    group: app\n").isRight)
    check("yaml: an unknown option", yamlError("name: x\nresources:\n  - file: /a\n    content: x\n    mod: \"0644\"\n").contains("unknown option(s) mod"))
    check("yaml: two kinds", yamlError("name: x\nresources:\n  - file: /a\n    package: b\n").contains("more than one kind"))
    check("yaml: no kind", yamlError("name: x\nresources:\n  - path: /a\n").contains("no kind"))
    check("yaml: an unquoted mode", yamlError("name: x\nresources:\n  - file: /a\n    content: x\n    mode: 0644\n").contains("quote it"))
    check("yaml: content and source", yamlError("name: x\nresources:\n  - file: /a\n    content: x\n    source: y\n").contains("not both"))
    check("yaml: absent with options", yamlError("name: x\nresources:\n  - file: /a\n    state: absent\n    mode: \"0644\"\n").contains("takes no mode"))
    check("yaml: a bad state", yamlError("name: x\nresources:\n  - file: /a\n    state: gone\n").contains("present or absent"))
    check("yaml: an unknown top-level key", yamlError("name: x\nresource: []\n").contains("unknown key(s) resource"))
    check("yaml: the error names the resource", yamlError("name: x\nresources:\n  - file: /a\n    content: x\n  - symlink: /b\n").startsWith("resource 2: symlink: target is needed"), yamlError("name: x\nresources:\n  - file: /a\n    content: x\n  - symlink: /b\n"))
    // The demo descriptions in manifests/ read and plan (sbt may run this from the repository or from orchestrator/).
    List("apply_demo.yaml", "apply_demo_remove.yaml").foreach { f =>
      List(s"manifests/$f", s"../manifests/$f").find(p => Files.exists(Paths.get(p))) match
        case None    => check(s"yaml: $f found", false)
        case Some(p) =>
          val d = ApplyYaml.load(p)
          check(s"yaml: $f reads and plans", d.exists(x => ApplyPlan(x.name, x.resources).isRight), d.toString)
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
    check("yaml: applied, then unchanged", desc.size == 3 && first.results.forall(_.changed) && !second.changed, first.toString + second)

  private def totalChecks(): Unit =
    def report(node: String, o: ApplyOutcome) = ApplyReport(node, "t", check = false, List(ResourceResult("file[/x]", o)))
    val rs = Map("a" -> report("a", ApplyOutcome.Changed(Nil, Nil)), "b" -> report("b", ApplyOutcome.Unchanged("")))
    check(
      "total: changed, unchanged, and a node without a report counted as failed",
      OrpheraApply.total(List("a", "b", "c"), rs, check = false) == "All 3 nodes: 1 changed, 1 unchanged, 1 failed (c)",
      OrpheraApply.total(List("a", "b", "c"), rs, check = false)
    )
    check(
      "total: check mode says to change, and no failures part when none failed",
      OrpheraApply.total(List("a", "b"), rs, check = true) == "All 2 nodes: 1 to change, 1 unchanged"
    )

  private def diffChecks(): Unit =
    val d = TextDiff.unified("a\nb\nc\nd\ne\nf\ng\n", "a\nb\nc\nX\ne\nf\ng\n", "/f")
    check("diff: header and the change", d.take(2) == List("--- /f (now)", "+++ /f (wanted)") && d.contains("-d") && d.contains("+X"), d.toString)
    check("diff: the removed line before the added one", d.indexOf("-d") >= 0 && d.indexOf("-d") < d.indexOf("+X"), d.toString)
    check("diff: two lines of context, not more", d.contains(" b") && d.contains(" f") && !d.contains(" a") && !d.contains(" g"), d.toString)

  // ---- the real node side, on this machine ------------------------------

  private def localRunner(nt: NamedTask, node: Node, env: TaskExecutor.Env): IO[Boolean] =
    def ev(kind: Event.Kind, msg: String, exit: Int = 0, ok: Boolean = true) =
      env.output.event(node, nt.name, Event(kind, msg, exit, ok))
    nt.task match
      case Task.RunCommand(command, _, _) =>
        IO.blocking {
          val p = new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
          val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
          (out.linesIterator.toList, p.waitFor())
        }.flatMap((out, code) =>
          out.traverse_(l => ev(Event.Kind.OUTPUT, l)) >> ev(Event.Kind.RESULT, "", code, code == 0) >>
            IO.raiseWhen(code != 0)(new TaskExecutor.RemoteFailure("FAILED")).as(true)
        )
      case Task.WriteFile(content, dest, _, _, mode) =>
        IO.blocking {
          val path = Paths.get(dest)
          val same = Files.exists(path) && new String(Files.readAllBytes(path), StandardCharsets.UTF_8) == content
          if !same then Files.write(path, content.getBytes(StandardCharsets.UTF_8))
          if mode > 0 then Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(rwx(mode)))
          same
        }.flatMap(same => ev(Event.Kind.RESULT, if same then "Skipped: unchanged" else "", 0, true).as(!same))
      case _ => IO.pure(false)

  private def rwx(mode: Int): String =
    (0 until 9).map(i => if (mode & (1 << (8 - i))) != 0 then "rwx"(i % 3) else '-').mkString

  private def localChecks(): Unit =
    val dir: Path = Files.createTempDirectory("orphera-apply-check")
    val base = dir.toString
    val node = Node("local", "localhost")
    val restart = command("touch the marker", s"touch $base/notified", onChangeOnly = true)
    def desc = List(
      directory(s"$base/conf.d", mode = "0750"),
      file(s"$base/conf.d/it's.conf", "x = 1\ny = 2\n", mode = "0640").after(directory(s"$base/conf.d")).notifies(restart),
      symlink(s"$base/current", s"$base/conf.d/it's.conf"),
      command("make the state file", s"echo made > $base/state", creates = s"$base/state"),
      restart
    )
    def run(rs: List[ApplyResource], check: Boolean): Ran =
      (for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        setFacts <- SetFacts.empty
        env = TaskExecutor.Env(
          ClusterContext(Map.empty),
          setFacts,
          TaskExecutor.Output((_, _, _) => IO.unit, (_, _, _) => IO.unit, (_, _, _) => IO.unit),
          templating = false,
          perform = localRunner
        )
        plan <- Apply.plan("local", rs)
        report <- TaskExecutor.applyOn(plan, node, env, check, stopOnFailure = false, s => lines.update(_ :+ s))
        ls <- lines.get
      yield Ran(report, ls)).unsafeRunSync()

    try
      val planned = run(desc, check = true).report
      check("local check: all would change, nothing made", planned.results.count(_.changed) == 5 && !Files.exists(Paths.get(s"$base/conf.d")), planned.toString)
      val ranFirst = run(desc, check = false)
      val first = ranFirst.report
      val l1 = ranFirst.lines
      check("local first run: no failures", !first.failed, l1.mkString("\n"))
      check(
        "local first run: made as described",
        Files.isDirectory(Paths.get(s"$base/conf.d")) &&
          Files.readString(Paths.get(s"$base/conf.d/it's.conf")) == "x = 1\ny = 2\n" &&
          Files.readSymbolicLink(Paths.get(s"$base/current")).toString == s"$base/conf.d/it's.conf" &&
          Files.exists(Paths.get(s"$base/state")) && Files.exists(Paths.get(s"$base/notified")),
        l1.mkString("\n")
      )
      check(
        "local first run: modes",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(s"$base/conf.d"))) == "rwxr-x---" &&
          PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(s"$base/conf.d/it's.conf"))) == "rw-r-----",
        l1.mkString("\n")
      )
      Files.delete(Paths.get(s"$base/notified"))
      val ranSecond = run(desc, check = false)
      val second = ranSecond.report
      val l2 = ranSecond.lines
      check("local second run: nothing changed (read back by the real observe script)", !second.changed && !Files.exists(Paths.get(s"$base/notified")), l2.mkString("\n"))
      val edited = desc.updated(1, file(s"$base/conf.d/it's.conf", "x = 1\ny = 3\n", mode = "0640").after(directory(s"$base/conf.d")).notifies(restart))
      val ranDiff = run(edited, check = true)
      val diff = ranDiff.report
      val l3 = ranDiff.lines
      check("local check after an edit: a real diff", diff.changed && l3.contains("  -y = 2") && l3.contains("  +y = 3"), l3.mkString("\n"))
      val clash = List(file(s"$base/conf.d", "not a dir"))
      check("local: a directory where a file is wanted fails", run(clash, check = false).report.failed)
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
      check("local: an installed package (bash) is unchanged", kindOutcome("package[bash]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), kl)
      check("local: an absent package stays absent", kindOutcome("package[orphera-no-such-package]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), kl)
      check("local: a unit that does not exist would change in check mode", kindOutcome("service[orphera-no-such-unit.service]").exists(_.isInstanceOf[ApplyOutcome.WouldChange]), kl)
      check("local: root exists", kindOutcome("user[root]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]) && kindOutcome("group[root]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), kl)
      check("local: absent user and group", kindOutcome("user[orphera-nobody]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]) && kindOutcome("group[orphera-nogroup]").exists(_.isInstanceOf[ApplyOutcome.Unchanged]), kl)
      check("local: a new group would be created", kindOutcome("group[orphera-new-group]").contains(ApplyOutcome.WouldChange(List("created"), Nil)), kl)
    finally
      new ProcessBuilder("rm", "-rf", base).start().waitFor()
      ()

  def main(args: Array[String]): Unit =
    planChecks()
    runChecks()
    kindChecks()
    yamlChecks()
    totalChecks()
    diffChecks()
    localChecks()
    println(s"ApplyCheck: $passes passed, $failures failed")
    if failures > 0 then sys.exit(1)
