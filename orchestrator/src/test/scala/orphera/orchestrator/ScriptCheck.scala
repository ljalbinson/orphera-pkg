// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import orphera.common.Event

/** Checks for OrpheraScript (docs/SCRIPT-DSL-DESIGN.md): values from one step
  * used by the next, `each` as a barrier that names the nodes that failed, a
  * query's non-zero exit as a value, step names unique on a node, `--resume`
  * skipping done changes but running queries again. A stand-in task runner
  * plays the agent: a shell step's script says what happens (`say <text>`
  * prints the text, `exit <n>` exits with n, `fail` fails, `change` changes
  * something). Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.ScriptCheck"`.
  */
object ScriptCheck:
  private var failures = 0
  private var passes = 0

  private def check(name: String, ok: Boolean, detail: => String): Unit =
    if ok then passes += 1
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val a = Node("a", "a.invalid")
  private val b = Node("b", "b.invalid")

  // The stand-in for the agent.
  private def fake(nt: NamedTask, node: Node, env: TaskExecutor.Env): IO[Boolean] =
    def event(kind: Event.Kind, msg: String, exit: Int = 0, ok: Boolean = true) =
      env.output.event(node, nt.name, Event(kind, msg, exit, ok))
    nt.task match
      case Task.RunCommand(List("sh", "-c", script), _, _) =>
        script.split(' ').toList match
          case "say" :: words =>
            event(Event.Kind.OUTPUT, words.mkString(" ")) >> event(Event.Kind.RESULT, "", 0, true).as(true)
          case "exit" :: n :: Nil =>
            val code = n.toIntOption.getOrElse(1)
            event(Event.Kind.OUTPUT, s"exiting $code") >>
              event(Event.Kind.RESULT, "", code, code == 0) >>
              IO.raiseWhen(code != 0)(new TaskExecutor.RemoteFailure("FAILED")).as(true)
          case "fail" :: _   => IO.raiseError(new RuntimeException(s"${node.name} failed on purpose"))
          case "change" :: _ => event(Event.Kind.RESULT, "", 0, true).as(true)
          case _             => event(Event.Kind.RESULT, "", 0, true).as(false)
      case _ => IO.pure(false)

  /** Runs `body` as a script on nodes a and b; returns its outcome and the
    * lines it printed (as "node|step|text").
    */
  private def runScript(
      done: List[(String, String)] = Nil
  )(body: Orphera => IO[Unit]): (Either[Throwable, Unit], Vector[String]) =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      output = TaskExecutor.Output(
        (node, task, event) =>
          lines.update(_ :+ s"${node.name}|$task|${if event.kind == Event.Kind.OUTPUT then event.message else s"exit=${event.exitCode}"}"),
        (node, task, text) => lines.update(_ :+ s"${node.name}|$task|$text"),
        (node, task, err) => lines.update(_ :+ s"${node.name}|$task|FAILED|${err.getMessage}")
      )
      checkpoint <- Checkpoint.disabled
      _ <- done.traverse_ { case (n, s) => checkpoint.markDone(n, s) }
      runLog <- RunLog.disabled
      orphera <- Orphera.make(
        "check",
        checkpoint,
        runLog,
        env => env.copy(output = output, perform = fake),
        inventory = List(a, b)
      )
      result <- IO.defer(body(orphera)).attempt
      recorded <- lines.get
    yield (result, recorded)).unsafeRunSync()

  def main(args: Array[String]): Unit =
    // A value from one node decides what happens, and is used on another.
    val (r1, l1) = runScript() { o =>
      for
        answer <- o.node("a").query("is a reboot needed", "say yes")
        _ <-
          if answer.stdout.trim == "yes" then o.node("a").sh("reboot it", "change").void
          else IO.unit
        name <- o.node("a").query("read the name", "say alpha")
        _ <- o.node("b").sh(s"use ${name.stdout}", s"say got ${name.stdout}")
      yield ()
    }
    check("a script runs", r1.isRight, r1.toString)
    check("a query's output decides the next step", l1.contains("a|reboot it|exit=0"), l1.toString)
    check("a value read on one node is used on another", l1.contains("b|use alpha|got alpha"), l1.toString)

    // A query's non-zero exit is a value; a change's is a failure.
    val (r2, _) = runScript() { o =>
      o.node("a").query("is the file there", "exit 3").flatMap(res =>
        IO.raiseUnless(res.exitCode == 3 && !res.changed)(new RuntimeException(s"got $res"))
      )
    }
    check("a query that exits 3 returns exitCode 3 and does not fail", r2.isRight, r2.toString)
    val (r3, l3) = runScript()(o => o.node("a").sh("must work", "exit 3").void)
    check(
      "a change step that exits non-zero fails the script",
      r3.isLeft && l3.exists(_.startsWith("a|must work|FAILED|")),
      s"$r3 $l3"
    )

    // each: all nodes run; the failure names the node; the next part does not run.
    val (r4, l4) = runScript() { o =>
      for
        _ <- o.nodes("a", "b").each(n => n.sh("first part", if n.name == "b" then "fail" else "say ok"))
        _ <- o.nodes("a", "b").each(_.sh("second part", "say never"))
      yield ()
    }
    check(
      "each fails after all nodes ran, naming the one that failed",
      r4.left.exists(_.getMessage == "failed on b") && l4.contains("a|first part|ok"),
      s"$r4 $l4"
    )
    check("after a failed each, the script stops", !l4.exists(_.contains("second part")), l4.toString)

    val (r5, _) = runScript() { o =>
      o.nodes("a", "b").each(n => n.query("which", s"say ${n.name}").map(_.stdout)).flatMap(names =>
        IO.raiseUnless(names == List("a", "b"))(new RuntimeException(names.toString))
      )
    }
    check("each returns each node's result, in order", r5.isRight, r5.toString)

    // Step names are unique on a node.
    val (r6, _) = runScript() { o =>
      List("sdb", "sdc").traverse_(_ => o.node("a").sh("zap", "change").void)
    }
    check(
      "a change step name used twice on a node fails, saying how to fix it",
      r6.left.exists(_.getMessage.contains("used twice on a")),
      r6.toString
    )
    val (r7, _) = runScript() { o =>
      List("sdb", "sdc").traverse_(d => o.node("a").sh(s"zap $d", "change").void) >>
        o.node("b").sh("zap sdb", "change").void >>
        o.node("a").query("look", "say x") >> o.node("a").query("look", "say x").void
    }
    check("names with the item, the same name on another node, and repeated queries are fine", r7.isRight, r7.toString)

    // --resume: a done change is skipped, with no output; queries run again.
    val (r8, l8) = runScript(done = List("a" -> "install it")) { o =>
      for
        skipped <- o.node("a").sh("install it", "say installing")
        q <- o.node("a").query("read it", "say value")
        _ <- IO.raiseUnless(skipped.skipped && skipped.output.isEmpty && q.stdout == "value")(
          new RuntimeException(s"$skipped $q")
        )
      yield ()
    }
    check(
      "on --resume a done change is skipped without output, and a query runs again",
      r8.isRight && !l8.contains("a|install it|installing") && l8.exists(_.contains("skipped (already completed")),
      s"$r8 $l8"
    )

    // Names and modes are checked.
    val (r9, _) = runScript()(o => o.node("nowhere").sh("x", "say x").void)
    check("an unknown node fails the script, naming it", r9.left.exists(_.getMessage.contains("'nowhere'")), r9.toString)
    val (r10, _) = runScript()(o => o.node("a").writeFile("bad mode", "x", "/tmp/x", mode = "0999").void)
    check("a mode that is not octal fails", r10.left.exists(_.getMessage.contains("not an octal number")), r10.toString)

    // A secret is masked when printed.
    val (r11, _) = runScript() { o =>
      val s = o.secret("s3cr3t-value-for-check")
      IO.raiseUnless(s.toString != "s3cr3t-value-for-check" && s.reveal == "s3cr3t-value-for-check")(
        new RuntimeException("secret shown")
      )
    }
    check("orphera.secret keeps the value out of toString", r11.isRight, r11.toString)

    // waitUntil: true in time, and a timeout that says what the last attempt was.
    val (r12, _) = runScript() { o =>
      Ref.of[IO, Int](0).flatMap(n => o.waitUntil("third try", 10, 0)(n.updateAndGet(_ + 1).map(_ >= 3)))
    }
    check("waitUntil returns once the check is true", r12.isRight, r12.toString)
    val (r13, _) = runScript()(o => o.waitUntil("never", 0, 0)(IO.pure(false)))
    check("waitUntil fails after its timeout", r13.left.exists(_.getMessage.contains("not true after 0s")), r13.toString)

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"ScriptCheck: all $passes checks passed")
