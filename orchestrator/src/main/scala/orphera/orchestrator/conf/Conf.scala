// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import orphera.orchestrator.Resource

// SPIKE. Typed OpenStack configuration files.
//
// Every OpenStack service declares its options in code with oslo.config: the
// section, the name, the type, the default, the allowed values and any old names.
// The online configuration reference is generated from the same declarations. A
// `Catalogue` holds that list for one service and release, and a `ConfFile` is a
// set of values checked against it, so a mistyped key, a section that does not
// exist, a number given where a switch is expected, or a value outside the
// allowed list fails when the playbook is built, not when the service starts or,
// worse, silently ignores the line.
//
// The catalogue is meant to be generated from the Kolla image we deploy
// (`oslo-config-generator --format json`), so it cannot drift from the release.
// Until that loader exists, a catalogue is built by hand; the one in
// ConfCheck is a test fixture, not the real placement options.

/** The type of an option, as oslo.config declares it. */
enum OptType:
  case Text
  case Integer
  case Flag
  case Real
  case PortNumber
  case Items
  case Dict
  case Other(name: String)
  case Choice(values: List[String])

  def describe: String = this match
    case Text           => "a string"
    case Integer        => "an integer"
    case Flag           => "true or false"
    case Real           => "a number"
    case PortNumber     => "a port number (0-65535)"
    case Items          => "a list"
    case Dict           => "a dict (key:value,key:value)"
    case Other(name)    => name
    case Choice(values) => s"one of ${values.mkString(", ")}"

/** A value to put in a configuration file. */
enum Value:
  case Str(s: String)
  case Whole(n: Long)
  case Dec(d: Double)
  case Bool(b: Boolean)
  case Many(items: List[String])

  /** As written to the file. */
  def render: String = this match
    case Str(s)      => s
    case Whole(n)    => n.toString
    case Dec(d)      => d.toString
    case Bool(b)     => b.toString
    case Many(items) => items.mkString(",")

  def kind: String = this match
    case Str(_)   => "a string"
    case Whole(_) => "an integer"
    case Dec(_)   => "a decimal"
    case Bool(_)  => "true or false"
    case Many(_)  => "a list"

/** One declared option. `deprecated` are old names still accepted by the service
  * but which a new file should not use; `secret` options are never printed in
  * messages.
  */
final case class OptionSpec(
    section: String,
    name: String,
    tpe: OptType,
    default: Option[String] = None,
    deprecated: List[String] = Nil,
    secret: Boolean = false
)

/** The options of a service (one or more oslo.config namespaces) at a release.
  *
  * oslo.config accepts `-` and `_` as the same character in an option name
  * (`log-dir` is written `log_dir` in files), so lookups ignore the difference.
  *
  * `openSections` are sections whose options are not all declared in the image:
  * keystonemiddleware's `[keystone_authtoken]` takes the options of the auth
  * plugin named by `auth_type` (`auth_url`, `username`, `password` ...), which
  * are loaded at run time and so are not in the generator's output. In an open
  * section a name the catalogue does not know is accepted, unless it is within
  * two edits of one it does know (then it is refused as a typo); a name it does
  * know is still checked.
  */
final case class Catalogue(
    service: String,
    options: List[OptionSpec],
    openSections: Set[String] = Set.empty
):
  private def norm(name: String): String = name.replace('-', '_')

  private val byName: Map[(String, String), OptionSpec] =
    options.map(o => (o.section, norm(o.name)) -> o).toMap
  private val byOldName: Map[(String, String), OptionSpec] =
    options.flatMap(o => o.deprecated.map(d => (o.section, norm(d)) -> o)).toMap

  val sections: Set[String] = options.map(_.section).toSet

  def find(section: String, name: String): Option[OptionSpec] =
    byName.get((section, norm(name)))

  def findDeprecated(section: String, name: String): Option[OptionSpec] =
    byOldName.get((section, norm(name)))

  def optionsIn(section: String): List[String] =
    options.filter(_.section == section).map(_.name)

enum ConfError:
  case UnknownSection(section: String, suggestions: List[String])
  case UnknownOption(section: String, name: String, suggestions: List[String])
  case Deprecated(section: String, name: String, useInstead: String)
  case WrongType(section: String, name: String, expected: String, got: String)
  case NotAllowed(section: String, name: String, allowed: List[String])

  def message: String = this match
    case UnknownSection(s, sug) =>
      s"no section [$s]" + hint(sug.map(x => s"[$x]"))
    case UnknownOption(s, n, sug) =>
      s"no option $n in [$s]" + hint(sug)
    case Deprecated(s, n, use) =>
      s"$n in [$s] is a deprecated name; use $use"
    case WrongType(s, n, expected, got) =>
      s"$n in [$s] must be $expected, not $got"
    case NotAllowed(s, n, allowed) =>
      s"$n in [$s] must be one of ${allowed.mkString(", ")}"

  private def hint(suggestions: List[String]): String =
    if suggestions.isEmpty then "" else s" (did you mean ${suggestions.mkString(" or ")}?)"

/** A configuration file: values checked against a catalogue, kept in the order
  * they were first set. Setting a key again replaces its value in place.
  */
final case class ConfFile private (
    path: String,
    catalogue: Catalogue,
    entries: Vector[(String, String, Value)]
):
  def set(section: String, name: String, value: Value): Either[ConfError, ConfFile] =
    ConfFile.check(catalogue, section, name, value).map { _ =>
      val i = entries.indexWhere((s, n, _) => s == section && n == name)
      val e = (section, name, value)
      copy(entries = if i >= 0 then entries.updated(i, e) else entries :+ e)
    }

  def set(section: String, name: String, value: String): Either[ConfError, ConfFile] =
    set(section, name, Value.Str(value))
  def set(section: String, name: String, value: Int): Either[ConfError, ConfFile] =
    set(section, name, Value.Whole(value.toLong))
  def set(section: String, name: String, value: Boolean): Either[ConfError, ConfFile] =
    set(section, name, Value.Bool(value))

  /** The file as written: `[section]` blocks in order of first use. */
  def render: String =
    val sections = entries.map(_._1).distinct
    sections
      .map { s =>
        val lines = entries.collect { case (`s`, n, v) => s"$n = ${v.render}" }
        (s"[$s]" +: lines).mkString("\n")
      }
      .mkString("", "\n\n", "\n")

object ConfFile:
  def empty(path: String, catalogue: Catalogue): ConfFile =
    ConfFile(path, catalogue, Vector.empty)

  /** Builds a file from many settings, reporting every error at once. */
  def of(path: String, catalogue: Catalogue)(
      settings: (String, String, Value)*
  ): Either[List[ConfError], ConfFile] =
    val (errors, file) =
      settings.foldLeft((List.empty[ConfError], empty(path, catalogue))) {
        case ((errs, f), (s, n, v)) =>
          f.set(s, n, v) match
            case Right(next) => (errs, next)
            case Left(e)     => (errs :+ e, f)
      }
    if errors.isEmpty then Right(file) else Left(errors)

  private[conf] def check(
      c: Catalogue,
      section: String,
      name: String,
      value: Value
  ): Either[ConfError, OptionSpec] =
    if !c.sections.contains(section) then
      Left(ConfError.UnknownSection(section, close(section, c.sections.toList)))
    else
      c.find(section, name) match
        case Some(spec) => typed(spec, value).map(_ => spec)
        case None =>
          c.findDeprecated(section, name) match
            case Some(spec) => Left(ConfError.Deprecated(section, name, spec.name))
            case None if c.openSections.contains(section) =>
              // Accepted as a plugin option, unless it is a near miss of an option
              // the catalogue does know: that is a typo, not a plugin key.
              val near = c.optionsIn(section).filter(k => distance(name, k) <= 2).take(3)
              if near.isEmpty then Right(OptionSpec(section, name, OptType.Other("a plugin option")))
              else Left(ConfError.UnknownOption(section, name, near))
            case None =>
              Left(ConfError.UnknownOption(section, name, close(name, c.optionsIn(section))))

  private def typed(spec: OptionSpec, v: Value): Either[ConfError, Unit] =
    def wrong = Left(ConfError.WrongType(spec.section, spec.name, spec.tpe.describe, v.kind))
    (spec.tpe, v) match
      case (OptType.Text, Value.Str(_) | Value.Whole(_) | Value.Dec(_)) => Right(())
      case (OptType.Integer, Value.Whole(_))                             => Right(())
      case (OptType.Flag, Value.Bool(_))                                 => Right(())
      case (OptType.Real, Value.Whole(_) | Value.Dec(_))                 => Right(())
      case (OptType.PortNumber, Value.Whole(n)) =>
        if n >= 0 && n <= 65535 then Right(()) else wrong
      case (OptType.Items, Value.Many(_)) => Right(())
      case (OptType.Dict, Value.Str(_))   => Right(())
      case (OptType.Other(_), _)          => Right(())
      case (OptType.Choice(allowed), _) =>
        if allowed.contains(v.render) then Right(())
        else Left(ConfError.NotAllowed(spec.section, spec.name, allowed))
      case _ => wrong

  /** Up to three known names within two edits, or sharing a prefix. */
  private def close(word: String, known: List[String]): List[String] =
    known
      .map(k => k -> distance(word, k))
      .filter((k, d) => d <= 2 || k.startsWith(word) || word.startsWith(k))
      .sortBy(_._2)
      .map(_._1)
      .take(3)

  private def distance(a: String, b: String): Int =
    val prev = Array.tabulate(b.length + 1)(identity)
    for i <- 1 to a.length do
      var diag = prev(0)
      prev(0) = i
      for j <- 1 to b.length do
        val tmp = prev(j)
        prev(j) = math.min(
          math.min(prev(j) + 1, prev(j - 1) + 1),
          diag + (if a(i - 1) == b(j - 1) then 0 else 1)
        )
        diag = tmp
    prev(b.length)

  /** A configuration file is a Resource: `file.write` and `file.check`. Mode 0640:
    * these files hold passwords.
    */
  given Resource[ConfFile] with
    def label(f: ConfFile): String = f.path
    def path(f: ConfFile): String = f.path
    def content(f: ConfFile): String = f.render
    override def mode(f: ConfFile): Int = 416 // 0640
