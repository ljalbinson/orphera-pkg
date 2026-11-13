// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Paths}

import cats.syntax.all.*

import io.circe.*
import io.circe.yaml.parser

/** A declarative description read from YAML (step 4 of docs/APPLY-DESIGN.md):
  * the same resources as `Resources.*` in Scala, for descriptions that need
  * no code.
  *
  * {{{
  * name: chrony
  * nodes: [tst8]
  * resources:
  *   - package: chrony
  *   - file: /etc/chrony.conf
  *     source: files/chrony.conf.mustache   # or content: |
  *     mode: "0644"
  *     notify: [service[chronyd]]
  *   - service: chronyd
  * }}}
  *
  * Each resource has one kind key (`file`, `directory`, `symlink`, `package`,
  * `service`, `user`, `group`, `command`) naming it, the options of that kind,
  * and `requires` / `notify` (resource ids, as in `file[/etc/x]`). `state:
  * absent` removes a file, directory, symlink, package, user or group. A
  * `source` path is relative to the directory orphera runs in, as `src:` is in
  * a YAML playbook and `fileFrom` in Scala. An
  * unknown key is an error, so a misspelt option is not silently ignored.
  */
final case class ApplyDescription(name: String, nodes: List[String], resources: List[ApplyResource])

object ApplyYaml:

  private val Kinds = List("file", "directory", "symlink", "package", "service", "user", "group", "command")

  private val Common = Set("requires", "notify", "state")

  // The options each kind takes, besides its kind key and the common ones.
  private val Options: Map[String, Set[String]] = Map(
    "file" -> Set("content", "source", "vars", "mode", "owner", "group"),
    "directory" -> Set("mode", "owner", "group"),
    "symlink" -> Set("target"),
    "package" -> Set("version", "update_cache"),
    "service" -> Set("enabled", "running"),
    "user" -> Set("uid", "group", "groups", "home", "shell", "system"),
    "group" -> Set("gid", "system"),
    "command" -> Set("run", "only_if", "unless", "creates", "on_change_only", "timeout")
  )

  def load(path: String): Either[String, ApplyDescription] =
    for
      text <- scala.util.Try(Files.readString(Paths.get(path))).toEither.left.map(e => s"$path: ${e.getMessage}")
      json <- parser.parse(text).left.map(e => s"$path: ${e.getMessage}")
      description <- decode(json).left.map(e => s"$path: $e")
    yield description

  /** `json` as a description. */
  def decode(json: Json): Either[String, ApplyDescription] =
    val c = json.hcursor
    for
      top <- json.asObject.toRight("expected a mapping with name, nodes and resources")
      _ <- top.keys.filterNot(Set("name", "nodes", "resources")).toList match
        case Nil     => Right(())
        case unknown => Left(s"unknown key(s) ${unknown.mkString(", ")} (expected name, nodes, resources)")
      name <- c.get[String]("name").left.map(_ => "name: a string is needed")
      nodes <- c.get[Option[List[String]]]("nodes").left.map(_ => "nodes: a list of node names").map(_.getOrElse(Nil))
      items <- c.get[List[Json]]("resources").left.map(_ => "resources: a list is needed")
      resources <- items.zipWithIndex.traverse((j, i) => resource(j).left.map(e => s"resource ${i + 1}: $e"))
    yield ApplyDescription(name, nodes, resources)

  private def resource(json: Json): Either[String, ApplyResource] =
    for
      obj <- json.asObject.toRight("expected a mapping such as `file: /etc/x`")
      // `group` is also an option of file, directory and user: there it is not the kind.
      present = Kinds.filter(obj.contains)
      kind <- (if present.size > 1 then present.filterNot(k => k == "group" && present.exists(o => Options.getOrElse(o, Set.empty).contains("group"))) else present) match
        case List(k) => Right(k)
        case Nil     => Left(s"no kind: give one of ${Kinds.mkString(", ")}")
        case many    => Left(s"more than one kind (${many.mkString(", ")})")
      allowed = Options.getOrElse(kind, Set.empty) ++ Common + kind
      _ <- obj.keys.filterNot(allowed).toList match
        case Nil     => Right(())
        case unknown => Left(s"$kind: unknown option(s) ${unknown.mkString(", ")}")
      f = new Fields(obj, kind)
      key <- f.str(kind).flatMap(k => if k.trim.isEmpty then Left(s"$kind: a name is needed") else Right(k))
      absent <- f.optStr("state").flatMap {
        case None | Some("present") => Right(false)
        case Some("absent")         => Right(true)
        case Some(other)            => Left(s"$kind[$key]: state must be present or absent, not '$other'")
      }
      requires <- f.ids("requires")
      notify <- f.ids("notify")
      base <- build(kind, key, absent, f)
    yield base.afterIds(requires*).notifiesIds(notify*)

  private def build(kind: String, key: String, absent: Boolean, f: Fields): Either[String, ApplyResource] =
    import Resources.*
    def onlyPresent(options: String*): Either[String, Unit] =
      options.filter(f.has).toList match
        case Nil  => Right(())
        case used => Left(s"$kind[$key]: state absent takes no ${used.mkString(", ")}")
    kind match
      case "file" if absent => onlyPresent("content", "source", "vars", "mode", "owner", "group").as(absentFile(key))
      case "file"           =>
        for
          mode <- f.mode
          owner <- f.optStr("owner")
          group <- f.optStr("group")
          content <- f.optStr("content")
          source <- f.optStr("source")
          vars <- f.vars
          r <- (content, source) match
            case (Some(text), None) if vars.isEmpty =>
              Right(file(key, text, mode, owner.getOrElse(""), group.getOrElse("")))
            case (Some(_), None) => Left(s"file[$key]: vars go with source, not content")
            case (None, Some(src)) =>
              Right(fileFrom(key, src, vars, mode, owner.getOrElse(""), group.getOrElse("")))
            case (Some(_), Some(_)) => Left(s"file[$key]: give content or source, not both")
            case (None, None)       => Left(s"file[$key]: give content or source (or state: absent)")
        yield r
      case "directory" if absent => onlyPresent("mode", "owner", "group").as(absentDirectory(key))
      case "directory"           =>
        for
          mode <- f.mode
          owner <- f.optStr("owner")
          group <- f.optStr("group")
        yield directory(key, mode, owner.getOrElse(""), group.getOrElse(""))
      case "symlink" if absent => onlyPresent("target").as(absentSymlink(key))
      case "symlink"           => f.str("target").map(t => symlink(key, t))
      case "package" if absent => onlyPresent("version", "update_cache").as(absentPkg(key))
      case "package"           =>
        for
          version <- f.optStr("version")
          update <- f.bool("update_cache")
        yield pkg(key, version.getOrElse(""), update)
      case "service" =>
        if absent then Left(s"service[$key]: state absent is not a thing for a service: use enabled: false, running: false")
        else
          for
            enabled <- f.tristate("enabled")
            running <- f.tristate("running")
          yield service(key, enabled, running)
      case "user" if absent => onlyPresent("uid", "group", "groups", "home", "shell", "system").as(absentUser(key))
      case "user"           =>
        for
          uid <- f.optInt("uid")
          group <- f.optStr("group")
          groups <- f.strList("groups")
          home <- f.optStr("home")
          shell <- f.optStr("shell")
          system <- f.bool("system")
        yield user(key, uid.getOrElse(-1), group.getOrElse(""), groups, home.getOrElse(""), shell.getOrElse(""), system)
      case "group" if absent => onlyPresent("gid", "system").as(absentGroup(key))
      case "group"           =>
        for
          gid <- f.optInt("gid")
          system <- f.bool("system")
        yield group(key, gid.getOrElse(-1), system)
      case _ => // command
        if absent then Left(s"command[$key]: a command has no state")
        else
          for
            run <- f.str("run")
            onlyIf <- f.optStr("only_if")
            unless <- f.optStr("unless")
            creates <- f.optStr("creates")
            onChange <- f.bool("on_change_only")
            timeout <- f.optInt("timeout")
          yield command(key, run, onlyIf.getOrElse(""), unless.getOrElse(""), creates.getOrElse(""), onChange, timeout.getOrElse(300))

  // Typed reads of one resource's options, with errors that name the resource and option.
  private final class Fields(obj: JsonObject, kind: String):
    private def at(k: String): String = s"$kind: $k"
    def has(k: String): Boolean = obj.contains(k)

    def str(k: String): Either[String, String] =
      obj(k) match
        case None    => Left(s"${at(k)} is needed")
        case Some(j) => scalar(j).toRight(s"${at(k)}: a string is needed")

    def optStr(k: String): Either[String, Option[String]] =
      obj(k) match
        case None                 => Right(None)
        case Some(j) if j.isNull  => Right(None)
        case Some(j)              => scalar(j).map(Some(_)).toRight(s"${at(k)}: a string is needed")

    // Strings, and numbers and booleans as written (a package version 2.0 is "2.0").
    private def scalar(j: Json): Option[String] =
      j.asString.orElse(j.asNumber.map(_.toString)).orElse(j.asBoolean.map(_.toString))

    def bool(k: String): Either[String, Boolean] =
      obj(k) match
        case None    => Right(false)
        case Some(j) => j.asBoolean.toRight(s"${at(k)}: true or false")

    def tristate(k: String): Either[String, Option[Boolean]] =
      obj(k) match
        case None                          => Right(Some(true))
        case Some(j) if j.asString.contains("any") => Right(None)
        case Some(j)                       => j.asBoolean.map(Some(_)).toRight(s"${at(k)}: true, false or any")

    def optInt(k: String): Either[String, Option[Int]] =
      obj(k) match
        case None    => Right(None)
        case Some(j) => j.asNumber.flatMap(_.toInt).map(Some(_)).toRight(s"${at(k)}: a whole number is needed")

    def strList(k: String): Either[String, List[String]] =
      obj(k) match
        case None                   => Right(Nil)
        case Some(j) if j.isString  => Right(j.asString.toList)
        case Some(j)                => j.as[List[String]].left.map(_ => s"${at(k)}: a list of strings is needed")

    def ids(k: String): Either[String, List[String]] = strList(k)

    // A mode must be a string: YAML reads 0644 unquoted as the number 420.
    def mode: Either[String, String] =
      obj("mode") match
        case None                  => Right("")
        case Some(j) if j.isString => Right(j.asString.getOrElse(""))
        case Some(_)               => Left(s"""${at("mode")}: quote it ("0644"): unquoted, YAML may read it as a decimal number""")

    def vars: Either[String, Map[String, String]] =
      obj("vars") match
        case None    => Right(Map.empty)
        case Some(j) =>
          j.asObject
            .toRight(s"${at("vars")}: a mapping is needed")
            .flatMap(_.toList.traverse((k, v) => scalar(v).map(k -> _).toRight(s"${at("vars")}: $k must be a string")))
            .map(_.toMap)
