// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.{InputStream, OutputStream}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channels, ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

/** Decrypting the secret store on another machine, the one the YubiKey is
  * plugged into, as ssh-agent forwarding does for SSH keys.
  *
  * On that machine (a Mac, say) `orphera secrets agent` listens on a Unix
  * socket and decrypts with its own age identity; age's PIN and touch prompts
  * appear in that terminal. The SSH session to the orphera host forwards the
  * socket (`ssh -R <socket there>:<socket here> host`), and there AgeCipher
  * sends what it has to decrypt through it instead of running `age -d`.
  * Encrypting needs only the recipients' public keys, so it stays local.
  *
  * Wire format, one request per connection: "DECRYPT <n>\n" and n bytes; the
  * reply is "OK <n>\n" and n bytes, or "ERR <message>\n".
  */
object AgeAgent:

  /** Where the agent listens, and where orphera looks for it:
    * $ORPHERA_AGE_AGENT, or ~/.config/orphera/agent.sock.
    */
  def socketPath: Path =
    sys.env
      .get("ORPHERA_AGE_AGENT")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(SecretStore.home.resolve(".config/orphera/agent.sock"))

  /** Why a request through the agent did not decrypt. */
  enum Failure:
    /** Nothing answered on the socket (a stale socket file, a closed session). */
    case Unreachable(message: String)
    /** The agent answered and could not decrypt (wrong PIN, no touch, ...). */
    case Refused(message: String)

  private val MaxBytes = 64 * 1024 * 1024

  def decrypt(socket: Path, data: Array[Byte]): Either[Failure, String] =
    Try(SocketChannel.open(StandardProtocolFamily.UNIX)).toEither.left
      .map(e => Failure.Unreachable(message(e)))
      .flatMap { ch =>
        try
          Try(ch.connect(UnixDomainSocketAddress.of(socket))).toEither.left
            .map(e => Failure.Unreachable(s"nothing answers on $socket (${message(e)})"))
            .flatMap { _ =>
              Try {
                val out = Channels.newOutputStream(ch)
                out.write(s"DECRYPT ${data.length}\n".getBytes(UTF_8))
                out.write(data)
                out.flush()
                reply(Channels.newInputStream(ch))
              }.toEither.left.map(e => Failure.Refused(s"the agent on $socket: ${message(e)}")).flatten
            }
        finally ch.close()
      }

  private def reply(in: InputStream): Either[Failure, String] =
    readLine(in) match
      case Some(l) if l.startsWith("OK ") =>
        l.drop(3).trim.toIntOption.filter(n => n >= 0 && n <= MaxBytes) match
          case Some(n) => readN(in, n).map(String(_, UTF_8)).toRight(Failure.Refused("the agent's reply was cut short"))
          case None    => Left(Failure.Refused(s"the agent sent '$l'"))
      case Some(l) if l.startsWith("ERR ") => Left(Failure.Refused(l.drop(4)))
      case Some(l)                         => Left(Failure.Refused(s"the agent sent '$l'"))
      case None                            => Left(Failure.Refused("the agent closed the connection without answering"))

  /** Listens on `socket` (mode 0600; a socket file already there is replaced)
    * and decrypts each request with `cipher`, one at a time; `say` reports each.
    * Runs until the process ends; the socket file is removed then.
    */
  def serve(socket: Path, cipher: Cipher, say: String => Unit): Either[String, Unit] =
    val addr = UnixDomainSocketAddress.of(socket)
    Try {
      Option(socket.getParent).foreach(Files.createDirectories(_))
      val _ = Files.deleteIfExists(socket)
      val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      server.bind(addr)
      val _ = Try(Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------")))
      Runtime.getRuntime.addShutdownHook(new Thread(() => { val _ = Files.deleteIfExists(socket) }))
      server
    }.toEither.left.map(e => s"cannot listen on $socket: ${message(e)}").map { server =>
      var n = 0
      while true do
        val ch = server.accept()
        n += 1
        try
          val in = Channels.newInputStream(ch)
          val out = Channels.newOutputStream(ch)
          readLine(in) match
            case Some(l) if l.startsWith("DECRYPT ") =>
              l.drop(8).trim.toIntOption.filter(k => k >= 0 && k <= MaxBytes).flatMap(k => readN(in, k)) match
                case None => answer(out, Left("a request the agent could not read"))
                case Some(data) =>
                  say(s"request $n: decrypt ${data.length} bytes (enter the PIN / touch the YubiKey if asked)")
                  val result = cipher.decrypt(data)
                  say(s"request $n: ${result.fold(e => s"failed: $e", _ => "done")}")
                  answer(out, result)
            case other => answer(out, Left(s"not a request: ${other.getOrElse("(nothing)")}"))
        catch case e: Exception => say(s"request $n: ${message(e)}")
        finally ch.close()
    }

  private def answer(out: OutputStream, result: Either[String, String]): Unit =
    result match
      case Right(text) =>
        val bytes = text.getBytes(UTF_8)
        out.write(s"OK ${bytes.length}\n".getBytes(UTF_8))
        out.write(bytes)
      case Left(err) => out.write(s"ERR ${err.replace('\n', ' ')}\n".getBytes(UTF_8))
    out.flush()

  private def readLine(in: InputStream): Option[String] =
    val b = new java.io.ByteArrayOutputStream()
    var c = in.read()
    while c != -1 && c != '\n' && b.size < 4096 do
      b.write(c)
      c = in.read()
    if c == -1 && b.size == 0 then None else Some(b.toString(UTF_8))

  private def readN(in: InputStream, n: Int): Option[Array[Byte]] =
    val bytes = in.readNBytes(n)
    Option.when(bytes.length == n)(bytes)

  private def message(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)
