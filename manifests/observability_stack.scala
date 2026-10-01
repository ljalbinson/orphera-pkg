// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Seventh infrastructure exercise, and a genuinely different category from
// everything before it: not another clustered service (etcd, Ceph,
// Galera) and not an app on top of one (wordpress_site.scala) — a
// cross-cutting exercise that reaches across the ENTIRE fleet at once
// (tst0-tst5, six nodes spanning three unrelated earlier exercises) rather
// than building one more self-contained thing.
//
// Two groups of nodes, two different jobs:
//   - node_exporter on EVERY existing node (tst0-tst5): a single metrics
//     endpoint (:9100) per node, scraped by Prometheus. No config beyond
//     installing the package — it exports whatever it finds on that host
//     with zero per-node customization needed, unlike every other
//     exercise in this project so far (which all needed per-node IPs,
//     priorities, or peer lists templated in).
//   - Prometheus + Grafana on a NEW node (tst6, 10.10.5.18, added to
//     inventory.yaml) — deliberately not reused from tst5, so the
//     monitoring stack doesn't share fate with the one workload node it
//     would otherwise live on: losing tst5 would mean losing WordPress
//     AND the ability to see anything was wrong, at the same time.
//
// A genuinely new technique for this project, not seen in any earlier
// manifest: Grafana has no official Ubuntu/Debian package at all (it
// never has, for licensing/release-cadence reasons unrelated to anything
// here) — getting it installed means adding Grafana's OWN apt repository
// (GPG key + a `signed-by` sources.list.d entry) before `apt-get install`
// will find it, not a plain `Task.Install` against Ubuntu's own repos the
// way every other package in this project has been. Written as a
// RunCommand script for exactly that reason — `Task.Install` has no
// concept of "add this repo first".
//
// Scope, deliberately: node-level fleet metrics only for this first pass
// (CPU/memory/disk/network via node_exporter) — NOT haproxy's own
// Prometheus-format stats endpoint (haproxy 2.x supports one natively)
// or a mysqld_exporter for Galera-specific wsrep metrics. Both are
// natural follow-ups once this base layer is confirmed working, same
// incremental approach every earlier exercise in this project took.
object observability_stack extends OrpheraClusterPlaybook:

  private val monitoringNode = "tst6"
  private val allNodes = List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5")

  // Grafana's default admin/admin credentials on a truly fresh install —
  // same "test-only, fine for disposable infrastructure" convention as
  // every other hardcoded credential in this project (sstPassword,
  // clustercheckPassword, wordpress's dbPassword). Grafana only forces a
  // password change through its own UI login flow, not the API used
  // below to confirm the datasource — unconfirmed against a real run,
  // flagged the same way mariadb_galera_cluster.scala flags its own
  // unix-socket-root assumption: an easy, loud failure to spot (401) if
  // it's wrong, not a silent wrong result.
  private val grafanaAdminUser = "admin"
  private val grafanaAdminPassword = "admin"

  // Builds the full `scrape_configs` target list from inventory data —
  // same {{nodes.<name>.cluster_ip}} templating every other manifest in
  // this project uses for cross-node addresses, just looped across six
  // nodes instead of three.
  private val nodeExporterTargets =
    allNodes.map(n => s"'{{nodes.$n.cluster_ip}}:9100'").mkString(", ")

  // Debian/Ubuntu's `prometheus-node-exporter` package auto-starts as a
  // side effect of its own postinst (the same class of gotcha
  // mariadb_galera_cluster.scala's and wordpress_site.scala's header
  // comments both already document for their own packages) — applying
  // that lesson proactively here rather than waiting to hit it again:
  // explicit `restart` + an active-state check, not just `enable --now`.
  private val startNodeExporterScript =
    """systemctl enable --now prometheus-node-exporter
      |systemctl restart prometheus-node-exporter
      |if ! systemctl is-active --quiet prometheus-node-exporter; then
      |  echo "prometheus-node-exporter failed to start — see 'journalctl -xeu prometheus-node-exporter' for the real reason" >&2
      |  exit 1
      |fi
      |echo "node_exporter running on :9100"""".stripMargin

  // tst6 only. Ubuntu's `prometheus` package ships a working default
  // config and systemd unit; this overwrites the scrape config with the
  // real fleet target list and nothing else (alerting/rules are left at
  // package defaults — out of scope for this first pass).
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
       |  - job_name: 'prometheus'
       |    static_configs:
       |      - targets: ['localhost:9090']
       |EOF
       |systemctl enable --now prometheus
       |systemctl restart prometheus
       |if ! systemctl is-active --quiet prometheus; then
       |  echo "prometheus failed to start — see 'journalctl -xeu prometheus' for the real reason" >&2
       |  exit 1
       |fi
       |sleep 2
       |if ! curl -sf http://localhost:9090/-/healthy > /dev/null; then
       |  echo "prometheus is running but /-/healthy didn't return success" >&2
       |  exit 1
       |fi
       |echo "prometheus running on :9090, configured to scrape ${allNodes.length} node_exporter targets"""".stripMargin

  // tst6 only. The one genuinely new technique in this file — see the
  // header comment. `signed-by` (not the deprecated `apt-key add`) is
  // Debian/Ubuntu's current recommended way to scope a keyring to one
  // repo rather than trusting it for every repo on the system.
  private val installGrafanaScript =
    """apt-get install -y -qq apt-transport-https software-properties-common wget gpg
      |mkdir -p /etc/apt/keyrings
      |wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor > /etc/apt/keyrings/grafana.gpg
      |echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" > /etc/apt/sources.list.d/grafana.list
      |apt-get update -qq
      |apt-get install -y -qq grafana
      |echo "grafana package installed from apt.grafana.com"""".stripMargin

  // tst6 only. Grafana only reads its provisioning directory at startup,
  // so writing this file alone does nothing until the next restart —
  // deliberately a separate task/script from installGrafanaScript so the
  // restart-and-check pattern below applies to both the initial install
  // AND this config change, not just the first one.
  //
  // Real run finding: a fresh `grafana-server` start kicks off a
  // background download-and-install of ~13 bundled datasource plugins
  // (elasticsearch, prometheus, mysql, postgres, zipkin, and more — all
  // visible in `journalctl`) that took upward of 50 seconds, while
  // `systemctl is-active` reports the service active almost immediately
  // (it's listening, just not finished with its own startup work yet). A
  // fixed `sleep 3` before the one-shot `/api/health` check was nowhere
  // near enough and failed every time on a fresh install, even though
  // Grafana was perfectly healthy barely a minute later. Fixed by polling
  // instead of guessing a sleep duration — same principle
  // etcd_grow_cluster.scala's registerScript and
  // test_mariadb_galera.sh's poll_for_marker already use.
  private val provisionDatasourceScript =
    """mkdir -p /etc/grafana/provisioning/datasources
      |cat > /etc/grafana/provisioning/datasources/prometheus.yaml <<EOF
      |apiVersion: 1
      |datasources:
      |  - name: Prometheus
      |    type: prometheus
      |    access: proxy
      |    url: http://localhost:9090
      |    isDefault: true
      |EOF
      |systemctl enable --now grafana-server
      |systemctl restart grafana-server
      |if ! systemctl is-active --quiet grafana-server; then
      |  echo "grafana-server failed to start — see 'journalctl -xeu grafana-server' for the real reason" >&2
      |  exit 1
      |fi
      |READY=false
      |for i in $(seq 1 30); do
      |  if curl -sf http://localhost:3000/api/health > /dev/null 2>&1; then
      |    READY=true
      |    break
      |  fi
      |  sleep 3
      |done
      |if [ "$READY" != "true" ]; then
      |  echo "grafana-server is running but /api/health never returned success after 90s (it may still be installing bundled plugins — see 'journalctl -xeu grafana-server')" >&2
      |  exit 1
      |fi
      |echo "grafana running on :3000, provisioned with a Prometheus datasource"""".stripMargin

  // Not just "is Prometheus up": confirms it has actually scraped every
  // node_exporter target successfully at least once (health=up for all
  // six, health=down for none) — same "prove the real thing, don't trust
  // a status flag alone" reasoning as test_mariadb_galera.sh's
  // replicated-row check and wordpress_site.scala's DB-error body check.
  // Also confirms Grafana's own API reports the Prometheus datasource was
  // actually provisioned, not just that the file was written.
  private val observabilityHealthScript =
    s"""TARGETS=$$(curl -s http://localhost:9090/api/v1/targets)
       |UP=$$(echo "$$TARGETS" | grep -o '"health":"up"' | wc -l)
       |DOWN=$$(echo "$$TARGETS" | grep -o '"health":"down"' | wc -l)
       |if [ "$$DOWN" != "0" ] || [ "$$UP" -lt "${allNodes.length + 1}" ]; then
       |  echo "prometheus targets not all healthy yet: up=$$UP down=$$DOWN (want ${allNodes.length + 1} up, 0 down)" >&2
       |  exit 1
       |fi
       |DATASOURCES=$$(curl -s -u $grafanaAdminUser:$grafanaAdminPassword http://localhost:3000/api/datasources)
       |if ! echo "$$DATASOURCES" | grep -q '"name":"Prometheus"'; then
       |  echo "grafana's Prometheus datasource doesn't show up in /api/datasources yet" >&2
       |  exit 1
       |fi
       |exit 0""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("observability-stack")(

      stage("install-node-exporter", allNodes*)
        .task("install prometheus-node-exporter")(
          Task.Install(packages = List("prometheus-node-exporter"), updateCache = true)
        )
        .task("start node_exporter")(
          Task.RunCommand(List("sh", "-c", startNodeExporterScript))
        )
        .build,

      stage("install-prometheus", monitoringNode)
        .task("install prometheus")(
          Task.Install(packages = List("prometheus"), updateCache = true)
        )
        .task("write prometheus.yml and (re)start prometheus")(
          Task.RunCommand(List("sh", "-c", prometheusConfigScript), timeoutSeconds = 60)
        )
        .build,

      stage("install-grafana", monitoringNode)
        .task("add Grafana's apt repo and install grafana")(
          Task.RunCommand(List("sh", "-c", installGrafanaScript), timeoutSeconds = 120)
        )
        .task("provision Prometheus datasource and (re)start grafana-server")(
          Task.RunCommand(List("sh", "-c", provisionDatasourceScript), timeoutSeconds = 60)
        )
        .build,

      // A separate stage, not more tasks on install-grafana above — see
      // wordpress_site.scala's own header comment for exactly why:
      // ClusterPlaybookRunner.runStage checks a stage's waitFor BEFORE
      // that stage's own tasks run, not after, so a health check sharing
      // a stage with the tasks that bring the thing up would be evaluated
      // too early. Learned once already this session; applied correctly
      // here from the start.
      stage("confirm-observability-healthy", monitoringNode)
        .waitFor(
          HealthCheck.Command(
            onNode = monitoringNode,
            command = List("sh", "-c", observabilityHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("observability stack confirmed")(
          Task.Debug(
            s"Prometheus at http://tst6.ljalbinson.com:9090 (scraping ${allNodes.length} node_exporter targets + itself), " +
              s"Grafana at http://tst6.ljalbinson.com:3000 (login $grafanaAdminUser/$grafanaAdminPassword, Prometheus datasource pre-wired)."
          )
        )
        .build
    )
