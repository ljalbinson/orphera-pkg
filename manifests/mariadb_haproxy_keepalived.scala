import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Fifth infrastructure exercise: fronts the mariadb_galera_cluster.scala
// 3-node cluster with a floating IP (10.10.5.100/24, user-provided) so a
// client talks to one stable address instead of needing to know which of
// tst0/tst1/tst2 to reach, with automatic failover if whichever node
// currently holds that address goes down.
//
// Colocated on tst0/tst1/tst2 — haproxy + keepalived run on the same
// three nodes as mariadb/galera itself, rather than on dedicated LB nodes
// (e.g. tst3/tst4) — the user's own choice when asked, and also the more
// common reference topology for a 3-node Galera cluster specifically:
// three LB instances gives keepalived's VRRP election three candidates
// instead of two, without standing up a separate node pair.
//
// Researched against a real guide before writing any of this, not
// assumed: https://computingforgeeks.com/mariadb-high-availability-galera-haproxy-keepalived/
// — the HAProxy health check against each Galera backend is a dedicated
// `clustercheck`-style HTTP endpoint on port 9200 (systemd socket
// activation here, rather than the older xinetd approach some guides
// still use — xinetd isn't a default-installed dependency on Ubuntu
// 24.04 the way systemd socket units always are), which queries
// `wsrep_local_state` and returns HTTP 200 only when the node is fully
// Synced (state 4). That's a real, necessary distinction from
// mariadb_galera_cluster.scala's own health check, which only checks
// wsrep_ready (this node can serve queries) — a node can be wsrep_ready
// while still catching up via SST/IST, and routing a write to it there
// would be a correctness bug, not just a slow query.
//
// HAProxy runs two listeners, same split the reference guide uses:
//   - :3306 (write) — one primary node (tst0) takes all traffic, the
//     other two are `backup` (only receiving traffic if tst0's own
//     health check fails). Pinning writes to one node at a time avoids
//     Galera's well-known multi-writer certification-conflict problem
//     (two nodes committing conflicting writes concurrently — one gets
//     rolled back by Galera's optimistic locking, surfacing to the
//     client as a deadlock-style error) under normal operation, while
//     still failing over automatically if tst0 goes down.
//   - :3307 (read) — round-robin across all three, safe for reads
//     regardless of which node serves them once that node's own :9200
//     check confirms it's Synced.
//
// A real port-binding conflict this surfaced, confirmed against a real
// run (journalctl: "cannot bind socket (Address already in use) for
// [0.0.0.0:3306]"): HAProxy originally bound `*:3306` (wildcard) on each
// node, same as the reference guide above — but that guide runs
// haproxy/keepalived on dedicated LB nodes, never colocated with a Galera
// backend on the same host, so it never hits this. On Linux, a wildcard
// bind conflicts with ANY already-bound specific address on the same
// port, regardless of how narrowly the OTHER process is bound — so even
// after narrowing mysqld's own bind-address to {{cluster_ip}} in
// mariadb_galera_cluster.scala (still worth doing, but not what actually
// fixes this), HAProxy's wildcard bind still collided with it. Properly
// fixed in `haproxyConfigScript` below: HAProxy now binds the VIP address
// specifically, never a wildcard, with `net.ipv4.ip_nonlocal_bind=1` set
// first so it can hold that bind on the backup nodes too, before
// keepalived has attached the VIP to their interface — see that val's own
// comment for the full story, including the first (masking-bug) fix that
// had to land before this second, real one could even be seen clearly.
object mariadb_haproxy_keepalived extends OrpheraClusterPlaybook:

  private val vip = "10.10.5.100"

  // Test-only credentials, same disclaimer as mariadb_galera_cluster.scala's
  // sstPassword — this project has no secrets management, fine for
  // disposable test infrastructure, not a pattern to reuse for anything
  // real. clustercheck gets USAGE only (just enough to connect and run
  // SHOW STATUS) — it never touches any actual data.
  private val clustercheckUser = "clustercheck"
  private val clustercheckPassword = "orphera-test-clustercheck-password"
  private val vrrpAuthPass = "orphera-test-vrrp-pass"

  // Created once, on tst0 only — like sst_user in mariadb_galera_cluster.scala,
  // CREATE USER/GRANT are ordinary DML/DDL that Galera replicates to every
  // node automatically, so this doesn't need repeating per-node.
  //
  // Caught in real use, fixed same day: the first version only created
  // 'clustercheck'@'localhost', which is all the LOCAL health-check
  // script needs (it connects via unix socket, and MariaDB's 'localhost'
  // host-match is special-cased to unix-socket connections specifically —
  // it never matches a TCP connection, even one that happens to
  // originate from the same machine). But a client connecting through
  // HAProxy arrives at mysqld as a genuine TCP connection, and HAProxy's
  // plain `mode tcp` doesn't preserve the original client's address for
  // the backend — mysqld sees it as coming from whichever address
  // HAProxy itself used to reach that backend (confirmed by the real
  // failure: `Access denied for user 'clustercheck'@'10.10.5.12'`, tst0's
  // own address, not 'localhost' and not the client's real origin
  // either). Fixed by also granting 'clustercheck'@'%' — both grants are
  // needed under the same username for the two genuinely different
  // connection paths (local socket for health checks, proxied TCP for
  // everything else), not a redundant belt-and-suspenders pair.
  private val createClustercheckUserScript =
    s"""mariadb -N -e "CREATE USER IF NOT EXISTS '$clustercheckUser'@'localhost' IDENTIFIED BY '$clustercheckPassword'; GRANT USAGE ON *.* TO '$clustercheckUser'@'localhost'; CREATE USER IF NOT EXISTS '$clustercheckUser'@'%' IDENTIFIED BY '$clustercheckPassword'; GRANT USAGE ON *.* TO '$clustercheckUser'@'%'; FLUSH PRIVILEGES;""""

  // Plain HTTP-over-a-raw-socket responder (no framework/HTTP server
  // needed for one canned response) — reads and discards the request
  // headers a real HTTP client sends (HAProxy's own `option httpchk GET
  // /`), then answers 200 only when wsrep_local_state = 4 (Synced), 503
  // otherwise. Uses raw"""...""" rather than s"""...""" because it needs
  // BOTH `$`-interpolation (for $clustercheckUser/$clustercheckPassword)
  // AND a literal `\` line continuation in the mariadb command — the
  // exact s""" vs raw""" escape-validation distinction documented in
  // etcd_grow_cluster.scala's joinScript, hit and fixed there first.
  private val clustercheckScript =
    raw"""#!/bin/bash
         |# HTTP 200 when this node is Synced (wsrep_local_state = 4), 503 otherwise.
         |while read -t 1 -r line; do line=$${line%$$'\r'}; [ -z "$$line" ] && break; done
         |STATE=$$(mariadb --connect-timeout=2 -u$clustercheckUser -p$clustercheckPassword -sN \
         |  -e "SHOW GLOBAL STATUS LIKE 'wsrep_local_state'" 2>/dev/null | awk '{print $$2}')
         |if [ "$$STATE" = "4" ]; then
         |  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nGalera node is synced.\n'
         |else
         |  printf 'HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nGalera node is not synced (state: %s).\n' "$${STATE:-down}"
         |fi
         |""".stripMargin

  // Writes the script above to disk (via a quoted 'SCRIPT_EOF' heredoc
  // delimiter, so none of the script's own many literal $ characters get
  // expanded while it's being written out — they're meant for bash to
  // interpret when the script itself later RUNS, not while this outer
  // script is writing it), then exposes it on :9200 via a systemd socket
  // unit (Accept=yes — one short-lived service instance per connection,
  // exactly matching xinetd's traditional role, without needing xinetd
  // installed at all).
  private val installClustercheckScript =
    s"""cat > /usr/local/bin/galera-clustercheck <<'SCRIPT_EOF'
       |$clustercheckScript
       |SCRIPT_EOF
       |chmod +x /usr/local/bin/galera-clustercheck
       |cat > /etc/systemd/system/clustercheck.socket <<'EOF'
       |[Socket]
       |ListenStream=9200
       |Accept=yes
       |
       |[Install]
       |WantedBy=sockets.target
       |EOF
       |cat > /etc/systemd/system/clustercheck@.service <<'EOF'
       |[Service]
       |ExecStart=/usr/local/bin/galera-clustercheck
       |StandardInput=socket
       |StandardOutput=socket
       |EOF
       |systemctl daemon-reload
       |systemctl enable --now clustercheck.socket
       |echo 'clustercheck health-check endpoint listening on :9200'""".stripMargin

  // Symmetric across all three nodes — same reasoning as Galera's own
  // wsrep_cluster_address: every node's haproxy.cfg lists the identical
  // three backends via {{nodes.<name>.cluster_ip}} templating, so no
  // DistributeFile step is needed here either.
  //
  // Caught in real use, fixed same day, TWICE — worth being honest about
  // both rounds rather than just the final state:
  //
  // Round 1: the first version of this script ran `systemctl restart
  // haproxy` without checking whether it actually succeeded — a failed
  // restart ("control process exited with error code") was followed by
  // an unconditional `echo` that still exited 0, so the task reported
  // `success=true` while haproxy was, in fact, not running at all. Fixed
  // by checking `systemctl is-active` after restarting and `exit 1` if
  // not — that fix was correct and stayed; it's what turned the SECOND
  // real failure below into a loud, honest one instead of another silent
  // false-success.
  //
  // Round 2: with the masking bug fixed, the real error surfaced —
  // `journalctl` showed `cannot bind socket (Address already in use) for
  // [0.0.0.0:3306]`, even though `ss` confirmed mysqld was correctly
  // bound to only its own specific address (`10.10.5.12:3306`), not the
  // wildcard — meaning the bind-address fix in
  // mariadb_galera_cluster.scala, while independently reasonable, never
  // actually addressed this conflict. The real cause: on Linux, a
  // wildcard bind (`0.0.0.0:PORT`, what `bind *:3306` originally used
  // here) overlaps and conflicts with ANY already-bound specific address
  // on the same port — it's not narrowed away by the OTHER process
  // binding narrowly; the WILDCARD side is what needs to stop being
  // wildcard. This is a genuine gap in the reference guide this file was
  // built from (see header comment): that guide runs haproxy/keepalived
  // on dedicated LB nodes, never colocated with a Galera backend on the
  // same host, so this exact same-host port collision never comes up for
  // it — colocating (the user's own choice when asked) is what surfaces
  // it here.
  //
  // Fixed properly by having HAProxy bind the VIP address SPECIFICALLY
  // (`$vip:3306`/`$vip:3307`), never a wildcard — which also means
  // clients only reach HAProxy via the VIP, exactly the intended design,
  // rather than incidentally also being reachable on each node's own
  // address. Binding a specific address that isn't currently configured
  // on this node's interface (true for whichever two nodes DON'T
  // currently hold the VIP) needs the kernel's `net.ipv4.ip_nonlocal_bind`
  // — off by default — set first; without it, HAProxy would fail to
  // start on the backup nodes with "Cannot assign requested address"
  // instead. Researched before writing, not assumed:
  // https://www.cyberciti.biz/faq/linux-bind-ip-that-doesnt-exist-with-net-ipv4-ip_nonlocal_bind/
  private val haproxyConfigScript =
    s"""cat > /etc/sysctl.d/99-orphera-haproxy-vip.conf <<'EOF'
       |net.ipv4.ip_nonlocal_bind=1
       |EOF
       |sysctl -p /etc/sysctl.d/99-orphera-haproxy-vip.conf
       |cat > /etc/haproxy/haproxy.cfg <<'EOF'
       |global
       |    daemon
       |    maxconn 256
       |
       |defaults
       |    mode tcp
       |    timeout connect 5s
       |    timeout client 30s
       |    timeout server 30s
       |
       |listen galera_write
       |    bind $vip:3306
       |    mode tcp
       |    option httpchk GET /
       |    http-check expect status 200
       |    default-server port 9200 inter 2s downinter 5s rise 3 fall 2 on-marked-down shutdown-sessions
       |    server tst0 {{nodes.tst0.cluster_ip}}:3306 check
       |    server tst1 {{nodes.tst1.cluster_ip}}:3306 check backup
       |    server tst2 {{nodes.tst2.cluster_ip}}:3306 check backup
       |
       |listen galera_read
       |    bind $vip:3307
       |    mode tcp
       |    balance roundrobin
       |    option httpchk GET /
       |    http-check expect status 200
       |    default-server port 9200 inter 2s downinter 5s rise 3 fall 2
       |    server tst0 {{nodes.tst0.cluster_ip}}:3306 check
       |    server tst1 {{nodes.tst1.cluster_ip}}:3306 check
       |    server tst2 {{nodes.tst2.cluster_ip}}:3306 check
       |EOF
       |systemctl enable --now haproxy
       |systemctl restart haproxy
       |sleep 1
       |if ! systemctl is-active --quiet haproxy; then
       |  echo "haproxy failed to start on this node — see 'systemctl status haproxy' / 'journalctl -xeu haproxy' for the real reason" >&2
       |  exit 1
       |fi
       |echo 'haproxy configured: write listener on :3306 (tst0 primary), read listener on :3307 (round-robin), bound to the VIP only'""".stripMargin

  // NOT symmetric, unlike the two scripts above — priority/state differ
  // per node, so this is three separate per-host stages below rather than
  // one shared stage, same "asymmetric content needs asymmetric stages"
  // pattern as mariadb_galera_cluster.scala's bootstrap-tst0/join-others
  // split. `interface` is detected at runtime (`ip -4 route show
  // default`) rather than hardcoded as e.g. "eth0" — guessing OS-level
  // device names instead of confirming them is exactly the class of bug
  // that caused this project's real /dev/sdX incident (see CHANGELOG),
  // and there's no reason to repeat that mistake for a NIC name here when
  // detecting it is one line.
  //
  // `state MASTER` only on the highest-priority node (tst0), `BACKUP` on
  // the other two, matching the reference guide's own convention exactly
  // — some operators prefer `state BACKUP` on every node instead (letting
  // priority alone decide the election, avoiding a narrow startup-race
  // edge case where two simultaneously-booting MASTERs briefly both claim
  // the VIP), which is worth knowing about but not what this file does.
  private def keepalivedPriorityAndState(host: String): (Int, String) =
    host match
      case "tst0" => (101, "MASTER")
      case "tst1" => (100, "BACKUP")
      case "tst2" => (99, "BACKUP")

  private def keepalivedPeers(host: String): List[String] =
    host match
      case "tst0" => List("tst1", "tst2")
      case "tst1" => List("tst0", "tst2")
      case "tst2" => List("tst0", "tst1")

  private def keepalivedConfigScript(host: String): String =
    val (priority, state) = keepalivedPriorityAndState(host)
    val peerLines = keepalivedPeers(host).map(p => s"        {{nodes.$p.cluster_ip}}").mkString("\n")
    s"""IFACE=$$(ip -4 route show default | awk '{print $$5; exit}')
       |cat > /etc/keepalived/keepalived.conf <<EOF
       |vrrp_script chk_haproxy {
       |    script "/usr/bin/pgrep -x haproxy"
       |    interval 2
       |    weight 4
       |}
       |
       |vrrp_instance GALERA_VIP {
       |    state $state
       |    interface $$IFACE
       |    virtual_router_id 51
       |    priority $priority
       |    advert_int 1
       |    unicast_src_ip {{cluster_ip}}
       |    unicast_peer {
       |$peerLines
       |    }
       |    authentication {
       |        auth_type PASS
       |        auth_pass $vrrpAuthPass
       |    }
       |    virtual_ipaddress {
       |        $vip/24
       |    }
       |    track_script {
       |        chk_haproxy
       |    }
       |}
       |EOF
       |systemctl daemon-reload
       |systemctl enable --now keepalived
       |systemctl restart keepalived
       |echo "keepalived configured on $host: priority=$priority state=$state, peers=${keepalivedPeers(host).mkString(",")}"""".stripMargin

  // Checked independently on EACH node (`requiredCount = 1` against three
  // candidates) — this isn't really a quorum in the usual sense (it's
  // "did keepalived's election settle on exactly one leader", not "do N
  // of M nodes agree"), but HealthCheck.Quorum's shape fits well enough:
  // it passes the moment any one node reports holding the VIP. Honest
  // limitation: this does NOT detect a split-brain double-VIP (two nodes
  // both reporting true) — it would still pass, since it only checks for
  // at least one healthy report, not exactly one. A real anti-split-brain
  // check would need to independently count how many nodes hold the VIP
  // in one pass rather than polling each node's own local view in
  // isolation, which HealthCheck.Quorum's per-node design doesn't support
  // as written.
  private val vipHeldScript = s"ip -4 addr show | grep -q '$vip/'"

  private val printVipHolderScript =
    s"""ip -4 addr show | grep -q '$vip/' && echo "this node currently holds the VIP ($vip)" || echo "VIP not held by this node""""

  // Proves the full client-facing path end-to-end — VIP -> haproxy ->
  // backend mariadb — from a node that's very likely NOT the one
  // currently holding the VIP or serving as haproxy's primary write
  // target, rather than just trusting each piece's own status output in
  // isolation. Reuses the clustercheck user (password auth, already
  // created) rather than adding a second test-only credential.
  private val vipConnectivityScript =
    s"""mariadb -h $vip -P 3306 -u$clustercheckUser -p$clustercheckPassword -N -e "SELECT CONCAT('served by ', @@hostname)""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("mariadb-haproxy-keepalived")(

      stage("install-haproxy-keepalived", "tst0", "tst1", "tst2")
        .task("install haproxy, keepalived")(
          Task.Install(packages = List("haproxy", "keepalived"), updateCache = true)
        )
        .build,

      // tst0 only — replicates to tst1/tst2 automatically via Galera, see
      // createClustercheckUserScript's own comment.
      stage("create-clustercheck-user", "tst0")
        .task("create the clustercheck MariaDB user (replicates cluster-wide)")(
          Task.RunCommand(List("sh", "-c", createClustercheckUserScript))
        )
        .build,

      stage("write-clustercheck-healthcheck", "tst0", "tst1", "tst2")
        .task("install the :9200 clustercheck health-check endpoint")(
          Task.RunCommand(List("sh", "-c", installClustercheckScript))
        )
        .build,

      stage("write-haproxy-config", "tst0", "tst1", "tst2")
        .task("write /etc/haproxy/haproxy.cfg and (re)start haproxy")(
          Task.RunCommand(List("sh", "-c", haproxyConfigScript))
        )
        .build,

      // Three separate single-node stages — see keepalivedConfigScript's
      // own comment for why this can't be one shared stage like the two
      // above.
      stage("write-keepalived-tst0", "tst0")
        .task("write /etc/keepalived/keepalived.conf and (re)start keepalived")(
          Task.RunCommand(List("sh", "-c", keepalivedConfigScript("tst0")))
        )
        .build,

      stage("write-keepalived-tst1", "tst1")
        .task("write /etc/keepalived/keepalived.conf and (re)start keepalived")(
          Task.RunCommand(List("sh", "-c", keepalivedConfigScript("tst1")))
        )
        .build,

      stage("write-keepalived-tst2", "tst2")
        .task("write /etc/keepalived/keepalived.conf and (re)start keepalived")(
          Task.RunCommand(List("sh", "-c", keepalivedConfigScript("tst2")))
        )
        .build,

      stage("confirm-ha-frontend", "tst1")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", vipHeldScript),
            requiredCount = 1,
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("print which node currently holds the VIP")(
          Task.RunCommand(List("sh", "-c", printVipHolderScript))
        )
        .task(s"connect through the VIP ($vip:3306) and confirm a response")(
          Task.RunCommand(List("sh", "-c", vipConnectivityScript))
        )
        .task("ha frontend confirmed")(
          Task.Debug(
            s"MariaDB Galera cluster is now fronted by haproxy+keepalived on $vip: " +
              "one node holds the VIP, haproxy routes writes to a single primary " +
              "and reads round-robin across all three, each gated on wsrep_local_state=4 (Synced)."
          )
        )
        .build
    )
