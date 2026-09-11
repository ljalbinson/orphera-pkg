// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import orphera.common.*

object Dispatcher:

  def dispatch(cmd: InstallPackages, queue: Queue[IO, Event]): IO[Unit] =
    AptInstaller.install(cmd, queue)

  def dispatchRemove(cmd: RemovePackages, queue: Queue[IO, Event]): IO[Unit] =
    AptInstaller.remove(cmd, queue)

  def dispatchAutoRemove(cmd: AutoRemove, queue: Queue[IO, Event]): IO[Unit] =
    AptInstaller.autoRemove(cmd, queue)

  def dispatchInstallDeb(cmd: InstallDeb, queue: Queue[IO, Event]): IO[Unit] =
    DebInstaller.install(cmd, queue)
