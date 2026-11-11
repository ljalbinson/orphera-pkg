// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

object PlaybookDsl:

  final class PlaybookBuilder private (
      name: String,
      nodes: List[String],
      tasks: List[NamedTask],
      handlers: List[NamedTask]
  ):

    private def withTasks(ts: List[NamedTask]): PlaybookBuilder =
      new PlaybookBuilder(name, nodes, ts, handlers)

    def task(taskName: String)(t: Task): PlaybookBuilder =
      withTasks(tasks :+ NamedTask(taskName, t))

    /** Adds tasks built elsewhere (a `List[NamedTask]` shared by several
      * playbooks): the Scala form of YAML's `include:`.
      */
    def includeTasks(more: List[NamedTask]): PlaybookBuilder =
      withTasks(tasks ++ more)

    /** The most recently added task notifies these handlers when it changes
      * something (see Playbook.handlers).
      */
    def notifies(handlerNames: String*): PlaybookBuilder =
      tasks.lastOption match
        case Some(last) =>
          withTasks(
            tasks.dropRight(1) :+ last.copy(notifies = last.notifies ++ handlerNames)
          )
        case None => this

    /** Adds a handler: a task run on a node after its tasks, once, if a task
      * that changed something notified it by this name.
      */
    def handler(handlerName: String)(t: Task): PlaybookBuilder =
      new PlaybookBuilder(name, nodes, tasks, handlers :+ NamedTask(handlerName, t))

    /** Attaches a `when:` condition to the most recently added task. */
    def when(condition: FactCondition): PlaybookBuilder =
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
    ): PlaybookBuilder =
      tasks.lastOption match
        case Some(last) =>
          withTasks(
            tasks.dropRight(1) :+ last.copy(retry = Retry.of(times, delaySeconds))
          )
        case None =>
          this

    def build: Playbook = Playbook(name, nodes, tasks, handlers)

  object PlaybookBuilder:
    def apply(name: String, nodes: List[String]): PlaybookBuilder =
      new PlaybookBuilder(name, nodes, Nil, Nil)

  def playbook(name: String, nodes: String*): PlaybookBuilder =
    PlaybookBuilder(name, nodes.toList)

  extension (key: String)
    def ===(value: String): FactCondition = FactCondition(key, value)
    def =!=(value: String): FactCondition =
      FactCondition(key, value, negate = true)
