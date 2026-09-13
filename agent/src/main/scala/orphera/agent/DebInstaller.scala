package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

object DebInstaller:

  def install(cmd: InstallDeb, queue: Queue[IO, Event]): IO[Unit] =
    for
      _ <- queue.offer(Event(Event.Kind.PROGRESS, s"Starting detached install of ${cmd.path}"))

      _ <- IO.blocking {
        new ProcessBuilder(
          "systemd-run", "--no-block", "--collect",
          "--unit", s"orphera-deploy-${System.currentTimeMillis()}",
          "dpkg", "-i", cmd.path
        ).inheritIO().start()
      }

      _ <- queue.offer(
        Event(
          Event.Kind.RESULT,
          "Install started as a detached unit — this connection will likely drop if the install restarts the agent",
          exitCode = 0,
          success = true
        )
      )
    yield ()

  private def run(
      command: List[String],
      stage: String,
      queue: Queue[IO, Event]
  ): IO[Unit] =
    for
      _ <- queue.offer(Event(Event.Kind.PROGRESS, stage))

      pb <- IO {
        val p = new ProcessBuilder(command*)
        p.environment().put("DEBIAN_FRONTEND", "noninteractive")
        p.redirectInput(ProcessBuilder.Redirect.PIPE)
        p
      }

      process <- IO.blocking(pb.start())
      _ <- IO.blocking(process.getOutputStream.close())

      stdout = new BufferedReader(new InputStreamReader(process.getInputStream))
      stderr = new BufferedReader(new InputStreamReader(process.getErrorStream))

      out <- read(stdout, queue).start
      err <- read(stderr, queue).start

      exit <- IO.interruptible(process.waitFor())
      _ <- out.joinWithNever
      _ <- err.joinWithNever

      _ <- queue.offer(
        Event(
          kind = Event.Kind.RESULT,
          message = if exit == 0 then "OK" else "FAILED",
          exitCode = exit,
          success = exit == 0
        )
      )
    yield ()

  private def read(reader: BufferedReader, queue: Queue[IO, Event]): IO[Unit] =
    IO.interruptible(reader.readLine()).flatMap {
      case null => IO.unit
      case line =>
        queue.offer(Event(Event.Kind.OUTPUT, line)) >> read(reader, queue)
    }
