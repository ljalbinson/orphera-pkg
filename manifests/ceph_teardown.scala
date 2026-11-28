// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// A rebuilt Ceph cluster starts empty: the pool `k8s-rbd` and the user
// `client.k8s` that Kubernetes volumes depend on are gone. After a rebuild, run
// k8s_ceph_csi.scala to make both again; volumes on the old cluster are lost.
object ceph_teardown extends OrpheraClusterPlaybook:

  // Per mon: its unit stopped (a unit that is already gone or already stopped is unchanged), then its data
  // directory and its configuration files removed. The unit is named after the host, so each mon has its own
  // description and its own stage.
  private def removeMonPlan(id: String): ApplyPlan =
    import Resources.{absentDirectory, absentFile, command, service}
    val reset = command(
      s"reset the failed state of ceph-mon@$id",
      s"systemctl reset-failed ceph-mon@$id",
      onlyIf = s"systemctl is-failed --quiet ceph-mon@$id"
    )
    val stopped =
      service(s"ceph-mon@$id", enabled = None, running = Some(false))
    val data = absentDirectory(s"/var/lib/ceph/mon/ceph-$id", recursive = true)
      .after(stopped)
    val files = List(
      "/etc/ceph/ceph.conf",
      "/etc/ceph/monmap",
      "/etc/ceph/fsid",
      "/etc/ceph/ceph.mon.keyring",
      "/etc/ceph/ceph.client.admin.keyring"
    ).map(f => absentFile(f).after(stopped))
    ApplyPlan.orFail(
      s"ceph-mon-$id-removed",
      List(stopped, reset.after(stopped), data) ++ files
    )

  // What is left once the packages are gone. /var/run/ceph is under a link (/var/run is /run) and the contents of
  // /etc/ceph are not a directory, so those two stay a guarded command.
  private val remainingPlan: ApplyPlan =
    import Resources.{absentDirectory, command}
    val libDir = absentDirectory("/var/lib/ceph", recursive = true)
    val logDir = absentDirectory("/var/log/ceph", recursive = true)
    val rest = command(
      "remove what is left in /etc/ceph and /var/run/ceph",
      """rm -rf /var/run/ceph
        |rm -rf /etc/ceph/*""".stripMargin,
      unless =
        """[ ! -e /var/run/ceph ] && [ -z "$(ls -A /etc/ceph 2>/dev/null)" ]"""
    )
    ApplyPlan.orFail("ceph-remaining", List(libDir, logDir, rest))

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-teardown")(
      stage("remove-mon-tst0", "tst0")
        .task("stop the mon service, remove mon data and ceph config")(
          Task.Apply(removeMonPlan("tst0"))
        )
        .build,

      stage("remove-mon-tst1", "tst1")
        .task("stop the mon service, remove mon data and ceph config")(
          Task.Apply(removeMonPlan("tst1"))
        )
        .build,

      stage("remove-mon-tst2", "tst2")
        .task("stop the mon service, remove mon data and ceph config")(
          Task.Apply(removeMonPlan("tst2"))
        )
        .build,

      stage("remove-packages", "tst0", "tst1", "tst2")
        .task("purge ceph packages")(
          Task.Remove(
            packages = List("ceph-mon", "ceph-base", "ceph-common"),
            purge = true
          )
        )
        .task("autoremove leftover dependencies")(
          Task.AutoRemove(purge = true)
        )
        .task("remove remaining ceph directories")(
          Task.Apply(remainingPlan)
        )
        .build
    )
