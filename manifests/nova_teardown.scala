// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes nova_database.scala, nova_rabbitmq.scala and nova_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst14
// removes Nova from Keystone (service `compute`, its endpoints, user `nova`)
// through the helper if it is still there, then the four containers, units,
// config, state, helper and Keystone CA copy; on tst10 removes the `nova` vhost
// and user from RabbitMQ (the broker itself and Cinder's use of it are
// untouched); on tst3 drops nova_api, nova, nova_cell0 and the nova user.
// podman and the images stay, and project `service` stays. Keep `node` in step
// with nova_single_node.
//
//   orphera apply manifests/nova_teardown.scala --check
//   orphera apply manifests/nova_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object nova_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst14"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbUser = "nova"
  private val helper = "/usr/local/sbin/orphera-nova-api"
  private val units =
    List("nova-api", "nova-scheduler", "nova-conductor", "nova-novncproxy")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst14 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val dirs = List(
    "/etc/kolla/nova",
    "/etc/kolla/nova-api",
    "/etc/kolla/nova-scheduler",
    "/etc/kolla/nova-conductor",
    "/etc/kolla/nova-novncproxy",
    "/etc/kolla/nova-ca",
    "/var/lib/orphera/nova",
    "/var/log/kolla/nova"
  )

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running
  // broker there is nothing to remove.
  private val rabbitHasNova =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx nova || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx nova; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost nova || true
      |podman exec rabbitmq rabbitmqctl delete_user nova || true
      |echo "rabbitmq vhost nova and user nova removed"""".stripMargin

  // ---- tst3 ----
  private val dropDatabasesScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS nova_api; DROP DATABASE IF EXISTS nova; DROP DATABASE IF EXISTS nova_cell0; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when any of the databases or the user exists. When MariaDB cannot be
  // queried this is also true, so that the drop runs and fails with MariaDB's own
  // message: a database that is down is not "nothing to remove".
  private val databasesExist =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('nova_api','nova','nova_cell0') UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        command(
          "drop nova_api, nova, nova_cell0 and the nova user on the Galera cluster",
          dropDatabasesScript,
          onlyIf = databasesExist
        )
      )
    else if target.name == rabbitNode then
      List(
        command(
          "remove the nova vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasNova,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Nova from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
