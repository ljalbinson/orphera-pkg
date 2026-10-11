import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Fourth infrastructure exercise, and the one the Keystone/Galera side
// discussion originally pointed at — done directly now rather than as
// part of a full OpenStack stack, same reasoning as picking etcd first:
// isolate the orchestration questions (cross-node config, asymmetric
// bootstrap ordering, cluster-wide health) from install-complexity that
// has nothing to do with Orphera itself.
//
// Targets tst3/tst4/tst5 — the same three nodes etcd_cluster.scala used.
// Run manifests/etcd_teardown.scala first if etcd is still installed on
// them; nothing here removes it, and the two would otherwise coexist
// fine (different ports, no real conflict), which is exactly why this
// file doesn't bother checking — it was a deliberate choice to reuse the
// same three nodes for a clean before/after, not a technical requirement.
//
// A genuinely different bootstrap SHAPE from both earlier exercises,
// worth naming explicitly since it doesn't match either:
//   - etcd_cluster.scala: symmetric CONFIG (every node's `--initial-cluster`
//     flag lists the same full peer set) AND symmetric START (all three
//     start at once, nothing waits on anything).
//   - ceph_mon_quorum.scala: asymmetric CONFIG (fsid/monmap generated
//     ONCE on tst3, then DistributeFile'd to the others) — nobody else
//     could even start correctly without that first node's output.
//   - THIS file: symmetric CONFIG (every node's `wsrep_cluster_address`
//     lists the exact same three peers, rendered via the same
//     {{nodes.<name>.cluster_ip}} templating as etcd_cluster.scala — no
//     DistributeFile needed) but ASYMMETRIC START ORDER — exactly one
//     node (tst3) must be launched with `galera_new_cluster` (which
//     bootstraps a brand-new cluster, ignoring the peer list since none
//     of them are up yet) BEFORE the other two do a normal
//     `systemctl start mariadb`, which makes them actually try to
//     connect to and sync from (State Snapshot Transfer, "SST") the
//     peers already listed in their own identical config. Get the order
//     wrong — start a joiner before the bootstrap node is up — and it
//     just fails to connect, same shape of failure as etcd's own
//     member-add-before-rejoin ordering requirement in
//     etcd_member_rejoin.scala, for an unrelated reason underneath.
//
// A real gotcha reasoned through before ever running this, not found by
// trial and error (worth flagging honestly, since it's exactly the kind
// of thing that's easy to get wrong on a first real run): installing
// `mariadb-server` on a fresh node auto-starts a STANDALONE (non-Galera)
// instance as a side effect of the package's own postinst script — this
// happens BEFORE this playbook has written any Galera config or run
// galera_new_cluster at all. A naive "is mariadb already active?"
// idempotency check would see that auto-started standalone instance and
// wrongly conclude the node's already properly bootstrapped/joined,
// leaving it running standalone forever instead of actually forming the
// cluster. Guarded against below by checking `wsrep_cluster_size`
// specifically (empty/zero when wsrep_on is off, as it is on a fresh
// standalone instance) rather than merely whether the service is active,
// and by explicitly stopping mariadb before writing config and
// (re)starting it either way.
object mariadb_galera_cluster extends OrpheraClusterPlaybook:

  // Test-only hardcoded credential for Galera's own cross-node SST
  // (State Snapshot Transfer) connection — this project has no secrets
  // management, and a real deployment would need one; this is fine for
  // disposable test infrastructure but is not a pattern to reuse
  // anywhere real credentials matter. Root itself needs no password at
  // all here — Ubuntu's mariadb-server ships root with `unix_socket`
  // auth by default, so every `mariadb -N -e "..."` call below, run as
  // the OS root user (same as every other privileged command in this
  // project), authenticates with no password and no -u/-p flags needed.
  // Confirm this assumption on first real run — if it's wrong, every
  // `mariadb -N -e` call below will fail to connect at all, which is an
  // easy, loud failure to spot, not a silent wrong result.
  private val sstPassword = "orphera-test-sst-password"

  private val clusterName = "orphera-galera-test"

  private val galeraPeers =
    "gcomm://{{nodes.tst3.cluster_ip}},{{nodes.tst4.cluster_ip}},{{nodes.tst5.cluster_ip}}"

  // Writes the exact same peer list on every node (see header comment) —
  // only $name/$clusterIp vary. A fresh overwrite every run, same
  // "idempotent because it's just rewritten identically" convention as
  // etcd_cluster.scala's systemd unit. Added on top of Ubuntu's packaged
  // /etc/mysql/mariadb.conf.d/50-server.cnf (included via my.cnf's
  // `!includedir`) as a new, later-loaded file rather than editing that
  // packaged one directly — 60-galera.cnf's settings win over
  // 50-server.cnf's for anything both set.
  //
  // Correction, made while building mariadb_haproxy_keepalived.scala:
  // bind-address was originally 0.0.0.0 (open to every address, reasoned
  // at the time as needed for "cross-node replication and this playbook's
  // own client connections" — overstated; Galera's own replication
  // traffic never goes through mysqld's bind-address at all, it uses
  // wsrep_node_address on separate ports 4567/4568/4444, and this
  // playbook's own health/status queries run locally via unix socket,
  // which bind-address doesn't affect either). Narrowed to {{cluster_ip}}
  // (this node's own static address only) once HAProxy needed to bind
  // *:3306 on the same host to serve both that address AND the floating
  // VIP keepalived adds later — two processes can't both bind the same
  // address:port, and mysqld's wildcard bind was claiming the VIP the
  // instant keepalived added it to the interface, before HAProxy ever
  // got a chance to.
  // NAME is resolved via `hostname -s`, not a Scala parameter — there is
  // no built-in {{node_name}}/{{name}} template var in Orphera (checked
  // against the real DSL/runner source rather than assumed, after
  // getting bitten by a similarly wrong assumption earlier in this
  // project's etcd work). {{cluster_ip}} works because it's a literal
  // per-node var in inventory.yaml; a node's own name isn't exposed the
  // same way, so this uses the exact same `$(hostname -s)` convention as
  // ceph_mon_quorum.scala/etcd_cluster.scala's own equivalent, with the
  // same assumption: each node's hostname matches its inventory name
  // (tst3/tst4/tst5).
  private val galeraConfigScript =
    s"""NAME=$$(hostname -s)
       |cat > /etc/mysql/mariadb.conf.d/60-galera.cnf <<EOF
       |[mariadbd]
       |wsrep_on=ON
       |wsrep_provider=/usr/lib/galera/libgalera_smm.so
       |wsrep_cluster_name="$clusterName"
       |wsrep_cluster_address="$galeraPeers"
       |wsrep_node_address="{{cluster_ip}}"
       |wsrep_node_name="$$NAME"
       |wsrep_sst_method=mariabackup
       |wsrep_sst_auth="sst_user:$sstPassword"
       |binlog_format=ROW
       |default_storage_engine=InnoDB
       |innodb_autoinc_lock_mode=2
       |bind-address={{cluster_ip}}
       |EOF""".stripMargin

  // Empty/zero specifically means "wsrep is off" (a fresh standalone
  // instance, per the header comment's gotcha) or "not running at all" —
  // either way, not yet a real member of this cluster.
  private val wsrepClusterSizeScript =
    """mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'" 2>/dev/null | awk '{print $2}'"""

  // tst3 only. `galera_new_cluster` (from the galera-4 package) starts
  // mariadb with the special `--wsrep-new-cluster` flag that bootstraps
  // a brand-new cluster from this node's own local data, ignoring
  // wsrep_cluster_address entirely for this one startup — it's the
  // "someone has to go first" step every Galera cluster needs. The
  // explicit `systemctl stop mariadb` before it matters even when
  // nothing's obviously wrong: it's what actually stops the
  // package-install-time standalone auto-start described in the header
  // comment, whether or not this is a first run.
  //
  // Two real bugs found from an actual run against tst3/tst4/tst5, both
  // fixed below:
  //
  // 1. Masked failure, same shape as the haproxy `systemctl restart`
  //    masking bug in mariadb_haproxy_keepalived.scala: `galera_new_cluster`'s
  //    own exit code wasn't checked, so the unconditional
  //    `echo "Bootstrapped..."` printed even when it had genuinely
  //    failed — `Task.RunCommand` then only caught the problem two lines
  //    later, when the final `CREATE USER` failed to connect at all
  //    (`ERROR 2002 (HY000): Can't connect to local server through
  //    socket`). Fixed by checking `galera_new_cluster`'s own exit status
  //    and failing loudly, immediately, if it fails.
  //
  // 2. The real reason `galera_new_cluster` failed on that run:
  //    `journalctl -xeu mariadb.service` showed
  //    `[ERROR] WSREP: It may not be safe to bootstrap the cluster from
  //    this node. It was not the last one to leave the cluster and may
  //    not contain all the updates`, with `/var/lib/mysql/grastate.dat`
  //    showing `safe_to_bootstrap: 0` and `seqno: -1`. This is Galera's
  //    own safety check: an unclean shutdown (or, as here, leftover data
  //    from an earlier test run that was never cleanly torn down) leaves
  //    grastate.dat unable to prove this copy of the data is the most
  //    up-to-date one, so it refuses to let it declare itself the
  //    authoritative bootstrap node. For a real production cluster the
  //    correct recovery is to run `mysqld --wsrep-recover` on every node
  //    and bootstrap from whichever one reports the highest seqno — NOT
  //    to force this blindly, since forcing the wrong node can silently
  //    discard committed data. Forcing it here instead (rewriting
  //    `safe_to_bootstrap: 0` to `1` in grastate.dat before calling
  //    `galera_new_cluster`) is deliberately a test-cluster-only shortcut:
  //    this playbook always treats tst3 as the sole bootstrap node by
  //    design, and mariadb_galera_teardown.scala's own job is to wipe
  //    /var/lib/mysql clean between runs anyway — so a stale
  //    safe_to_bootstrap: 0 here only ever means "this node's leftover
  //    data from last time", not "some other node has newer data I'd be
  //    discarding". Not a pattern to reuse anywhere real data is at
  //    stake.
  private val bootstrapScript =
    s"""SIZE=$$($wsrepClusterSizeScript)
       |if [ -n "$$SIZE" ] && [ "$$SIZE" != "0" ]; then
       |  echo "wsrep already active (cluster size reported: $$SIZE) — assuming already bootstrapped, skipping"
       |else
       |  systemctl stop mariadb 2>/dev/null || true
       |  if [ -f /var/lib/mysql/grastate.dat ] && grep -q 'safe_to_bootstrap: 0' /var/lib/mysql/grastate.dat; then
       |    echo "grastate.dat says safe_to_bootstrap: 0 (leftover state from a previous run) — forcing it to 1 to bootstrap this disposable test cluster from tst3"
       |    sed -i 's/safe_to_bootstrap: 0/safe_to_bootstrap: 1/' /var/lib/mysql/grastate.dat
       |  fi
       |  if ! galera_new_cluster; then
       |    echo "galera_new_cluster failed — see 'systemctl status mariadb' / 'journalctl -xeu mariadb.service' for the real reason" >&2
       |    exit 1
       |  fi
       |  echo "Bootstrapped a new Galera cluster on this node"
       |fi
       |# Idempotent regardless of the branch above: IF NOT EXISTS/re-GRANTing
       |# an existing user and privileges is a no-op, not an error.
       |mariadb -N -e "CREATE USER IF NOT EXISTS 'sst_user'@'%' IDENTIFIED BY '$sstPassword'; GRANT RELOAD, LOCK TABLES, PROCESS, REPLICATION CLIENT ON *.* TO 'sst_user'@'%'; FLUSH PRIVILEGES;"""".stripMargin

  // tst4/tst5 only. A normal `systemctl start mariadb` — no bootstrap
  // flag — which makes this node try to connect to the peers already
  // listed in its own wsrep_cluster_address and pull a State Snapshot
  // Transfer (SST, via mariabackup — see wsrep_sst_method in the config
  // above) from whichever of them is reachable and further ahead. Only
  // works once tst3 has actually finished bootstrapping — see the
  // register-tst3/join-tst3 two-stage split precedent in
  // etcd_member_rejoin.scala/etcd_grow_cluster.scala for why that
  // ordering is enforced as separate STAGES below, not just separate
  // tasks.
  private val joinScript =
    s"""SIZE=$$($wsrepClusterSizeScript)
       |if [ -n "$$SIZE" ] && [ "$$SIZE" != "0" ]; then
       |  echo "wsrep already active (cluster size reported: $$SIZE) — assuming already joined, skipping"
       |else
       |  systemctl stop mariadb 2>/dev/null || true
       |  systemctl start mariadb
       |  echo "Started mariadb — joining the existing cluster via SST"
       |fi""".stripMargin

  // Checked independently on EACH node (not just tst3's view) — same
  // per-node convention etcd_cluster.scala's HealthCheck.Quorum uses,
  // rather than trusting a single node's report of cluster-wide state.
  // wsrep_cluster_size alone isn't enough: a node mid-SST can already
  // see cluster_size=3 while it's still catching up, so wsrep_ready
  // (this node's own readiness to serve queries) and wsrep_cluster_status
  // (this node's view of whether the cluster has a quorum-agreed
  // "Primary" component, as opposed to a partitioned "non-Primary" one)
  // are both checked too.
  private val healthCheckScript =
    """SIZE=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'" 2>/dev/null | awk '{print $2}')
      |STATUS=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_status'" 2>/dev/null | awk '{print $2}')
      |READY=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_ready'" 2>/dev/null | awk '{print $2}')
      |[ "$SIZE" = "3" ] && [ "$STATUS" = "Primary" ] && [ "$READY" = "ON" ]""".stripMargin

  private val printClusterStatusScript =
    """mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'; SHOW STATUS LIKE 'wsrep_cluster_status'; SHOW STATUS LIKE 'wsrep_ready'; SHOW STATUS LIKE 'wsrep_incoming_addresses'" """.stripMargin.trim

  val playbook: ClusterPlaybook =
    clusterPlaybook("mariadb-galera-cluster")(
      stage("install-mariadb", "tst3", "tst4", "tst5")
        .task("install mariadb-server, mariadb-backup, galera-4")(
          Task.Install(
            packages = List(
              "mariadb-server",
              "mariadb-client",
              "mariadb-backup",
              "galera-4"
            ),
            updateCache = true
          )
        )
        .build,

      // Config on all three now — cheap and symmetric, so no reason to
      // delay writing it until just before each node's own start step.
      stage("write-galera-config", "tst3", "tst4", "tst5")
        .task("write /etc/mysql/mariadb.conf.d/60-galera.cnf")(
          Task.RunCommand(List("sh", "-c", galeraConfigScript))
        )
        .build,

      // tst3 only, and strictly before join-others below — see the
      // header comment's asymmetric-start-order explanation.
      stage("bootstrap-tst3", "tst3")
        .task("bootstrap a new Galera cluster and create the SST user")(
          Task
            .RunCommand(List("sh", "-c", bootstrapScript), timeoutSeconds = 90)
        )
        .build,

      stage("join-others", "tst4", "tst5")
        .task("join the cluster via SST from tst3")(
          Task.RunCommand(List("sh", "-c", joinScript), timeoutSeconds = 120)
        )
        .build,

      stage("confirm-cluster-healthy", "tst3")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst3", "tst4", "tst5"),
            command = List("sh", "-c", healthCheckScript),
            requiredCount = 3,
            pollIntervalSeconds = 5,
            timeoutSeconds = 180
          )
        )
        .task("print cluster status")(
          Task.RunCommand(List("sh", "-c", printClusterStatusScript))
        )
        .task("cluster confirmed")(
          Task.Debug(
            "3-node MariaDB Galera cluster healthy: wsrep_cluster_size=3, status=Primary, ready=ON on every node."
          )
        )
        .build
    )
