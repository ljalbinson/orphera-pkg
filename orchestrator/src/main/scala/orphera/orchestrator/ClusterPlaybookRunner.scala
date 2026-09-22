// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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
        setFacts <- SetFacts.empty
        _ <- IO.println(
          s"[${playbook.name}] Starting — ${playbook.stages.length} stage(s)"
        )
        _ <- runStages(playbook.stages, context, setFacts)
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
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
    stages match
      case Nil => IO.println("All stages completed.")

      case stage :: rest =>
        IO.println(s"--- Stage '${stage.name}' ---") >>
          runStage(stage, context, setFacts).flatMap {
            case true  => runStages(rest, context, setFacts)
            case false =>
              IO.println(
                s"Stage '${stage.name}' failed or did not become healthy — aborting remaining stages."
              )
          }

  private def runStage(
      stage: Stage,
      context: ClusterContext,
      setFacts: SetFacts
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
            case true  => runStageTasks(stage, targets, context, setFacts)
          }
        case None =>
          runStageTasks(stage, targets, context, setFacts)

  private def runStageTasks(
      stage: Stage,
      targets: List[Node],
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Boolean] =
    for
      results <- targets.parTraverse { node =>
        runTasksForNode(stage, node, context, setFacts).attempt.map(r =>
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
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
    val factsOpt = context.factsByNode.get(node.name)
    runTasks(stage.tasks, node, factsOpt, context, setFacts)

  private def runTasks(
      tasks: List[NamedTask],
      node: Node,
      facts: Option[orphera.common.Facts],
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
    tasks match
      case Nil               => IO.unit
      case namedTask :: rest =>
        setFacts.get(node.name).flatMap { ownSetFacts =>
          val shouldRun = namedTask.when match
            case None       => true
            case Some(cond) =>
              facts match
                case Some(f) => cond.matches(f, ownSetFacts)
                case None    => false

          if !shouldRun then
            IO.println(
              s"[${node.name}] ${namedTask.name}: skipped (condition not met)"
            ) >>
              runTasks(rest, node, facts, context, setFacts)
          else
            runSingleTask(namedTask, node, facts, context, setFacts).attempt
              .flatMap {
                case Right(()) => runTasks(rest, node, facts, context, setFacts)
                case Left(err) =>
                  IO.println(
                    s"[${node.name}] ${namedTask.name}: FAILED — ${err.getMessage}"
                  ) >> IO.raiseError(err)
              }
        }

  /** See PlaybookRunner.requireStreamedSuccess — identical logic, duplicated
    * here rather than shared (matching this file's existing pre-established
    * duplication of the flat runner's task-execution logic).
    */
  private def requireStreamedSuccess(
      render: orphera.common.Event => IO[Unit]
  )(run: (orphera.common.Event => IO[Unit]) => IO[Unit]): IO[Unit] =
    for
      failed <- Ref.of[IO, Boolean](false)
      wrappedRender = (event: orphera.common.Event) =>
        render(event) >> (
          if event.kind == orphera.common.Event.Kind.RESULT && !event.success
          then failed.set(true)
          else IO.unit
        )
      _ <- run(wrappedRender)
      didFail <- failed.get
      _ <-
        if didFail then
          IO.raiseError(
            new RuntimeException(
              "Remote command reported failure (see output above)"
            )
          )
        else IO.unit
    yield ()

  private def runSingleTask(
      namedTask: NamedTask,
      node: Node,
      facts: Option[orphera.common.Facts],
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
    val render: orphera.common.Event => IO[Unit] = event =>
      IO.println(s"[${node.name}] ${namedTask.name}: ${eventLine(event)}")

    namedTask.task match

      case Task.Install(packages, updateCache, version) =>
        buildTemplateVars(facts, context, node, setFacts).flatMap { vars =>
          val renderedVersion = if version.contains("{{") then
            Templating.render(version, vars)
          else version
          val versionedPackages =
            if renderedVersion.isEmpty then packages
            else packages.map(pkg => s"$pkg=$renderedVersion")
          requireStreamedSuccess(render)(r =>
            NodeClient.installPackages(node, versionedPackages, updateCache, r)
          )
        }

      case Task.Remove(packages, purge) =>
        requireStreamedSuccess(render)(r =>
          NodeClient.removePackages(node, packages, purge, r)
        )

      case Task.AutoRemove(purge) =>
        requireStreamedSuccess(render)(r =>
          NodeClient.autoRemove(node, purge, r)
        )

      case Task.Copy(src, dest, owner, group, mode, _) =>
        requireStreamedSuccess(render)(r =>
          NodeClient.copyFile(
            node,
            java.nio.file.Paths.get(src),
            dest,
            owner,
            group,
            mode,
            r
          )
        )

      case Task.NetworkApply(timeoutSeconds) =>
        requireStreamedSuccess(render)(r =>
          NodeClient.applyNetworkConfig(node, timeoutSeconds, r)
        )

      case Task.Reboot(delaySeconds, waitForReturn, waitTimeoutSeconds) =>
        NodeClient.reboot(
          node,
          delaySeconds,
          waitForReturn,
          waitTimeoutSeconds,
          line => IO.println(s"[${node.name}] ${namedTask.name}: $line")
        )

      case Task.RunCommand(command, timeoutSeconds) =>
        buildTemplateVars(facts, context, node, setFacts).flatMap { vars =>
          val renderedCommand = command.map { token =>
            if token.contains("{{") then Templating.render(token, vars)
            else token
          }
          requireStreamedSuccess(render)(r =>
            NodeClient.executeCommand(node, renderedCommand, timeoutSeconds, r)
          )
        }

      case Task.SetFact(key, value) =>
        buildTemplateVars(facts, context, node, setFacts).flatMap { vars =>
          val rendered = if value.contains("{{") then
            Templating.render(value, vars)
          else value
          setFacts.set(node.name, key, rendered) >>
            IO.println(
              s"[${node.name}] ${namedTask.name}: set $key = $rendered"
            )
        }

      case Task.Debug(message) =>
        buildTemplateVars(facts, context, node, setFacts).flatMap { vars =>
          val rendered = if message.contains("{{") then
            Templating.render(message, vars)
          else message
          IO.println(s"[${node.name}] ${namedTask.name}: $rendered")
        }

      case Task.DumpFacts() =>
        buildTemplateVars(facts, context, node, setFacts).flatMap { vars =>
          IO.println(
            s"[${node.name}] ${namedTask.name}:\n${FactPrinter.render(vars)}"
          )
        }

      case Task.DistributeFile(
            sourceNodeName,
            sourcePath,
            destPath,
            owner,
            group,
            mode
          ) =>
        Inventory.all.find(_.name == sourceNodeName) match
          case None =>
            IO.raiseError(
              new RuntimeException(
                s"DistributeFile source node '$sourceNodeName' not found in inventory"
              )
            )
          case Some(sourceNode) =>
            NodeClient.fetchFileBytes(sourceNode, sourcePath).flatMap {
              case Left(err) =>
                IO.raiseError(
                  new RuntimeException(
                    s"Failed to fetch $sourcePath from $sourceNodeName: $err"
                  )
                )
              case Right(content) =>
                NodeClient.copyBytes(
                  node,
                  content,
                  destPath,
                  owner,
                  group,
                  mode,
                  render
                )
            }

  /** See PlaybookRunner.buildTemplateVars — identical logic, duplicated here
    * rather than shared.
    */
  private def buildTemplateVars(
      facts: Option[orphera.common.Facts],
      context: ClusterContext,
      node: Node,
      setFacts: SetFacts
  ): IO[Map[String, Any]] =
    setFacts.snapshot.map { allSetFacts =>
      val ownInventoryVars: Map[String, Any] =
        Inventory.groupVarsFor(node.name) ++
          Inventory.all
            .find(_.name == node.name)
            .map(_.vars)
            .getOrElse(Map.empty)

      val ownFactVars: Map[String, Any] = facts match
        case Some(f) =>
          Map(
            "facts.hostname" -> f.hostname,
            "facts.os_id" -> f.osId,
            "facts.os_version" -> f.osVersion,
            "facts.architecture" -> f.architecture
          )
        case None => Map.empty

      val nestedNodeVars = buildNestedNodeFacts(context, allSetFacts)
      val ownSetFactVars: Map[String, Any] =
        allSetFacts.getOrElse(node.name, Map.empty).map { case (k, v) =>
          k -> v
        }

      ownInventoryVars ++ ownFactVars ++ nestedNodeVars ++ ownSetFactVars
    }

  private def buildNestedNodeFacts(
      context: ClusterContext,
      allSetFacts: Map[String, Map[String, String]]
  ): Map[String, Any] =
    val allNodeNames =
      context.factsByNode.keySet ++ allSetFacts.keySet ++ Inventory.all
        .map(_.name)
        .toSet

    val nodesMap: java.util.Map[String, Any] =
      allNodeNames
        .map { nodeName =>
          val groupFields: Map[String, Any] = Inventory.groupVarsFor(nodeName)
          val nodeFields: Map[String, Any] =
            Inventory.all
              .find(_.name == nodeName)
              .map(_.vars)
              .getOrElse(Map.empty)
          val inventoryFields: Map[String, Any] = groupFields ++ nodeFields

          val factFields: Map[String, Any] =
            context.factsByNode.get(nodeName) match
              case Some(f) =>
                Map(
                  "hostname" -> f.hostname,
                  "os_id" -> f.osId,
                  "os_version" -> f.osVersion,
                  "architecture" -> f.architecture
                ) ++ interfaceFields(f)
              case None => Map.empty

          val setFactFields: Map[String, Any] =
            allSetFacts.getOrElse(nodeName, Map.empty).map { case (k, v) =>
              k -> v
            }

          val inner: java.util.Map[String, Any] =
            (inventoryFields ++ factFields ++ setFactFields).asJava
          nodeName -> (inner: Any)
        }
        .toMap
        .asJava

    Map("nodes" -> nodesMap)

  private def interfaceFields(f: orphera.common.Facts): Map[String, Any] =
    val byName = f.interfaces.map { iface =>
      s"ip_${iface.name}" -> iface.ipAddresses.headOption.getOrElse("")
    }.toMap

    val secondary = f.interfaces
      .drop(1)
      .headOption
      .flatMap(_.ipAddresses.headOption)
      .getOrElse("")

    byName + ("ip_secondary" -> secondary)

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
      case HealthCheck.Sentinel(_, _, _, p, t) => (p, t)
      case HealthCheck.Command(_, _, p, t)     => (p, t)
      case HealthCheck.Quorum(_, _, _, p, t)   => (p, t)

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

  private def eventLine(event: orphera.common.Event): String =
    event.kind match
      case orphera.common.Event.Kind.PROGRESS => event.message
      case orphera.common.Event.Kind.OUTPUT   => event.message
      case orphera.common.Event.Kind.RESULT   =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
