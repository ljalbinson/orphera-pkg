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
            tasks.init :+ last.copy(when = Some(condition))
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
