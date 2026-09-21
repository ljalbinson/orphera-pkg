// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*

object TaskYaml:

  def decodeTask(
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

      case "set_fact" =>
        for
          key <- body.get[String]("key")
          value <- body.get[String]("value")
        yield Task.SetFact(key, value)

      case "run_command" =>
        for
          command <- body.get[List[String]]("command")
          timeout <- body.getOrElse[Int]("timeout")(60)
        yield Task.RunCommand(command, timeout)

      case "dump_facts" =>
        Right(Task.DumpFacts())

      case "distribute_file" =>
        for
          sourceNode <- body.get[String]("source_node")
          sourcePath <- body.get[String]("source_path")
          destPath <- body.get[String]("dest_path")
          owner <- body.getOrElse[String]("owner")("")
          group <- body.getOrElse[String]("group")("")
          modeStr <- body.getOrElse[String]("mode")("0")
          mode <- scala.util
            .Try(Integer.parseInt(modeStr, 8))
            .toEither
            .left
            .map(_ => DecodingFailure(s"Invalid mode: $modeStr", body.history))
        yield Task.DistributeFile(
          sourceNode,
          sourcePath,
          destPath,
          owner,
          group,
          mode
        )

      case "debug" =>
        body.get[String]("message").map(Task.Debug.apply)

      case other =>
        Left(DecodingFailure(s"Unknown task type: $other", body.history))

  def decodeCondition(str: String): Either[DecodingFailure, FactCondition] =
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

  def decodeNamedTask(c: HCursor): Either[DecodingFailure, NamedTask] =
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
