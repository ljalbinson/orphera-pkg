// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Ninth infrastructure exercise, and a follow-up to both
// observability_extended.scala AND the cephadm lifecycle
// (cephadm_install/cephadm_add_mons/cephadm_add_osds.scala): wires Ceph's
// own metrics into the SAME tst7 Prometheus/Grafana stack those two
// manifests already stood up, rather than standing up a second
// monitoring stack. This assumes the Ceph chain is already applied:
// cephadm_install -> cephadm_add_mons -> cephadm_add_osds (so there's a
// live 3-mon/3-osd cluster on tst0/tst1/tst2 to scrape), and that
// observability_stack.scala has been applied (so tst7's prometheus.yml
// names /etc/prometheus/scrape.d/*.yml, where this file puts its job).
//
// Note the node reuse: tst0/tst1/tst2 already run Galera + haproxy
// (mariadb_galera_cluster.scala / mariadb_haproxy_keepalived.scala) AND
// now also host the Ceph mon/osd daemons via cephadm — same three nodes,
// two unrelated clustered services, consistent with every other node in
// this lab being reused across exercises rather than kept single-purpose
// (same reasoning observability_stack.scala's header comment gives for
// why the monitoring node is a fresh node rather than reusing tst5).
//
// Deliberately does NOT use cephadm's own built-in monitoring stack
// (Prometheus/Grafana/node-exporter/alertmanager, auto-deployed unless
// skipped) — cephadm_install.scala already passes
// `--skip-dashboard --skip-monitoring-stack` at bootstrap specifically
// so Ceph's metrics land in THIS project's existing tst7 stack instead
// of a second, parallel one.
//
// Mechanism: Ceph's mgr daemon ships a built-in `prometheus` module
// (no separate exporter package, unlike haproxy/mysqld in
// observability_extended.scala) that exposes a `/metrics` endpoint
// directly — `ceph mgr module enable prometheus` is the entire
// "install" step. Default port 9283, default bind address `::` (all
// interfaces) — UNCONFIRMED against a real run, flagged the same way
// every other new mechanism in this project starts out: cephadm runs
// the mgr container with host networking, so the assumption is that
// port is reachable at the mgr host's {{cluster_ip}} with no extra
// config, but that's exactly the kind of assumption that's been wrong
// before in this project (haproxy's metrics bind in
// observability_extended.scala) and needs a real `curl` against it to
// confirm, not just a trust in the docs.
//
// Mgr placement: cephadm_install.scala's bootstrap puts the first (and,
// since nothing in this project's cephadm chain ever calls
// `ceph orch apply mgr` with a placement count, so far the ONLY) mgr on
// tst0. No standby mgr failover handling here — if that changes (a
// second mgr gets placed), this file's single-host assumption needs
// revisiting, same incremental-scope note observability_stack.scala
// gives for its own node_exporter-only first pass.
object ceph_observability extends OrpheraClusterPlaybook:

  private val mgrNode = "tst0"
  private val monitoringNode = "tst7"
  private val cephMetricsPort = 9283

  // `ceph mgr module enable` is idempotent — enabling an
  // already-enabled module is a no-op success, not an error — so no
  // "is it already on" guard is needed before this.
  private val enablePrometheusModuleScript =
    s"""cephadm shell -- ceph mgr module enable prometheus
       |READY=false
       |for i in $$(seq 1 20); do
       |  if curl -sf http://localhost:$cephMetricsPort/metrics 2>/dev/null | grep -q '^ceph_'; then
       |    READY=true
       |    break
       |  fi
       |  sleep 3
       |done
       |if [ "$$READY" != "true" ]; then
       |  echo "ceph mgr prometheus module enabled but :$cephMetricsPort/metrics never showed ceph_* series after 60s — check 'cephadm shell -- ceph mgr services' and 'cephadm shell -- ceph mgr module ls'" >&2
       |  exit 1
       |fi
       |echo "ceph mgr prometheus module active on :$cephMetricsPort"""".stripMargin

  // The monitoring node only. The ceph job is a file of its own under
  // /etc/prometheus/scrape.d, which observability_stack.scala's prometheus.yml
  // names with `scrape_config_files`: this playbook no longer rewrites
  // prometheus.yml, so it can no longer remove the jobs of
  // observability_stack.scala or observability_extended.scala, and re-running
  // those does not remove this one.
  private val cephExporterTarget =
    s"'{{nodes.$mgrNode.cluster_ip}}:$cephMetricsPort'"

  // The scrape file (`{{nodes.<n>.cluster_ip}}` rendered on the node), as an
  // apply description: the file, then prometheus restarted if it changed.
  private val prometheusPlan: ApplyPlan =
    import Resources.*
    val dir = directory("/etc/prometheus/scrape.d", mode = "0755")
    val cfg = fileTemplate(
      "/etc/prometheus/scrape.d/orphera-ceph.yml",
      s"""scrape_configs:
         |  - job_name: 'ceph'
         |    static_configs:
         |      - targets: [$cephExporterTarget]
         |""".stripMargin,
      mode = "0644",
      owner = "root",
      group = "root"
    )
    val unit = service("prometheus")
    ApplyPlan.orFail(
      "prometheus-scrape-ceph",
      List(dir, cfg.after(dir).notifies(unit), unit)
    )

  // Only this playbook's own job counts: the ceph mgr is one target.
  private val cephHealthScript =
    """COUNTS=$(curl -s http://localhost:9090/api/v1/targets | python3 -c 'import json,sys; t=[x for x in json.load(sys.stdin)["data"]["activeTargets"] if x["labels"].get("job")=="ceph"]; print(sum(x["health"]=="up" for x in t), sum(x["health"]=="down" for x in t))' 2>/dev/null)
      |UP=${COUNTS% *}
      |DOWN=${COUNTS#* }
      |if [ "$DOWN" != "0" ] || [ "$UP" -lt "1" ]; then
      |  echo "the ceph scrape target is not healthy yet: up=$UP down=$DOWN (want 1 up, 0 down)" >&2
      |  exit 1
      |fi
      |exit 0""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-observability")(
      stage("enable-ceph-prometheus-module", mgrNode)
        .task(
          "enable ceph mgr's built-in prometheus module and confirm :9283/metrics"
        )(
          Task.RunCommand(
            List("sh", "-c", enablePrometheusModuleScript),
            timeoutSeconds = 90
          )
        )
        .build,

      stage("update-prometheus-scrape-config-for-ceph", monitoringNode)
        .task("write the ceph scrape job and restart prometheus")(
          Task.Apply(prometheusPlan)
        )
        .build,

      // Separate stage, not more tasks above — waitFor runs before a
      // stage's own tasks (ClusterPlaybookRunner.runStage), the same
      // ordering fact every earlier observability manifest in this
      // project already learned and applies correctly here from the
      // start.
      stage("confirm-ceph-observability-healthy", monitoringNode)
        .waitFor(
          HealthCheck.Command(
            onNode = monitoringNode,
            command = List("sh", "-c", cephHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("ceph observability confirmed")(
          Task.Debug(
            s"Prometheus at http://tst7.ljalbinson.com:9090 now also scraping ceph mgr's prometheus module " +
              s"(:$cephMetricsPort/metrics on $mgrNode), target up. " +
              "In Grafana (http://tst7.ljalbinson.com:3000), Dashboards -> New -> Import -> ID 2842 " +
              "(\"Ceph - Cluster\") against the existing Prometheus datasource."
          )
        )
        .build
    )
