// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.File

import scala.concurrent.duration.*

import cats.effect.*

import fs2.grpc.syntax.all.*

import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}

import orphera.common.*

object NodeClient:

  private def channelBuilder(node: Node): NettyChannelBuilder =
    Installation.caCertProblem.foreach(problem =>
      throw new IllegalStateException(problem)
    )
    // The client certificate, when this orchestrator has one, for agents that
    // require it (mutual TLS: `issue-certs --client-auth`).
    val trusting = GrpcSslContexts
      .forClient()
      .trustManager(new File(Installation.caCert))
    val sslContext =
      (if ClientCert.present then
         trusting.keyManager(
           new File(Installation.clientCert),
           new File(Installation.clientKey)
         )
       else trusting).build()

    NettyChannelBuilder
      .forTarget(s"dns:///${node.host}:${node.port}")
      .sslContext(sslContext)

  private def authMetadata(): Metadata =
    val metadata = new Metadata()
    metadata.put(Auth.TokenKey, AgentToken.current)
    metadata

  def installPackages(
      node: Node,
      packages: List[String],
      updateCache: Boolean,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .install(InstallPackages(packages, updateCache), authMetadata())
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
      }

  def removePackages(
      node: Node,
      packages: List[String],
      purge: Boolean,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .remove(RemovePackages(packages, purge), authMetadata())
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
      }

  def autoRemove(
      node: Node,
      purge: Boolean,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .runAutoRemove(AutoRemove(purge), authMetadata())
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
      }

  def distUpgrade(
      node: Node,
      updateCache: Boolean,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .runDistUpgrade(DistUpgrade(updateCache), authMetadata())
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
      }

  private def localSha256(path: java.nio.file.Path): IO[String] =
    IO.blocking {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      val bytes = java.nio.file.Files.readAllBytes(path)
      digest.digest(bytes).map(b => f"$b%02x").mkString
    }

  def copyFile(
      node: Node,
      localPath: java.nio.file.Path,
      destPath: String,
      owner: String,
      group: String,
      mode: Int,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        val metadata = authMetadata()

        for
          hash <- localSha256(localPath)

          checkResult <- stub.checkFile(
            FileCheck(destPath, hash, owner, group, mode),
            metadata
          )

          _ <-
            if !checkResult.needsCopy then
              onEvent(
                Event(
                  Event.Kind.RESULT,
                  s"Skipped: ${checkResult.reason}",
                  exitCode = 0,
                  success = true
                )
              )
            else
              val metadataChunk =
                FileChunk(
                  FileChunk.Payload
                    .Metadata(FileMetadata(destPath, owner, group, mode))
                )

              val contentChunks =
                fs2.io.file
                  .Files[IO]
                  .readAll(fs2.io.file.Path.fromNioPath(localPath))
                  .chunkN(64 * 1024)
                  .map(chunk =>
                    FileChunk(
                      FileChunk.Payload.Content(
                        com.google.protobuf.ByteString.copyFrom(chunk.toArray)
                      )
                    )
                  )

              val requestStream =
                fs2.Stream.emit(metadataChunk) ++ contentChunks

              stub
                .copyFile(requestStream, metadata)
                .evalMap(e => onEvent(Secrets.mask(e)))
                .compile
                .drain
        yield ()
      }

  /** Pushes raw bytes already in memory, verbatim — no local file, no
    * idempotent pre-check (a freshly generated file, e.g. a keyring, should
    * always be pushed, not skipped because a stale version happens to match by
    * coincidence). Used by Task.DistributeFile.
    */
  def copyBytes(
      node: Node,
      content: Array[Byte],
      destPath: String,
      owner: String,
      group: String,
      mode: Int,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        val metadata = authMetadata()
        val metadataChunk = FileChunk(
          FileChunk.Payload.Metadata(FileMetadata(destPath, owner, group, mode))
        )
        val contentChunk = FileChunk(
          FileChunk.Payload
            .Content(com.google.protobuf.ByteString.copyFrom(content))
        )
        val requestStream =
          fs2.Stream.emit(metadataChunk) ++ fs2.Stream.emit(contentChunk)
        val hash = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(content)
          .map(b => f"$b%02x")
          .mkString

        // As copyFile: a file that already has this content, owner, group
        // and mode is not sent again ("Skipped: unchanged"), which is also how
        // a write_file task tells that it changed nothing.
        stub
          .checkFile(FileCheck(destPath, hash, owner, group, mode), metadata)
          .flatMap { check =>
            if !check.needsCopy then
              onEvent(
                Event(
                  Event.Kind.RESULT,
                  s"Skipped: ${check.reason}",
                  exitCode = 0,
                  success = true
                )
              )
            else
              stub
                .copyFile(requestStream, metadata)
                .evalMap(e => onEvent(Secrets.mask(e)))
                .compile
                .drain
          }
      }

  /** Simple boolean health-probe wrapper around CheckFile: true if the remote
    * file's content hash matches expectedSha256 exactly.
    */
  def checkFile(
      node: Node,
      remotePath: String,
      expectedSha256: String
  ): IO[Boolean] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .checkFile(
            FileCheck(remotePath, expectedSha256, "", "", 0),
            authMetadata()
          )
          .map(result => !result.needsCopy)
      }

  def applyNetworkConfig(
      node: Node,
      confirmTimeoutSeconds: Int,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        val metadata = authMetadata()

        for
          result <- stub.reloadNetwork(
            NetworkReload(confirmTimeoutSeconds),
            metadata
          )
          _ <- onEvent(
            Event(
              Event.Kind.PROGRESS,
              s"Reload applied, backup ${result.backupId}"
            )
          )

          _ <- IO.sleep(3.seconds)

          verifyResult <- stub
            .checkFile(FileCheck("/etc/hostname", "", "", "", 0), metadata)
            .attempt

          _ <- verifyResult match
            case Right(_) =>
              stub.confirmNetwork(NetworkConfirm(result.backupId), metadata) >>
                onEvent(
                  Event(
                    Event.Kind.RESULT,
                    "Confirmed — connectivity OK",
                    exitCode = 0,
                    success = true
                  )
                )
            case Left(err) =>
              onEvent(
                Event(
                  Event.Kind.RESULT,
                  s"Connectivity check failed, not confirming: ${err.getMessage}",
                  exitCode = 1,
                  success = false
                )
              )
        yield ()
      }

  def deployDeb(
      node: Node,
      localDebPath: java.nio.file.Path,
      remoteDebPath: String,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    for
      _ <- copyFile(
        node,
        localDebPath,
        remoteDebPath,
        "root",
        "root",
        420,
        onEvent
      )
      _ <- installDeb(node, remoteDebPath, onEvent)
    yield ()

  private def installDeb(
      node: Node,
      remotePath: String,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .installDebPackage(InstallDeb(remotePath), authMetadata())
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
          .handleErrorWith { err =>
            onEvent(
              Event(
                Event.Kind.RESULT,
                s"Connection lost during install, likely due to agent self-restart — verify agent version manually (${err.getMessage})",
                exitCode = 0,
                success = false
              )
            )
          }
      }

  def reboot(
      node: Node,
      delaySeconds: Int,
      waitForReturn: Boolean,
      waitTimeoutSeconds: Int,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    if Inventory.neverReboot(node) then
      IO.raiseError(
        new IllegalStateException(
          s"${node.name} has never-reboot set in the inventory, so it is not rebooted"
        )
      )
    else
      rebootAllowed(
        node,
        delaySeconds,
        waitForReturn,
        waitTimeoutSeconds,
        onLine
      )

  private def rebootAllowed(
      node: Node,
      delaySeconds: Int,
      waitForReturn: Boolean,
      waitTimeoutSeconds: Int,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      askedAt <- IO.monotonic
      ack <- channelBuilder(node)
        .resource[IO]
        .flatMap(AgentFs2Grpc.stubResource[IO])
        .use(_.triggerReboot(RebootRequest(delaySeconds), authMetadata()))

      _ <- onLine(ack.message)

      _ <-
        if !waitForReturn then IO.unit
        else
          onLine("Waiting for host to come back...") >>
            IO.sleep((delaySeconds + 5).seconds) >>
            pollUntilBack(node, waitTimeoutSeconds, onLine) >>
            confirmRebooted(node, askedAt, onLine)
    yield ()

  /** After `reboot --wait` sees the agent answer again: whether the host
    * really rebooted, rather than only the agent restarting. Its uptime must be
    * shorter than the time since the reboot was asked for. Fails if it is not;
    * an agent too old to report uptime gets a line saying the reboot could not
    * be confirmed.
    */
  private def confirmRebooted(
      node: Node,
      askedAt: FiniteDuration,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      now <- IO.monotonic
      uptime <- getUptime(node).attempt
      _ <- uptime match
        case Right(info) =>
          rebootVerdict(info.uptimeSeconds, (now - askedAt).toSeconds)
            .fold(why => IO.raiseError(new RuntimeException(why)), onLine)
        case Left(e) if NodeError.isUnimplemented(e) =>
          onLine(
            "Its agent is too old to report uptime, so the reboot is not confirmed"
          )
        case Left(e) => IO.raiseError(e)
    yield ()

  /** Whether a host whose uptime is `uptimeSeconds`, `sinceAskedSeconds` after
    * the reboot was asked for, rebooted: Right with a line, or Left with why
    * not.
    */
  def rebootVerdict(
      uptimeSeconds: Long,
      sinceAskedSeconds: Long
  ): Either[String, String] =
    Either.cond(
      uptimeSeconds < sinceAskedSeconds,
      s"Rebooted: up ${uptimeSeconds}s, ${sinceAskedSeconds}s after the reboot was asked for",
      s"the agent answers again but the host did not reboot: it has been up ${uptimeSeconds}s, " +
        s"longer than the ${sinceAskedSeconds}s since the reboot was asked for"
    )

  /** Powers the host off after `delaySeconds`. Returns once the agent has
    * acknowledged scheduling it — the host going down afterwards is not
    * confirmed (the agent is the thing being shut down), see `uptime`/ping.
    * Fails, without contacting the node, if it has `never-reboot` set: a host
    * that must not be rebooted must not be powered off either, and powering off
    * is worse, since it cannot be undone remotely.
    */
  def shutdown(
      node: Node,
      delaySeconds: Int,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    if Inventory.neverReboot(node) then
      IO.raiseError(
        new IllegalStateException(
          s"${node.name} has never-reboot set in the inventory, so it is not shut down"
        )
      )
    else shutdownAllowed(node, delaySeconds, onLine)

  private def shutdownAllowed(
      node: Node,
      delaySeconds: Int,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      ack <- channelBuilder(node)
        .resource[IO]
        .flatMap(AgentFs2Grpc.stubResource[IO])
        .use(_.triggerShutdown(ShutdownRequest(delaySeconds), authMetadata()))
      _ <- onLine(ack.message)
    yield ()

  private def pollUntilBack(
      node: Node,
      timeoutSeconds: Int,
      onLine: String => IO[Unit],
      elapsed: Int = 0
  ): IO[Unit] =
    if elapsed >= timeoutSeconds then
      IO.raiseError(
        new RuntimeException(
          s"timed out after ${timeoutSeconds}s waiting for the host to come back"
        )
      )
    else
      channelBuilder(node)
        .resource[IO]
        .flatMap(AgentFs2Grpc.stubResource[IO])
        .use(
          _.checkFile(FileCheck("/etc/hostname", "", "", "", 0), authMetadata())
        )
        .attempt
        .flatMap {
          case Right(_) =>
            onLine(s"Host is back after ~${elapsed}s")
          case Left(_) =>
            IO.sleep(5.seconds) >> pollUntilBack(
              node,
              timeoutSeconds,
              onLine,
              elapsed + 5
            )
        }

  /** Polls `getVersion` until it reports `expectedVersion` or `timeoutSeconds`
    * elapses — closes the gap `deployDeb`/`installDeb` left open:
    * `success = true` on the install's `RESULT` event only ever meant "the
    * detached `dpkg -i` was launched" (it has to run detached — see
    * `installDeb`'s doc comment on why — so the RPC call itself can't wait for
    * the install to actually finish), never "the install completed and the
    * agent is now running the new version." This is the actual confirmation.
    * Same shape as `pollUntilBack` above (poll every 5s, same timeout style),
    * but returns whether it was confirmed rather than just narrating it, so the
    * caller can decide the CLI's exit code on it.
    */
  def pollForVersion(
      node: Node,
      expectedVersion: String,
      timeoutSeconds: Int,
      onLine: String => IO[Unit],
      elapsed: Int = 0
  ): IO[Boolean] =
    if elapsed >= timeoutSeconds then
      onLine(
        s"Timed out after ${timeoutSeconds}s waiting for version $expectedVersion " +
          "(agent may still be mid-install, unreachable, or the install failed — check manually)"
      ).as(false)
    else
      getVersion(node).attempt.flatMap {
        case Right(v) if v == expectedVersion =>
          onLine(s"Confirmed running $v after ~${elapsed}s").as(true)
        case Right(_) =>
          // Reachable but not yet on the new version — still mid
          // install/restart, not a failure yet.
          IO.sleep(5.seconds) >>
            pollForVersion(
              node,
              expectedVersion,
              timeoutSeconds,
              onLine,
              elapsed + 5
            )
        case Left(_) =>
          // Unreachable — expected for a few seconds around the
          // agent's own service restart, same as pollUntilBack's
          // checkFile probe during a reboot.
          IO.sleep(5.seconds) >>
            pollForVersion(
              node,
              expectedVersion,
              timeoutSeconds,
              onLine,
              elapsed + 5
            )
      }

  def fetchFile(
      node: Node,
      remotePath: String,
      localPath: java.nio.file.Path
  ): IO[Either[String, FileMetadata]] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .remoteFetchFile(FetchFile(remotePath), authMetadata())
          .compile
          .toList
          .flatMap { chunks =>
            chunks.headOption.flatMap(_.payload.error) match

              case Some(err) =>
                IO.pure(Left(err))

              case None =>
                chunks.headOption.flatMap(_.payload.metadata) match
                  case None =>
                    IO.pure(Left("No metadata received from agent"))
                  case Some(metadata) =>
                    val content = chunks.drop(1).flatMap(_.payload.content)
                    IO.blocking {
                      val out = java.nio.file.Files.newOutputStream(localPath)
                      try content.foreach(c => out.write(c.toByteArray))
                      finally out.close()
                    }.as(Right(metadata))
          }
      }

  /** Same RPC as fetchFile, but returns the content as bytes in memory instead
    * of writing to a local file — used by Task.DistributeFile, which never
    * wants the fetched content to touch local disk.
    */
  def fetchFileBytes(
      node: Node,
      remotePath: String
  ): IO[Either[String, Array[Byte]]] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .remoteFetchFile(FetchFile(remotePath), authMetadata())
          .compile
          .toList
          .map { chunks =>
            chunks.headOption.flatMap(_.payload.error) match
              case Some(err) => Left(err)
              case None      =>
                val content = chunks.drop(1).flatMap(_.payload.content)
                Right(content.iterator.flatMap(_.toByteArray).toArray)
          }
      }

  def gatherFacts(node: Node): IO[Facts] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub.gatherFacts(FactsRequest(), authMetadata())
      }

  def getVersion(node: Node): IO[String] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub.getVersion(VersionRequest(), authMetadata()).map(_.version)
      }

  def getUptime(node: Node): IO[UptimeInfo] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub.getUptime(UptimeRequest(), authMetadata())
      }

  def getThreadInfo(node: Node, resetPeak: Boolean): IO[ThreadInfo] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub.getThreadInfo(ThreadInfoRequest(resetPeak), authMetadata())
      }

  def executeCommand(
      node: Node,
      command: List[String],
      timeoutSeconds: Int,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .executeCommand(
            RunCommandRequest(command, timeoutSeconds),
            authMetadata()
          )
          .evalMap(e => onEvent(Secrets.mask(e)))
          .compile
          .drain
      }
