import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_teardown extends OrpheraClusterPlaybook:

  // Replaces ceph-teardown.yaml for the cephadm-managed cluster. That
  // script's "stop OSD/mon service(s)" steps matched raw systemd unit
  // names (ceph-mon@*, ceph-osd@*) that don't exist under cephadm —
  // daemons run as podman containers under per-fsid templated units — so
  // those steps silently no-op'd, left daemons running, and every
  // subsequent zap failed with "Can't open /dev/sdb exclusively. Mounted
  // filesystem?" / "device has a signature". Confirmed twice in testing,
  // both from a "dirty" pre-existing cluster and from what were assumed
  // to be clean VMs (the disk itself had never actually been wiped).
  //
  // `cephadm rm-cluster` is the actual supported way to tear down a
  // cephadm-managed host: it stops and removes that host's daemons and
  // containers for a given fsid, and clears the local ceph directories.
  // It's local-only — no cluster-wide coordination needed — so it's safe
  // to run per-host in any order, including against a completely broken
  // or partially-torn-down cluster.
  //
  // Devices are identified by their stable /dev/disk/by-id/ path, NOT by
  // kernel-assigned /dev/sdX letters. Confirmed by testing (2026-09-28,
  // live incident on tst0): SCSI/virtio device letters are NOT guaranteed
  // stable across a reboot — the enumeration order at boot can shift
  // which physical/virtual disk gets which letter. A reboot moved tst0's
  // OS disk onto /dev/sdb — the exact device this playbook and
  // cephadm_add_osds.scala each separately hardcoded as a spare OSD disk.
  // /dev/disk/by-id/... paths are symlinks tied to the underlying QEMU
  // drive (scsi0-0-0-<N>), fixed at VM-definition time, and do NOT change
  // across reboots, unlike the /dev/sdX name the kernel happens to assign
  // this particular boot.
  //
  // The by-id values themselves now live in the shared inventory
  // (manifests/inventory.yaml), under each node's `zap_disks` var, NOT
  // hardcoded here. The first pass at this fix put a hardcoded Map in
  // this file AND a separately hardcoded Map in cephadm_add_osds.scala —
  // which is exactly how the original /dev/sdX bug happened in the first
  // place (two independently-maintained lists silently drifting apart),
  // and one of the two by-id entries for tst0 did in fact turn out wrong
  // in the first pass. Reading both from one inventory file removes the
  // second copy that can drift. zap_disks is deliberately the FULL set of
  // spare disks per host, not just whichever ones cephadm_add_osds.scala
  // currently selects as OSDs (see that file's `osd_disks` var) — a disk
  // not currently selected as an OSD can still carry a stale signature
  // from an earlier run's different selection.
  val hosts: List[String] = List("tst0", "tst1", "tst2")
  val devicesToZap: Map[String, List[String]] =
    hosts.map(h => h -> Inventory.csvVar(h, "zap_disks")).toMap

  private val removeClusterScript: String =
    """
      |# Stop and remove EVERY podman container on this host directly,
      |# before anything else — not just ones `cephadm ls` can enumerate.
      |# Found in testing: a container can still be running and holding a
      |# device open (e.g. an OSD's LV) even though `cephadm ls` reports
      |# zero daemons, if an earlier cleanup's directory sweep deleted a
      |# daemon's metadata without stopping its container first. Relying
      |# on cephadm's own bookkeeping alone is fragile once that metadata
      |# is gone — these are single-purpose test hosts, so unconditionally
      |# stopping every podman container here is safe and closes that gap.
      |podman stop -a 2>/dev/null
      |podman rm -fa 2>/dev/null
      |true
      |
      |# Handle EVERY fsid found, not just one — a host can carry leftover
      |# state from more than one prior cluster (e.g. two install/teardown
      |# cycles without a full clean between them), and cephadm's own fsid
      |# auto-inference refuses to guess when it sees multiple candidate
      |# /var/lib/ceph/<fsid> directories, failing every subsequent command
      |# with "Cannot infer an fsid, one must be specified" — exactly what
      |# happened here.
      |FSIDS=$(cephadm ls --no-detail 2>/dev/null | python3 -c '
      |import json, sys
      |try:
      |    ls = json.load(sys.stdin)
      |except Exception:
      |    ls = []
      |fsids = sorted(set(d.get("fsid", "") for d in ls if d.get("fsid")))
      |print(" ".join(fsids))
      |')
      |if [ -n "$FSIDS" ]; then
      |  for fsid in $FSIDS; do
      |    echo "Found cephadm cluster $fsid on this host — removing daemons/containers"
      |    cephadm rm-cluster --fsid "$fsid" --force
      |  done
      |else
      |  echo "No cephadm-managed daemons found on this host"
      |fi
      |# Unconditional final sweep: removes any fsid directory rm-cluster
      |# didn't know about (e.g. orphaned from a crashed/partial earlier
      |# run with no daemons left to report it), so a later `cephadm shell`
      |# invocation never again finds more than one fsid to infer from.
      |rm -rf /var/lib/ceph/* /etc/ceph/* /var/log/ceph/* /var/run/ceph/* 2>/dev/null
      |true
      |""".stripMargin

  // Confirmed by testing: podman-level cleanup does NOT fix this. A
  // ceph-created LV's /dev/mapper/<vg>-<lv> entry is a kernel
  // device-mapper table entry, independent of any process holding it
  // open — it persists until explicitly torn down with `dmsetup remove`.
  // Once a device's on-disk LVM signature has been wiped (e.g. by a
  // prior, incomplete zap), `pvs`/`vgs` can no longer see the VG/LV at
  // all, so a cleanup that only walks `pvs -o vg_name <dev>` silently
  // skips that device forever while the orphaned mapper entry keeps
  // refusing every later zap/OSD-create attempt against it ("Refusing
  // to zap the mapper device"). This sweep is host-wide (dm names
  // aren't tied to a specific /dev/sdX) and runs unconditionally, ahead
  // of and independent of the pvs-based per-device loop below.
  private val dmCleanupScript: String =
    """
      |for dm in $(dmsetup ls 2>/dev/null | awk '{print $1}' | grep -E '^ceph-'); do
      |  echo "Removing orphaned device-mapper entry: $dm"
      |  dmsetup remove -f "$dm" 2>/dev/null
      |done
      |true
      |""".stripMargin

  private def zapDevicesScript(host: String): String =
    dmCleanupScript + "\n" + devicesToZap.getOrElse(host, Nil).map { dev =>
      s"""
        |# Defense in depth, independent of by-id correctness: if this
        |# device (or any partition on it) is actually mounted right now,
        |# it is by definition not a spare OSD disk — refuse to touch it
        |# rather than trust the device list alone. This is exactly the
        |# check that would have caught the tst0 incident even before the
        |# by-id fix existed.
        |MOUNTED=$$(lsblk -rno MOUNTPOINT $dev 2>/dev/null | grep -v '^$$' || true)
        |if [ -n "$$MOUNTED" ]; then
        |  echo "REFUSING to zap $dev on $host — mounted at: $$MOUNTED"
        |else
        |for vg in $$(timeout 10 pvs --noheadings -o vg_name $dev 2>/dev/null | tr -d ' '); do
        |  [ -n "$$vg" ] || continue
        |  timeout 15 lvremove -f "$$vg" 2>/dev/null
        |  timeout 15 vgremove -f "$$vg" 2>/dev/null
        |done
        |timeout 15 pvremove -ff -y $dev 2>/dev/null || true
        |
        |# GPT keeps a backup partition table at the END of the disk
        |# (that's the whole point of it — it's meant to survive damage
        |# to the front). Confirmed by testing: zeroing only the first
        |# few MB removes the primary header, so nothing sees partitions
        |# right after teardown, but the backup header is untouched —
        |# the very next time something rescans the disk from scratch
        |# (a host reboot, in particular) the kernel finds the backup
        |# GPT table and reconstructs a partition, and the next add-osd
        |# run fails with "Device ... has partitions" even though
        |# nothing touched the disk in between. sgdisk --zap-all
        |# explicitly destroys both the primary and backup GPT
        |# structures (and any MBR), so it goes first; wipefs/dd/
        |# partprobe stay as belt-and-suspenders for anything sgdisk
        |# doesn't recognize (isn't installed, non-GPT signatures, etc).
        |sgdisk --zap-all $dev 2>/dev/null || true
        |wipefs -a $dev 2>/dev/null || true
        |dd if=/dev/zero of=$dev bs=1M count=10 oflag=direct,dsync 2>/dev/null || true
        |SIZE_BYTES=$$(blockdev --getsize64 $dev 2>/dev/null || echo 0)
        |if [ "$$SIZE_BYTES" -gt 10485760 ]; then
        |  SEEK_MB=$$(( SIZE_BYTES / 1048576 - 10 ))
        |  dd if=/dev/zero of=$dev bs=1M count=10 seek=$$SEEK_MB oflag=direct,dsync 2>/dev/null || true
        |fi
        |partprobe $dev 2>/dev/null || true
        |fi
        |""".stripMargin
    }.mkString("\n") + "true\n"

  private def teardownStage(host: String) =
    stage(s"teardown-$host", host)
      .task(s"remove cephadm-managed cluster on $host (if any)")(
        Task.RunCommand(List("sh", "-c", removeClusterScript), timeoutSeconds = 120)
      )
      .task(s"zap OSD disks on $host")(
        Task.RunCommand(List("sh", "-c", zapDevicesScript(host)), timeoutSeconds = 90)
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-teardown")(
      hosts.map(teardownStage)*
    )
