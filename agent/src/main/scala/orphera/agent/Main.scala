package orphera.agent

import cats.effect.*
import fs2.grpc.syntax.all.*
import io.grpc.{ServerBuilder, ServerInterceptors}
import orphera.common.AgentFs2Grpc

object Main extends IOApp.Simple:

  def run =
    AgentFs2Grpc
      .bindServiceResource[IO](new AgentServiceImpl)
      .flatMap { service =>
        ServerBuilder
          .forPort(50051)
          .addService(
            ServerInterceptors.intercept(service, new AuthInterceptor)
          )
          .resource[IO]
      }
      .use { server =>
        IO.println("Orphera Agent listening on 50051") >>
          IO.blocking(server.start()) >>
          IO.never
      }
      .guarantee(IO.println("Agent shutting down"))
