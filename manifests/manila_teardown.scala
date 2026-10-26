// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes manila_single_node.scala: removes Manila from Keystone (the service of type sharev2, its
// endpoints and user manila) through the helper installed by the main playbook if it is still there, stops
// and removes the three containers, their units, config, state and the helper on tst22, drops the manila
// database and user, removes the manila vhost and user from RabbitMQ, and deletes the cephx user
// client.manila. The share type and every share are in the database, so they go with it. podman, nfs-common
// and project service stay.
//
// The CephFS filesystem and the NFS cluster are KEPT, because shares made by Manila live in them (as
// subvolumes that nothing now tracks) and the filesystem may have other uses. Set MANILA_PURGE_CEPH=yes in
// the environment of the run to remove the NFS cluster and the filesystem too; that DESTROYS ALL DATA in
// the filesystem:
//   MANILA_PURGE_CEPH=yes orphera cluster-playbook manila_teardown.scala
// The secrets (manila/db, manila/service, manila/rabbit, manila/ceph-key) stay in the secret store.
// Keep node, rabbitNode and the names in step with manila_single_node. Safe to re-run when nothing is
// installed.
object manila_teardown extends OrpheraClusterPlaybook:

  private val node = "tst22"
  private val rabbitNode = "tst10"
  private val cephNode = "tst0"
  private val dbName = "manila"
  private val dbUser = "manila"
  private val rabbitVhost = "manila"
  private val rabbitUser = "manila"
  private val cephFs = "cephfs"
  private val nfsCluster = "manila"
  private val containers =
    List("manila-api", "manila-scheduler", "manila-share")
  private val purgeCeph = sys.env.get("MANILA_PURGE_CEPH").contains("yes")

  private val nodeScript =
    s"""if [ -x /usr/local/sbin/orphera-manila-api ]; then
       |  /usr/local/sbin/orphera-manila-api unregister || echo "keystone cleanup failed (continuing)"
       |fi
       |if mountpoint -q /mnt/orphera-manila-test 2>/dev/null; then umount /mnt/orphera-manila-test || true; fi
       |rmdir /mnt/orphera-manila-test 2>/dev/null || true
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |for c in ${containers.mkString(" ")}; do rm -rf /etc/kolla/$$c; done
       |rm -rf /etc/kolla/manila /etc/kolla/manila-ca
       |rm -rf /var/lib/orphera/manila /var/log/kolla/manila
       |rm -f /usr/local/sbin/orphera-manila-api
       |echo "manila containers, units, config and state removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val rabbitScript =
    s"""if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node; nothing to remove"
       |  exit 0
       |fi
       |if podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'; then
       |  podman exec rabbitmq rabbitmqctl delete_vhost '$rabbitVhost'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; then
       |  podman exec rabbitmq rabbitmqctl delete_user '$rabbitUser'
       |fi
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser removed"""".stripMargin

  private val cephScript: String =
    val common =
      """cephadm shell -- ceph auth del client.manila 2>/dev/null || true
        |echo "cephx user client.manila removed"""".stripMargin
    if purgeCeph then
      s"""$common
         |if cephadm shell -- ceph nfs cluster ls 2>/dev/null | grep -qx '$nfsCluster'; then
         |  cephadm shell -- ceph nfs cluster rm $nfsCluster
         |fi
         |if cephadm shell -- ceph fs ls 2>/dev/null | grep -q '^name: $cephFs,'; then
         |  cephadm shell -- ceph config set mon mon_allow_pool_delete true
         |  cephadm shell -- ceph fs volume rm $cephFs --yes-i-really-mean-it
         |fi
         |echo "NFS cluster $nfsCluster and filesystem $cephFs removed (MANILA_PURGE_CEPH=yes)"""".stripMargin
    else
      s"""$common
         |echo "CephFS $cephFs and the NFS cluster $nfsCluster kept (MANILA_PURGE_CEPH=yes removes them and all data in them)"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("manila-teardown")(
      stage("teardown-manila-node", node)
        .task(
          "unregister from Keystone, remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("drop-manila-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-manila-rabbitmq", rabbitNode)
        .task(s"remove the $rabbitVhost vhost and the $rabbitUser user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      stage("teardown-manila-ceph", cephNode)
        .task("remove client.manila (and CephFS and NFS if purging)")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 600)
        )
        .build
    )
