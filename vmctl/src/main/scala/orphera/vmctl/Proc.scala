// SPDX-License-Identifier: Apache-2.0

package orphera.vmctl

import java.io.{ByteArrayOutputStream, File, InputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

/** Running the commands vmctl drives (`orphera`, utils/list-vms.sh, bash), as
  * the shell version does: in the checkout, with the environment of
  * orphera-sources.sh, and with this terminal as their standard input.
  */
object Proc:

  /** The program `name` would run as: itself if it has a slash, else the
    * first executable of that name in PATH (an empty or relative PATH entry
    * counting from the checkout, the shell version's working directory).
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
    s"vmctl: ${cmd.headOption.getOrElse("")}: command not found\n"

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
        System.err.print(notFound(cmd))
        System.err.flush()
        127
      case Some(pb) =>
        pb.redirectOutput(Redirect.INHERIT)
        pb.redirectError(Redirect.INHERIT)
        try pb.start().waitFor()
        catch
          case NonFatal(e) =>
            System.err.println(s"vmctl: ${cmd.mkString(" ")}: ${e.getMessage}")
            127

  /** `$(cmd)`: its exit code and standard output (UTF-8, trailing newlines
    * removed). Standard error goes to this terminal, or nowhere with
    * `quiet` (`2>/dev/null`).
    */
  def capture(
      cmd: List[String],
      env: Map[String, String],
      root: Path,
      quiet: Boolean = false
  ): (Int, String) =
    builder(cmd, env, root) match
      case None =>
        if !quiet then
          System.err.print(notFound(cmd))
          System.err.flush()
        (127, "")
      case Some(pb) =>
        pb.redirectOutput(Redirect.PIPE)
        pb.redirectError(
          if quiet then Redirect.to(new File("/dev/null")) else Redirect.INHERIT
        )
        try
          val p = pb.start()
          val out = new String(readAll(p.getInputStream), StandardCharsets.UTF_8)
          val rc = p.waitFor()
          (rc, out.replaceAll("\n+$", ""))
        catch
          case NonFatal(e) =>
            if !quiet then
              System.err.println(s"vmctl: ${cmd.mkString(" ")}: ${e.getMessage}")
            (127, "")

  /** `env <extra> cmd >log 2>&1`: its exit code. A command that cannot be
    * found or started leaves the reason in the log, as bash would.
    */
  def toLog(
      cmd: List[String],
      env: Map[String, String],
      root: Path,
      log: Path
  ): Int =
    def note(text: String, rc: Int): Int =
      try
        Files.write(log, text.getBytes(StandardCharsets.UTF_8))
        ()
      catch
        case NonFatal(e) =>
          System.err.println(s"vmctl: $log: ${e.getMessage}")
      rc
    builder(cmd, env, root) match
      case None => note(notFound(cmd), 127)
      case Some(pb) =>
        pb.redirectErrorStream(true)
        pb.redirectOutput(Redirect.to(log.toFile))
        try pb.start().waitFor()
        catch
          case NonFatal(e) =>
            note(s"vmctl: ${cmd.mkString(" ")}: ${e.getMessage}\n", 1)
