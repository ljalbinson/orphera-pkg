// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

object PlaybookDsl:

  final class PlaybookBuilder private (
      name: String,
      nodes: List[String],
      tasks: List[NamedTask]
  ):

    def task(taskName: String)(t: Task): PlaybookBuilder =
      new PlaybookBuilder(name, nodes, tasks :+ NamedTask(taskName, t))

    /** Attaches a `when:` condition to the most recently added task. */
    def when(condition: FactCondition): PlaybookBuilder =
      tasks.lastOption match
        case Some(last) =>
          new PlaybookBuilder(
            name,
            nodes,
            tasks.dropRight(1) :+ last.copy(when = Some(condition))
          )
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
          new PlaybookBuilder(
            name,
            nodes,
            tasks.dropRight(1) :+ last.copy(retry = Retry.of(times, delaySeconds))
          )
        case None =>
          this

    def build: Playbook = Playbook(name, nodes, tasks)

  object PlaybookBuilder:
    def apply(name: String, nodes: List[String]): PlaybookBuilder =
      new PlaybookBuilder(name, nodes, Nil)

  def playbook(name: String, nodes: String*): PlaybookBuilder =
    PlaybookBuilder(name, nodes.toList)

  extension (key: String)
    def ===(value: String): FactCondition = FactCondition(key, value)
    def =!=(value: String): FactCondition =
      FactCondition(key, value, negate = true)
