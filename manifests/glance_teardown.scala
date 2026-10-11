// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes glance_single_node.scala: removes Glance from Keystone (service
// `image`, its endpoints, user `glance`) through the helper installed by the
// main playbook if it is still there, removes the container, unit, config,
// state and helper on tst13, drops the glance database and user, then deletes
// the `images` pool and the client.glance cephx user (the pool and every image
// in it are destroyed). podman stays installed; project `service` stays (Cinder
// uses it).
//
// Deleting a pool needs mon_allow_pool_delete; this turns it on for the
// duration and off again. Safe to re-run when nothing is installed.
object glance_teardown extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val dbName = "glance"
  private val dbUser = "glance"
  private val cephPool = "images"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-glance-api ]; then
      |  /usr/local/sbin/orphera-glance-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |systemctl disable --now orphera-glance-api 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-glance-api.service
      |if command -v podman >/dev/null 2>&1; then podman rm -f glance-api 2>/dev/null || true; fi
      |systemctl daemon-reload
      |rm -rf /etc/kolla/glance /etc/kolla/glance-api /etc/kolla/glance-ca
      |rm -rf /var/lib/orphera/glance /var/log/kolla/glance
      |rm -f /usr/local/sbin/orphera-glance-api
      |echo "glance-api container, unit, config and state removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val cephScript =
    s"""cephadm shell -- ceph config set mon mon_allow_pool_delete true
       |cephadm shell -- ceph osd pool delete $cephPool $cephPool --yes-i-really-really-mean-it || true
       |cephadm shell -- ceph config set mon mon_allow_pool_delete false
       |cephadm shell -- ceph auth del client.glance || true
       |echo "pool $cephPool and client.glance removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("glance-teardown")(
      stage("teardown-glance-node", node)
        .task("unregister from Keystone, remove the container, unit and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 180)
        )
        .build,

      stage("drop-glance-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-ceph-pool-and-user", "tst0")
        .task(s"delete the $cephPool pool and client.glance")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build
    )
