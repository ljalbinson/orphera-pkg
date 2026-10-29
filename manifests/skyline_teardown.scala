// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes skyline_single_node.scala: removes the Keystone user `skyline` through the helper installed by
// the main playbook if it is still there, stops and removes the container, its unit, config, state (including
// the PyMySQL directory) and the helper on tst13, and drops the skyline database and user. podman, the image
// and Keystone's CA stay, as do the secrets (skyline/db, skyline/service, skyline/secret-key). Horizon is not
// touched. Keep node and the names in step with skyline_single_node. Safe to re-run when nothing is installed.
object skyline_teardown extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val dbName = "skyline"
  private val dbUser = "skyline"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-skyline-api ]; then
      |  /usr/local/sbin/orphera-skyline-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |systemctl disable --now orphera-skyline 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-skyline.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then
      |  podman rm -f skyline skyline_bootstrap 2>/dev/null || true
      |fi
      |rm -rf /etc/skyline /var/lib/orphera/skyline /var/log/skyline
      |rm -f /usr/local/sbin/orphera-skyline-api /tmp/skyline-bootstrap.log
      |echo "skyline container, unit, config and state removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("skyline-teardown")(
      stage("teardown-skyline-node", node)
        .task("unregister from Keystone, remove the container, unit and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("drop-skyline-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build
    )
