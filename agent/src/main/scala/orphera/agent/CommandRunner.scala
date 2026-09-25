// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

object CommandRunner:

  def run(request: RunCommandRequest, queue: Queue[IO, Event]): IO[Unit] =
    if request.command.isEmpty then
      queue.offer(
        Event(
          Event.Kind.RESULT,
          "No command given",
          exitCode = 1,
          success = false
        )
      )
    else
      val timeoutSeconds =
        if request.timeoutSeconds > 0 then request.timeoutSeconds else 60

      for
        _ <- queue.offer(
          Event(
            Event.Kind.PROGRESS,
            s"Running: ${request.command.mkString(" ")}"
          )
        )

        pb <- IO {
          val p = new ProcessBuilder(request.command*)
          p.environment()
            .put(
              "PATH",
              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            )
          System.err.println(
            s"[DEBUG-CMD] argc=${request.command.length} args=${request.command.map(a => s"[$a]").mkString(" ")}"
          )
          System.err.println(
            s"[DEBUG-CMD] spawning: ${request.command.mkString(" ")} cwd=${p.directory()} PATH=${p.environment().get("PATH")}"
          )
          p.redirectInput(ProcessBuilder.Redirect.PIPE)
          p
        }

        process <- IO.blocking(pb.start())
        _ <- IO.blocking(process.getOutputStream.close())

        stdout = new BufferedReader(
          new InputStreamReader(process.getInputStream)
        )
        stderr = new BufferedReader(
          new InputStreamReader(process.getErrorStream)
        )

        out <- readLines(stdout, queue).start
        err <- readLines(stderr, queue).start

        exitOpt <- IO
          .interruptible(
            process
              .waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
          )
          .map(finished => if finished then Some(process.exitValue()) else None)

        _ <-
          if exitOpt.isEmpty then
            IO.blocking(process.destroyForcibly()) >>
              out.cancel >> err.cancel >>
              queue.offer(
                Event(
                  Event.Kind.RESULT,
                  s"Timed out after ${timeoutSeconds}s",
                  exitCode = 124,
                  success = false
                )
              )
          else
            out.joinWithNever >> err.joinWithNever >>
              queue.offer(
                Event(
                  kind = Event.Kind.RESULT,
                  message = if exitOpt.get == 0 then "OK" else "FAILED",
                  exitCode = exitOpt.get,
                  success = exitOpt.get == 0
                )
              )
      yield ()

  private def readLines(
      reader: BufferedReader,
      queue: Queue[IO, Event]
  ): IO[Unit] =
    IO.interruptible(reader.readLine()).flatMap {
      case null => IO.unit
      case line =>
        queue.offer(Event(Event.Kind.OUTPUT, line)) >> readLines(reader, queue)
    }
