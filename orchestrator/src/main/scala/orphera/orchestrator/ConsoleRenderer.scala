package orphera.orchestrator

import cats.effect.*
import orphera.common.*

object ConsoleRenderer:

  def render(node: Node, event: Event): IO[Unit] =
    event.kind match
      case Event.Kind.PROGRESS =>
        IO.println(s"[${node.name}] ${event.message}")
      case Event.Kind.OUTPUT =>
        IO.println(s"[${node.name}] ${event.message}")
      case Event.Kind.RESULT =>
        IO.println(s"[${node.name}] exit=${event.exitCode} success=${event.success}")
      case _ =>
        IO.unit
