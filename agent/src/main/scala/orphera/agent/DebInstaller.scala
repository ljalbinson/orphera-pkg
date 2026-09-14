// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import java.io.*
import orphera.common.*

object DebInstaller:

  def install(cmd: InstallDeb, queue: Queue[IO, Event]): IO[Unit] =
    for
      _ <- queue.offer(
        Event(Event.Kind.PROGRESS, s"Starting detached install of ${cmd.path}")
      )

      _ <- IO.blocking {
        new ProcessBuilder(
          "systemd-run",
          "--no-block",
          "--collect",
          "--unit",
          s"orphera-deploy-${System.currentTimeMillis()}",
          "dpkg",
          "-o DPkg::Lock::Timeout=60",
          "-i",
          cmd.path
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
