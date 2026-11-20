// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.{InputStream, OutputStream, PushbackInputStream}
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
  * reply is "OK <n>\n" and n bytes, or "ERR <message>\n". "PING\n" is answered
  * "PONG\n", so a login script can tell an agent from a forwarded socket with
  * nothing behind it (which accepts the connection, then closes it).
  */
object AgeAgent:

  private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

  /** A time of day as HH:mm:ss. LocalTime's own text leaves the seconds out when
    * they are zero ("15:16"), which breaks the column of a log.
    */
  def clock(t: java.time.LocalTime): String = clockFormat.format(t)

  /** When a cached answer expires: HH:mm:ss if that is today, otherwise
    * "yyyy-MM-dd HH:mm:ss". `--cache` may be longer than a day, and a bare time
    * of day would then read as an expiry earlier today.
    */
  def expiry(
      until: java.time.Instant,
      now: java.time.Instant,
      zone: java.time.ZoneId
  ): String =
    val t = java.time.LocalDateTime.ofInstant(until, zone)
    val today = java.time.LocalDateTime.ofInstant(now, zone).toLocalDate
    if t.toLocalDate == today then clock(t.toLocalTime)
    else s"${t.toLocalDate} ${clock(t.toLocalTime)}"

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
    /** Nothing answered on the socket: a stale socket file, a closed session,
      * or a forwarded socket with no agent at the far end (ssh accepts the
      * connection and closes it before any reply).
      */
    case Unreachable(message: String)

    /** The agent answered and could not decrypt (wrong PIN, no touch, ...). */
    case Refused(message: String)

  private val MaxBytes = 64 * 1024 * 1024

  /** How long to wait for the agent's answer (it may be waiting for a PIN and a
    * touch): $ORPHERA_AGE_AGENT_TIMEOUT seconds, 120 by default.
    */
  def timeoutSeconds: Int =
    sys.env
      .get("ORPHERA_AGE_AGENT_TIMEOUT")
      .flatMap(_.trim.toIntOption)
      .filter(_ > 0)
      .getOrElse(120)

  def decrypt(
      socket: Path,
      data: Array[Byte],
      timeout: Int = timeoutSeconds
  ): Either[Failure, String] =
    Try(SocketChannel.open(StandardProtocolFamily.UNIX)).toEither.left
      .map(e => Failure.Unreachable(message(e)))
      .flatMap { ch =>
        // Closing the channel from another thread ends a read that is waiting.
        val timedOut = new java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = new Thread(() =>
          try
            Thread.sleep(timeout * 1000L)
            timedOut.set(true)
            ch.close()
          catch case _: InterruptedException => ()
        )
        watchdog.setDaemon(true)
        watchdog.start()
        try
          Try(ch.connect(UnixDomainSocketAddress.of(socket))).toEither.left
            .map(e =>
              Failure.Unreachable(s"nothing answers on $socket (${message(e)})")
            )
            .flatMap { _ =>
              val in = new PushbackInputStream(Channels.newInputStream(ch))
              def failed(e: Throwable, replied: Boolean): Failure =
                if timedOut.get then
                  Failure.Refused(
                    s"no answer from the agent on $socket within ${timeout}s (is the YubiKey waiting for a PIN or a touch on the other machine?)"
                  )
                else if replied then
                  Failure.Refused(s"the agent on $socket: ${message(e)}")
                else noAgent(socket, Some(message(e)))
              // Until the first byte of a reply, a closed connection means no
              // agent answered: a forwarded socket with nothing at the far end.
              Try {
                val out = Channels.newOutputStream(ch)
                out.write(s"DECRYPT ${data.length}\n".getBytes(UTF_8))
                out.write(data)
                out.flush()
                in.read()
              }.toEither match
                case Left(e)   => Left(failed(e, replied = false))
                case Right(-1) => Left(noAgent(socket, None))
                case Right(b)  =>
                  in.unread(b)
                  Try(reply(in)).toEither.left
                    .map(e => failed(e, replied = true))
                    .flatten
            }
        finally
          watchdog.interrupt()
          ch.close()
      }

  private def noAgent(socket: Path, why: Option[String]): Failure =
    Failure.Unreachable(
      s"nothing answers at the far end of $socket${why.fold("")(w => s" ($w)")}"
    )

  private def reply(in: InputStream): Either[Failure, String] =
    readLine(in) match
      case Some(l) if l.startsWith("OK ") =>
        l.drop(3).trim.toIntOption.filter(n => n >= 0 && n <= MaxBytes) match
          case Some(n) =>
            readN(in, n)
              .map(String(_, UTF_8))
              .toRight(Failure.Refused("the agent's reply was cut short"))
          case None => Left(Failure.Refused(s"the agent sent '$l'"))
      case Some(l) if l.startsWith("ERR ") => Left(Failure.Refused(l.drop(4)))
      case Some(l) => Left(Failure.Refused(s"the agent sent '$l'"))
      case None    =>
        Left(
          Failure.Refused("the agent closed the connection without answering")
        )

  /** Listens on `socket` (mode 0600; a socket file already there is replaced)
    * and decrypts each request with `cipher`, one at a time; `say` reports
    * each. What it decrypted is kept in memory for `cacheMinutes` (0: not at
    * all) and returned for the same encrypted text without asking the YubiKey
    * again, so a series of runs needs one PIN and touch; a changed store is new
    * text, and is decrypted afresh. Runs until the process ends; the socket
    * file is removed then.
    */
  def serve(
      socket: Path,
      cipher: Cipher,
      say: String => Unit,
      cacheMinutes: Int = 0
  ): Either[String, Unit] =
    val addr = UnixDomainSocketAddress.of(socket)
    Try {
      Option(socket.getParent).foreach(Files.createDirectories(_))
      val _ = Files.deleteIfExists(socket)
      val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      server.bind(addr)
      val _ = Try(
        Files.setPosixFilePermissions(
          socket,
          PosixFilePermissions.fromString("rw-------")
        )
      )
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        val _ = Files.deleteIfExists(socket)
      }))
      server
    }.toEither.left.map(e => s"cannot listen on $socket: ${message(e)}").map {
      server =>
        var n = 0
        // sha-256 of the encrypted text -> (the decrypted text, when it expires)
        var cache = Map.empty[String, (String, java.time.Instant)]
        def key(data: Array[Byte]): String =
          java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(data)
            .map(b => f"${b & 0xff}%02x")
            .mkString
        while true do
          val ch = server.accept()
          n += 1
          try
            val in = Channels.newInputStream(ch)
            val out = Channels.newOutputStream(ch)
            readLine(in) match
              case Some("PING") =>
                out.write("PONG\n".getBytes(UTF_8))
                out.flush()
              case Some(l) if l.startsWith("DECRYPT ") =>
                l.drop(8)
                  .trim
                  .toIntOption
                  .filter(k => k >= 0 && k <= MaxBytes)
                  .flatMap(k => readN(in, k)) match
                  case None =>
                    answer(out, Left("a request the agent could not read"))
                  case Some(data) =>
                    val now = java.time.Instant.now
                    cache = cache.filter((_, v) => v._2.isAfter(now))
                    val k = key(data)
                    cache.get(k) match
                      case Some((text, until)) =>
                        say(
                          s"request $n: from the cache (until ${AgeAgent.expiry(until, now, java.time.ZoneId.systemDefault)})"
                        )
                        answer(out, Right(text))
                      case None =>
                        say(
                          s"request $n: decrypt ${data.length} bytes (enter the PIN / touch the YubiKey if asked)"
                        )
                        val result = cipher.decrypt(data)
                        say(
                          s"request $n: ${result.fold(e => s"failed: $e", _ => "done")}"
                        )
                        result.foreach(text =>
                          if cacheMinutes > 0 then
                            cache = cache + (k -> (
                              text,
                              now.plusSeconds(cacheMinutes * 60L)
                            ))
                        )
                        answer(out, result)
              case other =>
                answer(
                  out,
                  Left(s"not a request: ${other.getOrElse("(nothing)")}")
                )
          catch case e: Exception => say(s"request $n: ${message(e)}")
          finally ch.close()
    }

  private def answer(out: OutputStream, result: Either[String, String]): Unit =
    result match
      case Right(text) =>
        val bytes = text.getBytes(UTF_8)
        out.write(s"OK ${bytes.length}\n".getBytes(UTF_8))
        out.write(bytes)
      case Left(err) =>
        out.write(s"ERR ${err.replace('\n', ' ')}\n".getBytes(UTF_8))
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

  private def message(e: Throwable): String =
    Option(e.getMessage).getOrElse(e.toString)
