import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_osd extends OrpheraClusterPlaybook:

  private val checkDeviceScript =
    """test -b /dev/sdb || { echo "/dev/sdb is not a block device"; exit 1; }
      |if lsblk -no MOUNTPOINT /dev/sdb | grep -q .; then
      |  echo "/dev/sdb appears to be mounted — refusing to proceed"
      |  exit 1
      |fi""".stripMargin

  private val confirmOsdsScript =
    "ceph osd stat 2>/dev/null | grep -Eq '3 osds: 3 up'"

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-osd")(

      stage("install-osd-daemon", "tst0", "tst1", "tst2")
        .task("install ceph-osd")(
          Task.Install(packages = List("ceph-osd"), updateCache = true, version = "{{ceph_version}}")
        )
        .build,

      stage("prepare-and-activate", "tst0", "tst1", "tst2")
        .task("confirm /dev/sdb is a real, unpartitioned block device")(
          Task.RunCommand(List("sh", "-c", checkDeviceScript))
        )
        .task("create OSD on /dev/sdb")(
          Task.RunCommand(List("sh", "-c", "ceph-volume lvm create --data /dev/sdb"), timeoutSeconds = 300)
        )
        .build,

      stage("confirm-osds", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", confirmOsdsScript),
            requiredCount = 1,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("osds confirmed")(
          Task.Debug("All 3 OSDs are up and in.")
        )
        .build
    )
