// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes designate_single_node.scala: removes Designate from Keystone (service type `dns`, its
// endpoints, user `designate`) through the helper installed by the main playbook if it is still there,
// stops and removes the six containers (the five Designate services and BIND9), their units, config,
// state (including the zones BIND9 held) and the helper on the node, drops the designate database and
// user, and removes the `designate` vhost and user from RabbitMQ. podman stays installed and project
// `service` stays. Keep `node`, `rabbitNode` and the names in step with designate_single_node.
// Safe to re-run when nothing is installed.
object designate_teardown extends OrpheraClusterPlaybook:

  private val node = "tst16"
  private val rabbitNode = "tst10"
  private val dbName = "designate"
  private val dbUser = "designate"
  private val rabbitVhost = "designate"
  private val rabbitUser = "designate"
  private val containers = List(
    "designate-worker",
    "designate-producer",
    "designate-api",
    "designate-mdns",
    "designate-central",
    "designate-backend-bind9"
  )

  private val nodeScript =
    s"""if [ -x /usr/local/sbin/orphera-designate-api ]; then
       |  /usr/local/sbin/orphera-designate-api unregister || echo "keystone cleanup failed (continuing)"
       |fi
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |for c in ${containers.mkString(" ")}; do rm -rf /etc/kolla/$$c; done
       |rm -rf /etc/kolla/designate /etc/kolla/designate-ca
       |rm -rf /var/lib/orphera/designate /var/log/kolla/designate
       |rm -f /usr/local/sbin/orphera-designate-api
       |echo "designate containers, units, config and state removed"""".stripMargin

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
    clusterPlaybook("designate-teardown")(
      stage("teardown-designate-node", node)
        .task("unregister from Keystone, remove the containers, units and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("drop-designate-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-designate-rabbitmq", rabbitNode)
        .task(s"remove the $rabbitVhost vhost and the $rabbitUser user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
