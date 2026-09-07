package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*

object Orchestrator:

  def installPackages(
      nodes: List[Node],
      packages: List[String],
      updateCache: Boolean = false
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.installPackages(
        node,
        packages,
        updateCache,
        ConsoleRenderer.render(node, _)
      )
    }
