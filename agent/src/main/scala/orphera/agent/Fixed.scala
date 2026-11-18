// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, Paths}
import java.nio.file.attribute.{BasicFileAttributes, PosixFileAttributeView}
import java.security.MessageDigest
import java.util.concurrent.{CompletableFuture, TimeUnit}

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import orphera.common.ApplyReply

/** What a typed call (ObserveNode, ApplyFiles, ApplyAccess, ApplySoftware)
  * could not do: its message is the reply's error.
  */
final class ApplyFailure(message: String) extends RuntimeException(message)

/** The helpers the typed calls share, as in the Go agent (`observe.go`,
  * `applyfiles.go`): argument checks, fixed programs run without a shell, and
  * file attributes.
  */
object Fixed:

  def fail(message: String): Nothing = throw new ApplyFailure(message)

  /** A name safe to pass to a program as one argument: not empty, not an
    * option, no white space or control characters.
    */
  def validArg(s: String): Boolean =
    s.nonEmpty && !s.startsWith("-") &&
      !s.exists(c => Character.isWhitespace(c) || Character.isISOControl(c))

  def needName(what: String, s: String): Unit =
    if !validArg(s) then fail(s"not a valid $what: '$s'")

  def needAbsolute(path: String): Unit =
    if !path.startsWith("/") || path.contains('\u0000') then
      fail(s"not an absolute path: '$path'")

  /** An absolute path that is also safe as one program argument. */
  def validPathArg(s: String): Boolean = s.startsWith("/") && validArg(s)

  def isNumber(s: String): Boolean =
    s.nonEmpty && s.forall(c => c >= '0' && c <= '9') &&
      Try(s.toLong).toOption.exists(_ <= 4294967295L)

  /** An absolute path outside the agent's own directory. */
  def needChangeablePath(path: String): Unit =
    needAbsolute(path)
    if ProtectedPaths.refusal(path).isDefined then
      fail(s"protected path (${ProtectedPaths.Dir})")

  def firstLine(s: String): String =
    s.trim.takeWhile(_ != '\n').trim

  def fields(s: String): List[String] =
    s.trim.split("\\s+").toList.filter(_.nonEmpty)

  def lstat(p: Path): Option[BasicFileAttributes] =
    try
      Some(
        Files.readAttributes(
          p,
          classOf[BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS
        )
      )
    catch case _: NoSuchFileException => None

  /** The whole mode, setuid, setgid and sticky bits included. */
  def unixMode(p: Path, follow: Boolean = true): Int =
    val v =
      if follow then Files.getAttribute(p, "unix:mode")
      else Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS)
    v match
      case i: Integer => i.intValue
      case _          => 0

  def setMode(p: Path, mode: Int): Unit =
    Files.setAttribute(p, "unix:mode", Int.box(mode))

  /** An octal mode text; "" is no mode. */
  def parseMode(s: String): Option[Int] =
    if s.isEmpty then None
    else
      Try(Integer.parseInt(s, 8)).toOption.filter(n => n >= 0 && n <= 4095) match
        case None    => fail(s"not an octal mode: '$s'")
        case Some(n) => Some(n)

  /** chown and chmod: an empty owner, group or mode is left alone. The owner and
    * group first, since chown clears setuid and setgid.
    */
  def setAttributes(
      path: String,
      mode: String,
      owner: String,
      group: String
  ): Unit =
    val m = parseMode(mode)
    val p = Paths.get(path)
    val lookup = p.getFileSystem.getUserPrincipalLookupService
    if owner.nonEmpty then
      val who = Try(lookup.lookupPrincipalByName(owner)).getOrElse(
        fail(s"no such user: $owner")
      )
      Files.setOwner(p, who)
    if group.nonEmpty then
      val g = Try(lookup.lookupPrincipalByGroupName(group)).getOrElse(
        fail(s"no such group: $group")
      )
      Files.getFileAttributeView(p, classOf[PosixFileAttributeView]).setGroup(g)
    m.foreach(setMode(p, _))

  def sha256File(p: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(p)
    try
      val buf = new Array[Byte](65536)
      Iterator
        .continually(in.read(buf))
        .takeWhile(_ >= 0)
        .foreach(n => digest.update(buf, 0, n))
    finally in.close()
    digest.digest().map(b => f"$b%02x").mkString

  def sha256Text(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

  /** Runs a fixed program (never a shell). The output (with the error output
    * too when `mergeStderr`) and the exit code; -1 when it could not be run or
    * ran out of time.
    */
  def execute(
      name: String,
      args: List[String],
      timeout: FiniteDuration,
      env: Map[String, String],
      mergeStderr: Boolean
  ): (String, Int) =
    val builder = new ProcessBuilder((name :: args).asJava)
    env.foreach((k, v) => builder.environment().put(k, v))
    if mergeStderr then builder.redirectErrorStream(true)
    else builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    Try(builder.start()) match
      case Failure(e) => (Option(e.getMessage).getOrElse(e.toString), -1)
      case Success(p) =>
        p.getOutputStream.close()
        val reader = CompletableFuture.supplyAsync(() =>
          new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        )
        if p.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
          (reader.get(10, TimeUnit.SECONDS), p.exitValue())
        else
          p.destroyForcibly()
          (s"timed out after ${timeout.toSeconds} s", -1)

  /** What a program printed and its exit code, for a read (20 s). */
  def capture(name: String, args: List[String]): (String, Int) =
    execute(name, args, 20.seconds, Map.empty, mergeStderr = false)

  /** Runs a fixed program and fails with what it printed when it does not
    * succeed.
    */
  def run(
      name: String,
      args: List[String],
      timeout: FiniteDuration = 60.seconds,
      env: Map[String, String] = Map.empty
  ): Unit =
    val (out, code) = execute(name, args, timeout, env, mergeStderr = true)
    if code != 0 then
      val said = out.trim
      val tail = if said.length > 2000 then said.takeRight(2000) else said
      fail(if tail.isEmpty then s"$name: exit status $code" else s"$name: $tail")

  def have(name: String): Boolean =
    Option(System.getenv("PATH"))
      .getOrElse("/usr/sbin:/usr/bin:/sbin:/bin")
      .split(":")
      .exists(d => d.nonEmpty && Files.isExecutable(Paths.get(d, name)))

  /** A user's `getent passwd` line, split into its seven fields. */
  def passwd(name: String): Option[List[String]] =
    val (out, code) = capture("getent", List("passwd", name))
    if code != 0 then None
    else
      val f = firstLine(out).split(":", -1).toList
      Option.when(f.length >= 7)(f)

  /** Makes the actions in order and stops at the first that fails, saying how
    * far it got (the reply of the Apply calls).
    */
  def sequence[A](actions: List[A])(describe: A => String)(
      one: A => Unit
  ): ApplyReply =
    @tailrec def go(done: Int, rest: List[A]): ApplyReply = rest match
      case Nil       => ApplyReply(completed = done)
      case a :: tail =>
        Try(one(a)) match
          case Success(_) => go(done + 1, tail)
          case Failure(e) =>
            ApplyReply(
              completed = done,
              error = s"${describe(a)}: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
    go(0, actions)
