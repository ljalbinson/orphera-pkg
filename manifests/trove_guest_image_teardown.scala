// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes trove_datastore.scala and trove_guest_image.scala, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): on tst25 deletes the test database instance if one is left and removes the database
// helper; on tst3 removes the mysql datastore, its versions and their configuration parameters from the trove
// database on Galera; on tst21 deletes the guest image from Glance and the flavor orphera.trove and removes the
// image and flavor helpers.
//
// Instances you made yourself are NOT removed: delete them first (`openstack database instance delete`), or
// they keep running with no datastore behind them.
//
// The built image (/var/lib/orphera/trove-image), the diskimage-builder virtualenv and cache and the Trove
// source on tst21 stay, because building takes a long time; TROVE_REMOVE_BUILD=1 removes them too.
// Keep the names in step with trove_guest_image and trove_datastore.
//
//   orphera apply manifests/trove_guest_image_teardown.scala --check
//   orphera apply manifests/trove_guest_image_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database that cannot be queried fails the step instead of being skipped.
object trove_guest_image_teardown extends OrpheraApply:
  import Resources.*

  private val buildNode = "tst21"
  private val troveNode = "tst25"
  private val dbNode = "tst3"
  private val removeBuild = sys.env.get("TROVE_REMOVE_BUILD").contains("1")

  override def nodes: List[String] = List(dbNode, troveNode, buildNode)

  // ---- tst25 ----
  private val dbHelper = "/usr/local/sbin/orphera-trove-db"
  private val registerScriptPath =
    "/var/lib/orphera/trove/register-datastore.sh"
  private val cleanupInstance =
    s"""$dbHelper instance-cleanup || echo "instance cleanup failed (continuing)""""

  // ---- tst3 ----
  // Deletes the datastore rows in dependency order. The tables are those of Trove's schema.
  private val datastoreSql =
    "SET @ds := (SELECT id FROM trove.datastores WHERE name='mysql'); " +
      "DELETE FROM trove.datastore_configuration_parameters WHERE datastore_version_id IN (SELECT id FROM trove.datastore_versions WHERE datastore_id=@ds); " +
      "DELETE FROM trove.datastore_version_metadata WHERE datastore_version_id IN (SELECT id FROM trove.datastore_versions WHERE datastore_id=@ds); " +
      "UPDATE trove.datastores SET default_version_id=NULL WHERE id=@ds; " +
      "DELETE FROM trove.datastore_versions WHERE datastore_id=@ds; " +
      "DELETE FROM trove.datastores WHERE id=@ds;"
  private val dropDatastoreScript =
    s"""mariadb -N -e "$datastoreSql"
       |echo "mysql datastore removed from the trove database"""".stripMargin
  // True when the trove database has the datastore mysql. No trove database means nothing to remove; a
  // MariaDB that cannot be queried makes this true, so that the removal runs and fails with MariaDB's own
  // message.
  private val datastoreExists =
    s"""DBS=$$(mariadb -N -e "SHOW DATABASES" 2>/dev/null) || exit 0
       |printf '%s\\n' "$$DBS" | grep -qx trove || exit 1
       |OUT=$$(mariadb -N -e "SELECT 1 FROM trove.datastores WHERE name='mysql'" 2>/dev/null) || exit 0
       |[ -n "$$OUT" ]""".stripMargin

  // ---- tst21 ----
  private val imageName = "trove-guest-ubuntu-noble"
  private val flavorId = "orphera-trove"
  // The helper that older descriptions kept here, removed too.
  private val legacyHelper = "/usr/local/sbin/orphera-trove-image"
  private val buildDirectories =
    List(
      "/var/lib/orphera/trove-image",
      "/opt/diskimage-builder-env",
      "/var/cache/orphera-trove-dib",
      "/opt/trove-src"
    )

  def describe(target: Node): List[ApplyResource] =
    if target.name == dbNode then
      List(
        command(
          "remove the mysql datastore from the trove database",
          dropDatastoreScript,
          onlyIf = datastoreExists
        )
      )
    else if target.name == troveNode then
      val instance = command(
        "delete the test instance",
        cleanupInstance,
        onlyIf = s"[ -x $dbHelper ]",
        timeout = 1200
      )
      val files = List(absentFile(dbHelper), absentFile(registerScriptPath))
        .map(_.after(instance))
      instance :: files
    else
      val image = absentOpenstackImage("delete the guest image from Glance", imageName)
      val flavor = absentOpenstackFlavor("delete the flavor orphera.trove", flavorId).after(image)
      val glance = List(image, flavor)
      val helper = List(
        absentFile(openstackFlavorHelperPath(flavorId)),
        absentFile(openstackImageHelperPath(imageName)),
        absentFile(legacyHelper),
        absentFile("/var/lib/orphera/trove-image/ca.crt")
      ).map(_.after(glance*))
      if removeBuild then
        // The built image, the build environment and the Trove source (TROVE_REMOVE_BUILD=1).
        glance ++ helper ++ buildDirectories.map(d =>
          absentDirectory(d, recursive = true).after(helper*)
        )
      else glance ++ helper
