// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.*
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

  def removePackages(
      nodes: List[Node],
      packages: List[String],
      purge: Boolean = false
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.removePackages(
        node,
        packages,
        purge,
        ConsoleRenderer.render(node, _)
      )
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
      NodeClient.copyFile(
        node,
        localPath,
        destPath,
        owner,
        group,
        mode,
        ConsoleRenderer.render(node, _)
      )
    }

  def applyNetworkConfig(
      nodes: List[Node],
      confirmTimeoutSeconds: Int = 60
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.applyNetworkConfig(
        node,
        confirmTimeoutSeconds,
        ConsoleRenderer.render(node, _)
      )
    }

  def deployDeb(
      nodes: List[Node],
      localDebPath: java.nio.file.Path,
      remoteDebPath: String = "/tmp/orphera-agent.deb"
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.deployDeb(
        node,
        localDebPath,
        remoteDebPath,
        ConsoleRenderer.render(node, _)
      )
    }

  def bootstrapAgent(
      nodes: List[Node],
      localDebPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String = "/tmp/orphera-agent.deb"
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      SshDeployer.bootstrap(
        node,
        localDebPath,
        sshUser,
        sshKeyPath,
        remotePath,
        line => IO.println(s"[${node.name}] $line")
      )
    }

  def teardownAgent(
      nodes: List[Node],
      sshUser: String,
      sshKeyPath: Option[String],
      purgeConfig: Boolean = false
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      SshDeployer.teardown(
        node,
        sshUser,
        sshKeyPath,
        purgeConfig,
        line => IO.println(s"[${node.name}] $line")
      )
    }

  def fetchFile(
      nodes: List[Node],
      remotePath: String,
      localDir: java.nio.file.Path
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      val localPath = localDir.resolve(
        s"${node.name}-${java.nio.file.Paths.get(remotePath).getFileName}"
      )
      NodeClient.fetchFile(node, remotePath, localPath).flatMap {
        case Right(metadata) =>
          IO.println(
            s"[${node.name}] Fetched ${remotePath} -> $localPath (owner=${metadata.owner}, mode=${metadata.mode}%o)"
          )
        case Left(err) =>
          IO.println(s"[${node.name}] FAILED: $err")
      }
    }

  def gatherFacts(nodes: List[Node]): IO[Map[String, Facts]] =
    nodes
      .parTraverse { node =>
        NodeClient.gatherFacts(node).map(facts => node.name -> facts)
      }
      .map(_.toMap)

  def getVersions(nodes: List[Node]): IO[Map[String, String]] =
    nodes
      .parTraverse { node =>
        NodeClient.getVersion(node).attempt.map(result => node.name -> result)
      }
      .map(_.collect {
        case (name, Right(v)) => name -> v
        case (name, Left(_))  => name -> "unreachable"
      }.toMap)

  def reboot(
      nodes: List[Node],
      delaySeconds: Int = 5,
      waitForReturn: Boolean = false,
      waitTimeoutSeconds: Int = 300
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.reboot(
        node,
        delaySeconds,
        waitForReturn,
        waitTimeoutSeconds,
        line => IO.println(s"[${node.name}] $line")
      )
    }

  def getUptimes(nodes: List[Node]): IO[Map[String, UptimeInfo]] =
    nodes
      .parTraverse { node =>
        NodeClient.getUptime(node).attempt.map(result => node.name -> result)
      }
      .map(_.collect {
        case (name, Right(info)) => name -> info
      }.toMap)
