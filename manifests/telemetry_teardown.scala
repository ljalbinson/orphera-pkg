// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes telemetry_database.scala, telemetry_rabbitmq.scala, telemetry_prometheus.scala, telemetry_compute.scala
// and telemetry_single_node.scala, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst24
// removes Aodh, Ceilometer and the Prometheus entry from Keystone through the helper if it is still there, then
// the containers, units, config, state and helper, and the compute agent on tst17 and tst18; on tst7 removes
// the Ceilometer scrape job (the scrape_config_files line in prometheus.yml stays; with no file in scrape.d it
// does nothing); on tst3 drops the aodh database and user; on tst10 removes the aodh and ceilometer vhosts and
// users from RabbitMQ (the broker itself and the other services' use of it are untouched). Series already in
// Prometheus stay until its retention removes them. podman, the images and the secrets stay. Keep the names in
// step with telemetry_single_node.
//
//   orphera apply manifests/telemetry_teardown.scala --check
//   orphera apply manifests/telemetry_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object telemetry_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst24"
  private val computeNodes = List("tst17", "tst18")
  private val promNode = "tst7"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "aodh"
  private val dbUser = "aodh"
  private val helper = "/usr/local/sbin/orphera-telemetry-api"

  override def nodes: List[String] =
    List(dbNode, rabbitNode, promNode) ++ computeNodes :+ node

  // ---- the containers (tst24, and the compute nodes) ----
  private def anyUnit(units: List[String]): String =
    s"""for u in ${units.mkString(
        " "
      )}; do if [ -f /etc/systemd/system/orphera-$$u.service ] || systemctl is-active --quiet orphera-$$u || systemctl is-enabled --quiet orphera-$$u; then exit 0; fi; done; exit 1"""
  private def stopScript(units: List[String]): String =
    s"""for u in ${units.mkString(" ")}; do
       |  systemctl disable --now orphera-$$u 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$u.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$u 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |echo "telemetry containers and units removed"""".stripMargin

  // /etc/kolla/telemetry is where the cluster playbook kept its staging copies; it is listed so that a node set
  // up by the playbook is cleaned too.
  private def dirs(units: List[String]): List[String] =
    units.map(u => s"/etc/kolla/$u") ++
      List(
        "/etc/kolla/telemetry",
        "/etc/kolla/telemetry-ca",
        "/var/lib/orphera/telemetry",
        "/var/log/kolla/aodh",
        "/var/log/kolla/ceilometer"
      )

  private val nodeUnits =
    List("aodh-api", "aodh-evaluator", "aodh-notifier", "ceilometer-central")
  private val computeUnits = List("ceilometer-compute")

  // The helper removes the Keystone entries; it is there until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  // ---- tst7 ----
  private val scrapeFile = "/etc/prometheus/scrape.d/openstack-telemetry.yml"
  private val prometheusScript =
    s"""set -e
       |rm -f $scrapeFile
       |if systemctl is-active --quiet prometheus; then
       |  systemctl restart prometheus
       |fi
       |echo "ceilometer scrape job removed from prometheus"""".stripMargin

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or the user exists. When MariaDB cannot be queried this is also true, so that the
  // drop runs and fails with MariaDB's own message: a database that is down is not "nothing to remove".
  private val databaseExists =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when a vhost or user exists in a running broker; with no running broker there is nothing to remove.
  private val rabbitHasTelemetry =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qxE 'aodh|ceilometer' || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qxE 'aodh|ceilometer'; }"""
  private val rabbitScript =
    """for n in aodh ceilometer; do
      |  podman exec rabbitmq rabbitmqctl delete_vhost $n || true
      |  podman exec rabbitmq rabbitmqctl delete_user $n || true
      |done
      |echo "rabbitmq vhosts and users aodh and ceilometer removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        command(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          dropDatabaseScript,
          onlyIf = databaseExists
        )
      )
    else if target.name == rabbitNode then
      List(
        command(
          "remove the aodh and ceilometer vhosts and users from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasTelemetry,
          timeout = 60
        )
      )
    else if target.name == promNode then
      List(
        command(
          "remove the Ceilometer scrape job and restart Prometheus",
          prometheusScript,
          onlyIf = s"[ -e $scrapeFile ]",
          timeout = 120
        )
      )
    else if computeNodes.contains(target.name) then
      val stopped = command(
        "stop and remove the ceilometer-compute container and unit",
        stopScript(computeUnits),
        onlyIf = anyUnit(computeUnits),
        timeout = 240
      )
      // Typed removal, no script.
      val cleaned =
        dirs(computeUnits).map(
          absentDirectory(_, recursive = true).after(stopped)
        )
      List(stopped) ++ cleaned
    else
      val unregistered = command(
        "remove Aodh, Ceilometer and the Prometheus entry from Keystone",
        unregisterScript,
        onlyIf = hasHelper,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the telemetry containers and units",
        stopScript(nodeUnits),
        onlyIf = anyUnit(nodeUnits),
        timeout = 240
      ).after(unregistered)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned =
        (dirs(nodeUnits).map(absentDirectory(_, recursive = true)) :+
          absentFile(helper)).map(_.after(stopped))
      List(unregistered, stopped) ++ cleaned
