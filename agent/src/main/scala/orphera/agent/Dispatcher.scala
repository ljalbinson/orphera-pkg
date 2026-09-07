package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import orphera.common.*

object Dispatcher:

  def dispatch(
      cmd: InstallPackages,
      queue: Queue[IO, Event]
  ): IO[Unit] =
    AptInstaller.install(cmd, queue)
