// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import orphera.common.*

/** The rpm counterpart of DebInstaller, for the `InstallDebPackage` RPC (the
  * RPC's name predates rpm support; its `path` is a local package file). Same
  * rules: only this project's own agent package may be installed, verified from
  * the package's own metadata (`rpm -qp --queryformat %{NAME}`), and the
  * install runs detached via systemd-run because it restarts the agent whose
  * connection is running it. `dnf install <file>` is used rather than
  * `rpm -i` so the agent's Java dependency is resolved from the repositories.
  */
object RpmInstaller:

  private val expectedPackageName = "orphera-agent"

  def install(cmd: InstallDeb, queue: Queue[IO, Event]): IO[Unit] =
    verifyPackageName(cmd.path).flatMap {
      case Left(reason) =>
        queue.offer(
          Event(Event.Kind.RESULT, reason, exitCode = 1, success = false)
        )

      case Right(()) =>
        for
          _ <- queue.offer(
            Event(
              Event.Kind.PROGRESS,
              s"Starting detached install of ${cmd.path}"
            )
          )

          _ <- IO.blocking {
            val script =
              s"""for i in $$(seq 1 12); do
                 |  if dnf -y install ${cmd.path}; then exit 0; fi
                 |  sleep 5
                 |done
                 |exit 1""".stripMargin

            new ProcessBuilder(
              "systemd-run",
              "--no-block",
              "--collect",
              "--unit",
              s"orphera-deploy-${System.currentTimeMillis()}",
              "sh",
              "-c",
              script
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
    }

  private def verifyPackageName(path: String): IO[Either[String, Unit]] =
    IO.blocking {
      val process = new ProcessBuilder(
        "rpm",
        "-qp",
        "--queryformat",
        "%{NAME}",
        path
      ).redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes()).trim
      val exit = process.waitFor()
      (exit, output)
    }.attempt
      .map {
        case Right((0, name)) if name == expectedPackageName =>
          Right(())
        case Right((0, name)) =>
          Left(
            s"Refusing to install $path: its Name is '$name', not '$expectedPackageName'."
          )
        case Right((exit, _)) =>
          Left(
            s"Refusing to install $path: could not read its package metadata (rpm exited $exit) — is this a valid .rpm?"
          )
        case Left(err) =>
          Left(
            s"Refusing to install $path: failed to run rpm (${err.getMessage})"
          )
      }
