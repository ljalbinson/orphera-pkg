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
  case RunCommand(command: List[String], timeoutSeconds: Int = 60)
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

case class FactCondition(
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

case class NamedTask(
    name: String,
    task: Task,
    when: Option[FactCondition] = None
)

case class Playbook(
    name: String,
    nodeNames: List[String],
    tasks: List[NamedTask]
)
