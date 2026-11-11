// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Path, Paths}

import cats.syntax.all.*

import io.circe.*
import io.circe.yaml.parser

object PlaybookYaml:

  private def decodePlaybook(
      c: HCursor,
      dir: Path,
      self: Path
  ): Either[String, Playbook] =
    for
      name <- c.get[String]("name").left.map(_.getMessage)
      nodes <- c.get[List[String]]("nodes").left.map(_.getMessage)
      taskJson <- c.downField("tasks").as[List[Json]].left.map(_.getMessage)
      handlerJson <- c
        .get[Option[List[Json]]]("handlers")
        .left
        .map(_.getMessage)
      expanded <- YamlIncludes.expand(taskJson, dir, List(self))
      tasks <- expanded.tasks
        .traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
        .left
        .map(_.getMessage)
      handlers <- (handlerJson.getOrElse(Nil) ++ expanded.handlers)
        .traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
        .left
        .map(e => s"handlers: ${e.getMessage}")
    yield Playbook(name, nodes, tasks, handlers)

  def load(path: String): Either[String, Playbook] =
    val file = Paths.get(path).toAbsolutePath.normalize
    for
      content <- scala.util
        .Try(Files.readString(file))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      playbook <- decodePlaybook(
        json.hcursor,
        Option(file.getParent).getOrElse(Paths.get(".")),
        scala.util.Try(file.toRealPath()).getOrElse(file)
      )
    yield playbook
