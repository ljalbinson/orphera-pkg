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
        runNoResult(
          List("apt-get", "-o", "DPkg::Lock::Timeout=60", "update"),
          "Updating package cache",
          queue
        )
      else IO.unit

    val installCommand =
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
      ) ++ cmd.packages

    // A failed `apt-get update` must end the stream with a FAILED RESULT, as in
    // distUpgrade: runNoResult only raises, the raise dies in the detached
    // fiber, and the orchestrator (which waits for a RESULT) would hang. Seen
    // on tst0 with a stray download.ceph.com/debian-squid noble source (404).
    update.attempt.flatMap {
      case Right(_) =>
        runInstallWithPostCheck(
          installCommand,
          "Installing packages",
          cmd.packages,
          queue
        )
      case Left(err) =>
        queue.offer(
          Event(
            kind = Event.Kind.RESULT,
            message = s"FAILED: ${err.getMessage}",
            exitCode = 1,
            success = false
          )
        )
    }

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

  /** `apt-get dist-upgrade`, optionally preceded by `apt-get update`. Unlike
    * install's update step, a failed update here emits a FAILED RESULT itself
    * rather than just raising: runNoResult's raised error would die inside the
    * detached fiber that runs this, never reaching the queue, and the
    * orchestrator's stream (which only ends on a RESULT event) would hang.
    * `--force-confdef`/`--force-confold` keep existing config files instead of
    * stalling on a conffile prompt, since there is no tty to answer it.
    */
  def distUpgrade(cmd: DistUpgrade, queue: Queue[IO, Event]): IO[Unit] =
    val aptOptions = List(
      "-o",
      "DPkg::Lock::Timeout=60",
      "-o",
      "Dpkg::Use-Pty=0",
      "-o",
      "APT::Color=0",
      "-o",
      "APT::Get::Assume-Yes=true"
    )

    val update =
      if cmd.updateCache then
        runNoResult(
          List("apt-get") ++ aptOptions ++ List("update"),
          "Updating package cache",
          queue
        )
      else IO.unit

    val upgrade = run(
      List("apt-get") ++ aptOptions ++ List(
        "-o",
        "Dpkg::Options::=--force-confdef",
        "-o",
        "Dpkg::Options::=--force-confold",
        "dist-upgrade",
        "-y"
      ),
      "Upgrading packages (dist-upgrade)",
      queue
    )

    update.attempt.flatMap {
      case Right(_)  => upgrade
      case Left(err) =>
        queue.offer(
          Event(
            kind = Event.Kind.RESULT,
            message = s"FAILED: ${err.getMessage}",
            exitCode = 1,
            success = false
          )
        )
    }

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

  /** Same as `run`, but never emits a terminal RESULT event — for use as a
    * non-final step chained before another run/runInstallWithPostCheck call
    * within the same task. `run`'s RESULT is what closes the gRPC stream
    * (AgentServiceImpl's takeThrough(_.kind != RESULT)) — if an intermediate
    * step like `apt-get update` emits one, the stream closes immediately and
    * the orchestrator moves on to the next task, while the *real* install
    * (chained afterward via >>) keeps running invisibly in the background. This
    * was the actual cause of "install reports success but the package isn't
    * there yet" — not a timing race at all, a structural stream-termination
    * bug.
    */
  private def runNoResult(
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

      _ <-
        if exit != 0 then
          IO.raiseError(
            new RuntimeException(
              s"Command failed with exit $exit: ${command.mkString(" ")}"
            )
          )
        else IO.unit
    yield ()

  private def runInstallWithPostCheck(
      command: List[String],
      stage: String,
      packages: Seq[String],
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

      postCheckOk <-
        if exit == 0 then
          waitForBinariesVisible(packages).attempt.flatMap {
            case Right(_)  => IO.pure(true)
            case Left(err) =>
              IO.blocking(
                System.err.println(
                  s"[DEBUG-POSTCHECK-FAIL] ${err.getClass.getName}: ${err.getMessage}"
                )
              ) >>
                IO.pure(false)
          }
        else IO.pure(true)

      _ <- IO.blocking(
        System.err.println(
          s"[DEBUG-POSTCHECK] exit=$exit postCheckOk=$postCheckOk"
        )
      )

      finalSuccess = (exit == 0) && postCheckOk

      _ <- queue.offer(
        Event(
          kind = Event.Kind.RESULT,
          message = if finalSuccess then "OK" else "FAILED",
          exitCode = if finalSuccess then 0 else if exit != 0 then exit else 1,
          success = finalSuccess
        )
      )
    yield ()

  private def read(reader: BufferedReader, queue: Queue[IO, Event]): IO[Unit] =
    IO.interruptible(reader.readLine()).flatMap {
      case null => IO.unit
      case line =>
        queue.offer(Event(Event.Kind.OUTPUT, line)) >> read(reader, queue)
    }

  /** Waits until every `/usr/bin` and `/usr/sbin` file a package ships exists
    * and is executable — the install can report success a moment before that is
    * true. It must NEVER run the binaries: an earlier version executed each one
    * with no arguments to "prove" it worked, which on a package shipping
    * `halt`/`reboot`/`poweroff`/`shutdown` (molly-guard, systemd-sysv) halted
    * the host the moment the install finished (tst0, 2026-10-07).
    */
  private def waitForBinariesVisible(packages: Seq[String]): IO[Unit] =
    IO.blocking {
      val allFailed = scala.collection.mutable.ListBuffer.empty[String]

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
            ready =
              try
                val f = new java.io.File(path)
                f.exists() && f.canExecute()
              catch case _: Throwable => false

            if !ready then
              Thread.sleep(500)
              attempts += 1

          if !ready then allFailed += path
        }
      }

      if allFailed.nonEmpty then
        throw new RuntimeException(
          s"Binaries never became ready after install: ${allFailed.mkString(", ")}"
        )
    }
