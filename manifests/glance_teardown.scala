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
  private val imagesPool = "images"
  private val unit = "orphera-glance-api"
  private val image = "quay.io/openstack.kolla/glance-api:2025.1-ubuntu-noble"
  private val helper = "/usr/local/sbin/orphera-glance-api"

  override def nodes: List[String] = List(cephNode, dbNode, node)

  // ---- tst13 ----

  private val dirs = List(
    "/etc/kolla/glance",
    "/etc/kolla/glance-api",
    "/etc/kolla/glance-ca",
    "/var/lib/orphera/glance",
    "/var/log/kolla/glance"
  )

  private val imageExists =
    s"command -v podman >/dev/null 2>&1 && podman image exists $image"

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
      val user =
        absentCephUser("remove the client.glance cephx user", "client.glance")
      val pool =
        absentCephPool(s"delete the $imagesPool pool", imagesPool).after(user)
      List(user, pool)
    else
      val unregistered =
        absentKeystoneEntries("remove Glance from Keystone", "glance")
      val stopped = absentContainerUnits(List(unit -> "glance-api"))
        .map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("glance")))
        .map(_.after(stopped*))
      val removedImage = command(
        "remove the glance-api image",
        s"podman rmi -f $image",
        onlyIf = imageExists,
        timeout = 120
      ).after(stopped*)
      List(unregistered) ++ stopped ++ cleaned ++ List(removedImage)
