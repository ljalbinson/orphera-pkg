// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

enum Task:
  case Install(
      packages: List[String],
      updateCache: Boolean = false,
      version: String = ""
  )
  case Remove(packages: List[String], purge: Boolean = false)
  case AutoRemove(purge: Boolean = false)

  /** `apt-get dist-upgrade` on the agent. `updateCache` (default true — a
    * dist-upgrade against a stale index is rarely what anyone wants) runs
    * `apt-get update` first, in the same task. Existing config files are kept
    * rather than prompting (`--force-confdef`/`--force-confold`). Pair with
    * `AutoRemove` afterward to clear packages the upgrade orphaned.
    */
  case DistUpgrade(updateCache: Boolean = true)
  case Copy(
      src: String,
      dest: String,
      owner: String,
      group: String,
      mode: Int,
      vars: Map[String, String] = Map.empty
  )
  case NetworkApply(timeoutSeconds: Int = 60)
  case Reboot(
      delaySeconds: Int = 5,
      waitForReturn: Boolean = false,
      waitTimeoutSeconds: Int = 300
  )
  case SetFact(key: String, value: String)

  /** Writes `content` to `dest` on the agent, verbatim — no local source file
    * on the orchestrator's own disk required, unlike Copy (whose `src` must
    * name a file that already exists there). `content` is rendered through
    * Mustache first if it contains `{{...}}`, the same treatment
    * SetFact/Debug/run_command's own tokens already get, so
    * `{{nodes.tst0.cluster_ip}}`-style cross-node references work here too.
    * Reuses the same underlying CopyFile RPC/FileChunk streaming Copy does (via
    * NodeClient.copyBytes) — no new agent-side capability was needed to add
    * this.
    */
  case WriteFile(
      content: String,
      dest: String,
      owner: String = "",
      group: String = "",
      mode: Int = 0
  )

  /** Runs `command` on the agent. With a non-empty `register`, the command's
    * output (stdout and stderr lines, in the order they arrive, joined with
    * newlines, trailing whitespace removed) is stored as the fact `register` on
    * that node once the command has succeeded, like a SetFact whose value comes
    * from the command. Later tasks and later stages read it as `{{register}}`
    * on the same node or `{{{nodes.<node>.<register>}}}` for any node (the
    * triple braces matter: plain `{{ }}` HTML-escapes `=`, quotes and `<`).
    * Because stderr is captured too, a command that must produce an exact value
    * should send everything else to /dev/null. Nodes of one stage run in
    * parallel, so another node's value is only reliable from a later stage. A
    * failed command stores nothing.
    */
  case RunCommand(
      command: List[String],
      timeoutSeconds: Int = 60,
      register: String = ""
  )
  case DumpFacts()
  case DistributeFile(
      sourceNode: String,
      sourcePath: String,
      destPath: String,
      owner: String = "",
      group: String = "",
      mode: Int = 0
  )
  case Debug(message: String)

  /** Applies a declarative description to the node (Apply,
    * docs/APPLY-DESIGN.md): each resource observed, compared and changed only
    * where it differs; with `check`, only observed and compared. Changed if
    * any resource changed; fails if any failed.
    */
  case Apply(plan: ApplyPlan, check: Boolean = false, stopOnFailure: Boolean = false)

/** A `when:` condition: `key == "expected"`, or `!=` with `negate`. The key is
  * looked up in the same variables as a template's `{{...}}`
  * (TaskExecutor.conditionHolds): this node's set facts and inventory vars by
  * name, `facts.os_id` and the rest, and `nodes.<node>.<field>` for any node;
  * `os_id`, `os_version`, `arch` and `hostname` also still work. A key with
  * no value makes the condition false, `==` or `!=`.
  */
final case class FactCondition(
    key: String,
    expected: String,
    negate: Boolean = false
):
  /** Whether it holds, given how to look a key up; Left with why not. */
  def check(lookup: String => Option[String]): Either[String, Unit] =
    lookup(key) match
      case None => Left(s"condition not met: $key has no value")
      case Some(actual) =>
        Either.cond((actual == expected) != negate, (), "condition not met")

/** How a failed task is tried again: up to `retries` more times, waiting
  * `delaySeconds` before the first retry and twice as long before each one
  * after it, but never more than `maxDelaySeconds`. Every failure is retried —
  * a remote command that reported failure, a node that could not be reached —
  * so it is for tasks that are safe to run again.
  */
final case class Retry(
    retries: Int,
    delaySeconds: Int = Retry.DefaultDelaySeconds,
    maxDelaySeconds: Int = Retry.MaxDelaySeconds
):
  /** Seconds to wait before retry `n` (1 for the first). */
  def delayBefore(n: Int): Int =
    val doubled = delaySeconds.toLong << math.min(math.max(n - 1, 0), 30)
    math.min(doubled, maxDelaySeconds.toLong).toInt

object Retry:
  val DefaultDelaySeconds: Int = 5
  val MaxDelaySeconds: Int = 300

  /** A Retry for `retries` more attempts, or None for 0 (no retry). */
  def of(retries: Int, delaySeconds: Int = DefaultDelaySeconds): Option[Retry] =
    Option.when(retries > 0)(
      Retry(retries, math.max(delaySeconds, 0))
    )

/** A task in a playbook. `notifies` (YAML `notify:`) names handlers (of the playbook, or of the
  * cluster-playbook stage) to run on this node after its tasks, if this task
  * changed something (see TaskExecutor.changed).
  */
final case class NamedTask(
    name: String,
    task: Task,
    when: Option[FactCondition] = None,
    retry: Option[Retry] = None,
    notifies: List[String] = Nil
)

/** `handlers` run on a node after its tasks, each once, in this order, if a
  * task that changed something notified it (NamedTask.notifies), and only if
  * none of the node's tasks failed.
  */
final case class Playbook(
    name: String,
    nodeNames: List[String],
    tasks: List[NamedTask],
    handlers: List[NamedTask] = Nil
)

object Handlers:

  /** What is wrong with these tasks' `notifies` and these handlers, if anything:
    * a name no handler has, two handlers with one name, or a handler that
    * notifies (handlers do not chain).
    */
  def problems(tasks: List[NamedTask], handlers: List[NamedTask]): List[String] =
    val names = handlers.map(_.name)
    val unknown = tasks.flatMap(_.notifies).distinct.filterNot(names.contains)
    val duplicated = names.diff(names.distinct).distinct
    val chained = handlers.filter(_.notifies.nonEmpty).map(_.name)
    unknown.map(n => s"a task notifies '$n', but there is no handler of that name") ++
      duplicated.map(n => s"two handlers are named '$n'") ++
      chained.map(n => s"handler '$n' has notify:, but handlers do not notify other handlers")
