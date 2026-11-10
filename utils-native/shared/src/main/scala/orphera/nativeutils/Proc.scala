// SPDX-License-Identifier: Apache-2.0

package orphera.nativeutils

import java.io.{ByteArrayOutputStream, File, InputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

/** Where a captured command's standard error goes: this terminal, nowhere
  * (`2>/dev/null`), or into the captured output (`2>&1`).
  */
enum ErrMode:
  case Inherit, Discard, Merge

/** Running the commands the scripts drive (`orphera`, the other utils/
  * scripts, bash, date), as the shell versions do: in their working
  * directory, with the environment of orphera-sources.sh, and with this
  * terminal as their standard input.
  */
object Proc:

  /** The program `name` would run as: itself if it has a slash, else the
    * first executable of that name in PATH (an empty or relative PATH entry
    * counting from `root`, the shell version's working directory).
    */
  def which(name: String, env: Map[String, String], root: Path): Option[String] =
    if name.contains('/') then Some(name)
    else
      env
        .getOrElse("PATH", "")
        .split(":", -1)
        .iterator
        .map(d => root.resolve(if d.isEmpty then "." else d).resolve(name).toFile)
        .find(f => f.isFile && f.canExecute)
        .map(_.getPath)

  private def builder(
      cmd: List[String],
      env: Map[String, String],
      root: Path
  ): Option[ProcessBuilder] =
    cmd match
      case prog :: args =>
        which(prog, env, root).map { exe =>
          val pb = new ProcessBuilder((exe :: args)*)
          pb.directory(root.toFile)
          val pe = pb.environment()
          pe.clear()
          env.foreach((k, v) => pe.put(k, v))
          pb.redirectInput(Redirect.INHERIT)
          pb
        }
      case Nil => None

  private def notFound(cmd: List[String]): String =
    s"${cmd.headOption.getOrElse("")}: command not found"

  private def readAll(in: InputStream): Array[Byte] =
    val buf = new Array[Byte](8192)
    val acc = new ByteArrayOutputStream()
    var n = in.read(buf)
    while n >= 0 do
      acc.write(buf, 0, n)
      n = in.read(buf)
    acc.toByteArray

  /** Runs `cmd` with standard output and error passed through; its exit code
    * (127 if it cannot be found, as in the shell).
    */
  def inherit(cmd: List[String], env: Map[String, String], root: Path): Int =
    builder(cmd, env, root) match
      case None =>
        Out.err(notFound(cmd))
        127
      case Some(pb) =>
        pb.redirectOutput(Redirect.INHERIT)
        pb.redirectError(Redirect.INHERIT)
        try pb.start().waitFor()
        catch
          case NonFatal(e) =>
            Out.err(s"${cmd.mkString(" ")}: ${e.getMessage}")
            127

  /** `$(cmd)`: its exit code and standard output (UTF-8, trailing newlines
    * removed), with standard error as `err` says.
    */
  def capture(
      cmd: List[String],
      env: Map[String, String],
      root: Path,
      err: ErrMode = ErrMode.Inherit
  ): (Int, String) =
    builder(cmd, env, root) match
      case None =>
        err match
          case ErrMode.Inherit => Out.err(notFound(cmd)); (127, "")
          case ErrMode.Discard => (127, "")
          case ErrMode.Merge   => (127, notFound(cmd))
      case Some(pb) =>
        pb.redirectOutput(Redirect.PIPE)
        err match
          case ErrMode.Inherit => pb.redirectError(Redirect.INHERIT)
          case ErrMode.Discard => pb.redirectError(Redirect.to(new File("/dev/null")))
          case ErrMode.Merge   => pb.redirectErrorStream(true)
        try
          val p = pb.start()
          val out = new String(readAll(p.getInputStream), StandardCharsets.UTF_8)
          val rc = p.waitFor()
          (rc, out.replaceAll("\n+$", ""))
        catch
          case NonFatal(e) =>
            val msg = s"${cmd.mkString(" ")}: ${e.getMessage}"
            err match
              case ErrMode.Inherit => Out.err(msg); (127, "")
              case ErrMode.Discard => (127, "")
              case ErrMode.Merge   => (127, msg)

  private def note(log: Path, text: String): Unit =
    try
      Files.write(log, s"$text\n".getBytes(StandardCharsets.UTF_8))
      ()
    catch case NonFatal(e) => Out.err(s"$log: ${e.getMessage}")

  /** `cmd >log 2>&1`: its exit code. A command that cannot be found or
    * started leaves the reason in the log, as bash would.
    */
  def toLog(
      cmd: List[String],
      env: Map[String, String],
      root: Path,
      log: Path
  ): Int =
    background(cmd, env, root, log).fold(127)(_.waitFor())

  /** `cmd >log 2>&1 &`: the running process, or None (the reason in the log)
    * if it cannot be found or started.
    */
  def background(
      cmd: List[String],
      env: Map[String, String],
      root: Path,
      log: Path
  ): Option[Process] =
    builder(cmd, env, root) match
      case None =>
        note(log, notFound(cmd))
        None
      case Some(pb) =>
        pb.redirectErrorStream(true)
        pb.redirectOutput(Redirect.to(log.toFile))
        try Some(pb.start())
        catch
          case NonFatal(e) =>
            note(log, s"${cmd.mkString(" ")}: ${e.getMessage}")
            None
