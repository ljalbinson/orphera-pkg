// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

object ClusterPlaybookDsl:

  final class StageBuilder private (
      name: String,
      nodes: List[String],
      tasks: List[NamedTask],
      waitForCheck: Option[HealthCheck],
      handlers: List[NamedTask]
  ):

    private def withTasks(ts: List[NamedTask]): StageBuilder =
      new StageBuilder(name, nodes, ts, waitForCheck, handlers)

    def task(taskName: String)(t: Task): StageBuilder =
      withTasks(tasks :+ NamedTask(taskName, t))

    /** Adds tasks built elsewhere: the Scala form of YAML's `include:`. */
    def includeTasks(more: List[NamedTask]): StageBuilder =
      withTasks(tasks ++ more)

    /** The most recently added task notifies these handlers when it changes
      * something.
      */
    def notifies(handlerNames: String*): StageBuilder =
      tasks.lastOption match
        case Some(last) =>
          withTasks(
            tasks.dropRight(1) :+ last.copy(notifies = last.notifies ++ handlerNames)
          )
        case None => this

    /** Adds a handler, run on each node after its tasks in this stage, once,
      * if a task that changed something notified it.
      */
    def handler(handlerName: String)(t: Task): StageBuilder =
      new StageBuilder(
        name,
        nodes,
        tasks,
        waitForCheck,
        handlers :+ NamedTask(handlerName, t)
      )

    /** Attaches a `when:` condition to the most recently added task. */
    def when(condition: FactCondition): StageBuilder =
      tasks.lastOption match
        case Some(last) =>
          withTasks(tasks.dropRight(1) :+ last.copy(when = Some(condition)))
        case None =>
          this

    /** Retries the most recently added task when it fails: up to `times` more
      * times, `delaySeconds` before the first retry and doubling after it (see
      * Retry).
      */
    def retry(
        times: Int,
        delaySeconds: Int = Retry.DefaultDelaySeconds
    ): StageBuilder =
      tasks.lastOption match
        case Some(last) =>
          withTasks(
            tasks.dropRight(1) :+ last.copy(retry = Retry.of(times, delaySeconds))
          )
        case None =>
          this

    def waitFor(check: HealthCheck): StageBuilder =
      new StageBuilder(name, nodes, tasks, Some(check), handlers)

    def build: Stage = Stage(name, nodes, tasks, waitForCheck, handlers)

  object StageBuilder:
    def apply(name: String, nodes: List[String]): StageBuilder =
      new StageBuilder(name, nodes, Nil, None, Nil)

  def stage(name: String, nodes: String*): StageBuilder =
    StageBuilder(name, nodes.toList)

  def clusterPlaybook(name: String)(stages: Stage*): ClusterPlaybook =
    ClusterPlaybook(name, stages.toList)

  extension (key: String)
    def ===(value: String): FactCondition = FactCondition(key, value)
    def =!=(value: String): FactCondition =
      FactCondition(key, value, negate = true)
