// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes placement_single_node.scala: removes Placement from Keystone
// (service `placement`, its endpoints, user `placement`) through the helper
// installed by the main playbook if it is still there, removes the container,
// unit, config, state and helper on the node, and drops the placement database
// and user. Glance on the same node is untouched; podman stays installed and
// project `service` stays. Keep `node` in step with placement_single_node.
// Safe to re-run when nothing is installed.
object placement_teardown extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val dbName = "placement"
  private val dbUser = "placement"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-placement-api ]; then
      |  /usr/local/sbin/orphera-placement-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |systemctl disable --now orphera-placement-api 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-placement-api.service
      |if command -v podman >/dev/null 2>&1; then podman rm -f placement-api 2>/dev/null || true; fi
      |systemctl daemon-reload
      |rm -rf /etc/kolla/placement /etc/kolla/placement-api /etc/kolla/placement-ca
      |rm -rf /var/lib/orphera/placement /var/log/kolla/placement
      |rm -f /usr/local/sbin/orphera-placement-api
      |echo "placement-api container, unit, config and state removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("placement-teardown")(
      stage("teardown-placement-node", node)
        .task("unregister from Keystone, remove the container, unit and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 180)
        )
        .build,

      stage("drop-placement-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build
    )
