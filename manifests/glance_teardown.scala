// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes glance_database.scala, glance_ceph.scala and glance_single_node.scala,
// as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst13
// removes Glance from Keystone (service `image`, its endpoints, user `glance`)
// through the helper if it is still there, then the container, unit, config,
// state, helper and Keystone CA; on tst3 drops the glance database and user; on
// tst0 deletes the `images` pool (and every image in it) and the client.glance
// cephx user. podman stays installed; project `service` stays (Cinder uses it).
//
//   orphera apply manifests/glance_teardown.scala --check
//   orphera apply manifests/glance_teardown.scala
//
// Deleting a pool needs mon_allow_pool_delete; it is turned on for the
// duration and off again. Each step is a command that runs only while there is
// something to remove (`onlyIf`), so a second run changes nothing. Keep the
// names in step with the descriptions it undoes.
object glance_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val dbNode = "tst3"
  private val cephNode = "tst0"
  private val dbName = "glance"
  private val dbUser = "glance"
  private val cephPool = "images"
  private val unit = "orphera-glance-api"
  private val image = "quay.io/openstack.kolla/glance-api:2025.1-ubuntu-noble"
  private val helper = "/usr/local/sbin/orphera-glance-api"

  override def nodes: List[String] = List(cephNode, dbNode, node)

  // ---- tst13 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  private val dirs = List(
    "/etc/kolla/glance",
    "/etc/kolla/glance-api",
    "/etc/kolla/glance-ca",
    "/var/lib/orphera/glance",
    "/var/log/kolla/glance"
  )

  private val imageExists =
    s"command -v podman >/dev/null 2>&1 && podman image exists $image"

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or user exists. When MariaDB cannot be queried this is
  // also true, so that the drop runs and fails with MariaDB's own message: a
  // database that is down is not "nothing to remove".
  private val databaseExists =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

  // ---- tst0 ----
  private val cephScript =
    s"""cephadm shell -- ceph config set mon mon_allow_pool_delete true
       |cephadm shell -- ceph osd pool delete $cephPool $cephPool --yes-i-really-really-mean-it || true
       |cephadm shell -- ceph config set mon mon_allow_pool_delete false
       |cephadm shell -- ceph auth del client.glance || true
       |echo "pool $cephPool and client.glance removed"""".stripMargin
  private val cephExists =
    s"""cephadm shell -- ceph osd pool ls 2>/dev/null | grep -qx $cephPool || cephadm shell -- ceph auth get client.glance >/dev/null 2>&1"""

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
          s"delete the $cephPool pool and client.glance",
          cephScript,
          onlyIf = cephExists,
          timeout = 180
        )
      )
    else
      val unregistered = command(
        "remove Glance from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(List(unit -> "glance-api")).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      val removedImage = command(
        "remove the glance-api image",
        s"podman rmi -f $image",
        onlyIf = imageExists,
        timeout = 120
      ).after(stopped*)
      List(unregistered) ++ stopped ++ cleaned ++ List(removedImage)
