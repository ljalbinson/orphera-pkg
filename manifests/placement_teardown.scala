// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes placement_database.scala and placement_single_node.scala, as a
// declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst13
// removes Placement from Keystone (service `placement`, its endpoints, user
// `placement`) through the helper if it is still there, then the container,
// unit, config, state, helper and Keystone CA copy; on tst3 drops the placement
// database and user. Glance on the same node is untouched; podman and the image
// stay, and project `service` stays. Keep `node` in step with
// placement_single_node.
//
//   orphera apply manifests/placement_teardown.scala --check
//   orphera apply manifests/placement_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object placement_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val dbNode = "tst3"
  private val dbName = "placement"
  private val dbUser = "placement"
  private val unit = "orphera-placement-api"
  private val helper = "/usr/local/sbin/orphera-placement-api"

  override def nodes: List[String] = List(dbNode, node)


  private val dirs = List(
    "/etc/kolla/placement",
    "/etc/kolla/placement-api",
    "/etc/kolla/placement-ca",
    "/var/lib/orphera/placement",
    "/var/log/kolla/placement"
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
    else
      val unregistered = absentKeystoneEntries("remove Placement from Keystone", "placement")
      val stopped = absentContainerUnits(List(unit -> "placement-api")).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("placement"))).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
