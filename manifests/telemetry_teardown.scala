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


  // ---- tst7 ----
  private val scrapeFile = "/etc/prometheus/scrape.d/openstack-telemetry.yml"
  private val prometheusScript =
    s"""set -e
       |rm -f $scrapeFile
       |if systemctl is-active --quiet prometheus; then
       |  systemctl restart prometheus
       |fi
       |echo "ceilometer scrape job removed from prometheus"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        absentSqlDatabase(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          List(dbName),
          dbUser
        )
      )
    else if target.name == rabbitNode then
      List("aodh", "ceilometer").map(name =>
        absentRabbitAccount(
          s"remove the $name vhost and user from RabbitMQ",
          name,
          name
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
      val stopped =
        absentContainerUnits(computeUnits.map(u => s"orphera-$u" -> u))
      // Typed removal, no script.
      val cleaned =
        dirs(computeUnits).map(
          absentDirectory(_, recursive = true).after(stopped*)
        )
      stopped ++ cleaned
    else
      val unregisteredAodh = absentKeystoneEntries("remove Aodh and the Prometheus entry from Keystone", "aodh")
      val unregisteredCeilometer = absentKeystoneEntries("remove Ceilometer from Keystone", "ceilometer")
      val unregistered = List(unregisteredAodh, unregisteredCeilometer)
      val stopped =
        absentContainerUnits(nodeUnits.map(u => s"orphera-$u" -> u))
          .map(_.after(unregistered*))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned =
        (dirs(nodeUnits).map(absentDirectory(_, recursive = true)) :+
          absentFile(helper) :+ absentFile(keystoneHelperPath("aodh")) :+
          absentFile(keystoneHelperPath("ceilometer"))).map(_.after(stopped*))
      unregistered ++ stopped ++ cleaned
