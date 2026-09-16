// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.syntax.all.*
import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

object PlaybookYaml:

  private def decodePlaybook(c: HCursor): Either[DecodingFailure, Playbook] =
    for
      name <- c.get[String]("name")
      nodes <- c.get[List[String]]("nodes")
      taskCursors <- c.downField("tasks").as[List[Json]]
      tasks <- taskCursors.traverse(j => TaskYaml.decodeNamedTask(j.hcursor))
    yield Playbook(name, nodes, tasks)

  def load(path: String): Either[String, Playbook] =
    for
      content <- scala.util.Try(Files.readString(Paths.get(path))).toEither.left.map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      playbook <- decodePlaybook(json.hcursor).left.map(_.getMessage)
    yield playbook
