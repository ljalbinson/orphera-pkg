import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Tears down everything the etcd exercises (etcd_cluster.scala,
// etcd_member_rejoin.scala, etcd_grow_cluster.scala) left behind: stops
// and disables the service, removes its unit file, wipes the data and
// config dirs, removes the etcd/etcdctl binaries, and removes the
// system user created for it. Deliberately simple compared to
// cephadm_teardown.scala — there's no LVM/device-mapper state or raw
// disks involved here, just a systemd service, a couple of directories,
// two binaries and one user, so one task per host covers it.
//
// One stage PER HOST (mirroring cephadm_teardown.scala's shape), not one
// shared stage across all five nodes, deliberately: a cluster-playbook
// run aborts entirely on the first stage that fails (confirmed by the
// real etcd_grow_cluster.scala run against tst4's unreachable agent), so
// per-host stages mean a single unreachable node doesn't block cleanup
// on every other node — it only blocks whatever runs AFTER it.
//
// tst4 is deliberately LAST in the list for exactly that reason: its
// agent is currently unreachable (`UNAVAILABLE: io exception` in the
// last etcd_grow_cluster.scala attempt) and it never got past the first
// install task, so there's nothing on it to tear down anyway. Every
// other host's teardown stage runs and completes before tst4's stage is
// even attempted, so a still-unreachable tst4 fails last, not first.
//
// Every command below is written to be safe against partial/missing
// state — safe to run against a fully-built cluster, a partially-grown
// one (tst3 currently has the binaries installed but was never
// configured or started), or a host that never had etcd at all.
object etcd_teardown extends OrpheraClusterPlaybook:

  private val teardownScript =
    """systemctl stop etcd 2>/dev/null || true
      |systemctl disable etcd 2>/dev/null || true
      |rm -f /etc/systemd/system/etcd.service
      |systemctl daemon-reload
      |rm -rf /var/lib/etcd /etc/etcd
      |rm -f /usr/local/bin/etcd /usr/local/bin/etcdctl
      |id etcd >/dev/null 2>&1 && userdel etcd 2>/dev/null || true
      |echo 'etcd removed (service, unit file, data/config dirs, binaries, user)'""".stripMargin

  private def teardownStage(host: String) =
    stage(s"teardown-$host", host)
      .task(s"remove etcd install on $host")(
        Task.RunCommand(List("sh", "-c", teardownScript), timeoutSeconds = 60)
      )
      .build

  // tst4 last — see header comment.
  private val hosts = List("tst0", "tst1", "tst2", "tst3", "tst4")

  val playbook: ClusterPlaybook =
    clusterPlaybook("etcd-teardown")(
      hosts.map(teardownStage)*
    )
