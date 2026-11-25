// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes placement_database.scala and placement_single_node.scala, as a
// declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst13
// removes Placement from Keystone (service `placement`, its endpoints, user
// `placement`) through the helper if it is still there, then the container,
// unit, config, state, helper and Keystone CA copy; on tst3 drops the placement
// database and user. Glance on the same node is untouched; podman and the image
// stay, and project `service` stays. Keep `node` in step with
// placement_single_node.
//
//   orphera apply manifests/placement_teardown.scala --check
//   orphera apply manifests/placement_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object placement_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val dbNode = "tst3"
  private val dbName = "placement"
  private val dbUser = "placement"
  private val unit = "orphera-placement-api"
  private val helper = "/usr/local/sbin/orphera-placement-api"

  override def nodes: List[String] = List(dbNode, node)

  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val dirs = List(
    "/etc/kolla/placement",
    "/etc/kolla/placement-api",
    "/etc/kolla/placement-ca",
    "/var/lib/orphera/placement",
    "/var/log/kolla/placement"
  )

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or user exists. When MariaDB cannot be queried this is
  // also true, so that the drop runs and fails with MariaDB's own message: a
  // database that is down is not "nothing to remove".
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
    else
      val unregistered = command(
        "remove Placement from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(List(unit -> "placement-api")).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
