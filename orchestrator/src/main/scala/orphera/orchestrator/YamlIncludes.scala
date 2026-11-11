// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Path}

import scala.util.Try

import cats.syntax.all.*

import io.circe.*
import io.circe.yaml.parser

/** `- include: other.yaml` in a YAML playbook's (or a cluster-playbook
  * stage's) `tasks:`, replaced by the tasks of that file before the playbook
  * is decoded, so the rest of Orphera never sees an include.
  *
  * The included file is either a list of tasks, or a mapping with `tasks:`
  * and, optionally, `handlers:`; its handlers are added to the including
  * playbook's (or stage's). Its path is relative to the file that includes
  * it. An included file may include others; a file that includes itself,
  * directly or not, is an error. An include entry has only the `include:` key
  * (`when:`, `notify:` and the rest go on the included tasks themselves).
  */
object YamlIncludes:

  /** Tasks and handlers, as YAML not yet decoded. */
  final case class Expanded(tasks: List[Json], handlers: List[Json])

  private val MaxDepth = 20

  /** `entries` with every include replaced. `dir` is the directory of the file
    * they are in; `chain` the files being included, outermost first (for the
    * cycle check).
    */
  def expand(
      entries: List[Json],
      dir: Path,
      chain: List[Path]
  ): Either[String, Expanded] =
    entries
      .traverse(entry => expandEntry(entry, dir, chain))
      .map(parts => Expanded(parts.flatMap(_.tasks), parts.flatMap(_.handlers)))

  private def expandEntry(
      entry: Json,
      dir: Path,
      chain: List[Path]
  ): Either[String, Expanded] =
    entry.asObject.flatMap(_("include")) match
      case None          => Right(Expanded(List(entry), Nil))
      case Some(nameJson) =>
        for
          name <- nameJson.asString.toRight(
            s"include: needs a file name, not $nameJson"
          )
          others = entry.asObject.toList.flatMap(_.keys).filterNot(_ == "include")
          _ <- Either.cond(
            others.isEmpty,
            (),
            s"include: $name: an include has only the include: key, not ${others.mkString(", ")}"
          )
          file = dir.resolve(name).normalize
          real = Try(file.toRealPath()).getOrElse(file.toAbsolutePath)
          _ <- Either.cond(
            !chain.contains(real),
            (),
            s"include cycle: ${(chain :+ real).map(_.getFileName).mkString(" -> ")}"
          )
          _ <- Either.cond(
            chain.length < MaxDepth,
            (),
            s"include: $name: includes nested more than $MaxDepth deep"
          )
          text <- Try(Files.readString(file)).toEither.left.map(e =>
            s"include: cannot read $file: ${e.getMessage}"
          )
          json <- parser
            .parse(text)
            .left
            .map(e => s"include: $file is not valid YAML: ${e.getMessage}")
          parts <- partsOf(json, file)
          inner <- expand(
            parts._1,
            Option(file.getParent).getOrElse(dir),
            chain :+ real
          )
        yield Expanded(inner.tasks, parts._2 ++ inner.handlers)

  // An included file: a list of tasks, or a mapping with tasks: (and handlers:).
  private def partsOf(
      json: Json,
      file: Path
  ): Either[String, (List[Json], List[Json])] =
    json.asArray match
      case Some(tasks) => Right((tasks.toList, Nil))
      case None        =>
        val c = json.hcursor
        for
          tasks <- c
            .downField("tasks")
            .as[List[Json]]
            .left
            .map(_ => s"include: $file must be a list of tasks or have tasks:")
          handlers <- c
            .get[Option[List[Json]]]("handlers")
            .left
            .map(_ => s"include: $file: handlers: must be a list")
        yield (tasks, handlers.getOrElse(Nil))
