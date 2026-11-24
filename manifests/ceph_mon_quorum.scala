// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_mon_quorum extends OrpheraClusterPlaybook:

  private val bootstrapConfigScript =
    """FSID=$(cat /proc/sys/kernel/random/uuid)
      |cat > /etc/ceph/ceph.conf <<EOF
      |[global]
      |fsid = $FSID
      |mon_initial_members = tst0, tst1, tst2
      |mon_host = {{cluster_ip}}, {{nodes.tst1.cluster_ip}}, {{nodes.tst2.cluster_ip}}
      |auth_cluster_required = cephx
      |auth_service_required = cephx
      |auth_client_required = cephx
      |EOF
      |echo "$FSID" > /etc/ceph/fsid""".stripMargin

  private val buildMonmapScript =
    """FSID=$(cat /etc/ceph/fsid)
      |monmaptool --create --clobber --fsid "$FSID" \
      |  --add tst0 {{cluster_ip}} \
      |  --add tst1 {{nodes.tst1.cluster_ip}} \
      |  --add tst2 {{nodes.tst2.cluster_ip}} \
      |  /etc/ceph/monmap""".stripMargin

  private val mkfsScript =
    """MONID=$(hostname -s)
      |if [ -d /var/lib/ceph/mon/ceph-$MONID ] && [ ! -f /var/lib/ceph/mon/ceph-$MONID/keyring ]; then
      |  echo "Removing incomplete prior mon data dir"
      |  rm -rf /var/lib/ceph/mon/ceph-$MONID
      |fi
      |mkdir -p /var/lib/ceph/mon/ceph-$MONID
      |ceph-mon --mkfs -i $MONID --monmap /etc/ceph/monmap --keyring /etc/ceph/ceph.mon.keyring
      |test -f /var/lib/ceph/mon/ceph-$MONID/keyring || { echo "mkfs did not produce a keyring — treating as failure"; exit 1; }
      |chown -R ceph:ceph /var/lib/ceph/mon/ceph-$MONID /etc/ceph""".stripMargin

  // The mon unit is named after the host, and a description names its units outright, so each mon has its own
  // description and its own stage below. A unit that failed earlier is reset first (as the script it replaces
  // did), or systemd would refuse to start it again; the unit is then enabled and started, and a second run
  // finds it running and changes nothing (before: `systemctl enable --now` every run).
  private def startMonPlan(id: String): ApplyPlan =
    import Resources.{command, service}
    val reset = command(
      s"reset the failed state of ceph-mon@$id",
      s"systemctl reset-failed ceph-mon@$id",
      onlyIf = s"systemctl is-failed --quiet ceph-mon@$id"
    )
    ApplyPlan.orFail(
      s"ceph-mon-$id-running",
      List(reset, service(s"ceph-mon@$id").after(reset))
    )

  private val quorumCheckScript =
    """ceph --admin-daemon /var/run/ceph/ceph-mon.$(hostname -s).asok mon_status 2>/dev/null | grep -Eq '"state":\s*"(leader|peon)"'"""

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-mon-quorum")(
      stage("install-mon-daemon", "tst0", "tst1", "tst2")
        .task("install ceph-mon")(
          Task.Install(
            packages = List("ceph-mon", "ceph-base"),
            updateCache = true,
            version = "{{ceph_version}}"
          )
        )
        .build,

      stage("bootstrap-mon-config", "tst0")
        .task("generate fsid and ceph.conf")(
          Task.RunCommand(List("sh", "-c", bootstrapConfigScript))
        )
        .task("build monmap")(
          Task.RunCommand(List("sh", "-c", buildMonmapScript))
        )
        .build,

      stage("distribute-mon-config", "tst1", "tst2")
        .task("distribute ceph.conf from tst0")(
          Task.DistributeFile(
            "tst0",
            "/etc/ceph/ceph.conf",
            "/etc/ceph/ceph.conf",
            "root",
            "root",
            420
          )
        )
        .task("distribute monmap from tst0")(
          Task.DistributeFile(
            "tst0",
            "/etc/ceph/monmap",
            "/etc/ceph/monmap",
            "root",
            "root",
            420
          )
        )
        .build,

      stage("mkfs", "tst0", "tst1", "tst2")
        .task("prepare mon data dir and mkfs")(
          Task.RunCommand(List("sh", "-c", mkfsScript))
        )
        .build,

      stage("start-mon-tst0", "tst0")
        .task("enable and start mon service")(
          Task.Apply(startMonPlan("tst0"))
        )
        .build,

      stage("start-mon-tst1", "tst1")
        .task("enable and start mon service")(
          Task.Apply(startMonPlan("tst1"))
        )
        .build,

      stage("start-mon-tst2", "tst2")
        .task("enable and start mon service")(
          Task.Apply(startMonPlan("tst2"))
        )
        .build,

      stage("confirm-quorum", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", quorumCheckScript),
            requiredCount = 2,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("quorum confirmed")(
          Task.Debug("Mon cluster has reached quorum.")
        )
        .build
    )
