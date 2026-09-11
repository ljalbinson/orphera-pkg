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
      .forAddress(node.host, node.port)
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
                .evalMap(onEvent)
                .compile
                .drain
        yield ()
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
      _ <- copyFile(node, localDebPath, remoteDebPath, "root", "root", 420 /* 0644 octal */, onEvent)
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
