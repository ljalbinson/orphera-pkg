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
  private val cephPool = "volumes"
  private val helper = "/usr/local/sbin/orphera-openstack-api"
  private val units = List("cinder-api", "cinder-scheduler", "cinder-volume")

  override def nodes: List[String] = List(dbNode, cephNode, node)

  // ---- tst10 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

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
  // True when the pool or the user exists. When Ceph cannot be queried this is
  // also true, so that the removal runs and fails with Ceph's own message.
  private val cephHasCinder =
    s"""OUT=$$(cephadm shell -- ceph osd pool ls 2>/dev/null) || exit 0
       |echo "$$OUT" | grep -qx $cephPool && exit 0
       |cephadm shell -- ceph auth get client.cinder >/dev/null 2>&1 && exit 0
       |exit 1""".stripMargin
  private val cephScript =
    s"""R=0
       |cephadm shell -- ceph config set mon mon_allow_pool_delete true || R=1
       |cephadm shell -- ceph osd pool delete $cephPool $cephPool --yes-i-really-really-mean-it || R=1
       |cephadm shell -- ceph config set mon mon_allow_pool_delete false || R=1
       |cephadm shell -- ceph auth del client.cinder || R=1
       |[ "$$R" = 0 ] && echo "pool $cephPool and client.cinder removed"
       |exit $$R""".stripMargin

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
    else if target.name == cephNode then
      List(
        command(
          s"delete the $cephPool pool and the client.cinder cephx user",
          cephScript,
          onlyIf = cephHasCinder,
          timeout = 180
        )
      )
    else
      val unregistered = command(
        "remove Cinder from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
