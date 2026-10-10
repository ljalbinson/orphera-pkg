// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes keystone_single_node.scala: drops the keystone database and user on
// the Galera cluster, then stops and removes the container, its systemd unit,
// the config, keys, logs, the test CA and its system-trust entry from the
// Keystone node. podman itself is left installed. Safe to run when nothing is
// installed. Keep `node` and `image` in step with keystone_single_node.
object keystone_teardown extends OrpheraClusterPlaybook:

  private val node = "tst6"
  private val image = "quay.io/openstack.kolla/keystone:2025.1-ubuntu-noble"
  private val dbName = "keystone"
  private val dbUser = "keystone"

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val stopScript =
    """systemctl disable --now orphera-keystone 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-keystone.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then podman rm -f keystone 2>/dev/null || true; fi
      |echo "keystone container and unit removed"""".stripMargin

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
       |if command -v podman >/dev/null 2>&1; then podman rmi -f $image 2>/dev/null || true; fi
       |echo "keystone config, keys, logs, test CA and image removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("keystone-teardown")(
      stage("drop-keystone-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("teardown-keystone-node", node)
        .task("stop and remove the keystone container and unit")(
          Task.RunCommand(List("sh", "-c", stopScript))
        )
        .task("remove keystone files, test CA and image")(
          Task.RunCommand(List("sh", "-c", cleanupScript), timeoutSeconds = 120)
        )
        .build
    )
