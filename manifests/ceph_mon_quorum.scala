import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_mon_quorum extends OrpheraClusterPlaybook:

  private val bootstrapConfigScript =
  private val bootstrapConfigScript =
    """FSID=$(cat /proc/sys/kernel/random/uuid)
      |cat > /etc/ceph/ceph.conf <<EOF
      |[global]
      |fsid = $FSID
      |mon_initial_members = tst0, tst1, tst4
      |mon_host = {{cluster_ip}}, {{nodes.tst1.cluster_ip}}, {{nodes.tst4.cluster_ip}}
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
      |  --add tst4 {{nodes.tst4.cluster_ip}} \
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

  private val startServiceScript =
    "systemctl reset-failed ceph-mon@$(hostname -s) 2>/dev/null; systemctl enable --now ceph-mon@$(hostname -s)"

  private val quorumCheckScript =
    """ceph --admin-daemon /var/run/ceph/ceph-mon.$(hostname -s).asok mon_status 2>/dev/null | grep -Eq '"state":\s*"(leader|peon)"'"""

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-mon-quorum")(

      stage("install-mon-daemon", "tst0", "tst1", "tst4")
        .task("install ceph-mon")(
          Task.Install(packages = List("ceph-mon", "ceph-base"), updateCache = true, version = "{{ceph_version}}")
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

      stage("distribute-mon-config", "tst1", "tst4")
        .task("distribute ceph.conf from tst0")(
          Task.DistributeFile("tst0", "/etc/ceph/ceph.conf", "/etc/ceph/ceph.conf", "root", "root", 420)
        )
        .task("distribute monmap from tst0")(
          Task.DistributeFile("tst0", "/etc/ceph/monmap", "/etc/ceph/monmap", "root", "root", 420)
        )
        .build,

      stage("mkfs-and-start", "tst0", "tst1", "tst4")
        .task("prepare mon data dir and mkfs")(
          Task.RunCommand(List("sh", "-c", mkfsScript))
        )
        .task("enable and start mon service")(
          Task.RunCommand(List("sh", "-c", startServiceScript))
        )
        .build,

      stage("confirm-quorum", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst4"),
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
