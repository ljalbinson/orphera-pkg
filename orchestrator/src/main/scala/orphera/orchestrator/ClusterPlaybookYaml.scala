// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.syntax.all.*
import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

object ClusterPlaybookYaml:

  private def decodeHealthCheck(c: HCursor): Either[DecodingFailure, HealthCheck] =
    for
      onNode <- c.get[String]("on_node")
      pollInterval <- c.getOrElse[Int]("poll_interval")(5)
      timeout <- c.getOrElse[Int]("timeout")(120)
      commandOpt <- c.get[Option[List[String]]]("command")
      result <- commandOpt match
        case Some(command) =>
          Right(HealthCheck.Command(onNode, command, pollInterval, timeout))
        case None =>
          for
            sentinelPath <- c.get[String]("sentinel_path")
            expectedSha256 <- c.get[String]("expected_sha256")
          yield HealthCheck.Sentinel(onNode, sentinelPath, expectedSha256, pollInterval, timeout)
    yield result

  private def decodeStage(c: HCursor): Either[DecodingFailure, Stage] =
    for
      name <- c.get[String]("name")
      nodes <- c.get[List[String]]("nodes")
      taskCursors <- c.downField("tasks").as[List[Json]]
      tasks <- taskCursors.traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
      waitForOpt <- c.get[Option[Json]]("wait_for")
      waitFor <- waitForOpt match
        case None       => Right(None)
        case Some(json) => decodeHealthCheck(json.hcursor).map(Some(_))
    yield Stage(name, nodes, tasks, waitFor)

  private def decodeClusterPlaybook(
      c: HCursor
  ): Either[DecodingFailure, ClusterPlaybook] =
    for
      name <- c.get[String]("name")
      stageCursors <- c.downField("stages").as[List[Json]]
      stages <- stageCursors.traverse(j => decodeStage(j.hcursor))
    yield ClusterPlaybook(name, stages)

  def load(path: String): Either[String, ClusterPlaybook] =
    for
      content <- scala.util
        .Try(Files.readString(Paths.get(path)))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      playbook <- decodeClusterPlaybook(json.hcursor).left.map(_.getMessage)
    yield playbook
