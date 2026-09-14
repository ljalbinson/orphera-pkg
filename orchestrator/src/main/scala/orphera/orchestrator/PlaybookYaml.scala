// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.syntax.all.*
import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

object PlaybookYaml:

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
            .map(_ =>
              DecodingFailure(
                s"Invalid mode: $modeStr (expected octal, e.g. 0644)",
                body.history
              )
            )
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
        Left(
          DecodingFailure(
            s"Invalid when condition: $str (expected e.g. os_id == \"ubuntu\")",
            Nil
          )
        )

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

  private def decodePlaybook(c: HCursor): Either[DecodingFailure, Playbook] =
    for
      name <- c.get[String]("name")
      nodes <- c.get[List[String]]("nodes")
      taskCursors <- c.downField("tasks").as[List[Json]]
      tasks <- taskCursors.traverse(j => decodeNamedTask(j.hcursor))
    yield Playbook(name, nodes, tasks)

  def load(path: String): Either[String, Playbook] =
    for
      content <- scala.util
        .Try(Files.readString(Paths.get(path)))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      playbook <- decodePlaybook(json.hcursor).left.map(_.getMessage)
    yield playbook
