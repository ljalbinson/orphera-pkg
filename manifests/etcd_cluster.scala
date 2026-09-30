import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// First cut at the etcd exercise discussed alongside the Keystone/Galera
// side-thought: a 3-node etcd cluster, built from a static Go binary release
// rather than a distro package, specifically to isolate orchestration
// lessons from install-complexity (see CHANGELOG for the fuller writeup of
// why etcd was picked over Keystone+Galera for this).
//
// Deliberately mirrors manifests/ceph_mon_quorum.scala's shape (hand-rolled
// cluster bootstrap + a Quorum health gate) rather than the cephadm_*
// examples, since ceph_mon_quorum.scala is the closest existing precedent
// for "generate cluster identity, get every node to agree on it, then wait
// for quorum" without an external orchestrator tool (cephadm) doing the
// coordination for us.
//
// One genuine difference from ceph_mon_quorum worth calling out: that
// playbook generates cluster identity (fsid, monmap) ONCE on tst0 and
// DistributeFiles it to tst1/tst2 — an asymmetric bootstrap. etcd's static
// `--initial-cluster` bootstrap needs no such generate-then-distribute
// step: the full peer list is knowable in advance from inventory vars
// alone, so it's rendered identically (via {{nodes.<name>.cluster_ip}}
// templating) on all three nodes independently, with zero DistributeFile
// calls. If this turns out to work cleanly, it's a data point that
// Orphera's existing cross-node templating already covers etcd's
// symmetric-bootstrap case; the DistributeFile-shaped "run once, push the
// result" gap identified in the Keystone/Galera discussion would only
// bite on an actual asymmetric case (Galera, or etcd runtime membership
// changes) — not proven or disproven by this playbook alone.
object etcd_cluster extends OrpheraClusterPlaybook:

  // Bump this if a newer v3.5.x (or v3.6.x) release is preferred —
  // confirmed real as of writing: https://github.com/etcd-io/etcd/releases/tag/v3.5.21
  private val etcdVersion = "v3.5.21"

  // Idempotent: does nothing if the binaries are already in place (safe
  // to re-run this playbook, or --resume past this stage). Runs curl on
  // the node itself, not the orchestrator host — these test nodes need
  // outbound internet access to github.com for this to work at all,
  // which is a real prerequisite worth confirming before running this
  // for the first time.
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

  // The only per-node-varying piece is $NAME (resolved remotely via
  // `hostname -s`, matching ceph_mon_quorum.scala's $MONID convention —
  // this assumes each node's hostname matches its inventory name, i.e.
  // tst0/tst1/tst2). Everything else in --initial-cluster is the exact
  // same literal string on all three nodes: {{nodes.tst0.cluster_ip}} /
  // {{nodes.tst1.cluster_ip}} / {{nodes.tst2.cluster_ip}} are all
  // resolved by the orchestrator (Templating.render) before this script
  // ever reaches the node, so what actually lands in etcd.service is
  // three plain IPs baked in identically everywhere — no shell-side
  // cleverness needed to make the three nodes agree on who's in the
  // cluster.
  //
  // `--initial-cluster-state new` only governs first bootstrap against
  // an empty data dir — etcd ignores it and uses its own persisted
  // state on any later restart, so regenerating this unit file on a
  // resumed/re-run is safe. `Type=notify` assumes this etcd build calls
  // sd_notify on startup (true for the official static releases); if a
  // real run shows systemd timing out waiting for the READY notification,
  // that's the first thing to try changing to `Type=simple`.
  //
  // The ExecStart line's trailing `\` continuations below are for THIS
  // SOURCE FILE's readability only: an unquoted heredoc (`<<EOF`, no
  // quotes around the delimiter) has bash ignore every `\<newline>`
  // sequence it contains, same as inside a double-quoted string — so
  // those line breaks never make it into the written etcd.service at
  // all, and ExecStart lands there as one long single line. That's
  // functionally identical (systemd doesn't care either way) and not a
  // bug, just worth knowing before "fixing" what looks like a missing
  // continuation if you ever cat the deployed file.
  private val configureAndStartScript =
    """NAME=$(hostname -s)
      |cat > /etc/systemd/system/etcd.service <<EOF
      |[Unit]
      |Description=etcd distributed key-value store (orphera etcd_cluster test)
      |After=network.target
      |
      |[Service]
      |Type=notify
      |User=etcd
      |ExecStart=/usr/local/bin/etcd \
      |  --name $NAME \
      |  --data-dir /var/lib/etcd \
      |  --listen-peer-urls http://{{cluster_ip}}:2380 \
      |  --listen-client-urls http://{{cluster_ip}}:2379,http://127.0.0.1:2379 \
      |  --initial-advertise-peer-urls http://{{cluster_ip}}:2380 \
      |  --advertise-client-urls http://{{cluster_ip}}:2379 \
      |  --initial-cluster tst0=http://{{nodes.tst0.cluster_ip}}:2380,tst1=http://{{nodes.tst1.cluster_ip}}:2380,tst2=http://{{nodes.tst2.cluster_ip}}:2380 \
      |  --initial-cluster-token orphera-etcd-test \
      |  --initial-cluster-state new
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

  val playbook: ClusterPlaybook =
    clusterPlaybook("etcd-cluster")(

      stage("install-etcd", "tst0", "tst1", "tst2")
        .task("install curl/tar prerequisites")(
          Task.Install(packages = List("curl", "tar"), updateCache = true)
        )
        .task("download and install etcd binaries")(
          Task.RunCommand(List("sh", "-c", installEtcdScript), timeoutSeconds = 120)
        )
        .build,

      stage("configure-and-start", "tst0", "tst1", "tst2")
        .task("write systemd unit and start etcd")(
          Task.RunCommand(List("sh", "-c", configureAndStartScript))
        )
        .build,

      // Unlike cephadm_add_mons.scala's Quorum check (one node reporting
      // the CLUSTER's view via `ceph mon stat`), this asks each of the
      // three nodes to report on ITS OWN endpoint independently —
      // requiredCount = 3, not 1 — so it's really three simultaneous
      // single-node health checks rather than one cluster-wide read.
      // Worth confirming in practice whether that distinction matters
      // for how quickly HealthCheck.Quorum reports "reached": it should
      // behave identically (poll everyone, count how many pass), but
      // this is the first time it's driving per-node liveness rather
      // than a single node's view of consensus state.
      // Only tst0 runs this stage's own tasks (the roster print and the
      // final Debug) — HealthCheck.Quorum below checks all three nodes
      // regardless of the stage's own node list, those are independent.
      // Listing all three here as well would just print the same roster
      // three times over.
      stage("confirm-quorum", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", healthCheckScript),
            requiredCount = 3,
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("print member roster")(
          Task.RunCommand(List(
            "sh", "-c",
            "ETCDCTL_API=3 /usr/local/bin/etcdctl --endpoints=http://127.0.0.1:2379 member list --write-out=table"
          ))
        )
        .task("quorum confirmed")(
          Task.Debug("3-node etcd cluster healthy.")
        )
        .build
    )
