import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Tears down what mariadb_galera_cluster.scala built on tst0/tst1/tst2.
//
// One stage PER HOST (teardown-tst0, teardown-tst1, teardown-tst2), not one
// shared stage across all three — same reasoning as etcd_teardown.scala: a
// cluster-playbook run aborts entirely on the first stage that fails, so
// per-host stages mean one unreachable/broken node only blocks whatever
// teardown stage runs *after* it, not the ones before it.
//
// A deliberately different choice from cephadm_teardown.scala, worth
// calling out since it's the closest existing precedent for tearing down
// something installed via Task.Install rather than a raw binary (like
// etcd): cephadm_teardown.scala leaves the cephadm/podman packages
// installed and only cleans up cluster state, because cephadm is a heavy,
// slow-to-reinstall container orchestrator. mariadb-server is a normal,
// fast apt package, so this file fully purges it (Task.Remove(purge =
// true)) rather than just stopping the service and wiping data — a full
// purge-then-reinstall gives every future run of
// mariadb_galera_cluster.scala a genuinely clean slate, including a fresh
// pass through that file's own documented gotcha (the package's postinst
// auto-starting a standalone, non-Galera instance) instead of tearing down
// into some half-cleaned state that behaves differently from a true first
// run.
object mariadb_galera_teardown extends OrpheraClusterPlaybook:

  // Purge removes the packages and their own conffiles, but NOT:
  //   - /etc/mysql/mariadb.conf.d/60-galera.cnf — this playbook's own file,
  //     never owned by the package, so apt has no reason to touch it.
  //   - /var/lib/mysql — Ubuntu's mariadb-server postrm only removes this
  //     on purge if debconf is answered interactively; under
  //     DEBIAN_FRONTEND=noninteractive (same as every other Task.Install in
  //     this project) it's left in place more often than not. Removed
  //     explicitly below rather than relying on purge's own behavior here,
  //     since a leftover data dir with an old Galera cluster's state is
  //     exactly the kind of thing that would make the *next*
  //     mariadb_galera_cluster.scala run behave unpredictably.
  private val cleanupScript =
    """rm -f /etc/mysql/mariadb.conf.d/60-galera.cnf
      |rm -rf /var/lib/mysql
      |echo 'mariadb Galera config and data directory removed'""".stripMargin

  private def teardownStage(host: String) =
    stage(s"teardown-$host", host)
      .task(s"stop mariadb on $host")(
        Task.RunCommand(List("sh", "-c", "systemctl stop mariadb 2>/dev/null || true"))
      )
      .task(s"purge mariadb-server, mariadb-client, mariadb-backup, galera-4 on $host")(
        Task.Remove(
          packages = List("mariadb-server", "mariadb-client", "mariadb-backup", "galera-4"),
          purge = true
        )
      )
      .task(s"remove leftover Galera config/data on $host")(
        Task.RunCommand(List("sh", "-c", cleanupScript))
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("mariadb-galera-teardown")(
      teardownStage("tst0"),
      teardownStage("tst1"),
      teardownStage("tst2")
    )
