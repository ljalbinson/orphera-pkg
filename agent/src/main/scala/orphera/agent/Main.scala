// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.io.File

import cats.effect.*

import fs2.grpc.syntax.all.*

import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyServerBuilder}

import orphera.common.AgentFs2Grpc

object Main extends IOApp.Simple:

  def run =
    // A certificate of this node's own (tls/, issued by `orphera issue-certs`
    // or bootstrap) when there is one, else the wildcard the package ships.
    val perNode = (new File("tls/server.crt"), new File("tls/server.key"))
    val (certFile, keyFile) =
      if perNode._1.isFile && perNode._2.isFile then perNode
      else (new File("certs/server.crt"), new File("certs/server.key"))
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
              ServerInterceptors.intercept(bound, new AuthInterceptor)
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
