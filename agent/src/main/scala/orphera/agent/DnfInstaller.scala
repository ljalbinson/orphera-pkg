// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import orphera.common.*

/** The dnf (Rocky, Alma, RHEL, Fedora) counterpart of AptInstaller, speaking
  * the same protocol: PROGRESS/OUTPUT events and exactly one terminal RESULT.
  * The wire messages are package-manager neutral, so their apt-flavoured
  * fields map as follows: `updateCache` refreshes repository metadata
  * (`dnf makecache`), `purge` has no dnf equivalent and is ignored, and a
  * `name=version` package becomes dnf's `name-version`.
  */
object DnfInstaller:

  private val dnf = List("dnf", "-y")

  private def dnfPackage(spec: String): String = spec.replaceFirst("=", "-")

  def install(cmd: InstallPackages, queue: Queue[IO, Event]): IO[Unit] =
    val update =
      if cmd.updateCache then
        PackageSteps.runNoResult(
          dnf ++ List("makecache"),
          "Updating package cache",
          queue
        )
      else IO.unit

    val installCommand = dnf ++ List("install") ++ cmd.packages.map(dnfPackage)

    // As in AptInstaller.install: a failed cache update must end the stream
    // with a FAILED RESULT (runNoResult only raises).
    update.attempt.flatMap {
      case Right(_) =>
        PackageSteps.runInstallWithPostCheck(
          installCommand,
          "Installing packages",
          cmd.packages,
          queue,
          pkg => List("rpm", "-ql", pkg)
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
    PackageSteps.run(
      dnf ++ List("remove") ++ cmd.packages,
      "Removing packages",
      queue
    )

  def autoRemove(cmd: AutoRemove, queue: Queue[IO, Event]): IO[Unit] =
    PackageSteps.run(dnf ++ List("autoremove"), "Auto-removing packages", queue)

  /** `dnf upgrade` (Red Hat has no separate dist-upgrade: it applies all
    * available updates within the installed release). Modified config files
    * are kept by rpm, with the new version saved as `.rpmnew`.
    */
  def distUpgrade(cmd: DistUpgrade, queue: Queue[IO, Event]): IO[Unit] =
    val refresh = if cmd.updateCache then List("--refresh") else Nil
    PackageSteps.run(
      dnf ++ List("upgrade") ++ refresh,
      "Upgrading packages (dnf upgrade)",
      queue
    )
