import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Applies a config-file change to an already-running Galera cluster
// safely — restarting mariadb one node at a time, waiting for each node
// to rejoin (wsrep_ready=ON, wsrep_cluster_size=3) before moving to the
// next, so the cluster never drops below 2-of-3 quorum during the
// rolling restart.
//
// Built directly in response to a real gap this project's own
// bootstrap/join idempotency check has: mariadb_galera_cluster.scala's
// bootstrapScript/joinScript both check `wsrep_cluster_size` and skip
// restarting mariadb if it's already active/healthy — the right call for
// avoiding an unnecessary restart on an ordinary re-run, but it also
// means simply re-running that playbook after changing
// 60-galera.cnf's *content* (e.g. the bind-address fix noted in that
// file's own CHANGELOG entry) does NOT actually apply the change to an
// already-running cluster: the file on disk changes, but mysqld keeps
// running with whatever it read at its last real start until something
// explicitly restarts it. This file is that "something" — a deliberate,
// one-at-a-time restart, not a re-run of the whole build.
//
// Targets tst3/tst4/tst5, the same three nodes mariadb_galera_cluster.scala
// builds. Order (tst3, then tst4, then tst5) doesn't matter for
// correctness here — unlike that file's own bootstrap-before-join
// ordering, every node here is already a full member of an existing,
// healthy cluster, so any one of them restarting and rejoining via
// IST/SST is symmetric. Kept in a fixed, readable order rather than
// parallelizing all three, which is the entire point: restarting more
// than one node at once on a 3-node cluster risks dropping below the
// 2-of-3 quorum Galera needs to keep accepting writes.
object mariadb_galera_rolling_restart extends OrpheraClusterPlaybook:

  // Same three-field check as mariadb_galera_cluster.scala's own
  // healthCheckScript — deliberately duplicated rather than shared (these
  // per-file .scala manifests each compile and run standalone via
  // `orphera cluster-playbook <file>.scala`, the same reason
  // installEtcdScript is duplicated between the etcd_*.scala files).
  private val healthCheckScript =
    """SIZE=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'" 2>/dev/null | awk '{print $2}')
      |STATUS=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_status'" 2>/dev/null | awk '{print $2}')
      |READY=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_ready'" 2>/dev/null | awk '{print $2}')
      |[ "$SIZE" = "3" ] && [ "$STATUS" = "Primary" ] && [ "$READY" = "ON" ]""".stripMargin

  private val restartScript =
    """systemctl restart mariadb
      |echo 'mariadb restarted, waiting for this node to rejoin the cluster'""".stripMargin

  private def restartStage(host: String) =
    stage(s"restart-$host", host)
      .task(s"restart mariadb on $host")(
        Task.RunCommand(List("sh", "-c", restartScript), timeoutSeconds = 60)
      )
      .waitFor(
        HealthCheck.Quorum(
          nodes = List(host),
          command = List("sh", "-c", healthCheckScript),
          requiredCount = 1,
          pollIntervalSeconds = 5,
          // Generous relative to mariadb_galera_cluster.scala's own
          // confirm-cluster-healthy timeout (180s for all three at once,
          // from a cold start) — a rolling restart's IST (incremental,
          // just the writes missed while this one node was down) is
          // normally much faster than a fresh node's full SST, but this
          // stays cautious rather than assuming that.
          timeoutSeconds = 180
        )
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("mariadb-galera-rolling-restart")(
      restartStage("tst3"),
      restartStage("tst4"),
      restartStage("tst5")
    )
