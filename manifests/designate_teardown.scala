// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes designate_database.scala, designate_rabbitmq.scala and designate_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst16
// removes Designate from Keystone (the service of type `dns`, its endpoints and
// user `designate`) through the helper if it is still there, then the six
// containers (the five Designate services and BIND9), units, config, state
// (including the zones BIND9 held and the rndc key), helper and Keystone CA copy;
// on tst10 removes the `designate` vhost and user from RabbitMQ (the broker itself
// and the other services' use of it are untouched); on tst3 drops the designate
// database and user. podman, bind9-dnsutils, the images and project `service`
// stay. Every zone and record is destroyed with the database. Keep `node` in step
// with designate_single_node.
//
//   orphera apply manifests/designate_teardown.scala --check
//   orphera apply manifests/designate_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object designate_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst16"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "designate"
  private val dbUser = "designate"
  private val helper = "/usr/local/sbin/orphera-designate-api"
  private val units = List(
    "designate-worker",
    "designate-producer",
    "designate-api",
    "designate-mdns",
    "designate-central",
    "designate-backend-bind9"
  )

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst16 ----
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
       |echo "designate containers and units removed"""".stripMargin

  // /etc/kolla/designate is where the cluster playbook kept designate.conf, pools.yaml, named.conf and the
  // rndc key; it is listed so that a node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/designate",
    "/etc/kolla/designate-central",
    "/etc/kolla/designate-mdns",
    "/etc/kolla/designate-api",
    "/etc/kolla/designate-producer",
    "/etc/kolla/designate-worker",
    "/etc/kolla/designate-backend-bind9",
    "/etc/kolla/designate-ca",
    "/var/lib/orphera/designate",
    "/var/log/kolla/designate"
  )

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running
  // broker there is nothing to remove.
  private val rabbitHasDesignate =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx designate || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx designate; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost designate || true
      |podman exec rabbitmq rabbitmqctl delete_user designate || true
      |echo "rabbitmq vhost designate and user designate removed"""".stripMargin

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
          "remove the designate vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasDesignate,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Designate from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the designate containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped))
      List(unregistered, stopped) ++ cleaned
