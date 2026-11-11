// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*

/** Runs a cluster playbook's stages in order, each stage's nodes in parallel,
  * waiting first for a stage's health check when it has one. The tasks
  * themselves are carried out by TaskExecutor, as are a flat playbook's and a
  * direct command's.
  */
object ClusterPlaybookRunner:

  /** Returns whether every stage completed successfully. `Main.scala` maps this
    * to the process exit code — previously it never did: `run` always completed
    * as `IO[Unit]` regardless of whether a stage failed or a health check timed
    * out (`runStages`'s failure branch only ever printed "... aborting
    * remaining stages", never raised or signaled failure), so
    * `orphera cluster-playbook ...` always exited 0 even when a stage genuinely
    * failed — the exact behavior `test_ceph_lifecycle.sh` had to work around by
    * grepping output for that message instead of trusting the exit code.
    */
  def run(playbook: ClusterPlaybook, resume: Boolean = false): IO[Boolean] =
    val allNodeNames = playbook.stages.flatMap(_.nodeNames).distinct
    val allNodes = Inventory.all.filter(n => allNodeNames.contains(n.name))

    if allNodes.isEmpty then
      IO.println(
        s"[${playbook.name}] No matching nodes found in inventory across any stage."
      ) >> IO.pure(false)
    else
      for
        context <- TaskExecutor.gatherFacts(allNodes)
        setFacts <- SetFacts.empty
        checkpoint <- Checkpoint.load("cluster", playbook.name, resume)
        runLog <- RunLog.start("cluster", playbook.name)
        env = TaskExecutor.Env(
          context,
          setFacts,
          TaskExecutor.Output.playbook(),
          templating = true
        )
        startMs <- IO.delay(System.currentTimeMillis())
        _ <- runLog.event(
          "event" -> "run_start",
          "playbook" -> playbook.name,
          "stage_count" -> playbook.stages.length
        )
        _ <- IO.println(
          s"[${playbook.name}] Starting — ${playbook.stages.length} stage(s)"
        )
        ok <- runStages(playbook.stages, env, checkpoint, runLog)
        endMs <- IO.delay(System.currentTimeMillis())
        _ <- runLog.event(
          "event" -> "run_end",
          "playbook" -> playbook.name,
          "success" -> ok,
          "duration_ms" -> (endMs - startMs)
        )
      yield ok

  private def runStages(
      stages: List[Stage],
      env: TaskExecutor.Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle
  ): IO[Boolean] =
    stages match
      case Nil => IO.println("All stages completed.") >> IO.pure(true)

      case stage :: rest =>
        IO.println(s"--- Stage '${stage.name}' ---") >>
          runStageTimed(stage, env, checkpoint, runLog).flatMap {
            case true  => runStages(rest, env, checkpoint, runLog)
            case false =>
              IO.println(
                s"Stage '${stage.name}' failed or did not become healthy — aborting remaining stages."
              ) >> IO.pure(false)
          }

  private def runStageTimed(
      stage: Stage,
      env: TaskExecutor.Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle
  ): IO[Boolean] =
    for
      startMs <- IO.delay(System.currentTimeMillis())
      _ <- runLog.event("event" -> "stage_start", "stage" -> stage.name)
      ok <- runStage(stage, env, checkpoint, runLog)
      endMs <- IO.delay(System.currentTimeMillis())
      _ <- runLog.event(
        "event" -> "stage_end",
        "stage" -> stage.name,
        "success" -> ok,
        "duration_ms" -> (endMs - startMs)
      )
    yield ok

  private def runStage(
      stage: Stage,
      env: TaskExecutor.Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle
  ): IO[Boolean] =
    val targets = Inventory.all.filter(n => stage.nodeNames.contains(n.name))

    if targets.isEmpty then
      IO.println(s"[${stage.name}] No matching nodes found in inventory.") >> IO
        .pure(false)
    else
      stage.waitFor match
        case Some(check) =>
          waitForHealthy(stage.name, check).flatMap {
            case false => IO.pure(false)
            case true  => runStageTasks(stage, targets, env, checkpoint, runLog)
          }
        case None =>
          runStageTasks(stage, targets, env, checkpoint, runLog)

  private def runStageTasks(
      stage: Stage,
      targets: List[Node],
      env: TaskExecutor.Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle
  ): IO[Boolean] =
    for
      results <- targets.parTraverse { node =>
        TaskExecutor
          .runTasks(
            stage.tasks,
            node,
            env,
            checkpoint,
            runLog,
            List("stage" -> stage.name)
          )
          .attempt
          .map(r => node.name -> r.getOrElse(false))
      }
      failed = results.collect { case (name, false) => name }
      allOk = failed.isEmpty
      _ <-
        if !allOk then
          IO.println(s"[${stage.name}] Failed on: ${failed.mkString(", ")}")
        else IO.unit
    yield allOk

  private def waitForHealthy(
      stageName: String,
      check: HealthCheck
  ): IO[Boolean] =
    check match

      case HealthCheck.Quorum(
            nodeNames,
            command,
            requiredCount,
            pollIntervalSeconds,
            timeoutSeconds
          ) =>
        val nodes = Inventory.all.filter(n => nodeNames.contains(n.name))
        val missing = nodeNames.filterNot(name => nodes.exists(_.name == name))

        if missing.nonEmpty then
          IO.println(
            s"[$stageName] Quorum check node(s) not found in inventory: ${missing.mkString(", ")}"
          ) >>
            IO.pure(false)
        else
          IO.println(
            s"[$stageName] Waiting for quorum: $requiredCount of ${nodes.length} (${nodeNames.mkString(", ")}) (timeout ${timeoutSeconds}s)..."
          ) >>
            pollQuorum(
              stageName,
              nodes,
              command,
              requiredCount,
              pollIntervalSeconds,
              timeoutSeconds,
              elapsed = 0
            )

      // Ssh checks deliberately do NOT go through Inventory.all.find —
      // the whole point of this variant (see HealthCheck.Ssh's doc
      // comment) is checking a brand-new VM that nothing has registered
      // in inventory.yaml yet. The Node built here is synthetic, purely
      // so pollHealthy/checkOnce/SshDeployer.checkAlive can keep reusing
      // the same Node-shaped plumbing every other health check already
      // uses — it's never looked up anywhere, just carries `host` along.
      case ssh @ HealthCheck.Ssh(onNode, host, _, _, _, _, timeoutSeconds) =>
        IO.println(
          s"[$stageName] Waiting for SSH health check on $onNode ($host) (timeout ${timeoutSeconds}s)..."
        ) >>
          pollHealthy(stageName, Node(onNode, host), ssh, elapsed = 0)

      case single =>
        val onNode = single match
          case HealthCheck.Sentinel(n, _, _, _, _) => n
          case HealthCheck.Command(n, _, _, _)     => n
          case _                                   => ""

        val timeoutSeconds = single match
          case HealthCheck.Sentinel(_, _, _, _, t) => t
          case HealthCheck.Command(_, _, _, t)     => t
          case _                                   => 0

        Inventory.all.find(_.name == onNode) match
          case None =>
            IO.println(
              s"[$stageName] Health check node '$onNode' not found in inventory."
            ) >>
              IO.pure(false)
          case Some(n) =>
            IO.println(
              s"[$stageName] Waiting for health check on $onNode (timeout ${timeoutSeconds}s)..."
            ) >>
              pollHealthy(stageName, n, single, elapsed = 0)

  private def pollHealthy(
      stageName: String,
      node: Node,
      check: HealthCheck,
      elapsed: Int
  ): IO[Boolean] =
    val (pollIntervalSeconds, timeoutSeconds) = check match
      case HealthCheck.Sentinel(_, _, _, p, t)  => (p, t)
      case HealthCheck.Command(_, _, p, t)      => (p, t)
      case HealthCheck.Quorum(_, _, _, p, t)    => (p, t)
      case HealthCheck.Ssh(_, _, _, _, _, p, t) => (p, t)

    if elapsed >= timeoutSeconds then
      IO.println(
        s"[$stageName] Health check timed out after ${timeoutSeconds}s"
      ) >> IO.pure(false)
    else
      checkOnce(node, check).attempt.flatMap {
        case Right(true) =>
          IO.println(s"[$stageName] Healthy after ~${elapsed}s") >> IO.pure(
            true
          )
        case _ =>
          IO.sleep(pollIntervalSeconds.seconds) >>
            pollHealthy(stageName, node, check, elapsed + pollIntervalSeconds)
      }

  private def checkOnce(node: Node, check: HealthCheck): IO[Boolean] =
    check match
      case HealthCheck.Sentinel(_, sentinelPath, expectedSha256, _, _) =>
        NodeClient.checkFile(node, sentinelPath, expectedSha256)

      case HealthCheck.Command(_, command, _, _) =>
        collectExitCode(node, command).map(_ == 0)

      case HealthCheck.Ssh(_, _, sshUser, sshKeyPath, remoteCommand, _, _) =>
        SshDeployer.checkAlive(node, sshUser, sshKeyPath, remoteCommand)

      case HealthCheck.Quorum(_, _, _, _, _) =>
        IO.raiseError(
          new IllegalStateException(
            "Quorum checks are polled via pollQuorum, not checkOnce"
          )
        )

  private def pollQuorum(
      stageName: String,
      nodes: List[Node],
      command: List[String],
      requiredCount: Int,
      pollIntervalSeconds: Int,
      timeoutSeconds: Int,
      elapsed: Int
  ): IO[Boolean] =
    if elapsed >= timeoutSeconds then
      IO.println(
        s"[$stageName] Quorum check timed out after ${timeoutSeconds}s"
      ) >> IO.pure(false)
    else
      nodes
        .parTraverse { node =>
          collectExitCode(node, command).attempt.map {
            case Right(0) => Some(node.name)
            case _        => None
          }
        }
        .flatMap { results =>
          val healthy = results.flatten
          if healthy.length >= requiredCount then
            IO.println(
              s"[$stageName] Quorum reached after ~${elapsed}s: ${healthy.mkString(", ")} ($requiredCount required)"
            ) >> IO.pure(true)
          else
            IO.println(
              s"[$stageName] Quorum check: ${healthy.length}/$requiredCount healthy so far (${healthy.mkString(", ")})"
            ) >>
              IO.sleep(pollIntervalSeconds.seconds) >>
              pollQuorum(
                stageName,
                nodes,
                command,
                requiredCount,
                pollIntervalSeconds,
                timeoutSeconds,
                elapsed + pollIntervalSeconds
              )
        }

  private def collectExitCode(node: Node, command: List[String]): IO[Int] =
    Ref.of[IO, Int](-1).flatMap { exitRef =>
      NodeClient
        .executeCommand(
          node,
          command,
          timeoutSeconds = 30,
          onEvent = event =>
            if event.kind == orphera.common.Event.Kind.RESULT then
              exitRef.set(event.exitCode)
            else IO.unit
        )
        .flatMap(_ => exitRef.get)
    }
