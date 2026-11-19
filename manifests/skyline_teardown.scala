// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes skyline_database.scala and skyline_single_node.scala, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): removes the Keystone user `skyline` through the helper installed by the main
// description if it is still there, stops and removes the container, its unit, config, state (including the
// PyMySQL directory, the copy of the certificate and the helper) on tst13, and drops the skyline database and
// user on tst3. podman, the image, the certificate from dashboard_tls.scala and Keystone's CA stay, as do the
// secrets (skyline/db, skyline/service, skyline/secret-key). Horizon is not touched. Keep the names in step
// with skyline_single_node.
//
//   orphera apply manifests/skyline_teardown.scala --check
//   orphera apply manifests/skyline_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object skyline_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val dbNode = "tst3"
  private val dbName = "skyline"
  private val dbUser = "skyline"
  private val helper = "/usr/local/sbin/orphera-skyline-api"

  override def nodes: List[String] = List(dbNode, node)

  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val anyUnit =
    """[ -f /etc/systemd/system/orphera-skyline.service ] || systemctl is-active --quiet orphera-skyline || systemctl is-enabled --quiet orphera-skyline || { command -v podman >/dev/null 2>&1 && { podman container exists skyline || podman container exists skyline_bootstrap; }; }"""
  private val stopScript =
    """systemctl disable --now orphera-skyline 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-skyline.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then
      |  podman rm -f skyline skyline_bootstrap 2>/dev/null || true
      |fi
      |echo "skyline container and unit removed"""".stripMargin

  private val paths =
    s"/etc/skyline /var/lib/orphera/skyline /var/log/skyline /tmp/skyline-bootstrap.log $helper"
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  private val cleanupScript =
    s"""rm -rf $paths
       |echo "skyline config, state and helper removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or the user exists. When MariaDB cannot be queried this is also true, so that the
  // drop runs and fails with MariaDB's own message.
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
        "remove the Skyline user from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the skyline container and unit",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      val cleaned = command(
        "remove skyline files, state and the helper",
        cleanupScript,
        onlyIf = filesExist,
        timeout = 120
      ).after(stopped)
      List(unregistered, stopped, cleaned)
