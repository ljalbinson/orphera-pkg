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

final case class FactCondition(
    key: String,
    expected: String,
    negate: Boolean = false
):
  def matches(facts: orphera.common.Facts): Boolean =
    val actual = factValue(facts)
    val eq = actual.contains(expected)
    if negate then !eq else eq

  def matches(
      facts: orphera.common.Facts,
      setFacts: Map[String, String]
  ): Boolean =
    setFacts.get(key) match
      case Some(v) =>
        val eq = v == expected
        if negate then !eq else eq
      case None =>
        matches(facts)

  private def factValue(facts: orphera.common.Facts): Option[String] =
    key match
      case "os_id"      => Some(facts.osId)
      case "os_version" => Some(facts.osVersion)
      case "arch"       => Some(facts.architecture)
      case "hostname"   => Some(facts.hostname)
      case _            => None

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

final case class NamedTask(
    name: String,
    task: Task,
    when: Option[FactCondition] = None,
    retry: Option[Retry] = None
)

final case class Playbook(
    name: String,
    nodeNames: List[String],
    tasks: List[NamedTask]
)
