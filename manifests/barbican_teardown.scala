// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes barbican_database.scala, barbican_rabbitmq.scala and barbican_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst20
// removes Barbican from Keystone (the service of type key-manager, its endpoints
// and user barbican) through the helper if it is still there, then the two
// containers, units, config, state, helper and Keystone CA copy; on tst10
// removes the `barbican` vhost and user from RabbitMQ (the broker itself and the
// other services' use of it are untouched); on tst3 drops the barbican database
// and user. The roles creator, observer and audit stay, as do podman, the images,
// project `service` and Heat on the same node. EVERY SECRET STORED IN BARBICAN IS
// DESTROYED with the database; the key-encrypting key (secret barbican/kek) stays
// in the secret store. Keep `node` in step with barbican_single_node.
//
//   orphera apply manifests/barbican_teardown.scala --check
//   orphera apply manifests/barbican_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object barbican_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "barbican"
  private val dbUser = "barbican"
  private val helper = "/usr/local/sbin/orphera-barbican-api"
  private val units = List("barbican-api", "barbican-worker")

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
       |echo "barbican containers and units removed"""".stripMargin

  // /etc/kolla/barbican is where the cluster playbook kept barbican.conf; it is listed
  // so that a node set up by the playbook is cleaned too.
  private val paths =
    "/etc/kolla/barbican /etc/kolla/barbican-api /etc/kolla/barbican-worker /etc/kolla/barbican-ca /var/lib/orphera/barbican /var/log/kolla/barbican " + helper
  // True when ANY of them exists.
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  private val cleanupScript =
    s"""rm -rf $paths
       |echo "barbican config, state and helper removed"""".stripMargin

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running
  // broker there is nothing to remove.
  private val rabbitHasBarbican =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx barbican || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx barbican; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost barbican || true
      |podman exec rabbitmq rabbitmqctl delete_user barbican || true
      |echo "rabbitmq vhost barbican and user barbican removed"""".stripMargin

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
          "remove the barbican vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasBarbican,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Barbican from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the barbican containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      val cleaned = command(
        "remove barbican files, state and the helper",
        cleanupScript,
        onlyIf = filesExist,
        timeout = 120
      ).after(stopped)
      List(unregistered, stopped, cleaned)
