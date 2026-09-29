// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

object DebInstaller:

  // The only package this RPC is allowed to install, ever. Checked
  // against the .deb's own control metadata, not its filename or path
  // — see verifyPackageName below for why.
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
            Event(Event.Kind.PROGRESS, s"Starting detached install of ${cmd.path}")
          )

          _ <- IO.blocking {
            val script =
              s"""for i in $$(seq 1 12); do
                 |  if dpkg -i ${cmd.path}; then exit 0; fi
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

  /** Refuses to let `InstallDebPackage` be used to install anything
    * other than this project's own agent package. Previously this ran
    * `dpkg -i` against whatever path it was handed with no check at
    * all — meaning any authenticated caller (a legitimate orchestrator
    * invocation with a typo'd path, a compromised orchestrator host,
    * or a leaked `ORPHERA_TOKEN` used directly against the gRPC port)
    * could install an arbitrary `.deb` as root on every agent host
    * this RPC could reach. This is the actual trust boundary — TLS and
    * the shared token authenticate *who* can call this RPC, not *what*
    * it's allowed to do once called.
    *
    * Checked by reading the package's own control metadata
    * (`dpkg-deb -f <path> Package`), not by trusting the filename or
    * path — a malicious or mistaken file given an innocent-looking
    * name would sail straight through a filename-only check.
    */
  private def verifyPackageName(path: String): IO[Either[String, Unit]] =
    IO.blocking {
      val process = new ProcessBuilder("dpkg-deb", "-f", path, "Package")
        .redirectErrorStream(true)
        .start()
      val output = new String(process.getInputStream.readAllBytes()).trim
      val exit = process.waitFor()
      (exit, output)
    }.attempt.map {
      case Right((0, name)) if name == expectedPackageName =>
        Right(())
      case Right((0, name)) =>
        Left(
          s"Refusing to install $path: its Package field is '$name', not '$expectedPackageName'."
        )
      case Right((exit, _)) =>
        Left(
          s"Refusing to install $path: could not read its package metadata (dpkg-deb exited $exit) — is this a valid .deb?"
        )
      case Left(err) =>
        Left(
          s"Refusing to install $path: failed to run dpkg-deb (${err.getMessage})"
        )
    }
