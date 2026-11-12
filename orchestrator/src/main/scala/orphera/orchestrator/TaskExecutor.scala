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
            s"[${node.name}] $task: FAILED — ${describe(err)}$afterFailure"
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
      templating: Boolean,
      // Carries out one task and says whether it changed something;
      // runTask, except in checks that need a task to change something
      // without an agent.
      perform: (NamedTask, Node, Env) => IO[Boolean] = runTask,
      // Whether a copy renders a `.mustache` source (with the task's vars and
      // the template vars). Separate from `templating`, which is for `{{...}}`
      // in a task's own strings: a script (OrpheraScript) renders files but
      // not strings, a direct command neither.
      templateFiles: Boolean = true
  )

  /** A direct command's Env: no facts, no templating, console output. */
  def commandEnv(quiet: Boolean): IO[Env] =
    SetFacts.empty.map(setFacts =>
      Env(
        ClusterContext(Map.empty),
        setFacts,
        Output.console(quiet),
        templating = false,
        templateFiles = false
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
      logFields: List[(String, Any)] = Nil,
      handlers: List[NamedTask] = Nil
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

    def go(remaining: List[NamedTask], notified: Ref[IO, Vector[String]]): IO[Boolean] =
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
              ) >> go(rest, notified)
            else
              conditionHolds(namedTask.when, node, facts, env).flatMap {
                case Left(why) =>
                  skip(name, "condition_not_met", why) >> go(rest, notified)
                case Right(()) =>
                  for
                    startMs <- IO.delay(System.currentTimeMillis())
                    _ <- log("task_start", name)
                    result <- runWithRetry(namedTask, node, env, logSeq).attempt
                    endMs <- IO.delay(System.currentTimeMillis())
                    took = "duration_ms" -> (endMs - startMs)
                    ok <- result match
                      case Right(changed) =>
                        log(
                          "task_end",
                          name,
                          "success" -> true,
                          "changed" -> changed,
                          took
                        ) >>
                          checkpoint.markDone(node.name, name) >>
                          noteHandlers(namedTask, changed, notified) >>
                          go(rest, notified)
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

    // A changed task's handlers are noted (once each) and the line says so.
    def noteHandlers(
        namedTask: NamedTask,
        changed: Boolean,
        notified: Ref[IO, Vector[String]]
    ): IO[Unit] =
      if !changed || namedTask.notifies.isEmpty then IO.unit
      else
        notified.update(already =>
          already ++ namedTask.notifies.filterNot(already.contains)
        ) >> env.output.line(
          node,
          namedTask.name,
          s"changed; notifies ${namedTask.notifies.mkString(", ")}"
        )

    // The handlers notified, in the order they are listed, each once. A
    // handler is named "<name> (handler)" in the output, run log and
    // checkpoint, so it cannot be taken for a task of the same name.
    def runHandlers(names: Vector[String]): IO[Boolean] =
      val due = handlers
        .filter(h => names.contains(h.name))
        .map(h => h.copy(name = s"${h.name} (handler)", notifies = Nil))
      Ref.of[IO, Vector[String]](Vector.empty).flatMap(go(due, _))

    for
      notified <- Ref.of[IO, Vector[String]](Vector.empty)
      tasksOk <- go(tasks, notified)
      names <- notified.get
      ok <-
        if names.isEmpty then IO.pure(tasksOk)
        else if tasksOk then runHandlers(names)
        else
          env.output
            .line(
              node,
              "handlers",
              s"not run, because a task failed: ${names.mkString(", ")}"
            )
            .as(false)
    yield ok

  /** What a step of a script (OrpheraScript) returns: whether it changed
    * something, the exit code of its command (0 when it reported none), its
    * output lines, and whether it was skipped because a resumed run had
    * already done it (then it has no output).
    */
  final case class StepResult(
      changed: Boolean,
      exitCode: Int,
      output: Vector[String],
      skipped: Boolean = false
  ):
    /** The output lines joined, as a command would have printed them. */
    def stdout: String = output.mkString("\n")

  /** A script step that failed on a node; it has already been reported. */
  final class StepFailed(val node: String, val step: String, cause: Throwable)
      extends RuntimeException(s"'$step' failed on $node: ${describe(cause)}", cause)

  /** One task on one node, for a script: as a playbook runs a task (output,
    * retries, run log, the checkpoint when `checkpointed`), but returning a
    * StepResult made from the events the task reported, and raising StepFailed
    * when it fails. With `checkpointed`, a task the resumed run had already
    * done is skipped. Without `failOnExit` (a query), a command that ran but
    * exited non-zero is a result with that exit code, not a failure.
    */
  def runStep(
      namedTask: NamedTask,
      node: Node,
      env: Env,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle,
      checkpointed: Boolean,
      failOnExit: Boolean = true
  ): IO[StepResult] =
    val name = namedTask.name
    def log(kind: String, more: Seq[(String, Any)]): IO[Unit] =
      runLog.event(
        (List[(String, Any)]("event" -> kind, "node" -> node.name, "task" -> name) ++ more)*
      )
    val alreadyDone =
      if checkpointed then checkpoint.isDone(node.name, name) else IO.pure(false)
    alreadyDone.flatMap {
      case true =>
        env.output.line(
          node,
          name,
          "skipped (already completed in a previous run — resuming)"
        ) >> log("task_skipped", List("reason" -> "resume_checkpoint"))
          .as(StepResult(changed = false, 0, Vector.empty, skipped = true))
      case false =>
        for
          seen <- Ref.of[IO, Vector[Event]](Vector.empty)
          // The events of the last attempt (a retry starts the record again).
          recording = env.copy(
            output = env.output.copy(event = (n, t, e) =>
              seen.update(_ :+ e) >> env.output.event(n, t, e)
            ),
            perform = (nt, n, e) => seen.set(Vector.empty) >> env.perform(nt, n, e)
          )
          start <- IO.monotonic
          _ <- log("task_start", Nil)
          result <- runWithRetry(
            namedTask,
            node,
            recording,
            (kind, _, more) => log(kind, more)
          ).attempt
          end <- IO.monotonic
          took = "duration_ms" -> (end - start).toMillis
          events <- seen.get
          exitCode = events
            .filter(_.kind == Event.Kind.RESULT)
            .lastOption
            .fold(0)(_.exitCode)
          output = events.filter(_.kind == Event.Kind.OUTPUT).map(_.message)
          step <- result match
            case Left(_: RemoteFailure) if !failOnExit =>
              log(
                "task_end",
                List("success" -> true, "exit_code" -> exitCode, took)
              ).as(StepResult(changed = false, exitCode, output))
            case Right(changed) =>
              log("task_end", List("success" -> true, "changed" -> changed, took)) >>
                (if checkpointed then checkpoint.markDone(node.name, name)
                 else IO.unit).as(StepResult(changed, exitCode, output))
            case Left(err) =>
              log(
                "task_end",
                List("success" -> false, took, "error" -> err.getMessage)
              ) >> env.output.failed(node, name, err) >>
                IO.raiseError(new StepFailed(node.name, name, err))
        yield step
    }

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
  ): IO[Boolean] =
    val name = namedTask.name
    val once = env.perform(namedTask, node, env)
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
  def retrying[A](retry: Retry)(
      onRetry: (Int, Throwable, Int) => IO[Unit]
  )(action: IO[A]): IO[A] =
    def attempt(n: Int): IO[A] =
      action.handleErrorWith { err =>
        if n > retry.retries then IO.raiseError(err)
        else
          val pause = retry.delayBefore(n)
          onRetry(n, err, pause) >> IO.sleep(pause.seconds) >> attempt(n + 1)
      }
    attempt(1)

  /** Whether a task's `when:` lets it run on `node`: Right without one, else
    * the condition checked against the variables templates use
    * (lookupVar). Left says why not, for the skip line.
    */
  private def conditionHolds(
      when: Option[FactCondition],
      node: Node,
      facts: Option[Facts],
      env: Env
  ): IO[Either[String, Unit]] =
    when match
      case None       => IO.pure(Right(()))
      case Some(cond) =>
        templateVars(node, facts, env).map(vars =>
          cond.check(lookupVar(vars, _))
        )

  // The names `when:` has always had, for the facts templates call facts.*.
  private val ConditionAliases: Map[String, String] = Map(
    "os_id" -> "facts.os_id",
    "os_version" -> "facts.os_version",
    "arch" -> "facts.architecture",
    "facts.arch" -> "facts.architecture",
    "hostname" -> "facts.hostname"
  )

  /** A variable's value as a string, as `{{key}}` would give it, from the
    * variables of templateVars: a name of this node's (set fact, inventory or
    * group var, `facts.*`), or `nodes.<node>.<field>`; also the old `when:`
    * names (ConditionAliases). None when there is no such value, or it is not
    * a single value (a list or a map).
    */
  def lookupVar(vars: Map[String, Any], key: String): Option[String] =
    def scalar(v: Any): Option[String] =
      v match
        case null                    => None
        case _: java.util.Map[?, ?]  => None
        case _: java.util.List[?]    => None
        case _: Iterable[?]          => None
        case other                   => Some(other.toString)
    def direct(k: String): Option[String] = vars.get(k).flatMap(scalar)
    key.split("\\.", 3).toList match
      case "nodes" :: nodeName :: field :: Nil =>
        vars.get("nodes").flatMap {
          case nodes: java.util.Map[?, ?] =>
            Option(nodes.get(nodeName)).flatMap {
              case fields: java.util.Map[?, ?] =>
                Option(fields.get(field)).flatMap(scalar)
              case _ => None
            }
          case _ => None
        }
      case _ => direct(key).orElse(ConditionAliases.get(key).flatMap(direct))

  /** Carries out one task on one node; raises if it failed. Returns whether
    * it changed something (see changed).
    */
  def runTask(namedTask: NamedTask, node: Node, env: Env): IO[Boolean] =
    for
      seen <- Ref.of[IO, Vector[Event]](Vector.empty)
      _ <- carryOut(namedTask, node, env, e => seen.update(_ :+ e))
      events <- seen.get
    yield changed(namedTask.task, events)

  /** Whether a task that succeeded changed something, from what it reported.
    * A file task (copy, write_file, distribute_file) did unless the file was
    * already as asked ("Skipped: unchanged"); a package task did unless the
    * package manager says nothing was installed, upgraded or removed;
    * set_fact, debug and dump_facts never do; run_command, reboot and
    * network_apply always do, since nothing says otherwise.
    */
  def changed(task: Task, events: Vector[Event]): Boolean =
    def output: Vector[String] =
      events.filter(_.kind == Event.Kind.OUTPUT).map(_.message)
    task match
      case _: Task.Copy | _: Task.WriteFile | _: Task.DistributeFile =>
        !events.exists(e =>
          e.kind == Event.Kind.RESULT && e.message.startsWith("Skipped:")
        )
      case _: Task.Install | _: Task.Remove | _: Task.AutoRemove |
          _: Task.DistUpgrade =>
        packagesChanged(output)
      case _: Task.SetFact | _: Task.Debug | _: Task.DumpFacts => false
      case _                                                   => true

  private val AptSummary =
    raw"(\d+) upgraded, (\d+) newly installed, (\d+) to remove".r.unanchored

  /** From a package manager's output: apt's summary line (`0 upgraded, 0
    * newly installed, 0 to remove ...`) with any count above 0, or, from dnf
    * (no such line), anything but `Nothing to do.`. With neither, changed.
    */
  def packagesChanged(lines: Seq[String]): Boolean =
    val apt = lines.collect { case AptSummary(u, n, r) =>
      List(u, n, r).exists(c => c.toLongOption.exists(_ > 0))
    }
    if apt.nonEmpty then apt.exists(identity)
    else !lines.exists(_.trim == "Nothing to do.")

  private def carryOut(
      namedTask: NamedTask,
      node: Node,
      env: Env,
      record: Event => IO[Unit]
  ): IO[Unit] =
    val name = namedTask.name
    val facts = env.context.factsByNode.get(node.name)
    val render: Event => IO[Unit] = event =>
      record(event) >> env.output.event(node, name, event)
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
      case _: RemoteFailure                 => "the command reported failure (see output above)"
      case _: AgentToken.NotConfiguredError => AgentToken.NotConfigured
      case _ if isGrpcFailure(err)          => NodeError.line(err)
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
    if !env.templateFiles || !src.endsWith(".mustache") then
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

  private[orchestrator] def eventLine(event: Event): String =
    event.kind match
      case Event.Kind.PROGRESS => event.message
      case Event.Kind.OUTPUT   => event.message
      // A file left as it was says so: "Skipped: unchanged (exit=0 ...)".
      case Event.Kind.RESULT if event.message.startsWith("Skipped:") =>
        s"${event.message} (exit=${event.exitCode} success=${event.success})"
      case Event.Kind.RESULT =>
        s"exit=${event.exitCode} success=${event.success}"
      case _ => event.message
