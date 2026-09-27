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
  // Devices are zapped broadly (not just whatever cephadm_add_osds.scala's
  // osdDevices map currently lists) since a disk used by an earlier run —
  // even one no longer in that map — can still carry a stale LVM
  // signature that trips up a later apply-osd-spec run.
  val hosts: List[String] = List("tst0", "tst1", "tst2")
  val devicesToZap: List[String] = List("/dev/sdb", "/dev/sdc")

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

  private val zapDevicesScript: String =
    dmCleanupScript + "\n" + devicesToZap.map { dev =>
      s"""
        |for vg in $$(timeout 10 pvs --noheadings -o vg_name $dev 2>/dev/null | tr -d ' '); do
        |  [ -n "$$vg" ] || continue
        |  timeout 15 lvremove -f "$$vg" 2>/dev/null
        |  timeout 15 vgremove -f "$$vg" 2>/dev/null
        |done
        |timeout 15 pvremove -ff -y $dev 2>/dev/null || true
        |wipefs -a $dev 2>/dev/null || true
        |dd if=/dev/zero of=$dev bs=1M count=10 oflag=direct,dsync 2>/dev/null || true
        |partprobe $dev 2>/dev/null || true
        |""".stripMargin
    }.mkString("\n") + "true\n"

  private def teardownStage(host: String) =
    stage(s"teardown-$host", host)
      .task(s"remove cephadm-managed cluster on $host (if any)")(
        Task.RunCommand(List("sh", "-c", removeClusterScript), timeoutSeconds = 120)
      )
      .task(s"zap OSD disks on $host")(
        Task.RunCommand(List("sh", "-c", zapDevicesScript), timeoutSeconds = 90)
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-teardown")(
      hosts.map(teardownStage)*
    )
