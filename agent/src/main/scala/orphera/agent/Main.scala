// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*

import fs2.grpc.syntax.all.*

import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyServerBuilder}

import orphera.common.{AgentFs2Grpc, Auth}

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    Auth.requireToken(sys.env.get("ORPHERA_TOKEN")) match
      case Left(why) =>
        IO.println(why) >>
          IO.raiseError(new RuntimeException("no usable ORPHERA_TOKEN"))
      case Right(token) =>
        TlsFiles.choose match
          case None =>
            IO.println(TlsFiles.Missing) >>
              IO.raiseError(new RuntimeException("no TLS certificate"))
          case Some((certFile, keyFile)) => serve(token, certFile, keyFile)

  private def serve(
      token: String,
      certFile: java.io.File,
      keyFile: java.io.File
  ): IO[Unit] =
    val sslContext = GrpcSslContexts
      .forServer(certFile, keyFile)
      .build()

    for
      _ <- NetworkReloader.initKnownGood()
      pending <- Ref.of[IO, Map[String, Fiber[IO, Throwable, Unit]]](Map.empty)
      service = new AgentServiceImpl(pending)

      _ <- AgentFs2Grpc
        .bindServiceResource[IO](service)
        .flatMap { bound =>
          NettyServerBuilder
            .forPort(50051)
            .sslContext(sslContext)
            .addService(
              ServerInterceptors.intercept(bound, new AuthInterceptor(token))
            )
            .resource[IO]
        }
        .use { server =>
          IO.println(
            s"Orphera Agent listening on 50051 (TLS, ${certFile.getPath})"
          ) >>
            IO.blocking(server.start()) >>
            IO.never
        }
        .guarantee(IO.println("Agent shutting down"))
    yield ()
