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
        run(
          List("apt-get", "-o", "DPkg::Lock::Timeout=60", "update"),
          "Updating package cache",
          queue
        )
      else IO.unit

    val installPkgs =
      run(
        List(
          "apt-get",
          "-o",
          "DPkg::Lock::Timeout=60",
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

    update >> installPkgs >> waitForBinariesVisible(cmd.packages)

  def remove(cmd: RemovePackages, queue: Queue[IO, Event]): IO[Unit] =
    val subcommand = if cmd.purge then "purge" else "remove"
    val stageLabel =
      if cmd.purge then "Purging packages" else "Removing packages"

    run(
      List(
        "apt-get",
        "-o",
        "DPkg::Lock::Timeout=60",
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
        "apt-get",
        "-o",
        "DPkg::Lock::Timeout=60",
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

  /** Waits for each just-installed package's /usr/bin or /usr/sbin files to
    * actually exist on disk before returning. apt-get can report exit 0 for
    * `install` while the filesystem view a subsequent, separately-spawned
    * process sees is still briefly stale (dpkg trigger processing, or
    * filesystem/cache lag on container hosts) — a task that immediately follows
    * an install and invokes a binary from it can otherwise hit a transient
    * "command not found", exactly as observed in practice with ceph-authtool
    * and ceph-mon.
    *
    * Checks dpkg's own file listing for each package (not a fixed guess at a
    * path) and polls up to ~5s per package.
    */
  private def waitForBinariesVisible(packages: Seq[String]): IO[Unit] =
    IO.blocking {
      packages.foreach { rawPkg =>
        val pkg = rawPkg.takeWhile(_ != '=')

        val listing = new ProcessBuilder("dpkg", "-L", pkg).start()
        val output =
          try
            scala.io.Source
              .fromInputStream(listing.getInputStream)
              .getLines()
              .toList
          finally ()
        listing.waitFor()

        val binaries = output.filter(p =>
          p.startsWith("/usr/bin/") || p.startsWith("/usr/sbin/")
        )

        binaries.foreach { path =>
          var attempts = 0
          var ready = false
          while attempts < 40 && !ready do
            // Actually spawn a fresh process and try to execute the
            // binary, rather than just checking file existence from
            // this JVM's own process — cross-process executable
            // visibility can lag behind same-process File.exists()
            // checks on this environment (confirmed empirically).
            val check = new ProcessBuilder(path, "--version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start()
            val exit = check.waitFor()
            ready =
              exit == 0 || exit == 1 // some tools exit 1 on --version but that still proves it ran
            if !ready then
              Thread.sleep(500)
              attempts += 1
        }
      }
    }
