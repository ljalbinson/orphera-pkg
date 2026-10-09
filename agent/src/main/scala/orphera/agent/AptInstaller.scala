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
  ): IO[Unit] = PackageSteps.run(command, stage, queue)

  private def runNoResult(
      command: List[String],
      stage: String,
      queue: Queue[IO, Event]
  ): IO[Unit] = PackageSteps.runNoResult(command, stage, queue)

  private def runInstallWithPostCheck(
      command: List[String],
      stage: String,
      packages: Seq[String],
      queue: Queue[IO, Event]
  ): IO[Unit] =
    PackageSteps.runInstallWithPostCheck(
      command,
      stage,
      packages,
      queue,
      pkg => List("dpkg", "-L", pkg)
    )
