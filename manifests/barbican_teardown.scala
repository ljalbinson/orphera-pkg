// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes barbican_single_node.scala: removes Barbican from Keystone (the service of type
// key-manager, its endpoints and user barbican) through the helper installed by the main playbook if it is
// still there, stops and removes the two containers, their units, config, state and the helper on the node,
// drops the barbican database and user, and removes the barbican vhost and user from RabbitMQ. The roles
// creator, observer and audit stay, as do podman and project service. EVERY SECRET STORED IN BARBICAN IS
// DESTROYED with the database; the key-encrypting key (secret barbican/kek) stays in the secret store.
// Keep node, rabbitNode and the names in step with barbican_single_node. Safe to re-run when nothing is
// installed.
object barbican_teardown extends OrpheraClusterPlaybook:

  private val node = "tst20"
  private val rabbitNode = "tst10"
  private val dbName = "barbican"
  private val dbUser = "barbican"
  private val rabbitVhost = "barbican"
  private val rabbitUser = "barbican"
  private val containers = List("barbican-api", "barbican-worker")

  private val nodeScript =
    s"""if [ -x /usr/local/sbin/orphera-barbican-api ]; then
       |  /usr/local/sbin/orphera-barbican-api unregister || echo "keystone cleanup failed (continuing)"
       |fi
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |for c in ${containers.mkString(" ")}; do rm -rf /etc/kolla/$$c; done
       |rm -rf /etc/kolla/barbican /etc/kolla/barbican-ca
       |rm -rf /var/lib/orphera/barbican /var/log/kolla/barbican
       |rm -f /usr/local/sbin/orphera-barbican-api
       |echo "barbican containers, units, config and state removed"""".stripMargin

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
    clusterPlaybook("barbican-teardown")(
      stage("teardown-barbican-node", node)
        .task(
          "unregister from Keystone, remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("drop-barbican-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-barbican-rabbitmq", rabbitNode)
        .task(s"remove the $rabbitVhost vhost and the $rabbitUser user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
