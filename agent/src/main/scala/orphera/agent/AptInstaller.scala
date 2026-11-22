// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue

import orphera.common.*

object AptInstaller:

  // How long apt/dpkg waits for another process (typically unattended-upgrades
  // on a freshly provisioned VM, which can run for several minutes) to release
  // the dpkg lock before giving up with exit 100. Was 60 s, which was too short.
  private val lockTimeout = "DPkg::Lock::Timeout=600"

  /** `apt-get update` under a time limit (180 s, or APT_UPDATE_TIMEOUT), tried
    * up to three times when the limit is hit. A caching proxy that stalls
    * leaves apt waiting for ever otherwise, and with seven nodes asking for the
    * same file at once that does happen (apt-cacher-ng fails the clients that
    * collide). Any other failure is returned at once. The Go agent has the same
    * script (aptUpdateScript).
    */
  val updateScript: String =
    """for try in 1 2 3; do
  log=$(mktemp)
  timeout -k 10 "${APT_UPDATE_TIMEOUT:-180}" apt-get "$@" update > "$log" 2>&1
  rc=$?
  cat "$log"
  bad=0
  if grep -qE '^(Err:|W: Failed to fetch)' "$log"; then bad=1; fi
  rm -f "$log"
  if [ "$rc" -ne 124 ] && [ "$rc" -ne 137 ] && [ "$bad" -eq 0 ]; then exit "$rc"; fi
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
    echo "apt-get update did not finish in ${APT_UPDATE_TIMEOUT:-180} s (try $try of 3)" >&2
  else
    echo "apt-get update could not fetch every index (try $try of 3)" >&2
  fi
  if [ "$try" -lt 3 ]; then sleep "${APT_UPDATE_PAUSE:-5}"; fi
done
exit "$rc""""

  /** The command for `apt-get <opts> update` with that limit. */
  def updateCommand(opts: List[String]): List[String] =
    List("sh", "-c", updateScript, "apt-update") ++ opts

  def install(cmd: InstallPackages, queue: Queue[IO, Event]): IO[Unit] =
    val update =
      if cmd.updateCache then
        runNoResult(
          updateCommand(List("-o", lockTimeout)),
          "Updating package cache",
          queue
        )
      else IO.unit

    val installCommand =
      List(
        "apt-get",
        "-o",
        lockTimeout,
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
        lockTimeout,
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
        lockTimeout,
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
      lockTimeout,
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
          updateCommand(aptOptions),
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
