import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Follow-up to manifests/etcd_cluster.scala (run it first — this assumes
// a healthy 3-node cluster already exists on tst0/tst1/tst2). That first
// playbook only exercised etcd's SYMMETRIC static bootstrap: every node
// starts at once, already agreeing on the full peer list from inventory
// alone. This exercises the genuinely different, ASYMMETRIC shape flagged
// as still-unexercised in that file's own commentary: removing tst2 from
// the live cluster, wiping its state, then rejoining it — which etcd
// requires as a strict two-step, cross-node-ordered sequence:
//
//   1. `etcdctl member add` MUST be run against an EXISTING, already-
//      healthy member (tst0 here) to register the rejoining node's
//      identity in the cluster's membership list...
//   2. ...BEFORE the rejoining node (tst2) is allowed to start with
//      `--initial-cluster-state existing` — start it first and it just
//      fails to join, because as far as the live cluster is concerned
//      that peer doesn't exist yet.
//
// That's expressed here as two separate stages (register-new-identity on
// tst0, then rejoin-tst2 on tst2) rather than two tasks in one stage,
// because tasks within a single stage run in PARALLEL across that
// stage's nodes (see ClusterPlaybookRunner.runStageTasks) — putting both
// steps in one stage with nodes=(tst0, tst2) would race them, which is
// exactly the bug this two-stage split exists to avoid. Stages, unlike
// tasks-within-a-stage, run strictly one after another.
//
// Honest limit on what this proves: tst2's peer address is already known
// statically from inventory (`{{nodes.tst2.cluster_ip}}`), same as in
// etcd_cluster.scala — so this does NOT exercise a real gap identified
// alongside it, capturing a task's OUTPUT on one node (e.g. `member add`'s
// freshly assigned member ID, or a brand-new node's address nobody typed
// into inventory) and feeding it into a task on a different node.
//
// CORRECTION (see manifests/etcd_grow_cluster.scala): the line above
// overstated the gap. Checked against the real DSL/runner source since
// writing this file — `Task.SetFact` writes into a shared, run-scoped
// store (`SetFacts`), not a per-node-only one, and a later stage on ANY
// node CAN read another node's fact via `{{nodes.<name>.<key>}}`, the
// same templating already used here for static inventory vars. The real
// gap is narrower: there's no `register`/capture mechanism that turns a
// RunCommand's stdout (e.g. `member add`'s freshly assigned member ID)
// into a fact automatically — SetFact's value must be a literal or
// template expression, not "whatever the last command printed". That
// narrower gap is still real and still unexercised by this file or
// etcd_grow_cluster.scala, since every address either file uses comes
// from inventory, not from anything computed at runtime. What this file
// DOES prove is the narrower, but still real, "stage B must not start
// until stage A has truly finished on a different node" ordering
// requirement, and that wiping+rejoining a member under a fresh identity
// actually works end-to-end against real infrastructure.
object etcd_member_rejoin extends OrpheraClusterPlaybook:

  private def etcdCtl(cmd: String) =
    s"ETCDCTL_API=3 /usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 $cmd"

  // Idempotent: if tst2 isn't currently a member (already removed by an
  // earlier attempt at this playbook), this is a no-op rather than an
  // error — same "check first, act only if needed" convention as
  // etcd_cluster.scala's installEtcdScript.
  private val removeMemberScript =
    s"""MEMBER_ID=$$(${etcdCtl(
        "member list"
      )} | grep ', tst2,' | cut -d',' -f1 | tr -d ' ')
       |if [ -n "$$MEMBER_ID" ]; then
       |  echo "Removing tst2 (member $$MEMBER_ID) from the cluster"
       |  ${etcdCtl("member remove \"$MEMBER_ID\"")}
       |else
       |  echo "tst2 is not currently a member — nothing to remove"
       |fi""".stripMargin

  // A removed member's old WAL/snapshot data is tied to its old member
  // ID and can never be reused for a fresh join — starting etcd again
  // against stale data here would fail (or worse, silently confuse
  // raft) rather than rejoin cleanly, so the data dir has to actually
  // be empty, not just have the service stopped.
  private val stopAndWipeScript =
    """systemctl stop etcd 2>/dev/null || true
      |rm -rf /var/lib/etcd
      |mkdir -p /var/lib/etcd
      |chown etcd:etcd /var/lib/etcd""".stripMargin

  // Guarded the same way as removeMemberScript: safe to re-run this
  // whole playbook even if a previous attempt got partway through.
  private val registerNewIdentityScript =
    s"""EXISTING=$$(${etcdCtl("member list")} | grep ', tst2,' || true)
       |if [ -z "$$EXISTING" ]; then
       |  echo "Registering tst2 as a new member"
       |  ${etcdCtl(
        "member add tst2 --peer-urls=http://{{nodes.tst2.cluster_ip}}:2380"
      )}
       |else
       |  echo "tst2 is already registered as a member — skipping member add"
       |fi""".stripMargin

  // Same unit file as etcd_cluster.scala's configureAndStartScript, with
  // exactly one change: --initial-cluster-state existing, not new. `new`
  // is only valid for a member starting completely fresh cluster
  // formation; `existing` tells this node it's joining a cluster that
  // already has other members running — using `new` here would make
  // etcd refuse to start, since the cluster (tst0, tst1) already exists.
  private val rejoinScript =
    """cat > /etc/systemd/system/etcd.service <<EOF
      |[Unit]
      |Description=etcd distributed key-value store (orphera etcd_cluster test)
      |After=network.target
      |
      |[Service]
      |Type=notify
      |User=etcd
      |ExecStart=/usr/local/bin/etcd \
      |  --name tst2 \
      |  --data-dir /var/lib/etcd \
      |  --listen-peer-urls http://{{cluster_ip}}:2380 \
      |  --listen-client-urls http://{{cluster_ip}}:2379,http://127.0.0.1:2379 \
      |  --initial-advertise-peer-urls http://{{cluster_ip}}:2380 \
      |  --advertise-client-urls http://{{cluster_ip}}:2379 \
      |  --initial-cluster tst0=http://{{nodes.tst0.cluster_ip}}:2380,tst1=http://{{nodes.tst1.cluster_ip}}:2380,tst2=http://{{nodes.tst2.cluster_ip}}:2380 \
      |  --initial-cluster-token orphera-etcd-test \
      |  --initial-cluster-state existing
      |Restart=on-failure
      |RestartSec=5
      |
      |[Install]
      |WantedBy=multi-user.target
      |EOF
      |systemctl daemon-reload
      |systemctl enable --now etcd""".stripMargin

  private val healthCheckScript =
    "ETCDCTL_API=3 /usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 endpoint health >/dev/null 2>&1"

  private val printRosterScript = etcdCtl("member list --write-out=table")

  val playbook: ClusterPlaybook =
    clusterPlaybook("etcd-member-rejoin")(
      stage("show-roster-before", "tst0")
        .task("print member roster (before)")(
          Task.RunCommand(List("sh", "-c", printRosterScript))
        )
        .build,

      stage("remove-member", "tst0")
        .task("remove tst2 from the cluster")(
          Task.RunCommand(List("sh", "-c", removeMemberScript))
        )
        .build,

      stage("stop-and-wipe", "tst2")
        .task("stop etcd and wipe stale data dir")(
          Task.RunCommand(List("sh", "-c", stopAndWipeScript))
        )
        .build,

      // Must run BEFORE rejoin-tst2, on a DIFFERENT node — see the
      // header comment on why this can't be merged into one stage.
      stage("register-new-identity", "tst0")
        .task("register tst2 as a new member")(
          Task.RunCommand(List("sh", "-c", registerNewIdentityScript))
        )
        .build,

      stage("rejoin-tst2", "tst2")
        .task("rewrite unit as a joiner and start")(
          Task.RunCommand(List("sh", "-c", rejoinScript))
        )
        .build,

      stage("confirm-quorum-again", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", healthCheckScript),
            requiredCount = 3,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("print member roster (after)")(
          Task.RunCommand(List("sh", "-c", printRosterScript))
        )
        .task("rejoin confirmed")(
          // Compare this run's tst2 member ID in the "after" roster
          // against "before" — it should differ, since a removed and
          // re-added member gets a fresh ID even though the name and
          // address are unchanged. Same ID would mean this wasn't a
          // real rejoin.
          Task.Debug(
            "tst2 rejoined — compare its member ID above against the 'before' roster; it should be different."
          )
        )
        .build
    )
