// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import fs2.grpc.syntax.all.*
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyServerBuilder}
import java.io.File
import orphera.common.AgentFs2Grpc

object Main extends IOApp.Simple:

  def run =
    val sslContext = GrpcSslContexts
      .forServer(new File("certs/server.crt"), new File("certs/server.key"))
      .build()

    for
      pending <- Ref.of[IO, Map[String, Fiber[IO, Throwable, Unit]]](Map.empty)
      service = new AgentServiceImpl(pending)

      _ <- AgentFs2Grpc
        .bindServiceResource[IO](service)
        .flatMap { bound =>
          NettyServerBuilder
            .forPort(50051)
            .sslContext(sslContext)
            .addService(ServerInterceptors.intercept(bound, new AuthInterceptor))
            .resource[IO]
        }
        .use { server =>
          IO.println("Orphera Agent listening on 50051 (TLS)") >>
            IO.blocking(server.start()) >>
            IO.never
        }
        .guarantee(IO.println("Agent shutting down"))
    yield ()
