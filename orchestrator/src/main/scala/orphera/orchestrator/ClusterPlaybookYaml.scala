// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Path, Paths}

import cats.syntax.all.*

import io.circe.*
import io.circe.yaml.parser

object ClusterPlaybookYaml:

  private def decodeHealthCheck(
      c: HCursor
  ): Either[DecodingFailure, HealthCheck] =
    for
      pollInterval <- c.getOrElse[Int]("poll_interval")(5)
      timeout <- c.getOrElse[Int]("timeout")(120)
      nodesOpt <- c.get[Option[List[String]]]("nodes")
      result <- nodesOpt match

        case Some(nodes) =>
          // Plural "nodes" + "required" -> a quorum check.
          for
            command <- c.get[List[String]]("command")
            required <- c.get[Int]("required")
          yield HealthCheck.Quorum(
            nodes,
            command,
            required,
            pollInterval,
            timeout
          )

        case None =>
          for
            onNode <- c.get[String]("on_node")
            // "ssh_user" present -> a plain-SSH liveness check
            // (HealthCheck.Ssh), checked before "command" since it also
            // accepts an optional remote_command and would otherwise be
            // ambiguous with the agent-RPC Command case below. See
            // HealthCheck.Ssh's doc comment: this is for a brand-new VM
            // that can't possibly be running the Orphera agent yet.
            sshUserOpt <- c.get[Option[String]]("ssh_user")
            r <- sshUserOpt match
              case Some(sshUser) =>
                // `host` is required here, separately from `on_node` —
                // Ssh checks don't look `on_node` up in inventory.yaml at
                // all (see HealthCheck.Ssh's doc comment), so the actual
                // address to connect to has to come from the YAML itself.
                for
                  host <- c.get[String]("host")
                  sshKeyPath <- c.get[Option[String]]("ssh_key_path")
                  remoteCommand <- c.getOrElse[String]("remote_command")("true")
                yield HealthCheck.Ssh(
                  onNode,
                  host,
                  sshUser,
                  sshKeyPath,
                  remoteCommand,
                  pollInterval,
                  timeout
                )
              case None =>
                for
                  commandOpt <- c.get[Option[List[String]]]("command")
                  r2 <- commandOpt match
                    case Some(command) =>
                      Right(
                        HealthCheck
                          .Command(onNode, command, pollInterval, timeout)
                      )
                    case None =>
                      for
                        sentinelPath <- c.get[String]("sentinel_path")
                        expectedSha256 <- c.get[String]("expected_sha256")
                      yield HealthCheck.Sentinel(
                        onNode,
                        sentinelPath,
                        expectedSha256,
                        pollInterval,
                        timeout
                      )
                yield r2
          yield r
    yield result

  // `include:` in a stage's tasks is expanded relative to `dir`, the
  // playbook's directory (YamlIncludes).
  private def decodeStage(
      c: HCursor,
      dir: Path,
      self: Path
  ): Either[String, Stage] =
    for
      name <- c.get[String]("name").left.map(_.getMessage)
      nodes <- c.get[List[String]]("nodes").left.map(_.getMessage)
      taskJson <- c.downField("tasks").as[List[Json]].left.map(_.getMessage)
      handlerJson <- c
        .get[Option[List[Json]]]("handlers")
        .left
        .map(_.getMessage)
      expanded <- YamlIncludes
        .expand(taskJson, dir, List(self))
        .left
        .map(e => s"stage '$name': $e")
      tasks <- expanded.tasks
        .traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
        .left
        .map(_.getMessage)
      handlers <- (handlerJson.getOrElse(Nil) ++ expanded.handlers)
        .traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
        .left
        .map(e => s"stage '$name': handlers: ${e.getMessage}")
      waitForOpt <- c.get[Option[Json]]("wait_for").left.map(_.getMessage)
      waitFor <- waitForOpt
        .traverse(json => decodeHealthCheck(json.hcursor))
        .left
        .map(_.getMessage)
    yield Stage(name, nodes, tasks, waitFor, handlers)

  private def decodeClusterPlaybook(
      c: HCursor,
      dir: Path,
      self: Path
  ): Either[String, ClusterPlaybook] =
    for
      name <- c.get[String]("name").left.map(_.getMessage)
      stageCursors <- c.downField("stages").as[List[Json]].left.map(_.getMessage)
      stages <- stageCursors.traverse(j => decodeStage(j.hcursor, dir, self))
    yield ClusterPlaybook(name, stages)

  def load(path: String): Either[String, ClusterPlaybook] =
    val file = Paths.get(path).toAbsolutePath.normalize
    for
      content <- scala.util
        .Try(Files.readString(file))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      playbook <- decodeClusterPlaybook(
        json.hcursor,
        Option(file.getParent).getOrElse(Paths.get(".")),
        scala.util.Try(file.toRealPath()).getOrElse(file)
      )
    yield playbook
