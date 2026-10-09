// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import orphera.common.*

object Dispatcher:

  /** Runs the apt or the dnf implementation of a package task, whichever this
    * host has; a host with neither gets an immediate FAILED RESULT.
    */
  private def byPackageManager(
      queue: Queue[IO, Event],
      apt: => IO[Unit],
      dnf: => IO[Unit]
  ): IO[Unit] =
    PackageManager.detect.flatMap {
      case Some(PackageManager.Kind.Apt) => apt
      case Some(PackageManager.Kind.Dnf) => dnf
      case None                          =>
        queue.offer(
          Event(
            kind = Event.Kind.RESULT,
            message =
              "FAILED: no supported package manager found (need apt-get or dnf)",
            exitCode = 1,
            success = false
          )
        )
    }

  def dispatch(cmd: InstallPackages, queue: Queue[IO, Event]): IO[Unit] =
    byPackageManager(
      queue,
      AptInstaller.install(cmd, queue),
      DnfInstaller.install(cmd, queue)
    )

  def dispatchRemove(cmd: RemovePackages, queue: Queue[IO, Event]): IO[Unit] =
    byPackageManager(
      queue,
      AptInstaller.remove(cmd, queue),
      DnfInstaller.remove(cmd, queue)
    )

  def dispatchAutoRemove(cmd: AutoRemove, queue: Queue[IO, Event]): IO[Unit] =
    byPackageManager(
      queue,
      AptInstaller.autoRemove(cmd, queue),
      DnfInstaller.autoRemove(cmd, queue)
    )

  def dispatchDistUpgrade(cmd: DistUpgrade, queue: Queue[IO, Event]): IO[Unit] =
    byPackageManager(
      queue,
      AptInstaller.distUpgrade(cmd, queue),
      DnfInstaller.distUpgrade(cmd, queue)
    )

  def dispatchInstallDeb(cmd: InstallDeb, queue: Queue[IO, Event]): IO[Unit] =
    byPackageManager(
      queue,
      DebInstaller.install(cmd, queue),
      RpmInstaller.install(cmd, queue)
    )

  def dispatchRunCommand(
      cmd: RunCommandRequest,
      queue: Queue[IO, Event]
  ): IO[Unit] =
    CommandRunner.run(cmd, queue)
