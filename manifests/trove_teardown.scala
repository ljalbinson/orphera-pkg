// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes trove_database.scala, trove_rabbitmq.scala and trove_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst25 removes Trove, its guest security group and keypair, and the
// service from Keystone through the helper if it is still there, then the three containers, units, config,
// state and helper; on tst10 removes the `trove` vhost and user from RabbitMQ (the broker itself and the other
// services' use of it are untouched); on tst3 drops the trove database and user. podman, the images and
// project `service` stay. Keep `node` in step with trove_single_node.
//
// Database instances that Trove made are NOT removed: their Nova servers and Cinder volumes stay in project
// `service` and are no longer managed by anything. Delete the instances first (`openstack database instance
// delete`), and the guest image and datastore versions if you made them (trove_guest_image.scala).
//
//   orphera apply manifests/trove_teardown.scala --check
//   orphera apply manifests/trove_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object trove_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst25"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "trove"
  private val dbUser = "trove"
  private val helper = "/usr/local/sbin/orphera-trove-api"
  private val units = List("trove-api", "trove-taskmanager", "trove-conductor")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst25 ----
  // The helper removes the Keystone entries and the guest security group and keypair; it is there until the
  // last step removes it.
  private val hasHelper = s"[ -x $helper ]"
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
       |echo "trove containers and units removed"""".stripMargin

  private val dirs = List(
    "/etc/kolla/trove",
    "/etc/kolla/trove-api",
    "/etc/kolla/trove-taskmanager",
    "/etc/kolla/trove-conductor",
    "/etc/kolla/trove-ca",
    "/var/lib/orphera/trove",
    "/var/log/kolla/trove"
  )

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running broker there is nothing to
  // remove.
  private val rabbitHasTrove =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx trove || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx trove; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost trove || true
      |podman exec rabbitmq rabbitmqctl delete_user trove || true
      |echo "rabbitmq vhost trove and user trove removed"""".stripMargin

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or the user exists. When MariaDB cannot be queried this is also true, so that the
  // drop runs and fails with MariaDB's own message: a database that is down is not "nothing to remove".
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
          "remove the trove vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasTrove,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Trove, its guest security group and keypair from Keystone, Neutron and Nova",
        unregisterScript,
        onlyIf = hasHelper,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the trove containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped))
      List(unregistered, stopped) ++ cleaned
