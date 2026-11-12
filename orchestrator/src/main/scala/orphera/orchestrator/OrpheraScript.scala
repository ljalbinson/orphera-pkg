// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import scala.concurrent.duration.*
import scala.util.Try

import cats.effect.*
import cats.syntax.all.*

import orphera.common.Facts

/** A Scala playbook written as a script: plain Cats Effect `IO`, where each
  * step on a node returns its result (TaskExecutor.StepResult), so a value
  * found during the run (a command's output, a gathered fact, a value read on
  * one node) is used with ordinary `if`, `match` and interpolation instead of
  * `register`, `{{...}}` and `.when`. See docs/SCRIPT-DSL-DESIGN.md.
  *
  * {{{
  * object lustre_boot extends OrpheraScript:
  *   def script(using orphera: Orphera): IO[Unit] =
  *     val nodes = orphera.nodes("tst40", "tst41")
  *     nodes.each { node =>
  *       node.query("is a reboot needed", "...").flatMap { r =>
  *         if r.stdout.trim == "yes" then node.reboot("reboot").void else IO.unit
  *       }
  *     }.void
  * }}}
  *
  * Run as any Scala playbook: `orphera playbook manifests/lustre_boot.scala`,
  * with `--resume` and `--config` as for the others.
  */
trait OrpheraScript extends IOApp.Simple:

  def script(using orphera: Orphera): IO[Unit]

  /** The run's name, for its run log and checkpoint: the object's name. */
  def name: String = getClass.getSimpleName.stripSuffix("$")

  def run: IO[Unit] =
    val resume = sys.env.get("ORPHERA_RESUME").contains("true")
    IO.println(
      s"Running script '$name'${if resume then " (resuming from checkpoint)" else ""}"
    ) >> Orphera.run(name, resume)(o => script(using o))

/** The context of a script's run: its nodes, secrets and helpers. */
final class Orphera private[orchestrator] (
    val runName: String,
    private[orchestrator] val env: TaskExecutor.Env,
    private[orchestrator] val checkpoint: Checkpoint.Handle,
    private[orchestrator] val runLog: RunLog.Handle,
    // change-step names used so far, per node (each must be unique on a node)
    used: Ref[IO, Map[String, Set[String]]],
    factsCache: Ref[IO, Map[String, Facts]],
    inventory: List[Node]
):

  /** One node of the inventory. Fails (inside the script's IO) if there is
    * none of that name.
    */
  def node(name: String): NodeRef =
    inventory.find(_.name == name) match
      case Some(n) => new NodeRef(n, this)
      case None    =>
        throw new IllegalArgumentException(
          s"no node named '$name' in the inventory"
        )

  /** Nodes of the inventory, in the order given. */
  def nodes(names: String*): Nodes = Nodes(names.toList.map(node))

  /** The members of an inventory group. */
  def group(groupName: String): Nodes =
    Inventory.groups.find(_.name == groupName) match
      case Some(g) => Nodes(g.members.map(node))
      case None    =>
        throw new IllegalArgumentException(
          s"no group named '$groupName' in the inventory"
        )

  /** A value to keep out of the output and logs: registered for masking, and
    * shown as a placeholder if printed. `reveal` gives the text where it is
    * needed.
    */
  def secret(value: String): Secret =
    Secrets.register(value)
    Secret(value)

  /** Polls `check` every `everySeconds` until it is true; fails after
    * `timeoutSeconds`, saying what the last attempt was (false, or its
    * error).
    */
  def waitUntil(
      name: String,
      timeoutSeconds: Int = 300,
      everySeconds: Int = 5
  )(check: IO[Boolean]): IO[Unit] =
    def poll(elapsed: Int, last: String): IO[Unit] =
      if elapsed >= timeoutSeconds then
        IO.raiseError(
          new RuntimeException(
            s"$name: not true after ${timeoutSeconds}s (last attempt: $last)"
          )
        )
      else
        check.attempt.flatMap {
          case Right(true) => IO.println(s"[$runName] $name: true after ~${elapsed}s")
          case Right(false) =>
            IO.sleep(everySeconds.seconds) >> poll(elapsed + everySeconds, "false")
          case Left(err) =>
            IO.sleep(everySeconds.seconds) >>
              poll(elapsed + everySeconds, TaskExecutor.describe(err))
        }
    IO.println(s"[$runName] waiting until $name (timeout ${timeoutSeconds}s)") >>
      poll(0, "none")

  private[orchestrator] def claim(node: String, step: String): IO[Unit] =
    used
      .modify { m =>
        val names = m.getOrElse(node, Set.empty)
        if names.contains(step) then (m, false)
        else (m.updated(node, names + step), true)
      }
      .flatMap(fresh =>
        IO.raiseUnless(fresh)(
          new IllegalStateException(
            s"step '$step' is used twice on $node; a step's name must be unique on a node " +
              "(it is how --resume knows what was done): put the item in the name, e.g. s\"zap $disk\""
          )
        )
      )

  private[orchestrator] def factsOf(node: Node): IO[Facts] =
    factsCache.get.map(_.get(node.name)).flatMap {
      case Some(f) => IO.pure(f)
      case None    =>
        NodeClient.gatherFacts(node).flatTap(f =>
          factsCache.update(_.updated(node.name, f))
        )
    }

object Orphera:

  /** Runs `script` with a context for `name`: the agent token checked, a run
    * log, and a checkpoint (`--resume`). Fails if the script did.
    */
  def run(name: String, resume: Boolean, kind: String = "Script")(
      script: Orphera => IO[Unit]
  ): IO[Unit] =
    for
      _ <- AgentToken.requireConfigured
      checkpoint <- Checkpoint.load("script", name, resume)
      runLog <- RunLog.start("script", name)
      orphera <- make(name, checkpoint, runLog, identity)
      start <- IO.monotonic
      _ <- runLog.event("event" -> "run_start", "playbook" -> name)
      result <- IO.defer(script(orphera)).attempt
      end <- IO.monotonic
      _ <- runLog.event(
        "event" -> "run_end",
        "playbook" -> name,
        "success" -> result.isRight,
        "duration_ms" -> (end - start).toMillis
      )
      _ <- result match
        case Right(()) => IO.println(s"$kind '$name' finished.")
        case Left(err) =>
          IO.println(s"$kind '$name' failed: ${TaskExecutor.describe(err)}") >>
            IO.raiseError(new PlaybookFailed(s"$kind '$name' failed (see output above)"))
    yield ()

  /** A context; `adjust` changes its Env (checks give it a fake runner). */
  private[orchestrator] def make(
      name: String,
      checkpoint: Checkpoint.Handle,
      runLog: RunLog.Handle,
      adjust: TaskExecutor.Env => TaskExecutor.Env,
      inventory: List[Node] = Inventory.all
  ): IO[Orphera] =
    for
      setFacts <- SetFacts.empty
      used <- Ref.of[IO, Map[String, Set[String]]](Map.empty)
      facts <- Ref.of[IO, Map[String, Facts]](Map.empty)
      env = adjust(
        TaskExecutor.Env(
          ClusterContext(Map.empty),
          setFacts,
          TaskExecutor.Output.playbook(),
          templating = false,
          templateFiles = true
        )
      )
    yield new Orphera(name, env, checkpoint, runLog, used, facts, inventory)

/** One node, and the steps that can be run on it. Every step takes its name
  * first (what the output, run log and checkpoint show) and returns a
  * StepResult. Change steps are checkpointed, so `--resume` skips one already
  * done (its result then has `skipped` and no output); `query` and `facts`
  * are not, and always run. A failed step raises (StepFailed), so the rest of
  * the node's `for` does not run.
  */
final class NodeRef private[orchestrator] (val node: Node, orphera: Orphera):

  def name: String = node.name

  override def toString: String = s"NodeRef(${node.name})"

  private def change(name: String, task: Task, retry: Option[Retry]) =
    orphera.claim(node.name, name) >> TaskExecutor.runStep(
      NamedTask(name, task, retry = retry),
      node,
      orphera.env,
      orphera.checkpoint,
      orphera.runLog,
      checkpointed = true
    )

  private def octal(mode: String): IO[Int] =
    if mode.isEmpty then IO.pure(0)
    else
      IO.fromOption(Try(Integer.parseInt(mode, 8)).toOption)(
        new IllegalArgumentException(s"mode '$mode' is not an octal number such as \"0644\"")
      )

  /** Runs a command (a list: the program and its arguments). */
  def run(
      name: String,
      command: List[String],
      timeout: Int = 60,
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    change(name, Task.RunCommand(command, timeout), retry)

  /** Runs a shell script with `sh -c`. */
  def sh(
      name: String,
      script: String,
      timeout: Int = 60,
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    run(name, List("sh", "-c", script), timeout, retry)

  /** Runs a command that only reads (it must not change the node): not
    * checkpointed, so it runs again on `--resume` and its value is current,
    * and a non-zero exit is its `exitCode`, not a failure. `changed` is false.
    */
  def query(name: String, command: List[String]): IO[TaskExecutor.StepResult] =
    query(name, command, 60)

  def query(
      name: String,
      command: List[String],
      timeout: Int
  ): IO[TaskExecutor.StepResult] =
    TaskExecutor
      .runStep(
        NamedTask(name, Task.RunCommand(command, timeout)),
        node,
        orphera.env,
        orphera.checkpoint,
        orphera.runLog,
        checkpointed = false,
        failOnExit = false
      )
      .map(_.copy(changed = false))

  /** A query given as a shell script, run with `sh -c`. */
  def query(name: String, script: String): IO[TaskExecutor.StepResult] =
    query(name, List("sh", "-c", script), 60)

  def query(
      name: String,
      script: String,
      timeout: Int
  ): IO[TaskExecutor.StepResult] =
    query(name, List("sh", "-c", script), timeout)

  /** This node's gathered facts: gathered on first use, then kept for the run. */
  def facts: IO[Facts] = orphera.factsOf(node)

  def install(
      name: String,
      packages: List[String],
      updateCache: Boolean = false,
      version: String = "",
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    change(name, Task.Install(packages, updateCache, version), retry)

  def remove(
      name: String,
      packages: List[String],
      purge: Boolean = false,
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    change(name, Task.Remove(packages, purge), retry)

  /** Copies a local file to the node; a `.mustache` source is rendered first
    * with `vars` and this node's facts (`facts.os_id`, ...).
    */
  def copy(
      name: String,
      src: String,
      dest: String,
      owner: String = "",
      group: String = "",
      mode: String = "",
      vars: Map[String, String] = Map.empty,
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    octal(mode).flatMap { m =>
      val task = Task.Copy(src, dest, owner, group, m, vars)
      if !src.endsWith(".mustache") then change(name, task, retry)
      else
        // the template vars include this node's facts, gathered for it
        facts.flatMap { f =>
          orphera.claim(node.name, name) >> TaskExecutor.runStep(
            NamedTask(name, task, retry = retry),
            node,
            orphera.env.copy(context = ClusterContext(Map(node.name -> f))),
            orphera.checkpoint,
            orphera.runLog,
            checkpointed = true
          )
        }
    }

  /** Writes `content` to `dest`; left alone if already so (`changed` false). */
  def writeFile(
      name: String,
      content: String,
      dest: String,
      owner: String = "",
      group: String = "",
      mode: String = "",
      retry: Option[Retry] = None
  ): IO[TaskExecutor.StepResult] =
    octal(mode).flatMap(m =>
      change(name, Task.WriteFile(content, dest, owner, group, m), retry)
    )

  /** Reboots the node and (with `waitForReturn`) waits until it is back and
    * has really rebooted (see `reboot --wait`).
    */
  def reboot(
      name: String,
      delay: Int = 5,
      waitForReturn: Boolean = true,
      waitTimeout: Int = 300
  ): IO[TaskExecutor.StepResult] =
    change(name, Task.Reboot(delay, waitForReturn, waitTimeout), None)

  def networkApply(name: String, timeout: Int = 60): IO[TaskExecutor.StepResult] =
    change(name, Task.NetworkApply(timeout), None)

  /** Applies a declarative description to this node (docs/APPLY-DESIGN.md):
    * each resource read, compared and changed only where it differs; with
    * `check`, nothing is changed. Not checkpointed: running it again is its
    * resume. Raises (StepFailed) if a resource failed; otherwise the report
    * says what changed (`report.changed("file[/etc/x]")`).
    */
  def apply(plan: ApplyPlan, check: Boolean = false, stopOnFailure: Boolean = false): IO[ApplyReport] =
    val label = s"apply ${plan.name}"
    facts.flatMap { f =>
      val env = orphera.env.copy(context = ClusterContext(Map(node.name -> f)))
      for
        _ <- orphera.runLog.event("event" -> "task_start", "node" -> node.name, "task" -> label, "check" -> check)
        report <- TaskExecutor.applyOn(plan, node, env, check, stopOnFailure, text => env.output.line(node, label, text))
        _ <- orphera.runLog.event(
          "event" -> "task_end",
          "node" -> node.name,
          "task" -> label,
          "success" -> !report.failed,
          "changed" -> report.changed,
          "resources" -> report.results.map(r => s"${r.id} ${outcomeWord(r.outcome)}").mkString("; ")
        )
        _ <- IO.raiseWhen(report.failed)(
          new TaskExecutor.StepFailed(node.name, label, new RuntimeException(report.failures.mkString("; ")))
        )
      yield report
    }

  /** As `apply`, from the resources (checked first). */
  def apply(name: String, resources: List[ApplyResource]): IO[ApplyReport] =
    Apply.plan(name, resources).flatMap(p => apply(p))

  private def outcomeWord(o: ApplyOutcome): String = o match
    case ApplyOutcome.Unchanged(_)       => "unchanged"
    case ApplyOutcome.NotNotified        => "not notified"
    case ApplyOutcome.WouldChange(_, _)  => "would change"
    case ApplyOutcome.Changed(_, _)      => "changed"
    case ApplyOutcome.Skipped(_)         => "skipped"
    case ApplyOutcome.Failed(_)          => "failed"

/** Several nodes. `each` is the barrier between parts of a script, as a stage
  * is in a cluster playbook.
  */
final case class Nodes(toList: List[NodeRef]):

  def names: List[String] = toList.map(_.name)

  /** Runs `f` on every node in parallel; returns the results when all are
    * done. If any failed, fails after all have finished, naming them (a
    * failed step has already said why; any other error is printed here).
    */
  def each[A](f: NodeRef => IO[A]): IO[List[A]] =
    toList.parTraverse(n => f(n).attempt.map(n -> _)).flatMap { results =>
      val failed = results.collect { case (n, Left(err)) => n -> err }
      if failed.isEmpty then IO.pure(results.collect { case (_, Right(a)) => a })
      else
        failed.traverse_ {
          case (_, _: TaskExecutor.StepFailed) => IO.unit
          case (n, err) => IO.println(s"[${n.name}] FAILED — ${TaskExecutor.describe(err)}")
        } >> IO.raiseError(
          new RuntimeException(s"failed on ${failed.map(_._1.name).mkString(", ")}")
        )
    }

  def filter(p: NodeRef => Boolean): Nodes = Nodes(toList.filter(p))

  /** The first node; fails (inside the script's IO) if there are none. */
  def first: NodeRef =
    toList.headOption.getOrElse(
      throw new IllegalStateException("no nodes to take the first of")
    )
