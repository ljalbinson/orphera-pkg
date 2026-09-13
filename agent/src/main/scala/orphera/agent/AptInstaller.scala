// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

object AptInstaller:

  def install(cmd: InstallPackages, queue: Queue[IO, Event]): IO[Unit] =
    val update =
      if cmd.updateCache then
        run(List("apt-get", "-o", "DPkg::Lock::Timeout=60", "update"), "Updating package cache", queue)
      else IO.unit

    val installPkgs =
      run(
        List(
          "apt-get", "-o", "DPkg::Lock::Timeout=60",
          "-o",
          "Dpkg::Use-Pty=0",
          "-o",
          "APT::Color=0",
          "-o",
          "APT::Get::Assume-Yes=true",
          "install",
          "-y"
        ) ++ cmd.packages,
        "Installing packages",
        queue
      )

    update >> installPkgs

  def remove(cmd: RemovePackages, queue: Queue[IO, Event]): IO[Unit] =
    val subcommand = if cmd.purge then "purge" else "remove"
    val stageLabel =
      if cmd.purge then "Purging packages" else "Removing packages"

    run(
      List(
        "apt-get", "-o", "DPkg::Lock::Timeout=60",
        "-o",
        "Dpkg::Use-Pty=0",
        "-o",
        "APT::Color=0",
        "-o",
        "APT::Get::Assume-Yes=true",
        subcommand,
        "-y"
      ) ++ cmd.packages,
      stageLabel,
      queue
    )

  def autoRemove(cmd: AutoRemove, queue: Queue[IO, Event]): IO[Unit] =
    val flags = if cmd.purge then List("--purge") else Nil
    val stageLabel = if cmd.purge then "Auto-removing packages (purge)"
    else "Auto-removing packages"

    run(
      List(
        "apt-get", "-o", "DPkg::Lock::Timeout=60",
        "-o",
        "Dpkg::Use-Pty=0",
        "-o",
        "APT::Color=0",
        "-o",
        "APT::Get::Assume-Yes=true",
        "autoremove",
        "-y"
      ) ++ flags,
      stageLabel,
      queue
    )

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
        p.environment().put("APT_LISTCHANGES_FRONTEND", "none")
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
