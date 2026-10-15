// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes neutron_single_node.scala: removes Neutron from Keystone (service
// `network`, its endpoints, user `neutron`) through the helper installed by the main
// playbook if it is still there, removes the three containers, units, config, state
// and helper on tst19, deletes the Open vSwitch bridges br-provider and br-int (the
// provider NIC goes back to being an ordinary, address-less port), drops the
// neutron database and user, and removes the `neutron` vhost and user from RabbitMQ
// on tst10 (the broker, Cinder and Nova are untouched). podman and openvswitch-switch
// stay installed, project `service` stays. NOT restored: the address that enp2s0 had
// from provisioning (10.10.6.31/24); give it one again by hand or re-provision the VM
// from config/tst19.yaml. Keep `node` in step with neutron_single_node.
// Safe to re-run when nothing is installed.
object neutron_teardown extends OrpheraClusterPlaybook:

  private val node = "tst19"
  private val rabbitNode = "tst10"
  private val dbName = "neutron"
  private val dbUser = "neutron"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-neutron-api ]; then
      |  /usr/local/sbin/orphera-neutron-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |for u in neutron-server neutron-openvswitch-agent neutron-dhcp-agent; do
      |  systemctl disable --now orphera-$u 2>/dev/null || true
      |  rm -f /etc/systemd/system/orphera-$u.service
      |  if command -v podman >/dev/null 2>&1; then podman rm -f $u 2>/dev/null || true; fi
      |done
      |systemctl daemon-reload
      |if command -v ovs-vsctl >/dev/null 2>&1; then
      |  ovs-vsctl --if-exists del-br br-provider || true
      |  ovs-vsctl --if-exists del-br br-int || true
      |fi
      |rm -rf /etc/kolla/neutron /etc/kolla/neutron-server /etc/kolla/neutron-openvswitch-agent /etc/kolla/neutron-dhcp-agent /etc/kolla/neutron-ca
      |rm -rf /var/lib/orphera/neutron /var/log/kolla/neutron
      |rm -f /usr/local/sbin/orphera-neutron-api
      |echo "neutron containers, units, bridges, config and state removed"""".stripMargin

  private val rabbitScript =
    """if podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q .; then
      |  podman exec rabbitmq rabbitmqctl delete_vhost neutron || true
      |  podman exec rabbitmq rabbitmqctl delete_user neutron || true
      |fi
      |echo "rabbitmq vhost neutron and user neutron removed if present"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("neutron-teardown")(
      stage("teardown-neutron-node", node)
        .task(
          "unregister from Keystone, remove the containers, units, bridges and state"
        )(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("remove-neutron-rabbitmq", rabbitNode)
        .task("remove the neutron vhost and user from RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 60)
        )
        .build,

      stage("drop-neutron-database", "tst3")
        .task(s"drop $dbName and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build
    )
