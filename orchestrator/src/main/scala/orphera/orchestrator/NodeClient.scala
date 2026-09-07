package orphera.orchestrator

import cats.effect.*
import fs2.grpc.syntax.all.*
import io.grpc.{ManagedChannelBuilder, Metadata}
import orphera.common.*

object NodeClient:

  def installPackages(
      node: Node,
      packages: List[String],
      updateCache: Boolean,
      onEvent: Event => IO[Unit]
  ): IO[Unit] =
    ManagedChannelBuilder
      .forAddress(node.host, node.port)
      .usePlaintext()
      .resource[IO]
      .flatMap(AgentFs2Grpc.stubResource[IO])
      .use { stub =>
        val metadata = new Metadata()
        metadata.put(Auth.TokenKey, Auth.SharedToken)

        stub
          .install(InstallPackages(packages, updateCache), metadata)
          .evalMap(onEvent)
          .compile
          .drain
      }
