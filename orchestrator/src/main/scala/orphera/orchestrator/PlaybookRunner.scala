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

  private def runOnNode(
      playbook: Playbook,
      node: Node,
      context: ClusterContext,
      setFacts: SetFacts
  ): IO[Unit] =
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
                case Right(()) =>
                  runTasks(rest, node, facts, context, setFacts)
                case Left(err) =>
                  IO.println(
                    s"[${node.name}] ${namedTask.name}: FAILED — ${err.getMessage}. Stopping remaining tasks for this node."
                  )
              }
        }

  /** Wraps a streaming RPC call so that a terminal RESULT event with success =
    * false raises an error, same as an actual exception would — otherwise a
    * remote command that runs but exits non-zero (e.g. apt-get exit 100)
    * streams back normally and is silently treated as a successful task, since
    * nothing threw.
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
      facts: Option[Facts],
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

      case Task.Copy(src, dest, owner, group, mode, vars) =>
        resolveSourcePath(src, vars, facts, context, setFacts, node).flatMap {
          resolvedSrc =>
            requireStreamedSuccess(render)(r =>
              NodeClient.copyFile(
                node,
                resolvedSrc,
                dest,
                owner,
                group,
                mode,
                r
              )
            )
        }

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

  private def resolveSourcePath(
      src: String,
      vars: Map[String, String],
      facts: Option[Facts],
      context: ClusterContext,
      setFacts: SetFacts,
      node: Node
  ): IO[java.nio.file.Path] =
    if !src.endsWith(".mustache") then IO.pure(java.nio.file.Paths.get(src))
    else
      buildTemplateVars(facts, context, node, setFacts).flatMap {
        templateVars =>
          IO.blocking {
            val templateContent =
              java.nio.file.Files.readString(java.nio.file.Paths.get(src))
            val rendered =
              Templating.render(templateContent, templateVars ++ vars)
            val tmp =
              java.nio.file.Files.createTempFile("orphera-render", ".tmp")
            java.nio.file.Files.writeString(tmp, rendered)
            tmp
          }
      }

  /** Builds the full var context available to templates, debug messages,
    * set_fact values, and run_command/install version strings: this node's own
    * gathered facts (facts.*), this node's own inventory/group vars
    * unqualified, and every targeted node's gathered facts, inventory/group
    * vars, AND set-facts, nested under nodes.<name>.*.
    */
  private def buildTemplateVars(
      facts: Option[Facts],
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

  private def interfaceFields(f: Facts): Map[String, Any] =
    val byName = f.interfaces.map { iface =>
      s"ip_${iface.name}" -> iface.ipAddresses.headOption.getOrElse("")
    }.toMap

    val secondary = f.interfaces
      .drop(1)
      .headOption
      .flatMap(_.ipAddresses.headOption)
      .getOrElse("")

    byName + ("ip_secondary" -> secondary)

  private def eventLine(event: orphera.common.Event): String =
    event.kind match
      case orphera.common.Event.Kind.PROGRESS => event.message
      case orphera.common.Event.Kind.OUTPUT   => event.message
      case orphera.common.Event.Kind.RESULT   =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
