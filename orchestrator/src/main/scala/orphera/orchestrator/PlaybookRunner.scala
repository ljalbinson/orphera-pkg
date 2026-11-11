// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*

/** Runs a playbook's tasks on its nodes, the nodes in parallel. The tasks
  * themselves are carried out by TaskExecutor, as are a cluster playbook's and
  * a direct command's.
  */
object PlaybookRunner:

  /** Returns whether every targeted node's task sequence completed without a
    * failed task. `Main.scala` maps this to the process exit code.
    */
  def run(playbook: Playbook, resume: Boolean = false): IO[Boolean] =
    val targets = Inventory.all.filter(n => playbook.nodeNames.contains(n.name))

    if targets.isEmpty then
      IO.println(
        s"[${playbook.name}] No matching nodes found in inventory."
      ) >> IO.pure(false)
    else
      for
        _ <- AgentToken.requireConfigured
        context <- TaskExecutor.gatherFacts(targets)
        setFacts <- SetFacts.empty
        checkpoint <- Checkpoint.load("playbook", playbook.name, resume)
        runLog <- RunLog.start("playbook", playbook.name)
        env = TaskExecutor.Env(
          context,
          setFacts,
          TaskExecutor.Output.playbook(
            ". Stopping remaining tasks for this node."
          ),
          templating = true
        )
        startMs <- IO.delay(System.currentTimeMillis())
        _ <- runLog.event(
          "event" -> "run_start",
          "playbook" -> playbook.name,
          "target_count" -> targets.length
        )
        results <- targets.parTraverse(
          TaskExecutor.runTasks(playbook.tasks, _, env, checkpoint, runLog)
        )
        ok = results.forall(identity)
        endMs <- IO.delay(System.currentTimeMillis())
        _ <- runLog.event(
          "event" -> "run_end",
          "playbook" -> playbook.name,
          "success" -> ok,
          "duration_ms" -> (endMs - startMs)
        )
      yield ok

  /** A direct command (`orphera install`, `run`, `copy`, ...): a playbook of
    * one task, named after the command, on `nodes` in parallel. It differs
    * from a playbook run only in what TaskExecutor.commandEnv says: no facts
    * are gathered, `{{...}}` is sent as typed, there is no checkpoint or run
    * log (the audit log records the command), and the output is the console's
    * (`[node] ...`). A node that fails, or cannot be reached, does not stop the
    * others; with `retry` (`--retries`), it is tried again first. Returns
    * whether it succeeded on every node.
    */
  def runCommand(
      command: String,
      nodes: List[Node],
      task: Task,
      quiet: Boolean = false,
      retry: Option[Retry] = None
  ): IO[Boolean] =
    for
      _ <- AgentToken.requireConfigured
      env <- TaskExecutor.commandEnv(quiet)
      checkpoint <- Checkpoint.disabled
      runLog <- RunLog.disabled
      results <- nodes.parTraverse(
        TaskExecutor.runTasks(
          List(NamedTask(command, task, retry = retry)),
          _,
          env,
          checkpoint,
          runLog
        )
      )
    yield results.forall(identity)
