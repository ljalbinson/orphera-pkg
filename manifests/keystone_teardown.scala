// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes keystone_single_node.scala: drops the keystone database and user on
// the Galera cluster, then purges the packages, the Apache site, the keys,
// the test CA and its system-trust entry from the Keystone node. Safe to run
// when nothing is installed. Keep `node` in step with keystone_single_node.
object keystone_teardown extends OrpheraClusterPlaybook:

  private val node = "tst7"
  private val dbName = "keystone"
  private val dbUser = "keystone"

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val stopScript =
    """a2dissite orphera-keystone 2>/dev/null || true
      |systemctl stop apache2 2>/dev/null || true
      |echo "apache2 stopped"""".stripMargin

  private val cleanupScript =
    """rm -f /etc/apache2/sites-available/orphera-keystone.conf
      |rm -rf /etc/keystone /var/lib/keystone /var/log/keystone
      |rm -f /var/log/apache2/keystone.log /var/log/apache2/keystone_access.log
      |rm -f /usr/local/share/ca-certificates/orphera-test-ca.crt
      |update-ca-certificates --fresh
      |echo "keystone files, site config and test CA removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("keystone-teardown")(
      stage("drop-keystone-database", "tst0")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("teardown-keystone-node", node)
        .task("stop apache2 and disable the keystone site")(
          Task.RunCommand(List("sh", "-c", stopScript))
        )
        .task("purge keystone, apache2, mod_wsgi and the openstack client")(
          Task.Remove(
            packages = List(
              "keystone",
              "libapache2-mod-wsgi-py3",
              "apache2",
              "python3-openstackclient"
            ),
            purge = true
          )
        )
        .task("autoremove now-unneeded dependencies")(
          Task.AutoRemove(purge = true)
        )
        .task("remove leftover keystone files and the test CA")(
          Task.RunCommand(List("sh", "-c", cleanupScript))
        )
        .build
    )
