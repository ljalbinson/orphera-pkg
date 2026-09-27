import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_add_osds extends OrpheraClusterPlaybook:

  // Edit this map to control exactly which disks become OSDs, and on which
  // hosts — no more "--all-available-devices". Leave a host out of the map
  // (or give it an empty list) to skip OSD creation there entirely.
  //
  // Devices must already show as available before running this playbook:
  //   cephadm shell -- ceph orch device ls
  // A device with a stale LVM/filesystem signature from a prior run (e.g.
  // after ceph-teardown.yaml) will be rejected — re-wipe it first.
  val osdDevices: Map[String, List[String]] = Map(
    "tst0" -> List("/dev/sdb"),
    "tst1" -> List("/dev/sdb"),
    "tst2" -> List("/dev/sdb")
  )

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
      device          <- devices
    yield
      s"add osd on $host:$device" -> Task.RunCommand(
        List("sh", "-c", s"cephadm shell -- ceph orch daemon add osd $host:$device"),
        timeoutSeconds = 120
      )

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-add-osds")(

      addOsdTasks
        .foldLeft(stage("apply-osd-spec", "tst0")) { case (builder, (name, task)) =>
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
          Task.Debug(s"All $expectedOsdCount specified OSD device(s) are up and in.")
        )
        .build
    )
