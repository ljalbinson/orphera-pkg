// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
import scala.concurrent.duration.*

object ClusterPlaybookRunner:

  def run(playbook: ClusterPlaybook): IO[Unit] =
    val allNodeNames = playbook.stages.flatMap(_.nodeNames).distinct
    val allNodes = Inventory.all.filter(n => allNodeNames.contains(n.name))

    if allNodes.isEmpty then
      IO.println(
        s"[${playbook.name}] No matching nodes found in inventory across any stage."
      )
    else
      for
        context <- gatherClusterFacts(allNodes)
        _ <- IO.println(
          s"[${playbook.name}] Starting — ${playbook.stages.length} stage(s)"
        )
        _ <- runStages(playbook.stages, context)
      yield ()

  private def gatherClusterFacts(nodes: List[Node]): IO[ClusterContext] =
    nodes
      .parTraverse { node =>
        NodeClient
          .gatherFacts(node)
          .attempt
          .map(result => node.name -> result.toOption)
      }
      .map(pairs =>
        ClusterContext(pairs.collect { case (name, Some(f)) =>
          name -> f
        }.toMap)
      )

  private def runStages(
      stages: List[Stage],
      context: ClusterContext
  ): IO[Unit] =
    stages match
      case Nil => IO.println("All stages completed.")

      case stage :: rest =>
        IO.println(s"--- Stage '${stage.name}' ---") >>
          runStage(stage, context).flatMap {
            case true  => runStages(rest, context)
            case false =>
              IO.println(
                s"Stage '${stage.name}' failed or did not become healthy — aborting remaining stages."
              )
          }

  /** Runs every node in a stage in parallel, then — if all tasks on all nodes
    * succeeded — polls the stage's health gate (if any) before reporting the
    * stage as ready. Returns false on either a task failure or a health-check
    * timeout, either of which stops the whole cluster playbook.
    */
  private def runStage(stage: Stage, context: ClusterContext): IO[Boolean] =
    val targets = Inventory.all.filter(n => stage.nodeNames.contains(n.name))

    if targets.isEmpty then
      IO.println(s"[${stage.name}] No matching nodes found in inventory.") >> IO
        .pure(false)
    else
      stage.waitFor match
        case Some(check) =>
          waitForHealthy(stage.name, check).flatMap {
            case false => IO.pure(false)
            case true  => runStageTasks(stage, targets, context)
          }
        case None =>
          runStageTasks(stage, targets, context)

  private def runStageTasks(
      stage: Stage,
      targets: List[Node],
      context: ClusterContext
  ): IO[Boolean] =
    for
      results <- targets.parTraverse { node =>
        runTasksForNode(stage, node, context).attempt.map(r =>
          node.name -> r.isRight
        )
      }
      failed = results.collect { case (name, false) => name }
      allOk = failed.isEmpty
      _ <-
        if !allOk then
          IO.println(s"[${stage.name}] Failed on: ${failed.mkString(", ")}")
        else IO.unit
    yield allOk

  private def runTasksForNode(
      stage: Stage,
      node: Node,
      context: ClusterContext
  ): IO[Unit] =
    val factsOpt = context.factsByNode.get(node.name)
    runTasks(stage.tasks, node, factsOpt, context)

  // Reuses the exact task-execution logic from PlaybookRunner — a
  // node's task sequence within a stage behaves identically to a
  // node's task sequence in a flat Playbook, just grouped under a
  // named, ordered stage instead of running alone.
  private def runTasks(
      tasks: List[NamedTask],
      node: Node,
      facts: Option[orphera.common.Facts],
      context: ClusterContext
  ): IO[Unit] =
    tasks match
      case Nil               => IO.unit
      case namedTask :: rest =>
        val shouldRun = namedTask.when match
          case None       => true
          case Some(cond) =>
            facts match
              case Some(f) => cond.matches(f)
              case None    => false

        if !shouldRun then
          IO.println(
            s"[${node.name}] ${namedTask.name}: skipped (condition not met)"
          ) >>
            runTasks(rest, node, facts, context)
        else
          runSingleTask(namedTask, node, facts, context).attempt.flatMap {
            case Right(()) => runTasks(rest, node, facts, context)
            case Left(err) =>
              IO.println(
                s"[${node.name}] ${namedTask.name}: FAILED — ${err.getMessage}"
              ) >> IO.raiseError(err)
          }

  private def runSingleTask(
      namedTask: NamedTask,
      node: Node,
      facts: Option[orphera.common.Facts],
      context: ClusterContext
  ): IO[Unit] =
    val render: orphera.common.Event => IO[Unit] = event =>
      IO.println(s"[${node.name}] ${namedTask.name}: ${eventLine(event)}")

    namedTask.task match
      case Task.Install(packages, updateCache) =>
        NodeClient.installPackages(node, packages, updateCache, render)
      case Task.Remove(packages, purge) =>
        NodeClient.removePackages(node, packages, purge, render)
      case Task.AutoRemove(purge) =>
        NodeClient.autoRemove(node, purge, render)
      case Task.Copy(src, dest, owner, group, mode, _) =>
        NodeClient.copyFile(
          node,
          java.nio.file.Paths.get(src),
          dest,
          owner,
          group,
          mode,
          render
        )
      case Task.NetworkApply(timeoutSeconds) =>
        NodeClient.applyNetworkConfig(node, timeoutSeconds, render)
      case Task.Reboot(delaySeconds, waitForReturn, waitTimeoutSeconds) =>
        NodeClient.reboot(
          node,
          delaySeconds,
          waitForReturn,
          waitTimeoutSeconds,
          line => IO.println(s"[${node.name}] ${namedTask.name}: $line")
        )

  private def waitForHealthy(
      stageName: String,
      check: HealthCheck
  ): IO[Boolean] =
    val node = Inventory.all.find(_.name == check.onNode)

    node match
      case None =>
        IO.println(
          s"[$stageName] Health check node '${check.onNode}' not found in inventory."
        ) >>
          IO.pure(false)
      case Some(n) =>
        IO.println(
          s"[$stageName] Waiting for health check on ${check.onNode} (timeout ${check.timeoutSeconds}s)..."
        ) >>
          pollHealthy(stageName, n, check, elapsed = 0)

  private def pollHealthy(
      stageName: String,
      node: Node,
      check: HealthCheck,
      elapsed: Int
  ): IO[Boolean] =
    if elapsed >= check.timeoutSeconds then
      IO.println(
        s"[$stageName] Health check timed out after ${check.timeoutSeconds}s"
      ) >> IO.pure(false)
    else
      NodeClient
        .checkFile(node, check.sentinelPath, check.expectedSha256)
        .attempt
        .flatMap {
          case Right(healthy) if healthy =>
            IO.println(s"[$stageName] Healthy after ~${elapsed}s") >> IO.pure(
              true
            )
          case _ =>
            IO.sleep(check.pollIntervalSeconds.seconds) >>
              pollHealthy(
                stageName,
                node,
                check,
                elapsed + check.pollIntervalSeconds
              )
        }

  private def eventLine(event: orphera.common.Event): String =
    event.kind match
      case orphera.common.Event.Kind.PROGRESS => event.message
      case orphera.common.Event.Kind.OUTPUT   => event.message
      case orphera.common.Event.Kind.RESULT   =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
