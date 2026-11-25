// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes trove_database.scala, trove_rabbitmq.scala and trove_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst25 removes Trove's guest security group and keypair through the
// helper if it is still there, then Trove (the service and user) from Keystone, then the three containers, units, config,
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
  // The helper removes the guest security group and keypair (as the trove user, so before the Keystone entries
  // go); it is there until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val cleanupScript =
    s"""$helper cleanup-mgmt || echo "guest resource cleanup failed (continuing)""""

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
      val guestCleaned = command(
        "remove the guest security group and keypair from Neutron and Nova",
        cleanupScript,
        onlyIf = hasHelper,
        timeout = 120
      )
      val unregistered = absentKeystoneEntries("remove Trove from Keystone", "trove").after(guestCleaned)
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) ++
        List(absentFile(helper), absentFile(keystoneHelperPath("trove")))).map(_.after(stopped*))
      List(guestCleaned, unregistered) ++ stopped ++ cleaned
