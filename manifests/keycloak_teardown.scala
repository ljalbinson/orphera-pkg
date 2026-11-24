// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes keycloak_tls.scala, keycloak_database.scala and keycloak_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst26 removes the Keystone side of the federation (protocol, identity
// provider, mapping, and domain `keycloak` with its project, groups and federated users) through the helper if it
// is still there, then the Keycloak container, its unit, configuration, key and certificate and the helper; on
// tst6 removes the signed certificate and request kept beside the CA; on tst3 drops the keycloak database and
// user. Every realm, user and session goes with the database. podman, the image, the registry mirror file and
// the trusted test CA stay on tst26, as do the secrets (keycloak/db, keycloak/admin, keycloak/keystone-client,
// keycloak/demo-user, keycloak/kcadmin, keycloak/kcreader, keycloak/kcnogroup). Keystone's OIDC vhost settings
// belong to keystone_single_node.scala and stay; with no identity provider the federation path answers 404. Keep
// `node` and the names in step with keycloak_single_node.
//
//   orphera apply manifests/keycloak_teardown.scala --check
//   orphera apply manifests/keycloak_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run changes
// nothing. A database that cannot be queried fails the step instead of being skipped.
object keycloak_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst26"
  private val ksNode = "tst6"
  private val dbNode = "tst3"
  private val dbName = "keycloak"
  private val dbUser = "keycloak"
  private val helper = "/usr/local/sbin/orphera-keycloak-api"
  private val unit = "orphera-keycloak"
  private val signDir = s"/etc/kolla/keystone-ca/issued/$node"

  override def nodes: List[String] = List(dbNode, ksNode, node)

  // ---- tst26 ----
  // The helper removes the Keystone entries; it is there until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val unfederateScript =
    s"""$helper unfederate || echo "keystone cleanup failed (continuing)""""

  // True when the unit file exists, or the unit is active or enabled.
  private val anyUnit =
    s"""[ -f /etc/systemd/system/$unit.service ] || systemctl is-active --quiet $unit || systemctl is-enabled --quiet $unit"""
  private val stopScript =
    s"""systemctl disable --now $unit 2>/dev/null || true
       |rm -f /etc/systemd/system/$unit.service
       |if command -v podman >/dev/null 2>&1; then podman rm -f keycloak 2>/dev/null || true; fi
       |systemctl daemon-reload
       |echo "keycloak container and unit removed"""".stripMargin

  // ---- tst6 ----
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
    else if target.name == ksNode then
      List(
        absentDirectory(signDir, recursive = true)
      )
    else
      val unfederated = command(
        "remove the Keycloak federation from Keystone",
        unfederateScript,
        onlyIf = hasHelper,
        timeout = 180
      )
      val stopped = command(
        "stop and remove the keycloak container and unit",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unfederated)
      // The configuration and TLS files, the readiness file and the helper (the helper stays until the last step).
      val cleaned = List(
        absentDirectory("/etc/keycloak", recursive = true),
        absentFile("/tmp/kc-ready.json"),
        absentFile(helper)
      ).map(_.after(stopped))
      List(unfederated, stopped) ++ cleaned
