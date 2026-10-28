// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes magnum_single_node.scala. Through the helper the main playbook installed (if it is still
// there) it deletes every Magnum cluster (and waits for them to go), the cluster template, the flavor
// orphera.k8s, the key pair, the Kubernetes node image, and removes Magnum from Keystone (service type
// container-infra, its endpoints, user magnum, domain magnum). Then, on tst23, it stops and removes the two
// containers and their units, the config and state (with the downloaded
// image file); it drops the magnum database and user and removes the magnum vhost and user from RabbitMQ.
// The management cluster (capi_management_cluster.scala) is left alone; run capi_management_cluster_teardown
// separately. podman and the other packages stay. The secrets (magnum/*) stay in the secret store.
// Keep node, rabbitNode and the names in step with magnum_single_node. Safe to re-run when nothing is
// installed.
object magnum_teardown extends OrpheraClusterPlaybook:

  private val node = "tst23"
  private val rabbitNode = "tst10"
  private val dbName = "magnum"
  private val dbUser = "magnum"
  private val rabbitVhost = "magnum"
  private val rabbitUser = "magnum"
  private val containers = List("magnum-api", "magnum-conductor")

  private val nodeScript =
    s"""if [ -x /usr/local/sbin/orphera-magnum-api ]; then
       |  /usr/local/sbin/orphera-magnum-api teardown || echo "openstack cleanup failed (continuing)"
       |fi
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |for c in ${containers.mkString(" ")}; do rm -rf /etc/kolla/$$c; done
       |rm -rf /etc/kolla/magnum /etc/kolla/magnum-ca
       |rm -rf /var/lib/orphera/magnum /var/log/kolla/magnum
       |rm -f /usr/local/sbin/orphera-magnum-api
       |echo "magnum containers, units, config and state removed"""".stripMargin

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

  val playbook: ClusterPlaybook =
    clusterPlaybook("magnum-teardown")(
      stage("teardown-magnum-node", node)
        .task(
          "delete the clusters, template, image and Keystone entries; remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 3600)
        )
        .build,

      stage("drop-magnum-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-magnum-rabbitmq", rabbitNode)
        .task(s"remove the $rabbitVhost vhost and the $rabbitUser user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
