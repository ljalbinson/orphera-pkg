// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.syntax.all.*
import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

object InventoryYaml:

  private def decodeNode(c: HCursor): Either[DecodingFailure, Node] =
    for
      name <- c.get[String]("name")
      host <- c.get[String]("host")
      port <- c.getOrElse[Int]("port")(50051)
      vars <- c.getOrElse[Map[String, String]]("vars")(Map.empty)
    yield Node(name, host, port, vars)

  private def decodeGroup(c: HCursor): Either[DecodingFailure, Group] =
    for
      name <- c.get[String]("name")
      members <- c.get[List[String]]("members")
      vars <- c.getOrElse[Map[String, String]]("vars")(Map.empty)
    yield Group(name, members, vars)

  private def decodeInventory(
      c: HCursor
  ): Either[DecodingFailure, (List[Node], List[Group])] =
    for
      nodeCursors <- c.downField("nodes").as[List[Json]]
      nodes <- nodeCursors.traverse(j => decodeNode(j.hcursor))
      groupCursors <- c.getOrElse[List[Json]]("groups")(Nil)
      groups <- groupCursors.traverse(j => decodeGroup(j.hcursor))
    yield (nodes, groups)

  def load(path: String): Either[String, (List[Node], List[Group])] =
    for
      content <- scala.util
        .Try(Files.readString(Paths.get(path)))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      result <- decodeInventory(json.hcursor).left.map(_.getMessage)
    yield result
