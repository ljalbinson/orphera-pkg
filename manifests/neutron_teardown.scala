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

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        absentSqlDatabase(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          List(dbName),
          dbUser
        )
      )
    else if target.name == rabbitNode then
      List(
        absentRabbitAccount(
          "remove the neutron vhost and user from RabbitMQ",
          "neutron",
          "neutron"
        )
      )
    else
      val unregistered =
        absentKeystoneEntries("remove Neutron from Keystone", "neutron")
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u))
        .map(_.after(unregistered))
      val bridges = absentOvsBridge(
        "delete the Open vSwitch bridges br-provider and br-int",
        List("br-provider", "br-int")
      ).after(stopped*)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("neutron")))
        .map(_.after(stopped*))
      List(unregistered) ++ stopped ++ List(bridges) ++ cleaned
