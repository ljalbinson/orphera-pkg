// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes trove_guest_image.scala: deletes the test database instance if one is left, removes the mysql
// datastore, its versions and their configuration parameters from the trove database on Galera, deletes the
// guest image from Glance and the flavor orphera.trove, and removes the two helpers.
//
// Instances you made yourself are NOT removed: delete them first (`openstack database instance delete`), or
// they keep running with no datastore behind them.
//
// The built image (/var/lib/orphera/trove-image), the diskimage-builder virtualenv and cache and the Trove
// source on tst21 stay, because building takes a long time; TROVE_REMOVE_BUILD=1 removes them too.
// Keep the names in step with trove_guest_image. Safe to re-run when nothing is installed.
object trove_guest_image_teardown extends OrpheraClusterPlaybook:

  private val buildNode = "tst21"
  private val troveNode = "tst25"
  private val removeBuild = sys.env.get("TROVE_REMOVE_BUILD").contains("1")

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-trove-db ]; then
      |  /usr/local/sbin/orphera-trove-db instance-cleanup || echo "instance cleanup failed (continuing)"
      |fi
      |rm -f /usr/local/sbin/orphera-trove-db /var/lib/orphera/trove/register-datastore.sh
      |echo "test instance removed, database helper removed"""".stripMargin

  // Deletes the datastore rows in dependency order. The tables are those of Trove's schema.
  private val datastoreSql =
    "SET @ds := (SELECT id FROM trove.datastores WHERE name='mysql'); " +
      "DELETE FROM trove.datastore_configuration_parameters WHERE datastore_version_id IN (SELECT id FROM trove.datastore_versions WHERE datastore_id=@ds); " +
      "DELETE FROM trove.datastore_version_metadata WHERE datastore_version_id IN (SELECT id FROM trove.datastore_versions WHERE datastore_id=@ds); " +
      "UPDATE trove.datastores SET default_version_id=NULL WHERE id=@ds; " +
      "DELETE FROM trove.datastore_versions WHERE datastore_id=@ds; " +
      "DELETE FROM trove.datastores WHERE id=@ds;"

  private val dropDatastoreScript =
    s"""if mariadb -N -e "SHOW DATABASES" | grep -qx trove; then
       |  mariadb -N -e "$datastoreSql"
       |  echo "mysql datastore removed from the trove database"
       |else
       |  echo "no trove database; nothing to remove"
       |fi""".stripMargin

  private val buildCleanup =
    if removeBuild then
      "rm -rf /var/lib/orphera/trove-image /opt/diskimage-builder-env /var/cache/orphera-trove-dib /opt/trove-src; echo build files removed"
    else
      "rm -f /var/lib/orphera/trove-image/ca.crt; echo 'the built image and the build environment are kept (TROVE_REMOVE_BUILD=1 removes them)'"

  private val buildScript =
    s"""if [ -x /usr/local/sbin/orphera-trove-image ]; then
       |  /usr/local/sbin/orphera-trove-image remove || echo "Glance cleanup failed (continuing)"
       |fi
       |rm -f /usr/local/sbin/orphera-trove-image
       |$buildCleanup""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("trove-guest-image-teardown")(
      stage("remove-trove-test-instance", troveNode)
        .task("delete the test instance and the database helper")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 1200)
        )
        .build,

      stage("remove-trove-datastore", "tst3")
        .task("remove the mysql datastore from the trove database")(
          Task.RunCommand(List("sh", "-c", dropDatastoreScript))
        )
        .build,

      stage("remove-trove-guest-image", buildNode)
        .task("delete the guest image and the flavor, remove the helper")(
          Task.RunCommand(List("sh", "-c", buildScript), timeoutSeconds = 300)
        )
        .build
    )
