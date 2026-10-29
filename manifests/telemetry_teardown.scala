// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes telemetry_single_node.scala: removes Aodh, Ceilometer and the Prometheus entry from Keystone
// through the helper installed by the main playbook if it is still there, stops and removes the containers,
// units, config, state and helper on tst24 and the compute agent on tst17 and tst18, removes the Ceilometer
// scrape job from Prometheus on tst7 (the scrape_config_files line in prometheus.yml stays; with no file in
// scrape.d it does nothing), drops the aodh database and user, and removes the aodh and ceilometer vhosts and
// users from RabbitMQ. Series already in Prometheus stay until its retention removes them. podman and the
// secrets (aodh/db, aodh/service, aodh/rabbit, ceilometer/service, ceilometer/rabbit) stay.
// Keep the names in step with telemetry_single_node. Safe to re-run when nothing is installed.
object telemetry_teardown extends OrpheraClusterPlaybook:

  private val node = "tst24"
  private val computeNodes = List("tst17", "tst18")
  private val promNode = "tst7"
  private val rabbitNode = "tst10"
  private val dbName = "aodh"
  private val dbUser = "aodh"

  private def removeScript(containers: List[String], extra: String): String =
    s"""$extra
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |  rm -rf /etc/kolla/$$c
       |done
       |systemctl daemon-reload
       |rm -rf /etc/kolla/telemetry /etc/kolla/telemetry-ca
       |rm -rf /var/lib/orphera/telemetry /var/log/kolla/aodh /var/log/kolla/ceilometer
       |echo "telemetry containers, units, config and state removed"""".stripMargin

  private val nodeScript = removeScript(
    List(
      "aodh-api",
      "aodh-evaluator",
      "aodh-notifier",
      "ceilometer-central"
    ),
    """if [ -x /usr/local/sbin/orphera-telemetry-api ]; then
      |  /usr/local/sbin/orphera-telemetry-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |rm -f /usr/local/sbin/orphera-telemetry-api""".stripMargin
  )

  private val computeScript = removeScript(List("ceilometer-compute"), "true")

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val rabbitScript =
    s"""if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node; nothing to remove"
       |  exit 0
       |fi
       |for n in aodh ceilometer; do
       |  if podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx "$$n"; then
       |    podman exec rabbitmq rabbitmqctl delete_vhost "$$n"
       |  fi
       |  if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx "$$n"; then
       |    podman exec rabbitmq rabbitmqctl delete_user "$$n"
       |  fi
       |done
       |echo "rabbitmq vhosts and users aodh and ceilometer removed"""".stripMargin

  private val prometheusScript =
    """set -e
      |rm -f /etc/prometheus/scrape.d/openstack-telemetry.yml
      |if systemctl is-active --quiet prometheus; then
      |  systemctl restart prometheus
      |fi
      |echo "ceilometer scrape job removed from prometheus"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("telemetry-teardown")(
      stage("teardown-ceilometer-compute", computeNodes*)
        .task("remove the Ceilometer compute agent, its unit and state")(
          Task.RunCommand(List("sh", "-c", computeScript), timeoutSeconds = 240)
        )
        .build,

      stage("teardown-telemetry-node", node)
        .task(
          "unregister from Keystone, remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("remove-ceilometer-scrape-job", promNode)
        .task("remove the scrape job file and restart Prometheus")(
          Task.RunCommand(
            List("sh", "-c", prometheusScript),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("drop-aodh-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-telemetry-rabbitmq", rabbitNode)
        .task("remove the aodh and ceilometer vhosts and users")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
