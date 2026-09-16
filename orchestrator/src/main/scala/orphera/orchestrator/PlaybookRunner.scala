// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
import orphera.common.Facts
import scala.jdk.CollectionConverters.*

object PlaybookRunner:

  def run(playbook: Playbook): IO[Unit] =
    val targets = Inventory.all.filter(n => playbook.nodeNames.contains(n.name))

    if targets.isEmpty then
      IO.println(s"[${playbook.name}] No matching nodes found in inventory.")
    else
      for
        context <- gatherClusterFacts(targets)
        setFacts <- SetFacts.empty
        _ <- targets.parTraverse_(runOnNode(playbook, _, context, setFacts))
      yield ()

  private def gatherClusterFacts(nodes: List[Node]): IO[ClusterContext] =
    nodes
      .parTraverse { node =>
        NodeClient.gatherFacts(node).attempt.map(result => node.name -> result.toOption)
      }
      .map(pairs => ClusterContext(pairs.collect { case (name, Some(f)) => name -> f }.toMap))

  private def runOnNode(playbook: Playbook, node: Node, context: ClusterContext, setFacts: SetFacts): IO[Unit] =
    val factsOpt = context.factsByNode.get(node.name)
    runTasks(playbook.tasks, node, factsOpt, context, setFacts)

  private def runTasks(
      tasks: List[NamedTask],
      node: Node,
      facts: Option[Facts],
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
    tasks match
      case Nil => IO.unit

      case namedTask :: rest =>
        setFacts.get(node.name).flatMap { ownSetFacts =>
          val shouldRun = namedTask.when match
            case None => true
            case Some(cond) =>
              facts match
                case Some(f) => cond.matches(f, ownSetFacts)
                case None    => false

          if !shouldRun then
            IO.println(s"[${node.name}] ${namedTask.name}: skipped (condition not met)") >>
              runTasks(rest, node, facts, context, setFacts)
          else
            runSingleTask(namedTask, node, facts, context, setFacts).attempt.flatMap {
              case Right(()) =>
                runTasks(rest, node, facts, context, setFacts)
              case Left(err) =>
                IO.println(
                  s"[${node.name}] ${namedTask.name}: FAILED — ${err.getMessage}. Stopping remaining tasks for this node."
                )
            }
        }

  private def runSingleTask(
      namedTask: NamedTask,
      node: Node,
      facts: Option[Facts],
      context: ClusterContext,
      setFacts: SetFacts
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

      case Task.Copy(src, dest, owner, group, mode, vars) =>
        setFacts.get(node.name).flatMap { ownSetFacts =>
          resolveSourcePath(src, vars, facts, context, ownSetFacts).flatMap { resolvedSrc =>
            NodeClient.copyFile(node, resolvedSrc, dest, owner, group, mode, render)
          }
        }

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

      case Task.SetFact(key, value) =>
        setFacts.set(node.name, key, value) >>
          IO.println(s"[${node.name}] ${namedTask.name}: set $key = $value")

      case Task.Debug(message) =>
        setFacts.get(node.name).flatMap { ownSetFacts =>
          val vars = buildDebugVars(facts, context, ownSetFacts)
          val rendered = if message.contains("{{") then Templating.render(message, vars) else message
          IO.println(s"[${node.name}] ${namedTask.name}: $rendered")
        }

  private def resolveSourcePath(
      src: String,
      vars: Map[String, String],
      facts: Option[Facts],
      context: ClusterContext,
      ownSetFacts: Map[String, String]
  ): IO[java.nio.file.Path] =
    if !src.endsWith(".mustache") then IO.pure(java.nio.file.Paths.get(src))
    else
      IO.blocking {
        val templateContent = java.nio.file.Files.readString(java.nio.file.Paths.get(src))

        val ownFactVars: Map[String, Any] = facts match
          case Some(f) =>
            Map(
              "facts.hostname" -> f.hostname,
              "facts.os_id" -> f.osId,
              "facts.os_version" -> f.osVersion,
              "facts.architecture" -> f.architecture
            )
          case None => Map.empty

        val nestedNodeVars: Map[String, Any] = buildNestedNodeFacts(context)
        val setFactVars: Map[String, Any] = ownSetFacts.map { case (k, v) => k -> v }

        val rendered = Templating.render(templateContent, ownFactVars ++ nestedNodeVars ++ setFactVars ++ vars)

        val tmp = java.nio.file.Files.createTempFile("orphera-render", ".tmp")
        java.nio.file.Files.writeString(tmp, rendered)
        tmp
      }

  private def buildNestedNodeFacts(context: ClusterContext): Map[String, Any] =
    val nodesMap: java.util.Map[String, Any] =
      context.factsByNode.map { case (nodeName, f) =>
        val inner: java.util.Map[String, Any] =
          Map[String, Any](
            "hostname" -> f.hostname,
            "os_id" -> f.osId,
            "os_version" -> f.osVersion,
            "architecture" -> f.architecture
          ).asJava
        nodeName -> (inner: Any)
      }.asJava

    Map("nodes" -> nodesMap)

  private def buildDebugVars(
      facts: Option[Facts],
      context: ClusterContext,
      ownSetFacts: Map[String, String]
  ): Map[String, Any] =
    val ownFactVars: Map[String, Any] = facts match
      case Some(f) =>
        Map(
          "facts.hostname" -> f.hostname,
          "facts.os_id" -> f.osId,
          "facts.os_version" -> f.osVersion,
          "facts.architecture" -> f.architecture
        )
      case None => Map.empty

    val nestedNodeVars = buildNestedNodeFacts(context)
    val setFactVars: Map[String, Any] = ownSetFacts.map { case (k, v) => k -> v }

    ownFactVars ++ nestedNodeVars ++ setFactVars

  private def eventLine(event: orphera.common.Event): String =
    event.kind match
      case orphera.common.Event.Kind.PROGRESS => event.message
      case orphera.common.Event.Kind.OUTPUT   => event.message
      case orphera.common.Event.Kind.RESULT   =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
