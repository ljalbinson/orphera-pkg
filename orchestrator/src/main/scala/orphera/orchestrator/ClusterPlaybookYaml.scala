// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.syntax.all.*
import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

object ClusterPlaybookYaml:

  // Reuses PlaybookYaml's private task/condition decoders would be
  // ideal, but they're private to that object — duplicated here at
  // the single-task level rather than restructuring PlaybookYaml's
  // visibility. Worth consolidating later if this drifts.
  private def decodeTask(
      taskKey: String,
      body: ACursor
  ): Either[DecodingFailure, Task] =
    taskKey match
      case "install" =>
        for
          packages <- body.get[List[String]]("packages")
          updateCache <- body.getOrElse[Boolean]("update_cache")(false)
        yield Task.Install(packages, updateCache)
      case "remove" =>
        for
          packages <- body.get[List[String]]("packages")
          purge <- body.getOrElse[Boolean]("purge")(false)
        yield Task.Remove(packages, purge)
      case "autoremove" =>
        body.getOrElse[Boolean]("purge")(false).map(Task.AutoRemove.apply)
      case "copy" =>
        for
          src <- body.get[String]("src")
          dest <- body.get[String]("dest")
          owner <- body.getOrElse[String]("owner")("")
          group <- body.getOrElse[String]("group")("")
          modeStr <- body.getOrElse[String]("mode")("0")
          vars <- body.getOrElse[Map[String, String]]("vars")(Map.empty)
          mode <- scala.util
            .Try(Integer.parseInt(modeStr, 8))
            .toEither
            .left
            .map(_ => DecodingFailure(s"Invalid mode: $modeStr", body.history))
        yield Task.Copy(src, dest, owner, group, mode, vars)
      case "network_apply" =>
        body.getOrElse[Int]("timeout")(60).map(Task.NetworkApply.apply)
      case "reboot" =>
        for
          delay <- body.getOrElse[Int]("delay")(5)
          wait <- body.getOrElse[Boolean]("wait")(false)
          waitTimeout <- body.getOrElse[Int]("wait_timeout")(300)
        yield Task.Reboot(delay, wait, waitTimeout)
      case other =>
        Left(DecodingFailure(s"Unknown task type: $other", body.history))

  private def decodeCondition(
      str: String
  ): Either[DecodingFailure, FactCondition] =
    val negate = str.contains("!=")
    val sep = if negate then "!=" else "=="
    str.split(sep, 2).map(_.trim) match
      case Array(key, rawValue) =>
        Right(
          FactCondition(
            key,
            rawValue.stripPrefix("\"").stripSuffix("\""),
            negate
          )
        )
      case _ =>
        Left(DecodingFailure(s"Invalid when condition: $str", Nil))

  private def decodeNamedTask(c: HCursor): Either[DecodingFailure, NamedTask] =
    for
      name <- c.get[String]("name")
      keys = c.keys
        .map(_.toList)
        .getOrElse(Nil)
        .filterNot(k => k == "name" || k == "when")
      taskKey <- keys.headOption.toRight(
        DecodingFailure(s"Task '$name' has no task type key", c.history)
      )
      task <- decodeTask(taskKey, c.downField(taskKey))
      whenStr <- c.get[Option[String]]("when")
      when <- whenStr match
        case None      => Right(None)
        case Some(str) => decodeCondition(str).map(Some(_))
    yield NamedTask(name, task, when)

  private def decodeHealthCheck(
      c: HCursor
  ): Either[DecodingFailure, HealthCheck] =
    for
      onNode <- c.get[String]("on_node")
      sentinelPath <- c.get[String]("sentinel_path")
      expectedSha256 <- c.get[String]("expected_sha256")
      pollInterval <- c.getOrElse[Int]("poll_interval")(5)
      timeout <- c.getOrElse[Int]("timeout")(120)
    yield HealthCheck(
      onNode,
      sentinelPath,
      expectedSha256,
      pollInterval,
      timeout
    )

  private def decodeStage(c: HCursor): Either[DecodingFailure, Stage] =
    for
      name <- c.get[String]("name")
      nodes <- c.get[List[String]]("nodes")
      taskCursors <- c.downField("tasks").as[List[Json]]
      tasks <- taskCursors.traverse(j => decodeNamedTask(j.hcursor))
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
