// SPDX-License-Identifier: Apache-2.0

package orphera.common

import java.nio.file.{Files, Path, Paths}

import io.grpc.{Status, StatusRuntimeException}

/** A host's own refusal to be rebooted or powered off, kept on the host so that
  * it does not depend on which orchestrator is calling or how current it is.
  *
  * The inventory's `never-reboot` is checked by the orchestrator, but only a
  * current orchestrator checks it: an older build sent a shutdown straight to a
  * hypervisor. A host that must stay up therefore also carries a marker file,
  * `/etc/orphera-agent/never-reboot` (or the path in `ORPHERA_NEVER_REBOOT_FILE`),
  * and its agent refuses `TriggerReboot` and `TriggerShutdown` while the file
  * exists. The file is looked at on every request, so creating or removing it
  * needs no agent restart. `utils/sync-never-reboot.py` creates it from the
  * inventory.
  */
object NeverReboot:
  val DefaultMarker: String = "/etc/orphera-agent/never-reboot"
  val EnvVar: String = "ORPHERA_NEVER_REBOOT_FILE"

  def markerPath(env: Map[String, String]): Path =
    Paths.get(env.getOrElse(EnvVar, DefaultMarker))

  def isProtected(marker: Path): Boolean = Files.exists(marker)

  /** The error a protected host's agent answers with. `action` reads as "refuses
    * to <action>", for example "reboot" or "power off".
    */
  def refusal(action: String, marker: Path): StatusRuntimeException =
    Status.FAILED_PRECONDITION
      .withDescription(
        s"never-reboot: this host has $marker, so its agent refuses to $action " +
          "(remove that file to allow it)"
      )
      .asRuntimeException()
