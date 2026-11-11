// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import io.grpc.Status

/** Checks for TaskExecutor, the one executor that playbooks, cluster playbooks
  * and the direct commands share: templating on and off, a failure stopping
  * only that node's remaining tasks, `when:` without facts, and how a failure
  * is described. Needs no agent: the tasks used (set_fact, debug, and a
  * distribute_file from a node that is not in the inventory) never contact
  * one. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.TaskExecutorCheck"`.
  */
object TaskExecutorCheck:
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

  /** An Output that records each line as "node|task|text" and each failure as
    * "node|task|FAILED|message".
    */
  private def recording(lines: Ref[IO, Vector[String]]): TaskExecutor.Output =
    TaskExecutor.Output(
      (node, task, event) =>
        lines.update(_ :+ s"${node.name}|$task|event|${event.message}"),
      (node, task, text) => lines.update(_ :+ s"${node.name}|$task|$text"),
      (node, task, err) =>
        lines.update(_ :+ s"${node.name}|$task|FAILED|${err.getMessage}")
    )

  /** Runs `tasks` on each of `nodes` and returns each node's result and the
    * recorded lines.
    */
  private def run(
      tasks: List[NamedTask],
      nodes: List[Node],
      templating: Boolean
  ): (List[Boolean], Vector[String]) =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      setFacts <- SetFacts.empty
      env = TaskExecutor.Env(
        ClusterContext(Map.empty),
        setFacts,
        recording(lines),
        templating
      )
      checkpoint <- Checkpoint.disabled
      runLog <- RunLog.disabled
      results <- nodes.parTraverse(
        TaskExecutor.runTasks(tasks, _, env, checkpoint, runLog)
      )
      recorded <- lines.get
    yield (results, recorded)).unsafeRunSync()

  private val a = Node("check-a", "check-a.invalid")
  private val b = Node("check-b", "check-b.invalid")

  def main(args: Array[String]): Unit =
    val greet = List(
      NamedTask("set", Task.SetFact("greeting", "hello")),
      NamedTask("say", Task.Debug("{{greeting}} world"))
    )

    val (onOk, onLines) = run(greet, List(a), templating = true)
    check("templating on: the tasks succeed", onOk == List(true), onOk.toString)
    check(
      "templating on: set_fact is shown",
      onLines.contains("check-a|set|set greeting = hello"),
      onLines.toString
    )
    check(
      "templating on: {{...}} is rendered with the node's set facts",
      onLines.contains("check-a|say|hello world"),
      onLines.toString
    )

    val (_, offLines) = run(greet, List(a), templating = false)
    check(
      "templating off (a direct command): {{...}} is sent as typed",
      offLines.contains("check-a|say|{{greeting}} world"),
      offLines.toString
    )

    val failing = List(
      NamedTask("first", Task.Debug("one")),
      NamedTask(
        "fetch",
        Task.DistributeFile("no-such-node", "/etc/hostname", "/tmp/x")
      ),
      NamedTask("after", Task.Debug("never"))
    )
    val (failOk, failLines) = run(failing, List(a, b), templating = true)
    check(
      "a failed task fails each node it ran on",
      failOk == List(false, false),
      failOk.toString
    )
    check(
      "a failure is reported once per node, with its message",
      failLines.count(l => l.contains("|fetch|FAILED|") && l.contains("no-such-node")) == 2,
      failLines.toString
    )
    check(
      "the tasks before a failure ran on every node",
      failLines.count(_.endsWith("|first|one")) == 2,
      failLines.toString
    )
    check(
      "a failure stops that node's remaining tasks",
      !failLines.exists(_.contains("|after|")),
      failLines.toString
    )

    val conditional = List(
      NamedTask(
        "only-ubuntu",
        Task.Debug("ran"),
        Some(FactCondition("os_id", "ubuntu"))
      ),
      NamedTask("always", Task.Debug("ran"))
    )
    val (whenOk, whenLines) = run(conditional, List(a), templating = true)
    check(
      "a when: with no facts for the node skips the task, and the run goes on",
      whenOk == List(true) &&
        whenLines.contains("check-a|only-ubuntu|skipped (condition not met)") &&
        whenLines.contains("check-a|always|ran"),
      whenLines.toString
    )

    val unavailable = Status.UNAVAILABLE.withDescription("io exception").asRuntimeException()
    check(
      "a failed agent call is described as unreachable",
      TaskExecutor.failureLine(unavailable).startsWith("unreachable: UNAVAILABLE"),
      TaskExecutor.failureLine(unavailable)
    )
    check(
      "an agent call failure wrapped in another exception is still recognised",
      TaskExecutor
        .failureLine(new RuntimeException("wrapped", unavailable))
        .startsWith("unreachable:"),
      TaskExecutor.failureLine(new RuntimeException("wrapped", unavailable))
    )
    check(
      "a rejected token is described as unauthenticated",
      TaskExecutor
        .failureLine(Status.UNAUTHENTICATED.asRuntimeException())
        .startsWith("unauthenticated:")
    )
    check(
      "any other failure is FAILED with its message",
      TaskExecutor.failureLine(new RuntimeException("No such file: x")) ==
        "FAILED: No such file: x",
      TaskExecutor.failureLine(new RuntimeException("No such file: x"))
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"TaskExecutorCheck: all $passes checks passed")
