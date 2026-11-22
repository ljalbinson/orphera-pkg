// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import io.grpc.Metadata

import orphera.common.*

/** Checks the typed calls (ObserveNode, ApplyFiles, ApplyAccess, ApplySoftware)
  * and the role's path limits, as the Go agent's tests do. Run with
  * `sbt "agent/Test/runMain orphera.agent.AgentTypedCheck"`. Nothing here needs
  * root: what would change users, mounts or packages is checked only through
  * its refusals.
  */
object AgentTypedCheck:
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

  private def files(actions: FileAction.Action*): ApplyReply =
    TypedFiles.apply(actions.toList.map(a => FileAction(a)))

  private def text(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  def main(args: Array[String]): Unit =
    val dir = Files.createTempDirectory("agent-typed-check")
    val d = dir.toString

    // ApplyFiles
    val made = files(
      FileAction.Action.MakeDirectory(MakeDirectory(s"$d/a/b", mode = "0750"))
    )
    check(
      "make directory with parents",
      made.error.isEmpty && Files.isDirectory(dir.resolve("a/b")),
      made.toString
    )
    check(
      "directory mode",
      Fixed.unixMode(dir.resolve("a/b")) % 4096 == Integer.parseInt("750", 8)
    )
    val created = files(
      FileAction.Action.ReplaceText(
        ReplaceText(s"$d/a/f", "one\n", createMode = "0600")
      )
    )
    check(
      "replace text creates a file with the mode",
      created.error.isEmpty && text(dir.resolve("a/f")) == "one\n" &&
        Fixed.unixMode(dir.resolve("a/f")) % 4096 == Integer.parseInt("600", 8),
      created.toString
    )
    val again = files(
      FileAction.Action.ReplaceText(ReplaceText(s"$d/a/f", "two\n"))
    )
    check(
      "replace text with no hash refuses an existing file",
      again.completed == 0 && again.error.contains("exists now") &&
        text(dir.resolve("a/f")) == "one\n",
      again.toString
    )
    val stale = files(
      FileAction.Action.ReplaceText(
        ReplaceText(s"$d/a/f", "two\n", expectedSha256 = "00" * 32)
      )
    )
    check(
      "replace text with a stale hash refuses",
      stale.error.contains("changed since") && text(
        dir.resolve("a/f")
      ) == "one\n",
      stale.toString
    )
    val good = files(
      FileAction.Action.ReplaceText(
        ReplaceText(
          s"$d/a/f",
          "two\n",
          expectedSha256 = Fixed.sha256Text("one\n")
        )
      )
    )
    check(
      "replace text with the right hash keeps the mode",
      good.error.isEmpty && text(dir.resolve("a/f")) == "two\n" &&
        Fixed.unixMode(dir.resolve("a/f")) % 4096 == Integer.parseInt("600", 8),
      good.toString
    )
    val over = files(
      FileAction.Action.ReplaceText(
        ReplaceText(s"$d/a/f", "three\n", overwrite = true)
      )
    )
    check(
      "overwrite replaces a file",
      over.error.isEmpty && text(dir.resolve("a/f")) == "three\n",
      over.toString
    )
    val gone = files(
      FileAction.Action.ReplaceText(
        ReplaceText(s"$d/a/none", "x", expectedSha256 = "ab")
      )
    )
    check(
      "a hash for a missing file refuses",
      gone.error.contains("is gone"),
      gone.toString
    )
    val attrs = files(
      FileAction.Action.SetAttributes(SetAttributes(s"$d/a/f", mode = "0644"))
    )
    check(
      "set attributes changes the mode",
      attrs.error.isEmpty && Fixed.unixMode(
        dir.resolve("a/f")
      ) % 4096 == Integer.parseInt("644", 8),
      attrs.toString
    )
    val link = files(
      FileAction.Action.MakeSymlink(MakeSymlink(s"$d/a/l", "f")),
      FileAction.Action.MakeSymlink(MakeSymlink(s"$d/a/l", "g"))
    )
    check(
      "symlink made and replaced",
      link.error.isEmpty && link.completed == 2 &&
        Files.readSymbolicLink(dir.resolve("a/l")).toString == "g",
      link.toString
    )
    val dirLink = files(
      FileAction.Action.MakeSymlink(MakeSymlink(s"$d/a/b", "f"))
    )
    check(
      "a symlink does not replace a directory",
      dirLink.error.contains("is a directory"),
      dirLink.toString
    )
    val rmDir = files(FileAction.Action.RemovePath(RemovePath(s"$d/a")))
    check(
      "remove of a directory without the flag refuses",
      rmDir.error.contains("is a directory"),
      rmDir.toString
    )
    val rmFull = files(
      FileAction.Action.RemovePath(RemovePath(s"$d/a", directory = true))
    )
    check(
      "rmdir of a non-empty directory fails",
      rmFull.error.nonEmpty && Files.exists(dir.resolve("a/f")),
      rmFull.toString
    )
    val rm = files(
      FileAction.Action.RemovePath(RemovePath(s"$d/a/l")),
      FileAction.Action.RemovePath(RemovePath(s"$d/a/l")),
      FileAction.Action.RemovePath(RemovePath(s"$d/a/f")),
      FileAction.Action.RemovePath(RemovePath(s"$d/a/b", directory = true))
    )
    check(
      "remove link, again (gone is fine), file, empty directory",
      rm.error.isEmpty && rm.completed == 4 && !Files.exists(
        dir.resolve("a/b")
      ),
      rm.toString
    )
    Files.createDirectories(dir.resolve("t/d1/d2"))
    Files.writeString(dir.resolve("t/d1/d2/g"), "x")
    Files.writeString(dir.resolve("keep"), "x")
    Files.createSymbolicLink(dir.resolve("t/link"), dir.resolve("keep"))
    val tree = files(
      FileAction.Action.RemovePath(
        RemovePath(s"$d/t", directory = true, recursive = true)
      ),
      FileAction.Action.RemovePath(
        RemovePath(s"$d/t", directory = true, recursive = true)
      )
    )
    check(
      "recursive remove takes the tree, not what a link points to; gone is fine",
      tree.error.isEmpty && tree.completed == 2 && !Files.exists(
        dir.resolve("t")
      ) &&
        Files.exists(dir.resolve("keep")),
      tree.toString
    )
    for (bad, why) <- List(
        ("/srv", "too near the root"),
        (s"$d/x/", "not a clean path"),
        (s"$d/x/../y", "not a clean path"),
        ("rel/x", "not an absolute path")
      )
    do
      val r = files(
        FileAction.Action.RemovePath(
          RemovePath(bad, directory = true, recursive = true)
        )
      )
      check(
        s"recursive remove refuses $bad",
        r.error.contains(why) || r.error.nonEmpty,
        r.toString
      )
    val notDir = files(
      FileAction.Action.RemovePath(RemovePath(s"$d/keep", recursive = true))
    )
    check(
      "recursive needs the directory flag",
      notDir.error.contains("only for a directory"),
      notDir.toString
    )
    val (oldBusy, oldPoll) = (TypedSoftware.aptBusy, TypedSoftware.aptPoll)
    TypedSoftware.aptPoll = scala.concurrent.duration.FiniteDuration(1, "ms")
    var looks = 0
    TypedSoftware.aptBusy = () => { looks += 1; looks <= 3 }
    TypedSoftware.waitAptIdle(scala.concurrent.duration.FiniteDuration(1, "s"))
    check(
      "waitAptIdle waits while busy, then goes on",
      looks == 4,
      looks.toString
    )
    TypedSoftware.aptBusy = () => true
    val timedOut = scala.util
      .Try(
        TypedSoftware.waitAptIdle(
          scala.concurrent.duration.FiniteDuration(20, "ms")
        )
      )
      .isFailure
    check("waitAptIdle gives up when apt stays busy", timedOut)
    TypedSoftware.aptBusy = oldBusy
    TypedSoftware.aptPoll = oldPoll
    // apt-get update under a time limit: a stall is retried, a real failure is not
    def aptUpdateWith(body: String): (Int, Int, String) =
      val bin = Files.createTempDirectory(dir, "fake-apt")
      val counter = bin.resolve("calls")
      Files.writeString(
        bin.resolve("apt-get"),
        s"#!/bin/sh\necho x >> $counter\nn=$$(wc -l < $counter)\n$body\n"
      )
      bin.resolve("apt-get").toFile.setExecutable(true)
      val pb = new ProcessBuilder(AptInstaller.updateCommand(List("-q"))*)
      pb.environment().put("PATH", s"$bin:${sys.env.getOrElse("PATH", "")}")
      pb.environment().put("APT_UPDATE_TIMEOUT", "1")
      pb.environment().put("APT_UPDATE_PAUSE", "0")
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val out =
        new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val code = proc.waitFor()
      val n =
        if Files.exists(counter) then Files.readAllLines(counter).size else 0
      (code, n, out)
    val (c1, n1, o1) = aptUpdateWith("""echo "args: $*"""")
    check(
      "apt update: the options reach apt-get, once",
      c1 == 0 && n1 == 1 && o1.contains("args: -q update"),
      s"$c1 $n1 $o1"
    )
    val (c2, n2, o2) = aptUpdateWith(
      """if [ "$n" -eq 1 ]; then sleep 30; fi; echo done"""
    )
    check(
      "apt update: a stall is tried again",
      c2 == 0 && n2 == 2 && o2.contains("try 1 of 3"),
      s"$c2 $n2 $o2"
    )
    val (c3, n3, _) = aptUpdateWith("sleep 30")
    check(
      "apt update: gives up after three stalls",
      (c3 == 124 || c3 == 137) && n3 == 3,
      s"$c3 $n3"
    )
    val (c4, n4, o4) = aptUpdateWith("""echo "E: broken" >&2; exit 100""")
    check(
      "apt update: a real failure is not repeated",
      c4 == 100 && n4 == 1 && o4.contains("E: broken"),
      s"$c4 $n4 $o4"
    )
    val (c5, n5, o5) = aptUpdateWith(
      """if [ "$n" -eq 1 ]; then echo "Err:4 http://x InRelease"; echo "W: Failed to fetch http://x/InRelease  500"; else echo "Hit:1 ok"; fi"""
    )
    check(
      "apt update: an index that fails to fetch is tried again",
      c5 == 0 && n5 == 2 && o5.contains("Hit:1 ok") && o5.contains(
        "could not fetch every index (try 1 of 3)"
      ),
      s"$c5 $n5 $o5"
    )
    val (c6, n6, _) = aptUpdateWith(
      """echo "W: Failed to fetch http://x/InRelease  500""""
    )
    check(
      "apt update: keeps apt's exit status after three failed fetches",
      c6 == 0 && n6 == 3,
      s"$c6 $n6"
    )
    Files.write(dir.resolve("src.crt"), Array[Byte](1, 2, -1, 0, 3))
    val copied = files(
      FileAction.Action.ReplaceText(
        ReplaceText(
          s"$d/copy.crt",
          "",
          "",
          "0644",
          false,
          source = s"$d/src.crt"
        )
      )
    )
    check(
      "a file replaced from a source on the node: the bytes, not text",
      copied.error.isEmpty &&
        Files.readAllBytes(dir.resolve("copy.crt")).toList == List[Byte](1, 2,
          -1, 0, 3),
      copied.toString
    )
    for (name, act) <- List(
        (
          "text and a source",
          ReplaceText(s"$d/c2", "x", "", "", true, source = s"$d/src.crt")
        ),
        (
          "a missing source",
          ReplaceText(s"$d/c3", "", "", "", false, source = s"$d/none")
        ),
        (
          "a directory as the source",
          ReplaceText(s"$d/c4", "", "", "", false, source = d)
        ),
        (
          "a relative source",
          ReplaceText(s"$d/c5", "", "", "", false, source = "rel")
        )
      )
    do
      val r = files(FileAction.Action.ReplaceText(act))
      check(s"a source refused: $name", r.error.nonEmpty, r.toString)
    check(
      "the role's paths include the source",
      TypedFiles.paths(
        FileAction(
          FileAction.Action.ReplaceText(
            ReplaceText("/a/b", "", "", "", false, source = "/c/d")
          )
        )
      ) == List("/a/b", "/c/d")
    )
    val stop = files(
      FileAction.Action.MakeDirectory(MakeDirectory(s"$d/s1")),
      FileAction.Action.RemovePath(RemovePath("relative")),
      FileAction.Action.MakeDirectory(MakeDirectory(s"$d/s2"))
    )
    check(
      "it stops at the first failure and says how far it got",
      stop.completed == 1 && stop.error.startsWith("remove relative") && !Files
        .exists(dir.resolve("s2")),
      stop.toString
    )
    val prot = files(
      FileAction.Action.ReplaceText(
        ReplaceText("/etc/orphera-agent/agent.env", "x", overwrite = true)
      )
    )
    check(
      "the agent's own directory is refused",
      prot.error.contains("protected path"),
      prot.toString
    )
    val badSysctl = files(FileAction.Action.SetSysctl(SetSysctl("-w", "1")))
    check(
      "an option is not a sysctl key",
      badSysctl.error.contains("not a valid sysctl key"),
      badSysctl.toString
    )
    val badModule = files(FileAction.Action.LoadModule(LoadModule("a b")))
    check(
      "a module name with a space is refused",
      badModule.error.contains("not a valid module name"),
      badModule.toString
    )
    check(
      "the paths of an action",
      TypedFiles
        .paths(FileAction(FileAction.Action.SetSysctl(SetSysctl("a", "1"))))
        .isEmpty &&
        TypedFiles.paths(
          FileAction(FileAction.Action.MakeDirectory(MakeDirectory("/x")))
        ) == List("/x")
    )

    // ObserveNode
    Files.write(dir.resolve("o"), "hello\n".getBytes(StandardCharsets.UTF_8))
    val o = Observe.observePath(s"$d/o")
    check(
      "observe path: file",
      o.get("type").contains("file") && o
        .get("sha256")
        .contains(Fixed.sha256Text("hello\n")) && o.contains("mode") && o
        .contains("owner"),
      o.toString
    )
    check(
      "observe path: absent",
      Observe.observePath(s"$d/nope") == Map("type" -> "absent")
    )
    Files.createSymbolicLink(dir.resolve("ln"), Paths.get("o"))
    check(
      "observe path: link",
      Observe.observePath(s"$d/ln") == Map("type" -> "link", "target" -> "o")
    )
    check(
      "observe path: directory",
      Observe.observePath(d).get("type").contains("directory")
    )
    check(
      "observe refuses a relative path and a protected one",
      scala.util.Try(Observe.observePath("o")).isFailure &&
        scala.util
          .Try(Observe.observePath("/etc/orphera-agent/agent.env"))
          .isFailure
    )
    check(
      "observe file_text",
      Observe.observeFileText(s"$d/o") == Map(
        "exists" -> "yes",
        "truncated" -> "no",
        "content" -> "hello\n"
      ) &&
        Observe.observeFileText(s"$d/nope") == Map("exists" -> "no")
    )
    val item =
      Observe.item(Observation(id = 7, kind = "nonsense", target = "x"))
    check(
      "an unknown kind is that item's error",
      item.id == 7 && item.error.contains("unknown kind"),
      item.toString
    )
    val bad = Observe.item(Observation(id = 8, kind = "package", target = "-x"))
    check(
      "an option is not a package name",
      bad.error.contains("not a valid package name"),
      bad.toString
    )
    check(
      "observed paths",
      Observe.paths(Observation(1, "path", "/a")) == List("/a") &&
        Observe.paths(Observation(1, "package", "p")).isEmpty &&
        Observe.paths(Observation(1, "sysctl", "k", "/etc/sysctl.d/x")) == List(
          "/etc/sysctl.d/x"
        ) &&
        Observe.paths(Observation(1, "module", "m")).isEmpty
    )
    check(
      "sysctl and module persisted files must be directly in their directory",
      scala.util
        .Try(Observe.observeSysctl("kernel.ostype", "/etc/passwd"))
        .isFailure &&
        scala.util
          .Try(Observe.observeModule("loop", "/etc/sysctl.d/x"))
          .isFailure
    )
    val fstab = dir.resolve("fstab")
    Files.write(
      fstab,
      "# c\n/dev/sda1 /data ext4 defaults 0 2\n".getBytes(
        StandardCharsets.UTF_8
      )
    )
    val mi = dir.resolve("mountinfo")
    Files.write(
      mi,
      "36 35 98:0 / /my\\040disk rw - ext4 /dev/x rw\n".getBytes(
        StandardCharsets.UTF_8
      )
    )
    val m1 = Observe.observeMount("/data", fstab, mi)
    check(
      "observe mount: in fstab, not mounted",
      m1.get("fstab").contains("yes") && m1.get("dev").contains("/dev/sda1") &&
        m1.get("fstype").contains("ext4") && m1
          .get("opts")
          .contains("defaults") &&
        m1.get("mounted").contains("no") && m1.contains("fstab_text"),
      m1.toString
    )
    val m2 = Observe.observeMount("/my disk", fstab, mi)
    check(
      "observe mount: mounted, with an escaped name",
      m2.get("mounted").contains("yes") && m2.get("fstab").contains("no"),
      m2.toString
    )
    check(
      "unescape of mountinfo",
      Observe.unescapeMountField("/a\\040b\\011c") == "/a b\tc"
    )

    // ApplyAccess and ApplySoftware: validation only
    def access(a: AccessAction.Action*): ApplyReply =
      TypedAccess.apply(a.toList.map(x => AccessAction(x)))
    def software(a: SoftwareAction.Action*): ApplyReply =
      TypedSoftware.apply(a.toList.map(x => SoftwareAction(x)))
    check(
      "access: bad names and numbers are refused before anything runs",
      access(AccessAction.Action.CreateUser(CreateUser(name = "-x"))).error
        .contains("not a valid user name") &&
        access(
          AccessAction.Action.CreateUser(CreateUser(name = "u", uid = "1x"))
        ).error.contains("not a uid") &&
        access(
          AccessAction.Action.CreateUser(
            CreateUser(name = "u", groups = Seq("a,b"))
          )
        ).error.contains("not a valid group") &&
        access(AccessAction.Action.ModifyUser(ModifyUser(name = "u"))).error
          .contains("nothing to change") &&
        access(AccessAction.Action.ModifyGroup(ModifyGroup("g", "x"))).error
          .contains("not a valid group or gid") &&
        access(AccessAction.Action.DeleteGroup(DeleteGroup("a b"))).error
          .contains("not a valid group name") &&
        access(AccessAction.Action.Unmount(Unmount("rel"))).error
          .contains("not an absolute path") &&
        access(
          AccessAction.Action.AddAuthorizedKey(
            AddAuthorizedKey("-r", "ssh-ed25519 AAA")
          )
        ).error.contains("not a valid user name")
    )
    check(
      "software: bad names, versions and verbs are refused",
      software(
        SoftwareAction.Action.PackageInstall(PackageInstall(name = "a=b"))
      ).error.contains("not a valid package name") &&
        software(
          SoftwareAction.Action.PackageInstall(
            PackageInstall(name = "a", version = "1/2")
          )
        ).error.contains("not a valid package version") &&
        software(SoftwareAction.Action.PackageRemove(PackageRemove("-y"))).error
          .contains("not a valid package name") &&
        software(
          SoftwareAction.Action.UnitControl(UnitControl("../x", "start"))
        ).error.contains("not a valid unit name") &&
        software(
          SoftwareAction.Action.UnitControl(UnitControl("ssh.service", "mask"))
        ).error.contains("not a unit action") &&
        software(
          SoftwareAction.Action.UnitControl(UnitControl("ssh.service", "mask"))
        ).completed == 0
    )

    // Path limits
    Files.createDirectories(dir.resolve("allowed"))
    val policy = dir.resolve("policy")
    Files.write(
      policy,
      s"""default_role admin
         |role deployer copy,apply-files
         |paths deployer $d/allowed/
         |role admin all
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val revoked = dir.resolve("revoked")
    def headers(role: Option[String]): Metadata =
      val h = new Metadata()
      role.foreach(h.put(PathLimits.RoleKey, _))
      h
    def refused(role: Option[String], path: String): Boolean =
      PathLimits.refusal(headers(role), path, policy, revoked).isDefined
    check(
      "path limits: a limited role inside and outside its paths",
      !refused(Some("deployer"), s"$d/allowed/x") &&
        !refused(Some("deployer"), s"$d/allowed/new/dir/x") &&
        refused(Some("deployer"), s"$d/other/x") &&
        refused(Some("deployer"), s"$d/allowed/../other") &&
        refused(Some("deployer"), s"$d/allowed2/x")
    )
    check(
      "path limits: a role with no paths, and no role, are not limited",
      !refused(Some("admin"), "/etc/passwd") && !refused(None, "/etc/passwd")
    )
    Files.createSymbolicLink(dir.resolve("allowed/out"), Paths.get("/etc"))
    check(
      "path limits: a link out of the paths does not lead out",
      refused(Some("deployer"), s"$d/allowed/out/passwd")
    )
    check(
      "path limits: an unreadable policy limits nobody here (the interceptor refuses first)",
      !PathLimits
        .refusal(
          headers(Some("deployer")),
          "/x",
          dir.resolve("no-policy"),
          revoked
        )
        .isDefined
    )

    // The interceptor puts the caller's role in the headers
    val decision: AuthInterceptor.Decision =
      AuthInterceptor.Decision.Allow("x", Some("deployer"))
    check(
      "the decision carries the role",
      decision match
        case AuthInterceptor.Decision.Allow(_, role) =>
          role.contains("deployer")
        case _ => false
    )

    if failures == 0 then println(s"AgentTypedCheck: all $passes checks pass")
    else
      println(s"AgentTypedCheck: $failures failed, $passes passed")
      sys.exit(1)
