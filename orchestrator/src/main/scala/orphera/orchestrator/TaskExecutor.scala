// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import cats.effect.*
import cats.syntax.all.*

import io.grpc.{StatusException, StatusRuntimeException}

import orphera.common.{Event, Facts}

/** The one place a task is carried out on a node. A playbook
  * (PlaybookRunner), each stage of a cluster playbook (ClusterPlaybookRunner)
  * and a direct command (`orphera install`, `run`, `copy`, ...: a playbook of
  * one task, PlaybookRunner.runCommand) all run their tasks here, so a change
  * to how a task runs (a retry, a timeout, how a failure is detected) applies
  * to all three at once. Before, each had its own copy and they had drifted: a
  * cluster playbook's `copy` ignored `.mustache` templates, and a direct
  * command stopped every node when one was unreachable.
  *
  * What differs between the three is in Env: how output is shown, whether
  * `{{...}}` is rendered, and what the run keeps (checkpoint and run log).
  */
object TaskExecutor:

  /** A task whose remote command ran but reported failure (a RESULT event with
    * success = false), as opposed to one that could not be run at all.
    */
  final class RemoteFailure
      extends RuntimeException(
        "Remote command reported failure (see output above)"
      )

  /** How a run shows what happens on a node. Each function gets the node and
    * the task's name.
    */
  final case class Output(
      // an event streamed back by the agent
      event: (Node, String, Event) => IO[Unit],
      // a line of the orchestrator's own about the task (reboot's progress,
      // "skipped (...)", "set key = value")
      line: (Node, String, String) => IO[Unit],
      // the task failed; the node's remaining tasks are not run
      failed: (Node, String, Throwable) => IO[Unit]
  )

  object Output:

    /** Playbooks and cluster playbooks: every line is `[node] task: ...`.
      * `afterFailure` is added to the FAILED line.
      */
    def playbook(afterFailure: String = ""): Output =
      Output(
        (node, task, event) =>
          IO.println(s"[${node.name}] $task: ${eventLine(event)}"),
        (node, task, text) => IO.println(playbookLine(node, task, text)),
        (node, task, err) =>
          IO.println(
            s"[${node.name}] $task: FAILED — ${err.getMessage}$afterFailure"
          )
      )

    /** Direct commands: the agent's events as ConsoleRenderer shows them
      * (`[node] ...`, `[node] exit=0 success=true`), no task name. A failed
      * remote command has already shown its own result; any other failure (an
      * unreachable node, a missing local file) gets one line for that node.
      */
    def console(quiet: Boolean): Output =
      Output(
        (node, _, event) =>
          if quiet then ConsoleRenderer.renderQuiet(node, event)
          else ConsoleRenderer.render(node, event),
        (node, _, text) => IO.println(s"[${node.name}] $text"),
        (node, _, err) =>
          err match
            case _: RemoteFailure => IO.unit
            case other => IO.println(s"[${node.name}] ${failureLine(other)}")
      )

  // A text that starts on a new line (dump_facts) gets no space after the colon.
  private def playbookLine(node: Node, task: String, text: String): String =
    val gap = if text.startsWith("\n") then "" else " "
    s"[${node.name}] $task:$gap$text"

  /** What a run shares between its tasks. `context` is the facts gathered at
    * the start (empty for a direct command); `templating` says whether
    * `{{...}}` in a task's strings and `.mustache` sources are rendered (off
    * for a direct command, whose arguments are sent as typed).
    */
  final case class Env(
      context: ClusterContext,
      setFacts: SetFacts,
      output: Output,
      templating: Boolean
  )

  /** A direct command's Env: no facts, no templating, console output. */
  def commandEnv(quiet: Boolean): IO[Env] =
    SetFacts.empty.map(setFacts =>
      Env(
        ClusterContext(Map.empty),
        setFacts,
        Output.console(quiet),
        templating = false
      )
    )

  /** Facts from each node, for templating and `when:`; a node that does not
    * answer is left out (its tasks then see no facts).
    */
  def gatherFacts(nodes: List[Node]): IO[ClusterContext] =
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

  /** Runs `tasks` on `node` in order: a task already recorded in `checkpoint`
    * (a `--resume`d run) or whose `when:` does not hold is skipped, the first
    * failure stops the node's remaining tasks. Returns whether none failed.
    * `logFields` go into every run-log event after its "event" field (a
    * cluster playbook's stage).
    */
  def runTasks(
      tasks: List[NamedTask],
      node: Node,
      env: Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle,
      logFields: List[(String, Any)] = Nil
  ): IO[Boolean] =
    val facts = env.context.factsByNode.get(node.name)

    def log(kind: String, task: String, more: (String, Any)*): IO[Unit] =
      logSeq(kind, task, more)

    def logSeq(kind: String, task: String, more: Seq[(String, Any)]): IO[Unit] =
      runLog.event(
        ((("event" -> kind) :: logFields) ++
          List("node" -> node.name, "task" -> task) ++ more)*
      )

    def skip(task: String, reason: String, message: String): IO[Unit] =
      env.output.line(node, task, s"skipped ($message)") >>
        log("task_skipped", task, "reason" -> reason)

    def go(remaining: List[NamedTask]): IO[Boolean] =
      remaining match
        case Nil => IO.pure(true)

        case namedTask :: rest =>
          val name = namedTask.name
          checkpoint.isDone(node.name, name).flatMap { alreadyDone =>
            if alreadyDone then
              skip(
                name,
                "resume_checkpoint",
                "already completed in a previous run — resuming"
              ) >> go(rest)
            else
              env.setFacts.get(node.name).flatMap { ownSetFacts =>
                val shouldRun = namedTask.when match
                  case None       => true
                  case Some(cond) => facts.exists(cond.matches(_, ownSetFacts))

                if !shouldRun then
                  skip(name, "condition_not_met", "condition not met") >>
                    go(rest)
                else
                  for
                    startMs <- IO.delay(System.currentTimeMillis())
                    _ <- log("task_start", name)
                    result <- runWithRetry(namedTask, node, env, logSeq).attempt
                    endMs <- IO.delay(System.currentTimeMillis())
                    took = "duration_ms" -> (endMs - startMs)
                    ok <- result match
                      case Right(()) =>
                        log("task_end", name, "success" -> true, took) >>
                          checkpoint.markDone(node.name, name) >>
                          go(rest)
                      case Left(err) =>
                        log(
                          "task_end",
                          name,
                          "success" -> false,
                          took,
                          "error" -> err.getMessage
                        ) >>
                          env.output.failed(node, name, err).as(false)
                  yield ok
              }
          }

    go(tasks)

  /** Runs the task and, when it fails and has a `retry`, runs it again (see
    * retrying). Each failed attempt but the last gets a line (`attempt 1
    * failed (...); retry 1 of 3 in 5s`) and a `task_retry` run-log event; the
    * last one's error is the task's.
    */
  private def runWithRetry(
      namedTask: NamedTask,
      node: Node,
      env: Env,
      log: (String, String, Seq[(String, Any)]) => IO[Unit]
  ): IO[Unit] =
    val name = namedTask.name
    val once = runTask(namedTask, node, env)
    namedTask.retry match
      case None        => once
      case Some(retry) =>
        retrying(retry) { (n, err, pause) =>
          env.output.line(
            node,
            name,
            s"attempt $n failed (${describe(err)}); retry $n of ${retry.retries} in ${pause}s"
          ) >>
            log(
              "task_retry",
              name,
              List("attempt" -> n, "error" -> describe(err), "delay_s" -> pause)
            )
        }(once)

  /** Runs `action`, and when it fails runs it again up to `retry.retries` more
    * times, sleeping Retry.delayBefore(n) seconds before retry n. `onRetry`
    * gets the number of the attempt that failed, its error and the pause,
    * before the pause. The error of the last attempt is raised.
    */
  def retrying(retry: Retry)(
      onRetry: (Int, Throwable, Int) => IO[Unit]
  )(action: IO[Unit]): IO[Unit] =
    def attempt(n: Int): IO[Unit] =
      action.handleErrorWith { err =>
        if n > retry.retries then IO.raiseError(err)
        else
          val pause = retry.delayBefore(n)
          onRetry(n, err, pause) >> IO.sleep(pause.seconds) >> attempt(n + 1)
      }
    attempt(1)

  /** Carries out one task on one node; raises if it failed. */
  def runTask(namedTask: NamedTask, node: Node, env: Env): IO[Unit] =
    val name = namedTask.name
    val facts = env.context.factsByNode.get(node.name)
    val render: Event => IO[Unit] = env.output.event(node, name, _)
    def say(text: String): IO[Unit] = env.output.line(node, name, text)

    // Gives `f` a function that renders a string's `{{...}}`; the template
    // vars are built only when templating is on and `needed` says a string
    // has a template in it.
    def rendered[A](needed: Boolean)(f: (String => String) => A): IO[A] =
      if env.templating && needed then
        templateVars(node, facts, env).map { vars =>
          f(text =>
            if text.contains("{{") then Templating.render(text, vars) else text
          )
        }
      else IO.pure(f(identity))

    namedTask.task match

      case Task.Install(packages, updateCache, version) =>
        rendered(version.contains("{{"))(r => r(version)).flatMap {
          renderedVersion =>
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

      case Task.DistUpgrade(updateCache) =>
        requireStreamedSuccess(render)(r =>
          NodeClient.distUpgrade(node, updateCache, r)
        )

      case Task.Copy(src, dest, owner, group, mode, vars) =>
        sourcePath(src, vars, node, facts, env).flatMap { resolvedSrc =>
          requireStreamedSuccess(render)(r =>
            NodeClient.copyFile(node, resolvedSrc, dest, owner, group, mode, r)
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
          say
        )

      case Task.RunCommand(command, timeoutSeconds, register) =>
        rendered(command.exists(_.contains("{{")))(r => command.map(r))
          .flatMap { renderedCommand =>
            for
              lines <- Ref.of[IO, Vector[String]](Vector.empty)
              capture = (event: Event) =>
                if event.kind == Event.Kind.OUTPUT then
                  lines.update(_ :+ event.message)
                else IO.unit
              _ <- requireStreamedSuccess(event =>
                render(event) >> capture(event)
              )(r =>
                NodeClient.executeCommand(
                  node,
                  renderedCommand,
                  timeoutSeconds,
                  r
                )
              )
              captured <- lines.get
              _ <-
                if register.nonEmpty then
                  env.setFacts.set(
                    node.name,
                    register,
                    captured.mkString("\n").stripTrailing()
                  ) >> say(s"registered $register")
                else IO.unit
            yield ()
          }

      case Task.WriteFile(content, dest, owner, group, mode) =>
        rendered(content.contains("{{"))(r => r(content)).flatMap { text =>
          requireStreamedSuccess(render)(r =>
            NodeClient.copyBytes(
              node,
              text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              dest,
              owner,
              group,
              mode,
              r
            )
          )
        }

      case Task.SetFact(key, value) =>
        rendered(value.contains("{{"))(r => r(value)).flatMap { text =>
          env.setFacts.set(node.name, key, text) >> say(s"set $key = $text")
        }

      case Task.Debug(message) =>
        rendered(message.contains("{{"))(r => r(message)).flatMap(say)

      case Task.DumpFacts() =>
        templateVars(node, facts, env).flatMap { vars =>
          say(s"\n${FactPrinter.render(vars)}")
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
                requireStreamedSuccess(render)(r =>
                  NodeClient.copyBytes(
                    node,
                    content,
                    destPath,
                    owner,
                    group,
                    mode,
                    r
                  )
                )
            }

  /** One line for a task that could not be run: `unreachable: ...` (or
    * `unauthenticated`, `unsupported`) when the agent call failed, else
    * `FAILED: <message>`.
    */
  def failureLine(err: Throwable): String =
    if isGrpcFailure(err) then NodeError.line(err)
    else s"FAILED: ${message(err)}"

  /** A failure in a few words, for a retry line: as failureLine, without the
    * `FAILED: `.
    */
  def describe(err: Throwable): String =
    err match
      case _: RemoteFailure        => "the command reported failure"
      case _ if isGrpcFailure(err) => NodeError.line(err)
      case _                       => message(err)

  private def message(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.toString)

  private def isGrpcFailure(err: Throwable): Boolean =
    Iterator
      .iterate(Option(err))(_.flatMap(e => Option(e.getCause)))
      .take(10)
      .takeWhile(_.isDefined)
      .flatten
      .exists {
        case _: StatusRuntimeException => true
        case _: StatusException        => true
        case _                         => false
      }

  /** Wraps a streaming RPC call so that a terminal RESULT event with success =
    * false raises RemoteFailure, as an exception would — otherwise a remote
    * command that runs but exits non-zero (e.g. apt-get exit 100) streams back
    * normally and is treated as a successful task, since nothing threw.
    */
  private def requireStreamedSuccess(
      render: Event => IO[Unit]
  )(run: (Event => IO[Unit]) => IO[Unit]): IO[Unit] =
    for
      failed <- Ref.of[IO, Boolean](false)
      wrappedRender = (event: Event) =>
        render(event) >> (
          if event.kind == Event.Kind.RESULT && !event.success
          then failed.set(true)
          else IO.unit
        )
      _ <- run(wrappedRender)
      didFail <- failed.get
      _ <- IO.raiseWhen(didFail)(new RemoteFailure)
    yield ()

  /** The local file a `copy` sends: `src` as given (or under ORPHERA_ROOT, see
    * Installation.source). A `.mustache` source is rendered first, with the
    * task's own `vars` over the template vars, into a temporary file, when
    * templating is on.
    */
  private def sourcePath(
      src: String,
      vars: Map[String, String],
      node: Node,
      facts: Option[Facts],
      env: Env
  ): IO[java.nio.file.Path] =
    if !env.templating || !src.endsWith(".mustache") then
      IO.pure(Installation.source(src))
    else
      templateVars(node, facts, env).flatMap { templateVars =>
        IO.blocking {
          val templateContent =
            java.nio.file.Files.readString(Installation.source(src))
          val rendered =
            Templating.render(templateContent, templateVars ++ vars)
          val tmp =
            java.nio.file.Files.createTempFile("orphera-render", ".tmp")
          tmp.toFile.deleteOnExit()
          java.nio.file.Files.writeString(tmp, rendered)
        }
      }

  /** The vars available to templates, debug messages, set_fact values, and
    * run_command/install version strings: this node's own gathered facts
    * (facts.*), this node's own inventory/group vars unqualified, and every
    * node's gathered facts, inventory/group vars and set-facts, nested under
    * nodes.<name>.*.
    */
  private def templateVars(
      node: Node,
      facts: Option[Facts],
      env: Env
  ): IO[Map[String, Any]] =
    env.setFacts.snapshot.map { allSetFacts =>
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

      val nestedNodeVars = nestedNodeFacts(env.context, allSetFacts)
      val ownSetFactVars: Map[String, Any] =
        allSetFacts.getOrElse(node.name, Map.empty)

      ownInventoryVars ++ ownFactVars ++ nestedNodeVars ++ ownSetFactVars
    }

  private def nestedNodeFacts(
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
            allSetFacts.getOrElse(nodeName, Map.empty)

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

  private def eventLine(event: Event): String =
    event.kind match
      case Event.Kind.PROGRESS => event.message
      case Event.Kind.OUTPUT   => event.message
      case Event.Kind.RESULT   =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
