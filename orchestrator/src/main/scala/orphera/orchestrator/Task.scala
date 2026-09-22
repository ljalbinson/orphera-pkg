// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

enum Task:
  case Install(packages: List[String], updateCache: Boolean = false, version: String = "")
  case Remove(packages: List[String], purge: Boolean = false)
  case AutoRemove(purge: Boolean = false)
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
