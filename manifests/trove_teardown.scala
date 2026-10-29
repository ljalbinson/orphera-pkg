// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes trove_single_node.scala: removes Trove, its guest security group and keypair, and the service
// from Keystone through the helper installed by the main playbook if it is still there, stops and removes the
// containers, units, config, state and helper on tst25, drops the trove database and user, and removes the
// trove vhost and user from RabbitMQ. podman and the secrets (trove/db, trove/service, trove/rabbit) stay.
//
// Database instances that Trove made are NOT removed: their Nova servers and Cinder volumes stay in project
// `service` and are no longer managed by anything. Delete the instances first (`openstack database instance
// delete`), and the guest image and datastore versions if you made them (trove_guest_image.scala).
// Keep the names in step with trove_single_node. Safe to re-run when nothing is installed.
object trove_teardown extends OrpheraClusterPlaybook:

  private val node = "tst25"
  private val rabbitNode = "tst10"
  private val dbName = "trove"
  private val dbUser = "trove"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-trove-api ]; then
      |  /usr/local/sbin/orphera-trove-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |rm -f /usr/local/sbin/orphera-trove-api
      |for c in trove-api trove-taskmanager trove-conductor; do
      |  systemctl disable --now orphera-$c 2>/dev/null || true
      |  rm -f /etc/systemd/system/orphera-$c.service
      |  if command -v podman >/dev/null 2>&1; then podman rm -f $c 2>/dev/null || true; fi
      |  rm -rf /etc/kolla/$c
      |done
      |systemctl daemon-reload
      |rm -rf /etc/kolla/trove /etc/kolla/trove-ca /var/lib/orphera/trove /var/log/kolla/trove
      |echo "trove containers, units, config and state removed"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val rabbitScript =
    """if ! podman ps -q --filter 'name=^rabbitmq$' | grep -q .; then
      |  echo "no running 'rabbitmq' container on this node; nothing to remove"
      |  exit 0
      |fi
      |if podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx "trove"; then
      |  podman exec rabbitmq rabbitmqctl delete_vhost "trove"
      |fi
      |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $1}' | grep -qx "trove"; then
      |  podman exec rabbitmq rabbitmqctl delete_user "trove"
      |fi
      |echo "rabbitmq vhost and user trove removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("trove-teardown")(
      stage("teardown-trove-node", node)
        .task(
          "unregister from Keystone, remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("drop-trove-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-trove-rabbitmq", rabbitNode)
        .task("remove the trove vhost and user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
