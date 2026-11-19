// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The Ceilometer scrape job on the Prometheus of tst7, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It was a stage of telemetry_single_node.scala when that was a cluster playbook.
//
//   orphera apply manifests/telemetry_prometheus.scala --check
//   orphera apply manifests/telemetry_prometheus.scala
//
// The scrape job goes in its own file, /etc/prometheus/scrape.d/openstack-telemetry.yml, named by a
// `scrape_config_files` line in prometheus.yml (Prometheus 2.43 or later). observability_stack.scala and
// observability_extended.scala rewrite prometheus.yml whole and both carry that line, so a re-run of either
// keeps the job. This description does not touch the rest of prometheus.yml: it adds the line when it is
// missing (the old file is kept as prometheus.yml.orphera-bak and put back if promtool rejects the new one),
// writes the job file, has promtool check the configuration and restarts Prometheus only when one of them
// changed.
//
// Prerequisite, not checked here: Prometheus running on tst7 (observability_stack.scala).
object telemetry_prometheus extends OrpheraApply:
  import Resources.*

  private val node = "tst7"
  private val exporterPort = 9101
  // Keep in step with telemetry_single_node.scala.
  private val exporterHosts: List[String] =
    List("tst24", "tst17", "tst18").map(n => s"$n.ljalbinson.com")
  private val exporterTargets: String =
    exporterHosts.map(h => s"'$h:$exporterPort'").mkString(", ")

  override def nodes: List[String] = List(node)

  private val scrapeFile = "/etc/prometheus/scrape.d/openstack-telemetry.yml"
  private val scrapeContent =
    s"""scrape_configs:
       |  - job_name: 'ceilometer'
       |    static_configs:
       |      - targets: [$exporterTargets]
       |""".stripMargin

  private val hasLine =
    "grep -q '^scrape_config_files:' /etc/prometheus/prometheus.yml"
  private val addLineScript =
    s"""set -e
       |cp /etc/prometheus/prometheus.yml /etc/prometheus/prometheus.yml.orphera-bak
       |sed -i 's|^scrape_configs:|scrape_config_files:\\n  - /etc/prometheus/scrape.d/*.yml\\n\\nscrape_configs:|' /etc/prometheus/prometheus.yml
       |if ! $hasLine; then
       |  cp /etc/prometheus/prometheus.yml.orphera-bak /etc/prometheus/prometheus.yml
       |  echo "no scrape_configs: line in prometheus.yml to put scrape_config_files before" >&2
       |  exit 1
       |fi
       |if command -v promtool >/dev/null 2>&1 && ! promtool check config /etc/prometheus/prometheus.yml; then
       |  cp /etc/prometheus/prometheus.yml.orphera-bak /etc/prometheus/prometheus.yml
       |  echo "promtool rejected the configuration (Prometheus older than 2.43 has no scrape_config_files?); prometheus.yml put back" >&2
       |  exit 1
       |fi
       |echo "prometheus.yml names the scrape.d files"""".stripMargin

  // True when promtool is missing or accepts the configuration.
  private val configOk =
    "! command -v promtool >/dev/null 2>&1 || promtool check config /etc/prometheus/prometheus.yml >/dev/null 2>&1"
  private val configCheck = "promtool check config /etc/prometheus/prometheus.yml"

  private val healthy = "curl -sf http://localhost:9090/-/healthy >/dev/null"
  private val waitHealthy =
    s"""for i in $$(seq 1 12); do
       |  $healthy && exit 0
       |  sleep 5
       |done
       |echo "prometheus did not come back healthy - see 'journalctl -xeu prometheus'" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val prometheus = service("prometheus")
    val scrapeDir = directory("/etc/prometheus/scrape.d")
    val job = file(
      scrapeFile,
      scrapeContent,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(scrapeDir).notifies(prometheus)
    val line = command(
      "name the scrape.d files in prometheus.yml",
      addLineScript,
      unless = hasLine,
      timeout = 60
    ).notifies(prometheus)
    val accepted = command(
      "promtool accepts the configuration",
      configCheck,
      unless = configOk,
      timeout = 60
    ).after(job, line)
    val running = prometheus.after(accepted, job, line)
    val answering = command(
      "prometheus answers",
      waitHealthy,
      unless = healthy,
      timeout = 90
    ).after(running)
    List(scrapeDir, job, line, accepted, running, answering)
