// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes trove_database.scala, trove_rabbitmq.scala and trove_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst25 removes Trove's guest security group and keypair (the typed
// absent kinds, which act only while their helpers and the resources are there), then Trove (the service and user) from Keystone, then the three containers, units, config,
// state and helper; on tst10 removes the `trove` vhost and user from RabbitMQ (the broker itself and the other
// services' use of it are untouched); on tst3 drops the trove database and user. podman, the images and
// project `service` stay. Keep `node` in step with trove_single_node.
//
// Database instances that Trove made are NOT removed: their Nova servers and Cinder volumes stay in project
// `service` and are no longer managed by anything. Delete the instances first (`openstack database instance
// delete`), and the guest image and datastore versions if you made them (trove_guest_image.scala).
//
//   orphera apply manifests/trove_teardown.scala --check
//   orphera apply manifests/trove_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object trove_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst25"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "trove"
  private val dbUser = "trove"
  private val helper = "/usr/local/sbin/orphera-trove-api"
  private val units = List("trove-api", "trove-taskmanager", "trove-conductor")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst25 ----
  // The key pair belongs to the trove user, so it goes before the Keystone entries do; the helpers of both
  // kinds are there until the last step removes them.

  private val dirs = List(
    "/etc/kolla/trove",
    "/etc/kolla/trove-api",
    "/etc/kolla/trove-taskmanager",
    "/etc/kolla/trove-conductor",
    "/etc/kolla/trove-ca",
    "/var/lib/orphera/trove",
    "/var/log/kolla/trove"
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
          "remove the trove vhost and user from RabbitMQ",
          "trove",
          "trove"
        )
      )
    else
      val groupGone = absentOpenstackSecurityGroup("delete the guest security group", "trove-mgmt", "service")
      val pairGone = absentOpenstackKeypair("delete the guest key pair", "trove-mgmt", "trove")
      val unregistered = absentKeystoneEntries("remove Trove from Keystone", "trove").after(groupGone, pairGone)
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) ++
        List(
          absentFile(helper),
          absentFile(keystoneHelperPath("trove")),
          absentFile(openstackSecurityGroupHelperPath("trove-mgmt", "service")),
          absentFile(openstackKeypairHelperPath("trove-mgmt", "trove"))
        )).map(_.after(stopped*))
      List(groupGone, pairGone, unregistered) ++ stopped ++ cleaned
