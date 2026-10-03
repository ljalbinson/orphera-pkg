import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_add_osds extends OrpheraClusterPlaybook:

  // Which disks become OSDs, and on which hosts, is controlled by the
  // shared inventory (manifests/inventory.yaml), under each node's
  // `osd_disks` var — not a Map literal in this file. Edit that file to
  // change device selection; leave a node's osd_disks var unset (or
  // empty) to skip OSD creation there entirely.
  //
  // Devices are identified by their stable /dev/disk/by-id/ path, NOT by
  // kernel-assigned /dev/sdX letters — see cephadm_teardown.scala's
  // devicesToZap comment for the full incident writeup. Short version:
  // /dev/sdX letters can move across a reboot, and on 2026-09-28 tst0's
  // OS disk landed on /dev/sdb — the device this playbook had hardcoded
  // as a spare OSD disk at the time — so it attempted to add the running
  // root filesystem's own disk as an OSD (ceph-volume correctly refused
  // with "Device /dev/sdb has partitions"). by-id paths are tied to the
  // underlying QEMU drive and don't move across reboots — this is also
  // Ceph's own recommended practice for DriveGroup device specifications,
  // for exactly this reason.
  //
  // The device list used to be a Map literal hardcoded directly in this
  // file, duplicated from an independently hardcoded Map in
  // cephadm_teardown.scala — two copies of the same information that
  // silently drifted apart, which is exactly how the /dev/sdX bug above
  // happened. Reading both from the one inventory file removes the
  // second copy that can drift.
  //
  // Devices must already show as available before running this playbook:
  //   cephadm shell -- ceph orch device ls
  // A device with a stale LVM/filesystem signature from a prior run (e.g.
  // after ceph-teardown.yaml) will be rejected — re-wipe it first.
  //
  // Only one of tst1/tst2's two spare disks is selected in the inventory
  // right now, matching the original intent (single-OSD-per-node on
  // those two) — the other spare disk on each is still in that node's
  // zap_disks var, so cephadm_teardown.scala's broader sweep still
  // covers it, just not turned into an OSD by this playbook.
  private val hosts: List[String] = List("tst0", "tst1", "tst2")

  val osdDevices: Map[String, List[String]] =
    hosts.map(h => h -> Inventory.csvVar(h, "osd_disks")).toMap

  private val expectedOsdCount: Int =
    osdDevices.values.map(_.size).sum

  // One explicit "ceph orch daemon add osd host:device" task per device,
  // rather than a DriveGroup spec — this makes exactly which disk went
  // where visible in the playbook run log, and lets you re-run safely:
  // an already-consumed device just fails that one task without touching
  // the others.
  private val addOsdTasks: List[(String, Task.RunCommand)] =
    for
      (host, devices) <- osdDevices.toList.sortBy(_._1)
      device <- devices
    yield s"add osd on $host:$device" -> Task.RunCommand(
      List(
        "sh",
        "-c",
        s"cephadm shell -- ceph orch daemon add osd $host:$device"
      ),
      timeoutSeconds = 120
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-add-osds")(
      addOsdTasks
        .foldLeft(stage("apply-osd-spec", "tst0")) {
          case (builder, (name, task)) =>
            builder.task(name)(task)
        }
        .build,

      stage("confirm-osds", "tst0")
        .waitFor(
          // Asserts the real condition we care about — every OSD registered
          // so far (>= the count this run added) is both up and in — rather
          // than grepping a formatted mon-quorum string that has nothing to
          // do with OSDs (that was the bug in the original confirm-osds).
          HealthCheck.Quorum(
            nodes = List("tst0"),
            command = List(
              "sh",
              "-c",
              "cephadm shell -- ceph osd stat -f json 2>/dev/null | " +
                "python3 -c 'import json,sys; d=json.load(sys.stdin); " +
                s"sys.exit(0 if d[\"num_osds\"]==d[\"num_up_osds\"]==d[\"num_in_osds\"]>=$expectedOsdCount else 1)'"
            ),
            requiredCount = 1,
            pollIntervalSeconds = 10,
            timeoutSeconds = 300
          )
        )
        .task("osds confirmed")(
          Task.Debug(
            s"All $expectedOsdCount specified OSD device(s) are up and in."
          )
        )
        .build
    )
