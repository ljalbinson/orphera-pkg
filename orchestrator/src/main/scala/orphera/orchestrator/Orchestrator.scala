// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.*
import cats.effect.*
import cats.syntax.all.*
import java.nio.file.{Files, Paths}

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

  /** `content` is already-resolved bytes — Main.scala reads --content-file (or
    * UTF-8-encodes a literal --content string) before calling this, so this
    * stays a pure "push these bytes" operation, same shape as copyFile just
    * without a local path. Goes through the same CopyFile RPC/FileChunk
    * streaming copyFile does (NodeClient.copyBytes).
    */
  def writeFile(
      nodes: List[Node],
      content: Array[Byte],
      destPath: String,
      owner: String,
      group: String,
      mode: Int
  ): IO[Unit] =
    nodes.parTraverse_ { node =>
      NodeClient.copyBytes(
        node,
        content,
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

  /** Pushes and installs the agent `.deb`, then polls each node's `getVersion`
    * RPC until it reports the `.deb`'s own `Version` field or
    * `confirmTimeoutSeconds` elapses — see `NodeClient.pollForVersion`'s doc
    * comment for why this exists: the install itself runs detached (the agent's
    * own service restart would otherwise kill it mid-unpack), so the RPC call
    * that launches it can never itself confirm completion. Returns each node's
    * confirmation result rather than `IO[Unit]`, so the CLI can report which
    * nodes actually finished upgrading and set its exit code accordingly,
    * instead of exit-0-means-nothing.
    */
  def deployDeb(
      nodes: List[Node],
      localDebPath: java.nio.file.Path,
      remoteDebPath: String = "/tmp/orphera-agent.deb",
      confirmTimeoutSeconds: Int = 60
  ): IO[Map[String, Boolean]] =
    for
      expectedVersion <- requireOrpheraAgentPackage(localDebPath.toString)
      results <- nodes.parTraverse { node =>
        for
          _ <- NodeClient.deployDeb(
            node,
            localDebPath,
            remoteDebPath,
            ConsoleRenderer.render(node, _)
          )
          confirmed <- NodeClient.pollForVersion(
            node,
            expectedVersion,
            confirmTimeoutSeconds,
            line => IO.println(s"[${node.name}] $line")
          )
        yield node.name -> confirmed
      }
    yield results.toMap

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
  // Package Version`), not its filename, for the same reason as the
  // agent-side check: a wrong or tampered file given an
  // innocent-looking name would otherwise sail through.
  //
  // Also the source of the version `deployDeb` polls for afterward
  // (see `NodeClient.pollForVersion`) — reading both fields off one
  // `dpkg-deb` call rather than two.
  //
  // NOTE, found the hard way: `dpkg-deb -f <path> <one-field>` prints
  // just the bare value, no label — but as soon as you ask for MORE
  // than one field, it switches to labeled `Field: value` lines
  // instead (undocumented in the obvious places, but real: confirmed
  // against an actual `dpkg-deb` on 2026-09-30 after this exact check
  // rejected a genuine orphera-agent .deb with "its Package field is
  // 'Package: orphera-agent', not 'orphera-agent'" — the unlabeled
  // parsing below is what the earlier version of this method assumed
  // for the single-field case, and it broke the moment Version was
  // added alongside it). Parsed as `Field: value` pairs now, not
  // positionally, so it doesn't matter which order dpkg-deb prints them in.
  private def requireOrpheraAgentPackage(localDebPath: String): IO[String] =
    val expectedPackageName = "orphera-agent"

    // Checked up front, separately from dpkg-deb's own exit code:
    // dpkg-deb exits 2 for "no such file" exactly the same as it does
    // for "file exists but isn't a valid .deb", so without this check
    // a plain typo'd/missing path came back as "could not read its
    // package metadata — is this a valid .deb?", which sent someone
    // looking for a corrupt file that was never there in the first
    // place. Found in real use, same day as the fields-parsing fix
    // above.
    if !Files.exists(Paths.get(localDebPath)) then
      IO.raiseError(new RuntimeException(s"No such file: $localDebPath"))
    else
      IO.blocking {
        val process = new ProcessBuilder(
          "dpkg-deb",
          "-f",
          localDebPath,
          "Package",
          "Version"
        )
          .redirectErrorStream(true)
          .start()
        val output = new String(process.getInputStream.readAllBytes()).trim
        val exit = process.waitFor()
        val fields = output.linesIterator.flatMap { line =>
          line.split(":", 2) match
            case Array(key, value) => Some(key.trim -> value.trim)
            case _                 => None
        }.toMap
        (exit, fields, output)
      }.attempt
        .flatMap {
          case Right((0, fields, _))
              if fields.get("Package").contains(expectedPackageName) =>
            fields.get("Version") match
              case Some(version) => IO.pure(version)
              case None          =>
                IO.raiseError(
                  new RuntimeException(
                    s"Refusing to deploy $localDebPath: read its Package field but no Version field (dpkg-deb fields: ${fields.mkString(", ")})"
                  )
                )
          case Right((0, fields, _)) if fields.contains("Package") =>
            IO.raiseError(
              new RuntimeException(
                s"Refusing to deploy $localDebPath: its Package field is '${fields("Package")}', not '$expectedPackageName'. " +
                  "bootstrap/deploy-agent only install the orphera-agent package — build one with 'make deb' first."
              )
            )
          case Right((0, fields, _)) =>
            IO.raiseError(
              new RuntimeException(
                s"Refusing to deploy $localDebPath: could not find a Package field in dpkg-deb's output: ${fields.mkString(", ")}"
              )
            )
          case Right((exit, _, output)) =>
            IO.raiseError(
              new RuntimeException(
                s"Refusing to deploy $localDebPath: dpkg-deb exited $exit reading its metadata — " +
                  s"is this a valid .deb? (dpkg-deb said: ${
                      if output.isEmpty then "<no output>" else output
                    })"
              )
            )
          case Left(err) =>
            IO.raiseError(
              new RuntimeException(
                s"Refusing to deploy $localDebPath: failed to run dpkg-deb (${err.getMessage})"
              )
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
