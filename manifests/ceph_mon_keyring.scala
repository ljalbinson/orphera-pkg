import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_mon_keyring extends OrpheraClusterPlaybook:

  private val generateMonKeyringScript =
    """for i in $(seq 1 10); do
      |  command -v ceph-authtool >/dev/null 2>&1 && break
      |  sleep 1
      |done
      |ceph-authtool /etc/ceph/ceph.mon.keyring --create-keyring --gen-key -n mon. --cap mon 'allow *'""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-mon-keyring")(

      stage("install-tools", "tst0", "tst1", "tst2")
        .task("install ceph-common (pinned version)")(
          Task.Install(packages = List("ceph-common", "ceph-base"), updateCache = true, version = "{{ceph_version}}")
        )
        .build,

      stage("generate", "tst0")
        .task("generate mon keyring")(
          Task.RunCommand(List("sh", "-c", generateMonKeyringScript))
        )
        .build,

      stage("distribute", "tst1", "tst2")
        .task("distribute keyring from tst0")(
          Task.DistributeFile(
            sourceNode = "tst0",
            sourcePath = "/etc/ceph/ceph.mon.keyring",
            destPath = "/etc/ceph/ceph.mon.keyring",
            owner = "root",
            group = "root",
            mode = 384 // 0600 in decimal
          )
        )
        .build
    )
