// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes heat_database.scala, heat_rabbitmq.scala and heat_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst20
// removes Heat from Keystone (the services `orchestration` and `cloudformation`,
// their endpoints, user `heat`, and the stack user domain `heat` with every user
// and project Heat made in it) through the helper if it is still there, then the
// three containers, units, config, state, helper and Keystone CA copy; on tst10
// removes the `heat` vhost and user from RabbitMQ (the broker itself and the
// other services' use of it are untouched); on tst3 drops the heat database and
// user. The roles `heat_stack_owner` and `heat_stack_user` stay, as do podman,
// the images and project `service`. Stacks that still exist are not deleted
// first: delete them with `openstack stack delete` before this, or the servers
// and networks they made stay behind. Keep `node` in step with heat_single_node.
//
//   orphera apply manifests/heat_teardown.scala --check
//   orphera apply manifests/heat_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object heat_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "heat"
  private val dbUser = "heat"
  private val helper = "/usr/local/sbin/orphera-heat-api"
  private val units =
    List("heat-engine", "heat-api", "heat-api-cfn")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst20 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val unitList = units.mkString(" ")
  // True when ANY of the units has a unit file, or is active or enabled.
  private val anyUnit =
    s"""for u in $unitList; do if [ -f /etc/systemd/system/orphera-$$u.service ] || systemctl is-active --quiet orphera-$$u || systemctl is-enabled --quiet orphera-$$u; then exit 0; fi; done; exit 1"""
  private val stopScript =
    s"""for u in $unitList; do
       |  systemctl disable --now orphera-$$u 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$u.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$u 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |echo "heat containers and units removed"""".stripMargin

  private val paths =
    "/etc/kolla/heat /etc/kolla/heat-engine /etc/kolla/heat-api /etc/kolla/heat-api-cfn /etc/kolla/heat-ca /var/lib/orphera/heat /var/log/kolla/heat " + helper
  // True when ANY of them exists.
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  private val cleanupScript =
    s"""rm -rf $paths
       |echo "heat config, state and helper removed"""".stripMargin

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running
  // broker there is nothing to remove.
  private val rabbitHasHeat =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx heat || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx heat; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost heat || true
      |podman exec rabbitmq rabbitmqctl delete_user heat || true
      |echo "rabbitmq vhost heat and user heat removed"""".stripMargin

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or the user exists. When MariaDB cannot be
  // queried this is also true, so that the drop runs and fails with MariaDB's own
  // message: a database that is down is not "nothing to remove".
  private val databaseExists =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

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
          "remove the heat vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasHeat,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Heat from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the heat containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      val cleaned = command(
        "remove heat files, state and the helper",
        cleanupScript,
        onlyIf = filesExist,
        timeout = 120
      ).after(stopped)
      List(unregistered, stopped, cleaned)
