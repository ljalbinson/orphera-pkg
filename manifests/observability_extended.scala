// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Eighth infrastructure exercise, and a direct follow-up to
// observability_stack.scala: the two scrape targets that file's own
// header comment deliberately scoped OUT of the first pass.
//   - haproxy's own native Prometheus-format stats endpoint (the
//     `prometheus-exporter` service, built into haproxy's core since
//     2.0 on the upstream build). Ubuntu 24.04 packages haproxy 2.8, so
//     no extra package install is needed, just a config change — ASSUMED
//     the Ubuntu/Debian build has this module compiled in, same as every
//     distro build since it moved out of being a separate contrib/USE_PROMEX
//     flag; not independently confirmed against this project's actual
//     haproxy binary. If wrong, the failure is loud and immediate:
//     `haproxy -c` (run before restarting, see haproxyMetricsScript)
//     rejects the config with "unknown service name 'prometheus-exporter'"
//     rather than silently serving nothing.
//   - prometheus-mysqld-exporter for Galera/wsrep metrics, also on
//     tst0/tst1/tst2 — one exporter per node, each reading its own local
//     mysqld over a unix socket, same "every node exports its own view"
//     shape as node_exporter.
// Then tst6's prometheus.yml is rewritten to scrape both, on top of the
// node_exporter + self jobs observability_stack.scala already wrote —
// this file assumes that one is already applied and healthy, and does
// NOT repeat its stages.
//
// haproxy's metrics endpoint is bound to {{cluster_ip}} specifically, on
// a dedicated port (8404), not the wildcard — the exact same lesson
// mariadb_haproxy_keepalived.scala's own header comment already paid for
// once on this project (a wildcard bind on a node that ALSO runs other
// services on the same host collides with whichever of them binds a
// specific address on the same port first). 8404 is unused by anything
// else already running on tst0-2 (haproxy's own :3306/:3307 listeners
// are on the VIP, not this address), so no conflict is expected here,
// but the bind is still scoped deliberately rather than left wildcard
// out of habit.
//
// The haproxy config block is appended idempotently: a `sed` delete of
// any previously-appended block (bounded by BEGIN/END marker comments)
// runs before every append, so re-running this manifest never duplicates
// the frontend stanza — appending blindly on every run, the way
// haproxy.cfg's own full-file rewrite in mariadb_haproxy_keepalived.scala
// gets to just overwrite wholesale, isn't available here since this file
// doesn't own that file's other content and can't risk clobbering it.
//
// mysqld_exporter credentials: a dedicated, minimally-privileged
// 'exporter'@'localhost' MariaDB user (PROCESS + REPLICATION CLIENT +
// SELECT — the exact grant set mysqld_exporter's own docs recommend,
// nothing wider), created once on tst0 and relying on Galera to
// replicate the CREATE USER/GRANT to tst1/tst2 automatically — identical
// reasoning to clustercheckUser in mariadb_haproxy_keepalived.scala.
// '@localhost' (not '@%') is deliberate and sufficient here: unlike
// clustercheck, which also needs a grant for haproxy's proxied TCP path,
// each node's mysqld_exporter only ever talks to that SAME node's own
// mysqld over a local unix socket, so there's no second connection path
// needing '@%'.
//
// Honest flag, same convention as this project's other not-yet-verified
// assumptions (Grafana's admin/admin, mariadb_galera_cluster.scala's
// unix-socket-root): the exact config filename/variable the Debian
// `prometheus-mysqld-exporter` package reads for its connection string
// (assumed here to be /etc/default/prometheus-mysqld-exporter,
// DATA_SOURCE_NAME, mirroring how every other Debian prometheus-*
// package in this project takes its own /etc/default/<pkg> file) is NOT
// confirmed against a real run yet — an easy, loud failure to spot
// (service won't start, or /metrics returns no mysql_* series) if wrong,
// not a silent wrong result.
object observability_extended extends OrpheraClusterPlaybook:

  private val haProxyNodes = List("tst0", "tst1", "tst2")
  private val monitoringNode = "tst6"
  private val allNodes = List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5")

  private val haproxyMetricsPort = 8404

  // Test-only credential, same disclaimer as every other hardcoded
  // password in this project.
  private val exporterUser = "exporter"
  private val exporterPassword = "orphera-test-mysqld-exporter-password"

  // tst0/tst1/tst2 only. Appends (idempotently) a small Prometheus-format
  // stats frontend to the haproxy.cfg that mariadb_haproxy_keepalived.scala
  // already wrote — assumes that file is already applied; this does not
  // write a full haproxy.cfg of its own.
  private val haproxyMetricsScript =
    s"""sed -i '/# BEGIN orphera-prometheus-metrics/,/# END orphera-prometheus-metrics/d' /etc/haproxy/haproxy.cfg
       |cat >> /etc/haproxy/haproxy.cfg <<'EOF'
       |# BEGIN orphera-prometheus-metrics
       |frontend prometheus_metrics
       |    bind {{cluster_ip}}:$haproxyMetricsPort
       |    mode http
       |    http-request use-service prometheus-exporter if { path /metrics }
       |    no log
       |# END orphera-prometheus-metrics
       |EOF
       |if ! haproxy -c -f /etc/haproxy/haproxy.cfg; then
       |  echo "haproxy.cfg failed its own -c config check after adding the metrics frontend — see the haproxy output above" >&2
       |  exit 1
       |fi
       |systemctl restart haproxy
       |if ! systemctl is-active --quiet haproxy; then
       |  echo "haproxy failed to restart after adding the metrics frontend — see 'journalctl -xeu haproxy' for the real reason" >&2
       |  exit 1
       |fi
       |if ! curl -sf http://localhost:$haproxyMetricsPort/metrics | grep -q '^haproxy_'; then
       |  echo "haproxy is running but :$haproxyMetricsPort/metrics didn't return haproxy_* series" >&2
       |  exit 1
       |fi
       |echo "haproxy prometheus-exporter frontend listening on :$haproxyMetricsPort"""".stripMargin

  // tst0 only — see header comment for why '@localhost' (not '@%') is
  // sufficient here, unlike clustercheckUser.
  private val createExporterUserScript =
    s"""mariadb -N -e "CREATE USER IF NOT EXISTS '$exporterUser'@'localhost' IDENTIFIED BY '$exporterPassword'; GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO '$exporterUser'@'localhost'; FLUSH PRIVILEGES;""""

  // tst0/tst1/tst2 only. See header comment's honest flag on the
  // /etc/default file path and DATA_SOURCE_NAME variable name — assumed,
  // not yet confirmed against a real run.
  private val configureMysqldExporterScript =
    s"""cat > /etc/default/prometheus-mysqld-exporter <<EOF
       |DATA_SOURCE_NAME="$exporterUser:$exporterPassword@unix(/run/mysqld/mysqld.sock)/"
       |ARGS=
       |EOF
       |systemctl enable --now prometheus-mysqld-exporter
       |systemctl restart prometheus-mysqld-exporter
       |if ! systemctl is-active --quiet prometheus-mysqld-exporter; then
       |  echo "prometheus-mysqld-exporter failed to start — see 'journalctl -xeu prometheus-mysqld-exporter' for the real reason" >&2
       |  exit 1
       |fi
       |if ! curl -sf http://localhost:9104/metrics | grep -q '^mysql_up'; then
       |  echo "prometheus-mysqld-exporter is running but :9104/metrics didn't return mysql_up — check DATA_SOURCE_NAME in /etc/default/prometheus-mysqld-exporter" >&2
       |  exit 1
       |fi
       |echo "mysqld_exporter running on :9104, reading this node's own mysqld over the local socket"""".stripMargin

  // tst6 only. Full rewrite of prometheus.yml, now four scrape jobs
  // instead of observability_stack.scala's original two — node_exporter
  // and the self-scrape job are carried over unchanged, haproxy and
  // mysqld are new. This file owns the full prometheus.yml from this
  // point on; re-running observability_stack.scala after this would
  // overwrite these two new jobs back out, which is expected (that file
  // doesn't know about this one) rather than a bug in either.
  private val nodeExporterTargets =
    allNodes.map(n => s"'{{nodes.$n.cluster_ip}}:9100'").mkString(", ")
  private val haproxyExporterTargets =
    haProxyNodes.map(n => s"'{{nodes.$n.cluster_ip}}:$haproxyMetricsPort'").mkString(", ")
  private val mysqldExporterTargets =
    haProxyNodes.map(n => s"'{{nodes.$n.cluster_ip}}:9104'").mkString(", ")

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
       |echo "prometheus reconfigured: now also scraping ${haProxyNodes.length} haproxy + ${haProxyNodes.length} mysqld targets"""".stripMargin

  // Same "prove the real thing" shape as observability_stack.scala's own
  // health check: total expected targets is node_exporter(6) +
  // haproxy(3) + mysqld(3) + prometheus-self(1) = 13.
  private val expectedTargets = allNodes.length + haProxyNodes.length + haProxyNodes.length + 1

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
    clusterPlaybook("observability-extended")(

      stage("add-haproxy-metrics-endpoint", haProxyNodes*)
        .task("append prometheus-exporter frontend to haproxy.cfg and restart")(
          Task.RunCommand(List("sh", "-c", haproxyMetricsScript), timeoutSeconds = 60)
        )
        .build,

      // tst0 only — replicates to tst1/tst2 via Galera, same as
      // clustercheckUser in mariadb_haproxy_keepalived.scala.
      stage("create-mysqld-exporter-user", "tst0")
        .task("create the exporter MariaDB user (replicates cluster-wide)")(
          Task.RunCommand(List("sh", "-c", createExporterUserScript))
        )
        .build,

      stage("install-mysqld-exporter", haProxyNodes*)
        .task("install prometheus-mysqld-exporter")(
          Task.Install(packages = List("prometheus-mysqld-exporter"), updateCache = true)
        )
        .task("configure and (re)start mysqld_exporter")(
          Task.RunCommand(List("sh", "-c", configureMysqldExporterScript), timeoutSeconds = 60)
        )
        .build,

      stage("update-prometheus-scrape-config", monitoringNode)
        .task("rewrite prometheus.yml with haproxy + mysqld jobs and restart")(
          Task.RunCommand(List("sh", "-c", prometheusConfigScript), timeoutSeconds = 60)
        )
        .build,

      // Separate stage, not more tasks above — waitFor runs before a
      // stage's own tasks (ClusterPlaybookRunner.runStage), the same
      // ordering fact wordpress_site.scala and observability_stack.scala
      // both already learned and applied correctly here from the start.
      stage("confirm-extended-observability-healthy", monitoringNode)
        .waitFor(
          HealthCheck.Command(
            onNode = monitoringNode,
            command = List("sh", "-c", extendedHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("extended observability confirmed")(
          Task.Debug(
            s"Prometheus at http://tst6.ljalbinson.com:9090 now also scraping ${haProxyNodes.length} haproxy " +
              s"(:$haproxyMetricsPort/metrics) and ${haProxyNodes.length} mysqld_exporter (:9104/metrics) targets, " +
              s"$expectedTargets targets total, all up."
          )
        )
        .build
    )
