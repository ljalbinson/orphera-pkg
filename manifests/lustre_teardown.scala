// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Removes the Lustre file system that lustre_cluster.scala builds: unmounts the
// clients, then the OSTs, then the MDTs and the MGT, and wipes the signatures
// off every disk listed in each server's `lustre_targets` (inventory.yaml).
// ALL DATA IN THE FILE SYSTEM IS LOST.
//
// Left in place, because they are slow to redo and harmless: the Lustre
// packages and kernel, the version locks, the repository file, LNet and its
// modprobe file, SELinux permissive. lustre_cluster.scala then rebuilds from
// the format stage on.
//
// Run:   orphera cluster-playbook manifests/lustre_teardown.scala
// Uses the same LUSTRE_FSNAME, LUSTRE_SERVERS and LUSTRE_CLIENTS settings as
// lustre_cluster.scala, and unmounts the Ubuntu clients of lustre_client.scala
// first too (LUSTRE_UBUNTU_CLIENTS, default tst49; set it to "" when that node is
// down, or the first stage fails there and nothing is wiped). The clients keep
// their Lustre build. A disk that is still mounted after the unmounts (or is
// not a block device) stops the run rather than being wiped.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object lustre_teardown extends OrpheraClusterPlaybook:

  private def envList(name: String, default: String): List[String] =
    sys.env
      .getOrElse(name, default)
      .split("[ ,]+")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private val fsName = sys.env.getOrElse("LUSTRE_FSNAME", "lfs0")
  private val servers =
    envList("LUSTRE_SERVERS", "tst43 tst44 tst45 tst46 tst47 tst48")
  private val clients =
    (envList("LUSTRE_CLIENTS", "tst43 tst50") ++ envList(
      "LUSTRE_UBUNTU_CLIENTS",
      "tst49"
    )).distinct

  private val helpers =
    """fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
      |targets() { printf '%s\n' '{{{lustre_targets}}}' | tr ',' '\n' | sed -n 's/^\([a-z0-9]*\):\(.*\)$/\1 \2/p'; }
      |""".stripMargin

  private val clientUmountScript =
    """mp=/mnt/@FSNAME@
      |if mountpoint -q "$mp"; then umount "$mp" || umount -f "$mp"; echo "unmounted $mp"; else echo "$mp not mounted"; fi
      |""".stripMargin.replace("@FSNAME@", fsName)

  // Unmounts this node's targets whose role starts with one of `roles`.
  private def umountScript(roles: String): String = (helpers +
    """set -e
      |for want in @ROLES@; do
      |  targets | while read -r role dev; do
      |    case "$role" in "$want"*) ;; *) continue ;; esac
      |    if [ "$role" = mgt ]; then mp=/lustre/mgs; else mp="/lustre/@FSNAME@-$role"; fi
      |    if mountpoint -q "$mp"; then umount "$mp"; echo "$role: unmounted $mp"; else echo "$role: not mounted"; fi
      |  done
      |done""".stripMargin)
    .replace("@ROLES@", roles)
    .replace("@FSNAME@", fsName)

  private val wipeScript = helpers +
    """set -e
      |targets | while read -r role dev; do
      |  [ -b "$dev" ] || fail "$dev (for $role) is not a block device"
      |  real=$(readlink -f "$dev")
      |  if grep -q "^$real " /proc/mounts; then fail "$dev ($real) is still mounted"; fi
      |  wipefs -a -q "$dev"
      |  echo "$role: wiped $dev"
      |done
      |rmdir /lustre/* 2>/dev/null || true""".stripMargin

  private def sh(script: String, timeout: Int = 300): Task =
    Task.RunCommand(List("sh", "-c", script), timeoutSeconds = timeout)

  val playbook: ClusterPlaybook =
    clusterPlaybook("lustre-teardown")(
      stage("umount-clients", clients*)
        .task(s"unmount /mnt/$fsName")(sh(clientUmountScript))
        .build,
      stage("umount-ost", servers*)
        .task("unmount the OSTs")(sh(umountScript("ost")))
        .build,
      stage("umount-mdt-mgt", servers*)
        .task("unmount the MDTs, then the MGT")(sh(umountScript("mdt mgt")))
        .build,
      stage("wipe", servers*)
        .task("wipe the target disks")(sh(wipeScript))
        .build
    )
