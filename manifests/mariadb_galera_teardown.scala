// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Tears down what mariadb_galera_cluster.scala AND mariadb_haproxy_keepalived.scala
// (both above) build on tst3/tst4/tst5 — the base Galera cluster and the
// haproxy+keepalived VIP frontend on top of it. One combined teardown
// rather than two, since the HA frontend can't meaningfully outlive the
// Galera cluster it fronts (there'd be nothing left to load-balance).
//
// Deliberately different from cephadm_teardown.scala, the closest
// existing precedent for a package-installed (not raw-binary) thing:
// cephadm/podman are heavy and slow to reinstall, so that file leaves the
// packages in place and only cleans up cluster state. mariadb-server,
// haproxy and keepalived are all normal, fast apt packages, so this file
// fully purges all of them (`Task.Remove(purge = true)`) rather than just
// stopping services — giving every future build run a genuinely clean
// slate, including a fresh pass through mariadb_galera_cluster.scala's
// own documented package-auto-starts-a-standalone-instance gotcha,
// instead of tearing down into a half-cleaned state that would behave
// differently from a true first run.
object mariadb_galera_teardown extends OrpheraClusterPlaybook:

  // Purge removes the packages and their own conffiles (including
  // /etc/haproxy/haproxy.cfg and /etc/keepalived/keepalived.conf — both
  // are package-owned conffiles that mariadb_haproxy_keepalived.scala
  // only ever overwrote the *content* of, never created as new files, so
  // purge is expected to remove them cleanly), but NOT:
  //   - /etc/mysql/mariadb.conf.d/60-galera.cnf — never package-owned,
  //     mariadb_galera_cluster.scala's own file.
  //   - /var/lib/mysql — Ubuntu's postrm only removes this on purge if
  //     debconf is answered interactively, which it isn't here (same
  //     `DEBIAN_FRONTEND=noninteractive` convention as every
  //     `Task.Install` in this project).
  //   - /usr/local/bin/galera-clustercheck, the clustercheck.socket/
  //     clustercheck@.service systemd units, and
  //     /etc/sysctl.d/99-orphera-haproxy-vip.conf — none of these are
  //     package-owned either; all four were written directly by
  //     mariadb_haproxy_keepalived.scala.
  // All removed explicitly below rather than trusted to purge's own
  // behavior, since leftover state from either exercise is exactly what
  // would make the next build run unpredictably.
  private val mariadbCleanupPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "galera-cleanup",
      List(
        absentFile("/etc/mysql/mariadb.conf.d/60-galera.cnf"),
        absentDirectory("/var/lib/mysql", recursive = true)
      )
    )

  // Not package-owned, so purging haproxy/keepalived doesn't touch any of
  // this — the clustercheck endpoint, the sysctl override that let
  // haproxy bind the not-yet-present VIP, and (belt-and-suspenders, in
  // case keepalived's own shutdown didn't get to it in time) any VIP
  // still lingering on this node's interface.
  private val haproxyKeepalivedCleanupPlan: ApplyPlan =
    import Resources.*
    val reload = command(
      "reload systemd after the clustercheck units were removed",
      "systemctl daemon-reload",
      onChangeOnly = true
    )
    val units = List(
      "/etc/systemd/system/clustercheck.socket",
      "/etc/systemd/system/clustercheck@.service"
    ).map(p => absentFile(p).notifies(reload))
    val others = List(
      "/usr/local/bin/galera-clustercheck",
      "/etc/sysctl.d/99-orphera-haproxy-vip.conf",
      "/etc/sysctl.d/60-orphera-net.ipv4.ip_nonlocal_bind.conf"
    ).map(absentFile)
    val nonLocalBind = command(
      "turn net.ipv4.ip_nonlocal_bind off again",
      "sysctl -w net.ipv4.ip_nonlocal_bind=0",
      unless = """[ "$(sysctl -n net.ipv4.ip_nonlocal_bind 2>/dev/null)" = 0 ]"""
    ).after(others*)
    val vip = command(
      "remove a lingering VIP from the interface",
      """IFACE=$(ip -4 route show default | awk '{print $5; exit}')
        |ip addr del 10.10.5.100/24 dev "$IFACE" 2>/dev/null || true""".stripMargin,
      onlyIf = "ip -4 addr show | grep -q 'inet 10.10.5.100/'"
    )
    ApplyPlan.orFail(
      "galera-vip-cleanup",
      units ::: others ::: List(reload, nonLocalBind, vip)
    )

  // Stopped through the apply engine: a unit that is not there, or is already stopped, is left alone.
  private val stopServicesPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "galera-stop-services",
      List("mariadb", "haproxy", "keepalived", "clustercheck.socket").map(u =>
        service(u, enabled = None, running = Some(false))
      )
    )

  private def teardownStage(host: String) =
    stage(s"teardown-$host", host)
      .task(s"stop mariadb, haproxy, keepalived on $host")(
        Task.Apply(stopServicesPlan)
      )
      .task(
        s"purge mariadb-server, mariadb-client, mariadb-backup, galera-4 on $host"
      )(
        Task.Remove(
          packages = List(
            "mariadb-server",
            "mariadb-client",
            "mariadb-backup",
            "galera-4"
          ),
          purge = true
        )
      )
      .task(s"purge haproxy, keepalived on $host")(
        Task.Remove(packages = List("haproxy", "keepalived"), purge = true)
      )
      .task(s"autoremove now-unneeded dependencies on $host")(
        Task.AutoRemove(purge = true)
      )
      .task(s"remove leftover Galera config/data on $host")(
        Task.Apply(mariadbCleanupPlan)
      )
      .task(s"remove leftover haproxy/keepalived VIP-frontend state on $host")(
        Task.Apply(haproxyKeepalivedCleanupPlan)
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("mariadb-galera-teardown")(
      teardownStage("tst3"),
      teardownStage("tst4"),
      teardownStage("tst5")
    )
