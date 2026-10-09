// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

/** Process-running steps shared by the package-manager backends (AptInstaller,
  * DnfInstaller). Moved out of AptInstaller unchanged except that the install
  * post-check takes the "list a package's files" command as a parameter.
  */
object PackageSteps:

  private[agent] def run(
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
  private[agent] def runNoResult(
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

  /** `listFiles(pkg)` is the command that lists the files an installed package
    * ships (`dpkg -L <pkg>` on Debian, `rpm -ql <pkg>` on Red Hat).
    */
  private[agent] def runInstallWithPostCheck(
      command: List[String],
      stage: String,
      packages: Seq[String],
      queue: Queue[IO, Event],
      listFiles: String => List[String]
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
          waitForBinariesVisible(packages, listFiles).attempt.flatMap {
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
  private def waitForBinariesVisible(
      packages: Seq[String],
      listFiles: String => List[String]
  ): IO[Unit] =
    IO.blocking {
      val allFailed = scala.collection.mutable.ListBuffer.empty[String]

      packages.foreach { rawPkg =>
        val pkg = rawPkg.takeWhile(_ != '=')

        val listing = new ProcessBuilder(listFiles(pkg)*).start()
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
