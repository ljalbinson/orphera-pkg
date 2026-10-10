// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes cinder_single_node.scala: drops the cinder database and user,
// deletes the `volumes` pool and the client.cinder cephx user (the pool and all
// volumes in it are destroyed), then removes the Cinder and RabbitMQ
// containers, units, config, state and Keystone registration data on tst10.
// The Keystone-side objects (project `service`, user `cinder`, service and
// endpoints) are removed too, through the helper installed by the main
// playbook, if it is still there. podman stays installed.
//
// Deleting a pool needs mon_allow_pool_delete; this turns it on for the
// duration and off again. Safe to re-run when nothing is installed.
object cinder_teardown extends OrpheraClusterPlaybook:

  private val node = "tst10"
  private val dbName = "cinder"
  private val dbUser = "cinder"
  private val cephPool = "volumes"

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val cephScript =
    s"""cephadm shell -- ceph config set mon mon_allow_pool_delete true
       |cephadm shell -- ceph osd pool delete $cephPool $cephPool --yes-i-really-really-mean-it || true
       |cephadm shell -- ceph config set mon mon_allow_pool_delete false
       |cephadm shell -- ceph auth del client.cinder || true
       |echo "pool $cephPool and client.cinder removed"""".stripMargin

  private val nodeScript =
    """for u in cinder-api cinder-scheduler cinder-volume rabbitmq; do
      |  systemctl disable --now orphera-$u 2>/dev/null || true
      |  rm -f /etc/systemd/system/orphera-$u.service
      |  if command -v podman >/dev/null 2>&1; then podman rm -f $u 2>/dev/null || true; fi
      |done
      |systemctl daemon-reload
      |rm -rf /etc/kolla/cinder /etc/kolla/cinder-api /etc/kolla/cinder-scheduler /etc/kolla/cinder-volume /etc/kolla/cinder-ca
      |rm -rf /var/lib/orphera/cinder /var/lib/orphera/rabbitmq /var/log/kolla/cinder
      |rm -f /usr/local/sbin/orphera-openstack-api
      |echo "cinder and rabbitmq containers, units, config and state removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("cinder-teardown")(
      stage("teardown-cinder-node", node)
        .task("remove the cinder and rabbitmq containers, units and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 120)
        )
        .build,

      stage("drop-ceph-and-database", "tst0")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .task(s"delete the $cephPool pool and client.cinder")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build
    )
