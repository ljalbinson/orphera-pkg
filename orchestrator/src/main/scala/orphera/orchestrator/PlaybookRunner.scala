// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
import orphera.common.Facts

object PlaybookRunner:

  def run(playbook: Playbook): IO[Unit] =
    val targets = Inventory.all.filter(n => playbook.nodeNames.contains(n.name))

    if targets.isEmpty then
      IO.println(s"[${playbook.name}] No matching nodes found in inventory.")
    else
      targets.parTraverse_(runOnNode(playbook, _))

  private def runOnNode(playbook: Playbook, node: Node): IO[Unit] =
    for
      factsResult <- NodeClient.gatherFacts(node).attempt
      factsOpt = factsResult.toOption
      _ <- runTasks(playbook.tasks, node, factsOpt)
    yield ()

  private def runTasks(tasks: List[NamedTask], node: Node, facts: Option[Facts]): IO[Unit] =
    tasks match
      case Nil => IO.unit

      case namedTask :: rest =>
        val shouldRun = namedTask.when match
          case None => true
          case Some(cond) =>
            facts match
              case Some(f) => cond.matches(f)
              case None    => false

        if !shouldRun then
          IO.println(s"[${node.name}] ${namedTask.name}: skipped (condition not met)") >>
            runTasks(rest, node, facts)
        else
          runSingleTask(namedTask, node, facts).attempt.flatMap {
            case Right(()) =>
              runTasks(rest, node, facts)
            case Left(err) =>
              IO.println(s"[${node.name}] ${namedTask.name}: FAILED — ${err.getMessage}. Stopping remaining tasks for this node.")
          }

  private def runSingleTask(namedTask: NamedTask, node: Node, facts: Option[Facts]): IO[Unit] =
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
        resolveSourcePath(src, vars, facts).flatMap { resolvedSrc =>
          NodeClient.copyFile(node, resolvedSrc, dest, owner, group, mode, render)
        }

      case Task.NetworkApply(timeoutSeconds) =>
        NodeClient.applyNetworkConfig(node, timeoutSeconds, render)

  private def resolveSourcePath(
      src: String,
      vars: Map[String, String],
      facts: Option[Facts]
  ): IO[java.nio.file.Path] =
    if !src.endsWith(".mustache") then
      IO.pure(java.nio.file.Paths.get(src))
    else
      IO.blocking {
        val templateContent = java.nio.file.Files.readString(java.nio.file.Paths.get(src))

        val factVars = facts match
          case Some(f) =>
            Map(
              "facts.hostname" -> f.hostname,
              "facts.os_id" -> f.osId,
              "facts.os_version" -> f.osVersion,
              "facts.architecture" -> f.architecture
            )
          case None => Map.empty

        val rendered = Templating.render(templateContent, factVars ++ vars)

        val tmp = java.nio.file.Files.createTempFile("orphera-render", ".tmp")
        java.nio.file.Files.writeString(tmp, rendered)
        tmp
      }

  private def eventLine(event: orphera.common.Event): String =
    event.kind match
      case orphera.common.Event.Kind.PROGRESS => event.message
      case orphera.common.Event.Kind.OUTPUT    => event.message
      case orphera.common.Event.Kind.RESULT    => s"exit=${event.exitCode} success=${event.success}"
      case _                                    => event.message
