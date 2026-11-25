// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes cinder_database.scala, cinder_ceph.scala and cinder_single_node.scala,
// as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst10
// removes Cinder from Keystone (service `volumev3`, its endpoints, user `cinder`)
// through the helper if it is still there, then the three containers, units,
// config, state, helper and Keystone CA copy; on tst0 deletes the `volumes` pool
// (every volume in it is destroyed) and the client.cinder cephx user; on tst3
// drops the cinder database and user. podman and the images stay, and project
// `service` stays.
//
// The RabbitMQ broker on tst10 is NOT touched: Nova, Neutron and the other
// services have their vhosts and users on it. rabbitmq_teardown.scala removes it.
// Keep `node` in step with cinder_single_node.
//
//   orphera apply manifests/cinder_teardown.scala --check
//   orphera apply manifests/cinder_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database or a Ceph cluster that
// cannot be queried fails the step instead of being skipped. Deleting a pool
// needs mon_allow_pool_delete; the step turns it on for the deletion and off
// again.
object cinder_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst10"
  private val dbNode = "tst3"
  private val cephNode = "tst0"
  private val dbName = "cinder"
  private val dbUser = "cinder"
  private val volumesPool = "volumes"
  private val helper = "/usr/local/sbin/orphera-openstack-api"
  private val units = List("cinder-api", "cinder-scheduler", "cinder-volume")

  override def nodes: List[String] = List(dbNode, cephNode, node)

  // ---- tst10 ----

  private val dirs = List(
    "/etc/kolla/cinder",
    "/etc/kolla/cinder-api",
    "/etc/kolla/cinder-scheduler",
    "/etc/kolla/cinder-volume",
    "/etc/kolla/cinder-ca",
    "/var/lib/orphera/cinder",
    "/var/log/kolla/cinder"
  )

  // ---- tst0 ----
  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        absentSqlDatabase(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          List(dbName),
          dbUser
        )
      )
    else if target.name == cephNode then
      val user = absentCephUser("remove the client.cinder cephx user", "client.cinder")
      val pool = absentCephPool(s"delete the $volumesPool pool", volumesPool).after(user)
      List(user, pool)
    else
      val unregistered = absentKeystoneEntries("remove Cinder from Keystone", "cinder")
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("cinder"))).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
