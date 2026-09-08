package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*

object Orchestrator:

  def installPackages(nodes: List[Node], packages: List[String], updateCache: Boolean = false): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.installPackages(node, packages, updateCache, ConsoleRenderer.render(node, _))
    }

  def removePackages(nodes: List[Node], packages: List[String], purge: Boolean = false): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.removePackages(node, packages, purge, ConsoleRenderer.render(node, _))
    }

  def autoRemove(nodes: List[Node], purge: Boolean = false): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.autoRemove(node, purge, ConsoleRenderer.render(node, _))
    }

  def copyFile(
      nodes: List[Node],
      localPath: java.nio.file.Path,
      destPath: String,
      owner: String,
      group: String,
      mode: Int
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.copyFile(node, localPath, destPath, owner, group, mode, ConsoleRenderer.render(node, _))
    }

  def applyNetworkConfig(nodes: List[Node], confirmTimeoutSeconds: Int = 60): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.applyNetworkConfig(node, confirmTimeoutSeconds, ConsoleRenderer.render(node, _))
    }
