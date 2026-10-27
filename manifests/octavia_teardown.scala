// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes octavia_single_node.scala: through the helper that the main playbook installed (if it is
// still there) it deletes the amphora VMs (Nova servers named amphora-*), the health manager port, the
// network lb-mgmt-net, the two security groups, the flavor orphera.amphora and the amphora image, and removes
// Octavia from Keystone (service type load-balancer, its endpoints, user octavia). Then, on tst19, it stops
// and removes the four containers, their units and the o-hm0 unit, deletes the o-hm0 port from br-int, and
// removes the config (with the certificates), the state (with the downloaded image file) and the helper;
// then it drops the octavia database and user, and removes the octavia vhost and user from RabbitMQ.
// Load balancers are in the database, so they go with it; run it only when none is needed. podman and the
// other packages stay. The secrets (octavia/db, octavia/service, octavia/rabbit, octavia/ca-passphrase,
// octavia/heartbeat-key) stay in the secret store.
// Keep node, rabbitNode and the names in step with octavia_single_node. Safe to re-run when nothing is
// installed.
object octavia_teardown extends OrpheraClusterPlaybook:

  private val node = "tst19"
  private val rabbitNode = "tst10"
  private val dbName = "octavia"
  private val dbUser = "octavia"
  private val rabbitVhost = "octavia"
  private val rabbitUser = "octavia"
  private val containers =
    List(
      "octavia-api",
      "octavia-worker",
      "octavia-health-manager",
      "octavia-housekeeping"
    )

  private val nodeScript =
    s"""if [ -x /usr/local/sbin/orphera-octavia-api ]; then
       |  /usr/local/sbin/orphera-octavia-api teardown || echo "openstack cleanup failed (continuing)"
       |fi
       |for c in ${containers.mkString(" ")}; do
       |  systemctl disable --now orphera-$$c 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$c.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$c 2>/dev/null || true; fi
       |done
       |systemctl disable --now orphera-octavia-hm-port.service 2>/dev/null || true
       |rm -f /etc/systemd/system/orphera-octavia-hm-port.service /usr/local/sbin/orphera-octavia-hm-port
       |if command -v ovs-vsctl >/dev/null 2>&1; then ovs-vsctl --if-exists del-port br-int o-hm0 || true; fi
       |systemctl daemon-reload
       |for c in ${containers.mkString(" ")}; do rm -rf /etc/kolla/$$c; done
       |rm -rf /etc/kolla/octavia /etc/kolla/octavia-ca
       |rm -rf /var/lib/orphera/octavia /var/log/kolla/octavia
       |rm -f /usr/local/sbin/orphera-octavia-api
       |echo "octavia containers, units, config and state removed"""".stripMargin

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
    clusterPlaybook("octavia-teardown")(
      stage("teardown-octavia-node", node)
        .task(
          "delete the amphorae, network, image and Keystone entries; remove the containers, units and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 600)
        )
        .build,

      stage("drop-octavia-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("drop-octavia-rabbitmq", rabbitNode)
        .task(s"remove the $rabbitVhost vhost and the $rabbitUser user")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build
    )
