// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.SecureRandom

import scala.util.Try

/** `orphera issue-client-cert [--days N] [--force]`: this orchestrator's own
  * client certificate, `client.crt` and `client.key` beside the CA (in
  * `ORPHERA_CERTS_DIR`, default `certs`), signed by `ca.key`. The orchestrator
  * presents it to every agent; an agent that has the CA as `tls/ca.crt`
  * (`orphera issue-certs --client-auth`) accepts only callers that do (mutual
  * TLS), so the token alone no longer gets in.
  *
  * Each orchestrator (scala0, scala1, ...) needs one; on one without `ca.key`,
  * copy `client.crt` and `client.key` from another (the key is the
  * orchestrator's identity, so keep it as private as `ca.key`). It runs on this
  * machine only and sends nothing to a node.
  */
object ClientCert:

  val DefaultDays = 825

  val Usage: String =
    "Usage: orphera issue-client-cert [--days <n>] [--force]   " +
      s"(makes client.crt and client.key in ${Installation.DefaultCertsDir}, or ORPHERA_CERTS_DIR, " +
      s"signed by ca.key; default $DefaultDays days; --force replaces an existing pair)"

  final case class Options(days: Int, force: Boolean)

  /** Whether this orchestrator has a client certificate and key. */
  def present: Boolean =
    Files.isRegularFile(Paths.get(Installation.clientCert)) &&
      Files.isRegularFile(Paths.get(Installation.clientKey))

  def parse(args: List[String]): Either[String, Options] =
    @scala.annotation.tailrec
    def go(rest: List[String], o: Options): Either[String, Options] =
      rest match
        case Nil                       => Right(o)
        case "--force" :: tail         => go(tail, o.copy(force = true))
        case "--days" :: value :: tail =>
          value.toIntOption.filter(_ > 0) match
            case Some(n) => go(tail, o.copy(days = n))
            case None    =>
              Left(s"--days needs a positive number of days, got '$value'")
        case other :: _ => Left(s"Unknown argument to issue-client-cert: $other")
    go(args, Options(DefaultDays, force = false))

  /** Why no client certificate may be made in `dir`, or None. */
  def refusal(
      dir: Path,
      force: Boolean,
      exists: Path => Boolean
  ): Option[String] =
    val missingCa = List("ca.crt", "ca.key").map(dir.resolve).filterNot(exists)
    val present = List("client.crt", "client.key").map(dir.resolve).filter(exists)
    if missingCa.nonEmpty then
      Some(
        s"${missingCa.mkString(" and ")} not found: the client certificate is signed by the CA " +
          "(orphera init-ca makes one; on an orchestrator without ca.key, copy client.crt and " +
          "client.key from one that has it)"
      )
    else if present.nonEmpty && !force then
      Some(
        s"${present.mkString(" and ")} already exist; nothing was changed (--force replaces them)"
      )
    else None

  private def runLocal(command: List[String]): Either[String, (Int, String)] =
    Try {
      val p = new ProcessBuilder(command*).redirectErrorStream(true).start()
      p.getOutputStream.close()
      val out = new String(p.getInputStream.readAllBytes(), UTF_8)
      (p.waitFor(), out)
    }.toEither.left.map(e =>
      s"cannot run ${command.headOption.getOrElse("")}: ${e.getMessage}"
    )

  private def ok(ran: (Int, String), what: String): Either[String, Unit] =
    Either.cond(ran._1 == 0, (), s"$what: ${ran._2.trim}")

  private def randomSerial(): String =
    val bytes = new Array[Byte](16)
    new SecureRandom().nextBytes(bytes)
    bytes(0) = (bytes(0) & 0x7f).toByte
    "0x" + bytes.map(b => f"$b%02x").mkString

  /** Makes the pair in `dir`; returns the certificate's subject. The new files
    * are made beside the old and moved into place only when both are made, so
    * a failure leaves the current pair (if any) as it was.
    */
  def create(dir: Path, o: Options): Either[String, String] =
    refusal(dir, o.force, p => Files.exists(p)) match
      case Some(why) => Left(why)
      case None      =>
        val host =
          Try(InetAddress.getLocalHost.getHostName).getOrElse("orchestrator")
        val subject = s"/CN=orphera-orchestrator@$host"
        val work = Files.createTempDirectory(dir, ".client-cert")
        val key = work.resolve("client.key")
        val csr = work.resolve("client.csr")
        val ext = work.resolve("client.ext")
        val cert = work.resolve("client.crt")
        val result =
          for
            _ <- Try(
              Files.writeString(
                ext,
                "keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=clientAuth\n"
              )
            ).toEither.left.map(e => s"cannot write $ext: ${e.getMessage}")
            req <- runLocal(
              List(
                "openssl",
                "req",
                "-new",
                "-newkey",
                "rsa:4096",
                "-nodes",
                "-subj",
                subject,
                "-keyout",
                key.toString,
                "-out",
                csr.toString
              )
            )
            _ <- ok(req, "openssl could not make the key")
            _ <- Try(
              Files.setPosixFilePermissions(
                key,
                PosixFilePermissions.fromString("rw-------")
              )
            ).toEither.left.map(e => s"cannot protect $key: ${e.getMessage}")
            signed <- runLocal(
              List(
                "openssl",
                "x509",
                "-req",
                "-in",
                csr.toString,
                "-CA",
                dir.resolve("ca.crt").toString,
                "-CAkey",
                dir.resolve("ca.key").toString,
                "-set_serial",
                randomSerial(),
                "-days",
                o.days.toString,
                "-extfile",
                ext.toString,
                "-out",
                cert.toString
              )
            )
            _ <- ok(signed, "openssl could not sign the certificate")
            _ <- Try {
              Files.move(
                key,
                dir.resolve("client.key"),
                StandardCopyOption.REPLACE_EXISTING
              )
              Files.move(
                cert,
                dir.resolve("client.crt"),
                StandardCopyOption.REPLACE_EXISTING
              )
            }.toEither.left.map(e =>
              s"cannot move the new pair into $dir: ${e.getMessage}"
            )
          yield subject.stripPrefix("/")
        Try {
          Option(work.toFile.listFiles()).toList.flatten.foreach(_.delete())
          Files.deleteIfExists(work)
        }
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
          case Right(subject) =>
            println(
              s"Made ${dir.resolve("client.crt")} and ${dir.resolve("client.key")} " +
                s"($subject, valid ${options.days} days). This orchestrator now presents it to every agent."
            )
            println(
              s"Keep ${dir.resolve("client.key")} private. Next, to require it on nodes: " +
                "orphera issue-certs --nodes <nodes> --client-auth"
            )
            0
