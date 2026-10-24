// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

/** `orphera init-ca [--days N] [--name NAME]`: makes the certificate authority
  * that signs each node's certificate (`orphera bootstrap`, `issue-certs`), in
  * the directory of `ORPHERA_CERTS_DIR` (default `certs`): `ca.crt`, which the
  * orchestrator trusts, and `ca.key`, which must stay private.
  *
  * This is what `make certs` does, for an installation that has no checkout to
  * run `make` in. It refuses when a CA is already there, because replacing it
  * leaves every node with a certificate nobody trusts. It runs on this machine
  * only and sends nothing to a node.
  */
object CaInit:

  val DefaultDays = 3650
  val DefaultName = "Orphera CA"

  val Usage: String =
    "Usage: orphera init-ca [--days <n>] [--name <name>]   " +
      s"(makes ca.crt and ca.key in ${Installation.DefaultCertsDir}, or ORPHERA_CERTS_DIR; " +
      s"defaults: $DefaultDays days, name '$DefaultName')"

  final case class Options(days: Int, name: String)

  def parse(args: List[String]): Either[String, Options] =
    def go(rest: List[String], o: Options): Either[String, Options] =
      rest match
        case Nil                       => Right(o)
        case "--days" :: value :: tail =>
          value.toIntOption.filter(_ > 0) match
            case Some(n) => go(tail, o.copy(days = n))
            case None    =>
              Left(s"--days needs a positive number of days, got '$value'")
        case "--name" :: value :: tail =>
          // The name goes into an openssl -subj string, where '/' separates fields.
          if value.trim.isEmpty || value.contains('/') then
            Left("--name needs a name without '/'")
          else go(tail, o.copy(name = value.trim))
        case flag :: Nil if flag == "--days" || flag == "--name" =>
          Left(s"$flag needs a value")
        case other :: _ => Left(s"Unknown argument to init-ca: $other")
    go(args, Options(DefaultDays, DefaultName))

  /** The `openssl` command that makes a self-signed CA certificate and its key.
    */
  def opensslArgs(o: Options, keyFile: Path, certFile: Path): List[String] =
    List(
      "openssl",
      "req",
      "-x509",
      "-newkey",
      "rsa:4096",
      "-nodes",
      "-days",
      o.days.toString,
      "-subj",
      s"/CN=${o.name}",
      "-keyout",
      keyFile.toString,
      "-out",
      certFile.toString
    )

  /** Why no CA may be made in `dir`, or `None` when none is there. */
  def refusal(dir: Path, exists: Path => Boolean): Option[String] =
    val present = List("ca.crt", "ca.key").map(dir.resolve).filter(exists)
    if present.isEmpty then None
    else
      Some(
        s"${present.mkString(" and ")} already exist${
            if present.size == 1 then "s" else ""
          }; nothing was changed. " +
          "To renew node certificates: orphera issue-certs. To start again with a new CA, move the old one away " +
          "(every node then needs a certificate from the new CA: orphera issue-certs)"
      )

  private def runLocal(command: List[String]): Either[String, (Int, String)] =
    Try {
      val p = new ProcessBuilder(command*).redirectErrorStream(true).start()
      p.getOutputStream.close()
      val out = new String(p.getInputStream.readAllBytes(), UTF_8)
      (p.waitFor(), out)
    }.toEither.left.map(e =>
      s"cannot run ${command.headOption.getOrElse("")}: ${e.getMessage} (it is needed to make the CA)"
    )

  /** Makes the CA in `dir`. Returns its SHA-256 fingerprint. A failure leaves
    * no half-made files behind.
    */
  def create(dir: Path, o: Options): Either[String, String] =
    refusal(dir, p => Files.exists(p)) match
      case Some(why) => Left(why)
      case None      =>
        val key = dir.resolve("ca.key")
        val cert = dir.resolve("ca.crt")
        def cleanUp(): Unit =
          List(key, cert).foreach(p => Files.deleteIfExists(p))
        val result =
          for
            _ <- Try(Files.createDirectories(dir)).toEither.left
              .map(e => s"cannot create $dir: ${e.getMessage}")
            ran <- runLocal(opensslArgs(o, key, cert))
            _ <- Either.cond(
              ran._1 == 0,
              (),
              s"openssl could not make the CA: ${ran._2.trim}"
            )
            _ <- Try(
              Files.setPosixFilePermissions(
                key,
                PosixFilePermissions.fromString("rw-------")
              )
            ).toEither.left.map(e => s"cannot protect $key: ${e.getMessage}")
            fp <- runLocal(
              List(
                "openssl",
                "x509",
                "-noout",
                "-fingerprint",
                "-sha256",
                "-in",
                cert.toString
              )
            )
          yield fp._2.trim
            .stripPrefix("sha256 Fingerprint=")
            .stripPrefix("SHA256 Fingerprint=")
        if result.isLeft then cleanUp()
        result

  def run(args: List[String]): Int =
    parse(args) match
      case Left(err) =>
        System.err.println(s"Error: $err")
        System.err.println(Usage)
        2
      case Right(options) =>
        val dir = Paths.get(Installation.certsDirOf(key => sys.env.get(key)))
        create(dir, options) match
          case Left(err) =>
            System.err.println(s"Error: $err")
            1
          case Right(fingerprint) =>
            println(
              s"Made ${dir.resolve("ca.crt")} and ${dir.resolve("ca.key")} " +
                s"(${options.name}, valid ${options.days} days)."
            )
            println(s"SHA-256 fingerprint: $fingerprint")
            println(
              s"Keep ${dir.resolve("ca.key")} private. Next: orphera bootstrap --nodes <nodes> --ssh-user <user>"
            )
            0
