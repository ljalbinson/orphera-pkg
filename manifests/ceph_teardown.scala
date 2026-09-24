import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_teardown extends OrpheraClusterPlaybook:

  private val stopMonServiceScript =
    "systemctl stop ceph-mon@$(hostname -s) 2>/dev/null; systemctl reset-failed ceph-mon@$(hostname -s) 2>/dev/null; true"

  private val removeMonDataScript =
    """MONID=$(hostname -s)
      |rm -rf /var/lib/ceph/mon/ceph-$MONID
      |rm -f /etc/ceph/ceph.conf /etc/ceph/monmap /etc/ceph/fsid
      |rm -f /etc/ceph/ceph.mon.keyring /etc/ceph/ceph.client.admin.keyring
      |true""".stripMargin

  private val removeRemainingDirsScript =
    """rm -rf /var/lib/ceph /var/log/ceph /var/run/ceph
      |rm -rf /etc/ceph/*
      |true""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-teardown")(

      stage("stop-and-wipe", "tst0", "tst1", "tst2")
        .task("stop mon service")(
          Task.RunCommand(List("sh", "-c", stopMonServiceScript))
        )
        .task("remove mon data and ceph config")(
          Task.RunCommand(List("sh", "-c", removeMonDataScript))
        )
        .build,

      stage("remove-packages", "tst0", "tst1", "tst2")
        .task("purge ceph packages")(
          Task.Remove(packages = List("ceph-mon", "ceph-base", "ceph-common"), purge = true)
        )
        .task("autoremove leftover dependencies")(
          Task.AutoRemove(purge = true)
        )
        .task("remove remaining ceph directories")(
          Task.RunCommand(List("sh", "-c", removeRemainingDirsScript))
        )
        .build
    )
