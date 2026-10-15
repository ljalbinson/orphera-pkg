// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes nova_single_node.scala: removes Nova from Keystone (service
// `compute`, its endpoints, user `nova`) through the helper installed by the main
// playbook if it is still there, removes the four containers, units, config,
// state and helper on tst14, drops the nova_api, nova and nova_cell0 databases
// and the nova user, and removes the `nova` vhost and user from RabbitMQ on
// tst10 (the broker itself and Cinder's use of it are untouched). podman stays
// installed and project `service` stays. Keep `node` in step with
// nova_single_node. Safe to re-run when nothing is installed.
object nova_teardown extends OrpheraClusterPlaybook:

  private val node = "tst14"
  private val rabbitNode = "tst10"
  private val dbUser = "nova"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-nova-api ]; then
      |  /usr/local/sbin/orphera-nova-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |for u in nova-api nova-scheduler nova-conductor nova-novncproxy; do
      |  systemctl disable --now orphera-$u 2>/dev/null || true
      |  rm -f /etc/systemd/system/orphera-$u.service
      |  if command -v podman >/dev/null 2>&1; then podman rm -f $u 2>/dev/null || true; fi
      |done
      |systemctl daemon-reload
      |rm -rf /etc/kolla/nova /etc/kolla/nova-api /etc/kolla/nova-scheduler /etc/kolla/nova-conductor /etc/kolla/nova-novncproxy /etc/kolla/nova-ca
      |rm -rf /var/lib/orphera/nova /var/log/kolla/nova
      |rm -f /usr/local/sbin/orphera-nova-api
      |echo "nova containers, units, config and state removed"""".stripMargin

  private val rabbitScript =
    """if podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q .; then
      |  podman exec rabbitmq rabbitmqctl delete_vhost nova || true
      |  podman exec rabbitmq rabbitmqctl delete_user nova || true
      |fi
      |echo "rabbitmq vhost nova and user nova removed if present"""".stripMargin

  private val dropDatabasesScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS nova_api; DROP DATABASE IF EXISTS nova; DROP DATABASE IF EXISTS nova_cell0; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("nova-teardown")(
      stage("teardown-nova-node", node)
        .task(
          "unregister from Keystone, remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("remove-nova-rabbitmq", rabbitNode)
        .task("remove the nova vhost and user from RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 60)
        )
        .build,

      stage("drop-nova-databases", "tst3")
        .task(
          "drop nova_api, nova, nova_cell0 and the nova user on the Galera cluster"
        )(
          Task.RunCommand(List("sh", "-c", dropDatabasesScript))
        )
        .build
    )
