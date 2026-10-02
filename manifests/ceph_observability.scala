// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Ninth infrastructure exercise, and a follow-up to both
// observability_extended.scala AND the cephadm lifecycle
// (cephadm_install/cephadm_add_mons/cephadm_add_osds.scala): wires Ceph's
// own metrics into the SAME tst6 Prometheus/Grafana stack those two
// manifests already stood up, rather than standing up a second
// monitoring stack. This assumes BOTH chains are already applied:
// cephadm_install -> cephadm_add_mons -> cephadm_add_osds (so there's a
// live 3-mon/3-osd cluster on tst0/tst1/tst2 to scrape), and
// observability_stack -> observability_extended (so tst6's
// prometheus.yml already has the node_exporter/haproxy/mysqld/self
// jobs this file extends rather than replaces).
//
// Note the node reuse: tst0/tst1/tst2 already run Galera + haproxy
// (mariadb_galera_cluster.scala / mariadb_haproxy_keepalived.scala) AND
// now also host the Ceph mon/osd daemons via cephadm — same three nodes,
// two unrelated clustered services, consistent with every other node in
// this lab being reused across exercises rather than kept single-purpose
// (same reasoning observability_stack.scala's header comment gives for
// why tst6 is a fresh node rather than reusing tst5).
//
// Deliberately does NOT use cephadm's own built-in monitoring stack
// (Prometheus/Grafana/node-exporter/alertmanager, auto-deployed unless
// skipped) — cephadm_install.scala already passes
// `--skip-dashboard --skip-monitoring-stack` at bootstrap specifically
// so Ceph's metrics land in THIS project's existing tst6 stack instead
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
  private val monitoringNode = "tst6"
  private val cephMetricsPort = 9283

  // Carried forward unchanged from observability_extended.scala — this
  // file's prometheus.yml rewrite has to restate every existing job,
  // the same way that file restated observability_stack.scala's
  // node_exporter + self jobs when it added haproxy + mysqld.
  private val haProxyNodes = List("tst0", "tst1", "tst2")
  private val allNodes = List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5")

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

  // tst6 only. Fifth scrape job added on top of observability_extended.scala's
  // four (node_exporter, haproxy, mysqld, prometheus-self) — this file
  // owns the full prometheus.yml from this point on, same "last writer
  // wins, re-running an earlier manifest after this one overwrites these
  // new jobs back out, and that's expected" relationship
  // observability_extended.scala already has with observability_stack.scala.
  private val nodeExporterTargets =
    allNodes.map(n => s"'{{nodes.$n.cluster_ip}}:9100'").mkString(", ")
  private val haproxyExporterTargets =
    haProxyNodes.map(n => s"'{{nodes.$n.cluster_ip}}:8404'").mkString(", ")
  private val mysqldExporterTargets =
    haProxyNodes.map(n => s"'{{nodes.$n.cluster_ip}}:9104'").mkString(", ")
  private val cephExporterTarget =
    s"'{{nodes.$mgrNode.cluster_ip}}:$cephMetricsPort'"

  private val prometheusConfigScript =
    s"""cat > /etc/prometheus/prometheus.yml <<EOF
       |global:
       |  scrape_interval: 15s
       |
       |scrape_configs:
       |  - job_name: 'node_exporter'
       |    static_configs:
       |      - targets: [$nodeExporterTargets]
       |
       |  - job_name: 'haproxy'
       |    static_configs:
       |      - targets: [$haproxyExporterTargets]
       |
       |  - job_name: 'mysqld'
       |    static_configs:
       |      - targets: [$mysqldExporterTargets]
       |
       |  - job_name: 'ceph'
       |    static_configs:
       |      - targets: [$cephExporterTarget]
       |
       |  - job_name: 'prometheus'
       |    static_configs:
       |      - targets: ['localhost:9090']
       |EOF
       |systemctl restart prometheus
       |if ! systemctl is-active --quiet prometheus; then
       |  echo "prometheus failed to restart after the scrape-config update — see 'journalctl -xeu prometheus' for the real reason" >&2
       |  exit 1
       |fi
       |sleep 2
       |if ! curl -sf http://localhost:9090/-/healthy > /dev/null; then
       |  echo "prometheus is running but /-/healthy didn't return success" >&2
       |  exit 1
       |fi
       |echo "prometheus reconfigured: now also scraping the ceph mgr target"""".stripMargin

  // node_exporter(6) + haproxy(3) + mysqld(3) + ceph(1) + prometheus-self(1) = 14.
  private val expectedTargets = allNodes.length + haProxyNodes.length + haProxyNodes.length + 1 + 1

  private val extendedHealthScript =
    s"""TARGETS=$$(curl -s http://localhost:9090/api/v1/targets)
       |UP=$$(echo "$$TARGETS" | grep -o '"health":"up"' | wc -l)
       |DOWN=$$(echo "$$TARGETS" | grep -o '"health":"down"' | wc -l)
       |if [ "$$DOWN" != "0" ] || [ "$$UP" -lt "$expectedTargets" ]; then
       |  echo "prometheus targets not all healthy yet: up=$$UP down=$$DOWN (want $expectedTargets up, 0 down)" >&2
       |  exit 1
       |fi
       |exit 0""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-observability")(

      stage("enable-ceph-prometheus-module", mgrNode)
        .task("enable ceph mgr's built-in prometheus module and confirm :9283/metrics")(
          Task.RunCommand(List("sh", "-c", enablePrometheusModuleScript), timeoutSeconds = 90)
        )
        .build,

      stage("update-prometheus-scrape-config-for-ceph", monitoringNode)
        .task("rewrite prometheus.yml with the ceph job and restart")(
          Task.RunCommand(List("sh", "-c", prometheusConfigScript), timeoutSeconds = 60)
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
            command = List("sh", "-c", extendedHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("ceph observability confirmed")(
          Task.Debug(
            s"Prometheus at http://tst6.ljalbinson.com:9090 now also scraping ceph mgr's prometheus module " +
              s"(:$cephMetricsPort/metrics on $mgrNode), $expectedTargets targets total, all up. " +
              "In Grafana (http://tst6.ljalbinson.com:3000), Dashboards -> New -> Import -> ID 2842 " +
              "(\"Ceph - Cluster\") against the existing Prometheus datasource."
          )
        )
        .build
    )
