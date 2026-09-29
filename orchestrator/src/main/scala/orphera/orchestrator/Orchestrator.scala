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
    requireOrpheraAgentPackage(localDebPath.toString) >>
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
    requireOrpheraAgentPackage(localDebPath) >>
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

  // The only package this project's own deploy paths are allowed to
  // push and install. `deployDeb` also gets this checked again on the
  // agent side (DebInstaller.verifyPackageName) — that's the real
  // trust boundary, since deployDeb goes over an authenticated gRPC
  // call that could in principle be made directly. `bootstrapAgent`
  // has no agent to defer to yet (that's the whole point of
  // bootstrap), so this client-side check is its ONLY enforcement —
  // there is no second layer for that path.
  //
  // Checked via the .deb's own control metadata (`dpkg-deb -f <path>
  // Package`), not its filename, for the same reason as the agent-side
  // check: a wrong or tampered file given an innocent-looking name
  // would otherwise sail through.
  private def requireOrpheraAgentPackage(localDebPath: String): IO[Unit] =
    val expectedPackageName = "orphera-agent"
    IO.blocking {
      val process = new ProcessBuilder("dpkg-deb", "-f", localDebPath, "Package")
        .redirectErrorStream(true)
        .start()
      val output = new String(process.getInputStream.readAllBytes()).trim
      val exit = process.waitFor()
      (exit, output)
    }.attempt.flatMap {
      case Right((0, name)) if name == expectedPackageName =>
        IO.unit
      case Right((0, name)) =>
        IO.raiseError(new RuntimeException(
          s"Refusing to deploy $localDebPath: its Package field is '$name', not '$expectedPackageName'. " +
            "bootstrap/deploy-agent only install the orphera-agent package — build one with 'make deb' first."
        ))
      case Right((exit, _)) =>
        IO.raiseError(new RuntimeException(
          s"Refusing to deploy $localDebPath: could not read its package metadata (dpkg-deb exited $exit) — is this a valid .deb?"
        ))
      case Left(err) =>
        IO.raiseError(new RuntimeException(
          s"Refusing to deploy $localDebPath: failed to run dpkg-deb (${err.getMessage})"
        ))
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
      .map(_.collect { case (name, Right(info)) =>
        name -> info
      }.toMap)

  def executeCommand(
      nodes: List[Node],
      command: List[String],
      timeoutSeconds: Int = 60
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.executeCommand(
        node,
        command,
        timeoutSeconds,
        ConsoleRenderer.render(node, _)
      )
    }
