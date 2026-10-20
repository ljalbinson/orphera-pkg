// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference

/** Where the inventory and the per-VM configuration files live, as URIs.
  *
  * A location is a plain path (`inventory.yaml`, `/etc/orphera/inventory.yaml`)
  * or a URI (`file:///etc/orphera/inventory.yaml`). Only `file:` and plain
  * paths can be read today; `Sources.register` is where another scheme (a
  * database, a dynamic connector) plugs in, and nothing else needs to change.
  *
  * Each of the two locations is taken from, in order:
  *   1. the global option before the command: `-I`/`--inventory <uri>` and
  *      `-c`/`--config-dir <uri>`
  *   1. the environment: `ORPHERA_INVENTORY` and `ORPHERA_CONFIG_DIR`
  *   1. the default: `inventory.yaml` and `config`
  */
object Locations:

  val DefaultInventory = "inventory.yaml"
  val DefaultConfigDir = "config"

  final case class Overrides(
      inventory: Option[String],
      configDir: Option[String]
  )

  private val current =
    new AtomicReference[Overrides](Overrides(None, None))

  /** Takes the global options off the front of the arguments: they go before
    * the command, as `git -C` does, so `orphera -I file:///x/inv.yaml -c
    * file:///x/cfg version`. After the command a `-c` would be
    * indistinguishable from the command's own (`orphera run sh -c ...`).
    */
  def extract(args: List[String]): Either[String, (Overrides, List[String])] =
    def go(
        rest: List[String],
        acc: Overrides
    ): Either[String, (Overrides, List[String])] =
      rest match
        case ("-I" | "--inventory") :: value :: tl if !value.startsWith("-") =>
          go(tl, acc.copy(inventory = Some(value)))
        case ("-c" | "--config-dir") :: value :: tl if !value.startsWith("-") =>
          go(tl, acc.copy(configDir = Some(value)))
        case (flag @ ("-I" | "--inventory" | "-c" | "--config-dir")) :: _ =>
          Left(s"$flag needs a location (a path or a URI)")
        case other => Right((acc, other))
    go(args, Overrides(None, None))

  def install(overrides: Overrides): Unit = current.set(overrides)

  private def fromEnv(name: String): Option[String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  def inventory: String =
    current
      .get()
      .inventory
      .orElse(fromEnv("ORPHERA_INVENTORY"))
      .getOrElse(DefaultInventory)

  def configDir: String =
    current
      .get()
      .configDir
      .orElse(fromEnv("ORPHERA_CONFIG_DIR"))
      .getOrElse(DefaultConfigDir)

  /** The environment a child process (a playbook script, a pipeline step)
    * needs so it looks in the same places: the options given on this command
    * line, which the environment it inherits does not have.
    */
  def childEnv: Map[String, String] =
    val c = current.get()
    (c.inventory.toList.map("ORPHERA_INVENTORY" -> _) ++
      c.configDir.toList.map("ORPHERA_CONFIG_DIR" -> _)).toMap

  /** What `--config <value>` refers to. A name with no `/`, no scheme and no
    * `.yaml`/`.yml` ending (`tst0`) is a file of the config directory; anything
    * else (`config/tst0.yaml`, `/x/y.yaml`, `file:///x/y.yaml`) is taken as it
    * is, as before.
    */
  def resolveConfig(value: String): String =
    val isName = !value.contains("/") && !value.contains(":") &&
      !value.endsWith(".yaml") && !value.endsWith(".yml")
    if isName then s"${configDir.stripSuffix("/")}/$value.yaml" else value

  /** The short name of a config for output and log names: the last path
    * segment without its extension (`file:///x/tst3.yaml` -> `tst3`).
    */
  def label(config: String): String =
    val last = config.takeWhile(c => c != '?' && c != '#').split('/').lastOption
    val name = last.filter(_.nonEmpty).getOrElse(config)
    val dot = name.lastIndexOf('.')
    if dot > 0 then name.substring(0, dot) else name

/** Reads the text behind a location. */
object Sources:

  type Reader = String => Either[String, String]

  private val SchemePattern = "^([A-Za-z][A-Za-z0-9+.-]+):.*$".r

  private val readers = new AtomicReference[Map[String, Reader]](
    Map("file" -> readFile)
  )

  /** Adds the reader for a URI scheme (`postgres`, `https`, ...). */
  def register(scheme: String, reader: Reader): Unit =
    readers.set(readers.get() + (scheme.toLowerCase -> reader))

  /** The scheme of a location, or None for a plain path. */
  def schemeOf(location: String): Option[String] =
    location match
      case SchemePattern(scheme) => Some(scheme.toLowerCase)
      case _                     => None

  def read(location: String): Either[String, String] =
    schemeOf(location) match
      case None         => readPath(location)
      case Some(scheme) =>
        readers.get().get(scheme) match
          case Some(reader) => reader(location)
          case None         =>
            Left(
              s"unsupported location scheme '$scheme' in $location " +
                s"(supported: ${readers.get().keys.toList.sorted.mkString(", ")})"
            )

  private def readPath(path: String): Either[String, String] =
    scala.util
      .Try(Files.readString(Paths.get(path), UTF_8))
      .toEither
      .left
      .map(e => s"cannot read $path: ${e.getMessage}")

  private def readFile(location: String): Either[String, String] =
    scala.util
      .Try(new URI(location))
      .toEither
      .left
      .map(e => s"not a valid URI: $location (${e.getMessage})")
      .flatMap { uri =>
        if Option(uri.getHost).exists(_.nonEmpty) then
          Left(
            s"file: URI with a host is not supported: $location (use file:///path)"
          )
        else readPath(uri.getPath)
      }
