// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_mgr extends OrpheraClusterPlaybook:

  private val bootstrapMgrScript =
    """MGRID=$(hostname -s)
      |mkdir -p /var/lib/ceph/mgr/ceph-$MGRID
      |ceph auth get-or-create mgr.$MGRID \
      |  mon 'allow profile mgr' osd 'allow *' mds 'allow *' \
      |  -o /var/lib/ceph/mgr/ceph-$MGRID/keyring
      |chown -R ceph:ceph /var/lib/ceph/mgr/ceph-$MGRID""".stripMargin

  // The mgr unit is enabled and running; a unit that failed earlier is reset first, as the script it replaces did.
  private val startMgrPlan: ApplyPlan =
    import Resources.{command, service}
    val reset = command(
      "reset the failed state of ceph-mgr@tst0",
      "systemctl reset-failed ceph-mgr@tst0",
      onlyIf = "systemctl is-failed --quiet ceph-mgr@tst0"
    )
    ApplyPlan.orFail(
      "ceph-mgr-running",
      List(reset, service("ceph-mgr@tst0").after(reset))
    )

  private val confirmMgrScript =
    "ceph -s 2>/dev/null | grep -q 'mgr:.*active'"

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-mgr")(
      stage("install-mgr-daemon", "tst0")
        .task("install ceph-mgr")(
          Task.Install(
            packages = List("ceph-mgr"),
            updateCache = true,
            version = "{{ceph_version}}"
          )
        )
        .build,

      stage("bootstrap-mgr", "tst0")
        .task("create mgr keyring and data dir")(
          Task.RunCommand(List("sh", "-c", bootstrapMgrScript))
        )
        .task("enable and start mgr service")(
          Task.Apply(startMgrPlan)
        )
        .build,

      stage("confirm-mgr", "tst0")
        .waitFor(
          HealthCheck.Command(
            "tst0",
            List("sh", "-c", confirmMgrScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("mgr confirmed")(
          Task.Debug("Mgr daemon active.")
        )
        .build
    )
