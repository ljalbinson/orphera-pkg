// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

object ClusterPlaybookDsl:

  final class StageBuilder private (
      name: String,
      nodes: List[String],
      tasks: List[NamedTask],
      waitForCheck: Option[HealthCheck]
  ):

    def task(taskName: String)(t: Task): StageBuilder =
      new StageBuilder(
        name,
        nodes,
        tasks :+ NamedTask(taskName, t),
        waitForCheck
      )

    /** Attaches a `when:` condition to the most recently added task. */
    def when(condition: FactCondition): StageBuilder =
      tasks.lastOption match
        case Some(last) =>
          new StageBuilder(
            name,
            nodes,
            tasks.init :+ last.copy(when = Some(condition)),
            waitForCheck
          )
        case None =>
          this

    def waitFor(check: HealthCheck): StageBuilder =
      new StageBuilder(name, nodes, tasks, Some(check))

    def build: Stage = Stage(name, nodes, tasks, waitForCheck)

  object StageBuilder:
    def apply(name: String, nodes: List[String]): StageBuilder =
      new StageBuilder(name, nodes, Nil, None)

  def stage(name: String, nodes: String*): StageBuilder =
    StageBuilder(name, nodes.toList)

  def clusterPlaybook(name: String)(stages: Stage*): ClusterPlaybook =
    ClusterPlaybook(name, stages.toList)

  extension (key: String)
    def ===(value: String): FactCondition = FactCondition(key, value)
    def =!=(value: String): FactCondition =
      FactCondition(key, value, negate = true)
