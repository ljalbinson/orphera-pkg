// SPDX-License-Identifier: Apache-2.0

// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import fs2.grpc.syntax.all.*
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}
import java.io.File
import scala.concurrent.duration.*
import orphera.common.*

object NodeClient:

  private def channelBuilder(node: Node): NettyChannelBuilder =
    val sslContext = GrpcSslContexts
      .forClient()
      .trustManager(new File("certs/ca.crt"))
      .build()

    NettyChannelBuilder
      .forTarget(s"dns:///${node.host}:${node.port}")
      .sslContext(sslContext)

  private def authMetadata(): Metadata =
    val metadata = new Metadata()
    metadata.put(Auth.TokenKey, Auth.SharedToken)
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
          .evalMap(onEvent)
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
          .evalMap(onEvent)
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
          .evalMap(onEvent)
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
              onEvent(Event(Event.Kind.RESULT, s"Skipped: ${checkResult.reason}", exitCode = 0, success = true))
            else
              val metadataChunk =
                FileChunk(FileChunk.Payload.Metadata(FileMetadata(destPath, owner, group, mode)))

              val contentChunks =
                fs2.io.file.Files[IO]
                  .readAll(fs2.io.file.Path.fromNioPath(localPath))
                  .chunkN(64 * 1024)
                  .map(chunk => FileChunk(FileChunk.Payload.Content(com.google.protobuf.ByteString.copyFrom(chunk.toArray))))

              val requestStream = fs2.Stream.emit(metadataChunk) ++ contentChunks

              stub
                .copyFile(requestStream, metadata)
                .evalMap(onEvent)
                .compile
                .drain
        yield ()
      }

  /** Pushes raw bytes already in memory, verbatim — no local file, no
    * idempotent pre-check (a freshly generated file, e.g. a keyring,
    * should always be pushed, not skipped because a stale version
    * happens to match by coincidence). Used by Task.DistributeFile.
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
        val metadataChunk = FileChunk(FileChunk.Payload.Metadata(FileMetadata(destPath, owner, group, mode)))
        val contentChunk = FileChunk(FileChunk.Payload.Content(com.google.protobuf.ByteString.copyFrom(content)))
        val requestStream = fs2.Stream.emit(metadataChunk) ++ fs2.Stream.emit(contentChunk)

        stub
          .copyFile(requestStream, metadata)
          .evalMap(onEvent)
          .compile
          .drain
      }

  /** Simple boolean health-probe wrapper around CheckFile: true if the
    * remote file's content hash matches expectedSha256 exactly.
    */
  def checkFile(node: Node, remotePath: String, expectedSha256: String): IO[Boolean] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .checkFile(FileCheck(remotePath, expectedSha256, "", "", 0), authMetadata())
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
          result <- stub.reloadNetwork(NetworkReload(confirmTimeoutSeconds), metadata)
          _ <- onEvent(Event(Event.Kind.PROGRESS, s"Reload applied, backup ${result.backupId}"))

          _ <- IO.sleep(3.seconds)

          verifyResult <- stub.checkFile(FileCheck("/etc/hostname", "", "", "", 0), metadata).attempt

          _ <- verifyResult match
            case Right(_) =>
              stub.confirmNetwork(NetworkConfirm(result.backupId), metadata) >>
                onEvent(Event(Event.Kind.RESULT, "Confirmed — connectivity OK", exitCode = 0, success = true))
            case Left(err) =>
              onEvent(Event(Event.Kind.RESULT, s"Connectivity check failed, not confirming: ${err.getMessage}", exitCode = 1, success = false))
        yield ()
      }

  def deployDeb(
      node: Node,
      localDebPath: java.nio.file.Path,
      remoteDebPath: String,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    for
      _ <- copyFile(node, localDebPath, remoteDebPath, "root", "root", 420, onEvent)
      _ <- installDeb(node, remoteDebPath, onEvent)
    yield ()

  private def installDeb(node: Node, remotePath: String, onEvent: Event => IO[Unit]): IO[Unit] =
    channelBuilder(node)
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        stub
          .installDebPackage(InstallDeb(remotePath), authMetadata())
          .evalMap(onEvent)
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
    for
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
            pollUntilBack(node, waitTimeoutSeconds, onLine)
    yield ()

  private def pollUntilBack(
      node: Node,
      timeoutSeconds: Int,
      onLine: String => IO[Unit],
      elapsed: Int = 0
  ): IO[Unit] =
    if elapsed >= timeoutSeconds then
      onLine(s"Timed out after ${timeoutSeconds}s waiting for host to return")
    else
      channelBuilder(node)
        .resource[IO]
        .flatMap(AgentFs2Grpc.stubResource[IO])
        .use(_.checkFile(FileCheck("/etc/hostname", "", "", "", 0), authMetadata()))
        .attempt
        .flatMap {
          case Right(_) =>
            onLine(s"Host is back after ~${elapsed}s")
          case Left(_) =>
            IO.sleep(5.seconds) >> pollUntilBack(node, timeoutSeconds, onLine, elapsed + 5)
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

  /** Same RPC as fetchFile, but returns the content as bytes in memory
    * instead of writing to a local file — used by Task.DistributeFile,
    * which never wants the fetched content to touch local disk.
    */
  def fetchFileBytes(node: Node, remotePath: String): IO[Either[String, Array[Byte]]] =
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
              case None =>
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
          .executeCommand(RunCommandRequest(command, timeoutSeconds), authMetadata())
          .evalMap(onEvent)
          .compile
          .drain
      }
