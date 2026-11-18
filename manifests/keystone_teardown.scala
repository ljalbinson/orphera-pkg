// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes keystone_database.scala and keystone_single_node.scala, as a
// declarative description (OrpheraApply, docs/APPLY-DESIGN.md): drops the
// keystone database and user on the Galera cluster (tst3), then stops and
// removes the container, its systemd unit, the config, keys, logs, the test CA
// and its system-trust entry from the Keystone node (tst6). podman itself is
// left installed.
//
//   orphera apply keystone_teardown.scala --check
//   orphera apply keystone_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing, and a run where nothing was
// installed changes nothing either. Keep `node`, `image`, `dbName` and `dbUser`
// in step with keystone_single_node.scala and keystone_database.scala.
object keystone_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst6"
  private val dbNode = "tst3"
  private val image = "quay.io/openstack.kolla/keystone:2025.1-ubuntu-noble"
  private val dbName = "keystone"
  private val dbUser = "keystone"
  private val unit = "orphera-keystone"

  override def nodes: List[String] = List(dbNode, node)

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  private val databaseExists =
    s"""[ -n "$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'")" ]"""

  private val unitExists =
    s"[ -f /etc/systemd/system/$unit.service ] || systemctl is-active --quiet $unit || systemctl is-enabled --quiet $unit"
  private val stopScript =
    s"""systemctl disable --now $unit 2>/dev/null || true
       |rm -f /etc/systemd/system/$unit.service
       |systemctl daemon-reload
       |if command -v podman >/dev/null 2>&1; then podman rm -f keystone 2>/dev/null || true; fi
       |echo "keystone container and unit removed"""".stripMargin

  // True when ANY of them exists (`ls -d` with several arguments fails if one
  // is missing, which is the opposite).
  private val filesExist =
    "for p in /etc/kolla/keystone /etc/kolla/keystone-ca /var/lib/orphera/keystone /var/log/kolla/keystone /usr/local/sbin/keystone-admin-token /usr/local/share/ca-certificates/orphera-test-ca.crt /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; do [ -e \"$p\" ] && exit 0; done; exit 1"

  private val cleanupScript =
    s"""rm -rf /etc/kolla/keystone /etc/kolla/keystone-ca /var/lib/orphera/keystone /var/log/kolla/keystone
       |rm -f /usr/local/sbin/keystone-admin-token
       |if command -v update-ca-certificates >/dev/null 2>&1; then
       |  rm -f /usr/local/share/ca-certificates/orphera-test-ca.crt
       |  update-ca-certificates --fresh
       |elif command -v update-ca-trust >/dev/null 2>&1; then
       |  rm -f /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt
       |  update-ca-trust
       |fi
       |echo "keystone config, keys, logs and test CA removed"""".stripMargin

  private val imageExists = s"command -v podman >/dev/null 2>&1 && podman image exists $image"

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
      val stopped = command(
        "stop and remove the keystone container and unit",
        stopScript,
        onlyIf = unitExists
      )
      val cleaned = command(
        "remove keystone files and the test CA",
        cleanupScript,
        onlyIf = filesExist,
        timeout = 120
      ).after(stopped)
      val image_ = command(
        "remove the keystone image",
        s"podman rmi -f $image",
        onlyIf = imageExists,
        timeout = 120
      ).after(stopped)
      List(stopped, cleaned, image_)
