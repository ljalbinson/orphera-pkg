// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes neutron_database.scala, neutron_rabbitmq.scala and
// neutron_single_node.scala, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): on tst19 removes Neutron from Keystone (service
// `network`, its endpoints, user `neutron`) through the helper if it is still
// there, then the four containers, units, the Open vSwitch bridges br-provider
// and br-int (the provider NIC goes back to being an ordinary, address-less
// port), config, state, helper and Keystone CA copy; on tst10 removes the
// `neutron` vhost and user from RabbitMQ (the broker, Cinder and Nova are
// untouched); on tst3 drops the neutron database and user. podman and
// openvswitch-switch stay installed, project `service` stays. NOT restored: the
// address that enp2s0 had from provisioning (10.10.6.31/24); give it one again by
// hand or re-provision the VM from config/tst19.yaml. Keep `node` in step with
// neutron_single_node.
//
//   orphera apply manifests/neutron_teardown.scala --check
//   orphera apply manifests/neutron_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object neutron_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst19"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "neutron"
  private val dbUser = "neutron"
  private val helper = "/usr/local/sbin/orphera-neutron-api"
  private val units = List(
    "neutron-server",
    "neutron-openvswitch-agent",
    "neutron-dhcp-agent",
    "neutron-l3-agent"
  )

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst19 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val bridgesExist =
    "command -v ovs-vsctl >/dev/null 2>&1 && { ovs-vsctl br-exists br-provider || ovs-vsctl br-exists br-int; }"
  private val bridgesScript =
    """ovs-vsctl --if-exists del-br br-provider || true
      |ovs-vsctl --if-exists del-br br-int || true
      |echo "br-provider and br-int removed"""".stripMargin

  private val dirs = List(
    "/etc/kolla/neutron",
    "/etc/kolla/neutron-server",
    "/etc/kolla/neutron-openvswitch-agent",
    "/etc/kolla/neutron-dhcp-agent",
    "/etc/kolla/neutron-l3-agent",
    "/etc/kolla/neutron-ca",
    "/var/lib/orphera/neutron",
    "/var/log/kolla/neutron"
  )

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running
  // broker there is nothing to remove.
  private val rabbitHasNeutron =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx neutron || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx neutron; }"""
  private val rabbitScript =
    """podman exec rabbitmq rabbitmqctl delete_vhost neutron || true
      |podman exec rabbitmq rabbitmqctl delete_user neutron || true
      |echo "rabbitmq vhost neutron and user neutron removed"""".stripMargin

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or user exists. When MariaDB cannot be queried this is
  // also true, so that the drop runs and fails with MariaDB's own message: a
  // database that is down is not "nothing to remove".
  private val databaseExists =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        command(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          dropDatabaseScript,
          onlyIf = databaseExists
        )
      )
    else if target.name == rabbitNode then
      List(
        command(
          "remove the neutron vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasNeutron,
          timeout = 60
        )
      )
    else
      val unregistered = command(
        "remove Neutron from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      val bridges = command(
        "delete the Open vSwitch bridges br-provider and br-int",
        bridgesScript,
        onlyIf = bridgesExist,
        timeout = 60
      ).after(stopped*)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ List(bridges) ++ cleaned
