import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_mon_keyring extends OrpheraClusterPlaybook:

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-mon-keyring")(

      stage("install-tools", "tst0", "tst1", "tst4")
        .task("install ceph-common (pinned version)")(
          Task.Install(packages = List("ceph-common"), updateCache = true, version = "{{ceph_version}}")
        )
        .build,

      stage("generate", "tst0")
        .task("generate mon keyring")(
          Task.RunCommand(
            List("sh", "-c", "ceph-authtool /etc/ceph/ceph.mon.keyring --create-keyring --gen-key -n mon. --cap mon 'allow *'")
          )
        )
        .build,

      stage("distribute", "tst1", "tst4")
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
