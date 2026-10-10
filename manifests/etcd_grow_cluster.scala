import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Third etcd exercise, after etcd_cluster.scala (symmetric 3-node bootstrap)
// and etcd_member_rejoin.scala (remove/wipe/rejoin an existing member).
// This one grows the live cluster from 3 nodes to 5 by adding tst3 and
// tst4 as genuinely NEW members — not a rejoin of something that was
// already part of the cluster's history.
//
// Correction to a claim made in etcd_member_rejoin.scala's own header
// comment: that file says Orphera "has no mechanism" for a task's output
// on one node feeding a task on a different node. That's wrong — checked
// against the real DSL/runner source this time rather than assumed.
// `Task.SetFact` writes into a shared, run-scoped store (`SetFacts`, see
// `ClusterPlaybookRunner`/`SetFacts.scala`), not a per-node-only one, and
// a later stage on ANY node can read another node's fact via
// `{{nodes.<name>.<key>}}` — exactly the same templating this file (and
// etcd_cluster.scala) already uses for static inventory vars. The actual
// gap is narrower than previously stated: there is no `register`/capture
// mechanism that turns a RunCommand's stdout (e.g. the member ID
// `etcdctl member add` prints back) into a fact automatically — SetFact's
// value has to be a literal or template expression, not "whatever the
// last command printed". That narrower gap is still real and still
// unexercised here, since tst3/tst4's addresses come from inventory, not
// from anything computed at runtime.
//
// Growing one member at a time (register tst3, start it, confirm 4/4
// healthy, THEN register tst4, start it, confirm 5/5) rather than adding
// both together follows etcd's own operational guidance: changing cluster
// membership by more than one node at a time risks the cluster
// temporarily requiring more simultaneous votes than are actually
// available, even when the math looks fine on paper. Each existing
// member's own unit file is untouched throughout — `--initial-cluster`
// only governs a node's OWN first bootstrap against an empty data dir
// (see etcd_cluster.scala's commentary), so tst0/tst1/tst2 don't need
// restarting or reconfiguring just because the cluster's membership grew
// around them.
// NOTE (2026-10-10): tst3 and tst4 now run Galera (see the node roles in
// inventory.yaml), so this growth test must not be run against them as written;
// point it at free nodes first.
object etcd_grow_cluster extends OrpheraClusterPlaybook:

  private val etcdVersion = "v3.5.21"

  // Identical to etcd_cluster.scala's installEtcdScript — duplicated
  // rather than shared, since each manifest here is a standalone object.
  private val installEtcdScript =
    s"""test -x /usr/local/bin/etcd && test -x /usr/local/bin/etcdctl && exit 0
       |set -e
       |cd /tmp
       |curl -fsSL -o etcd.tar.gz "https://github.com/etcd-io/etcd/releases/download/$etcdVersion/etcd-$etcdVersion-linux-amd64.tar.gz"
       |tar xzf etcd.tar.gz
       |install -m 0755 "etcd-$etcdVersion-linux-amd64/etcd" /usr/local/bin/etcd
       |install -m 0755 "etcd-$etcdVersion-linux-amd64/etcdctl" /usr/local/bin/etcdctl
       |rm -rf etcd.tar.gz "etcd-$etcdVersion-linux-amd64"
       |id etcd >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin etcd
       |mkdir -p /var/lib/etcd /etc/etcd
       |chown etcd:etcd /var/lib/etcd""".stripMargin

  private def etcdCtl(cmd: String) =
    s"ETCDCTL_API=3 /usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 $cmd"

  private val printRosterScript = etcdCtl("member list --write-out=table")

  private val healthCheckScript =
    "ETCDCTL_API=3 /usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 endpoint health >/dev/null 2>&1"

  // Idempotent, same "check the current membership list first" convention
  // as etcd_member_rejoin.scala: safe to re-run this whole playbook if a
  // previous attempt got partway through registering either node.
  //
  // Retries `member add` itself on failure, up to 6 times / 5s apart
  // (30s max), rather than a blind sleep before every call regardless of
  // whether one's needed. Exists because of a real failure hit running
  // this file: register-tst4 failed once with "etcdserver: unhealthy
  // cluster" immediately after tst3 joined — etcd's `mayAddMember` check
  // (`isConnectedToQuorumAfterAddingNewMemberSince`) requires the node
  // handling the request to have a settled rafthttp peer connection to
  // the newly-joined member, which can lag slightly behind that member
  // answering its own local health check (see the longer writeup on
  // confirm-4-healthy below, and the CHANGELOG entry for this file, for
  // the full trace — etcd doesn't publish a constant for how long this
  // actually takes). Retrying the real operation instead of guessing a
  // fixed wait means: registering tst3 (no prior addition, so nothing to
  // settle) succeeds on the first attempt with zero added delay, and
  // registering tst4 pays only as many 5s waits as the actual settle
  // takes, not a fixed cost either way.
  private def registerScript(name: String, peerUrl: String) =
    s"""EXISTING=$$(${etcdCtl("member list")} | grep ', $name,' || true)
       |if [ -z "$$EXISTING" ]; then
       |  echo "Registering $name as a new member"
       |  ATTEMPT=0
       |  MAX_ATTEMPTS=6
       |  until ${etcdCtl(s"member add $name --peer-urls=$peerUrl")}; do
       |    ATTEMPT=$$((ATTEMPT + 1))
       |    if [ "$$ATTEMPT" -ge "$$MAX_ATTEMPTS" ]; then
       |      echo "member add $name failed after $$MAX_ATTEMPTS attempts — giving up"
       |      exit 1
       |    fi
       |    echo "member add $name failed (attempt $$ATTEMPT/$$MAX_ATTEMPTS) — retrying in 5s"
       |    sleep 5
       |  done
       |else
       |  echo "$name is already registered as a member — skipping member add"
       |fi""".stripMargin

  // `initialCluster` is the FULL membership this joining node should
  // believe in at start time — every node already registered via
  // `member add` up to and including itself, but NOT nodes that haven't
  // been registered yet (tst4 is absent from tst3's own unit file, since
  // at the point tst3 starts, tst4 doesn't exist in the cluster's
  // membership list yet). `--initial-cluster-state existing` (not `new`)
  // is what tells this node it's joining a cluster that already has
  // other live members, same as etcd_member_rejoin.scala's rejoin step.
  // `raw"""..."""`, not `s"""..."""`: this string needs $-interpolation
  // (name/clusterIp/initialCluster) but also contains literal `\` line
  // continuations in ExecStart below. An interpolated triple-quoted
  // string (`s"""`) still validates escape sequences the same as a
  // normal string, unlike a bare `"""..."""` (fully raw, which is what
  // etcd_cluster.scala's non-interpolated configureAndStartScript relies
  // on) — a bare `\` before a newline isn't one of the recognized escapes
  // ([\b,\t,\n,\f,\r,\\,\",\',\uxxxx]) and fails to compile under `s`.
  // `raw` keeps $-interpolation but skips escape validation entirely, so
  // the backslashes pass through untouched — caught by a real compile
  // error the first time this file was actually run.
  private def joinScript(
      name: String,
      clusterIp: String,
      initialCluster: String
  ) =
    raw"""cat > /etc/systemd/system/etcd.service <<EOF
       |[Unit]
       |Description=etcd distributed key-value store (orphera etcd_cluster test)
       |After=network.target
       |
       |[Service]
       |Type=notify
       |User=etcd
       |ExecStart=/usr/local/bin/etcd \
       |  --name $name \
       |  --data-dir /var/lib/etcd \
       |  --listen-peer-urls http://$clusterIp:2380 \
       |  --listen-client-urls http://$clusterIp:2379,http://127.0.0.1:2379 \
       |  --initial-advertise-peer-urls http://$clusterIp:2380 \
       |  --advertise-client-urls http://$clusterIp:2379 \
       |  --initial-cluster $initialCluster \
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

  // Built once per joining node from static inventory vars — same
  // {{nodes.<name>.cluster_ip}} templating etcd_cluster.scala uses, not
  // the SetFacts cross-node mechanism described above, since nothing
  // here is actually computed at runtime.
  private val clusterAtThreePlusTst3 =
    "tst0=http://{{nodes.tst0.cluster_ip}}:2380," +
      "tst1=http://{{nodes.tst1.cluster_ip}}:2380," +
      "tst2=http://{{nodes.tst2.cluster_ip}}:2380," +
      "tst3=http://{{nodes.tst3.cluster_ip}}:2380"

  private val clusterAtFourPlusTst4 =
    clusterAtThreePlusTst3 + ",tst4=http://{{nodes.tst4.cluster_ip}}:2380"

  val playbook: ClusterPlaybook =
    clusterPlaybook("etcd-grow-cluster")(
      stage("install-etcd-new-nodes", "tst3", "tst4")
        .task("install curl/tar prerequisites")(
          Task.Install(packages = List("curl", "tar"), updateCache = true)
        )
        .task("download and install etcd binaries")(
          Task.RunCommand(
            List("sh", "-c", installEtcdScript),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("show-roster-before", "tst0")
        .task("print member roster (3 nodes)")(
          Task.RunCommand(List("sh", "-c", printRosterScript))
        )
        .build,

      // register-tst3 and join-tst3 MUST be separate stages, on different
      // nodes, for the same reason as etcd_member_rejoin.scala's
      // register-new-identity/rejoin-tst2 split: tasks within one stage
      // run in parallel across that stage's nodes, so combining "register
      // on tst0" and "start on tst3" into one stage would race them.
      stage("register-tst3", "tst0")
        .task("register tst3 as a new member")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              registerScript("tst3", "http://{{nodes.tst3.cluster_ip}}:2380")
            )
          )
        )
        .build,

      stage("join-tst3", "tst3")
        .task("write systemd unit and start etcd")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              joinScript("tst3", "{{cluster_ip}}", clusterAtThreePlusTst3)
            )
          )
        )
        .build,

      // Confirm 4/4 healthy BEFORE touching tst4 at all — verifying each
      // addition independently, rather than adding both new nodes back
      // to back, is what actually follows etcd's own guidance here.
      //
      // Why this Quorum check alone doesn't guarantee register-tst4 (the
      // next stage) will succeed on its first try: caught in real use
      // (2026-09-30) — register-tst4 once failed immediately after this
      // stage reported 4/4 in ~0s, with "etcdserver: unhealthy cluster".
      // etcd's `--strict-reconfig-check` (on by default) refuses a
      // membership change unless the node handling it (tst0) has a LIVE
      // PEER-TRANSPORT (rafthttp) CONNECTION to a quorum of the cluster
      // the change would create — the actual check is `mayAddMember`'s
      // `isConnectedToQuorumAfterAddingNewMemberSince`. That's a
      // materially different, slightly slower signal than THIS stage's
      // `HealthCheck.Quorum`, which only asks each node to answer its own
      // local `etcdctl endpoint health` over loopback — tst3 can answer
      // that within milliseconds of starting, well before tst0's own
      // outbound rafthttp connection to it has necessarily settled, and
      // neither `member list` nor `endpoint health` exposes that state to
      // check for directly. No etcd-published constant for how long that
      // settling takes turned up (checked the docs and several real
      // GitHub issues hitting the same error). `registerScript` above
      // handles this with a bounded retry around the actual `member add`
      // call instead of a blind pre-sleep here — see its own comment.
      stage("confirm-4-healthy", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2", "tst3"),
            command = List("sh", "-c", healthCheckScript),
            requiredCount = 4,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("print member roster (4 nodes)")(
          Task.RunCommand(List("sh", "-c", printRosterScript))
        )
        .build,

      stage("register-tst4", "tst0")
        .task("register tst4 as a new member")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              registerScript("tst4", "http://{{nodes.tst4.cluster_ip}}:2380")
            )
          )
        )
        .build,

      stage("join-tst4", "tst4")
        .task("write systemd unit and start etcd")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              joinScript("tst4", "{{cluster_ip}}", clusterAtFourPlusTst4)
            )
          )
        )
        .build,

      stage("confirm-5-healthy", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2", "tst3", "tst4"),
            command = List("sh", "-c", healthCheckScript),
            requiredCount = 5,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("print member roster (5 nodes)")(
          Task.RunCommand(List("sh", "-c", printRosterScript))
        )
        .task("growth confirmed")(
          Task.Debug(
            "etcd cluster grown from 3 to 5 nodes: tst0, tst1, tst2, tst3, tst4."
          )
        )
        .build
    )
