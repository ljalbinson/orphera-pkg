// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes nova_database.scala, nova_rabbitmq.scala and nova_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst14
// removes Nova from Keystone (service `compute`, its endpoints, user `nova`)
// through the helper if it is still there, then the four containers, units,
// config, state, helper and Keystone CA copy; on tst10 removes the `nova` vhost
// and user from RabbitMQ (the broker itself and Cinder's use of it are
// untouched); on tst3 drops nova_api, nova, nova_cell0 and the nova user.
// podman and the images stay, and project `service` stays. Keep `node` in step
// with nova_single_node.
//
//   orphera apply manifests/nova_teardown.scala --check
//   orphera apply manifests/nova_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object nova_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst14"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbUser = "nova"
  private val helper = "/usr/local/sbin/orphera-nova-api"
  private val units =
    List("nova-api", "nova-scheduler", "nova-conductor", "nova-novncproxy")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst14 ----

  private val dirs = List(
    "/etc/kolla/nova",
    "/etc/kolla/nova-api",
    "/etc/kolla/nova-scheduler",
    "/etc/kolla/nova-conductor",
    "/etc/kolla/nova-novncproxy",
    "/etc/kolla/nova-ca",
    "/var/lib/orphera/nova",
    "/var/log/kolla/nova"
  )

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        absentSqlDatabase(
          "drop nova_api, nova, nova_cell0 and the nova user on the Galera cluster",
          List("nova_api", "nova", "nova_cell0"),
          dbUser
        )
      )
    else if target.name == rabbitNode then
      List(
        absentRabbitAccount(
          "remove the nova vhost and user from RabbitMQ",
          "nova",
          "nova"
        )
      )
    else
      val unregistered =
        absentKeystoneEntries("remove Nova from Keystone", "nova")
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u))
        .map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("nova")))
        .map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
