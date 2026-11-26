// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes rgw_single_node.scala, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): removes
// object storage from Keystone (the services of type object-store and s3, their endpoints and user rgw)
// through the helper that keystoneEntries installed if it is still there, removes the cephadm service
// rgw.orphera (the RGW daemon stops), clears its options from the monitors' configuration database, and
// removes the helpers, the Keystone CA copy and the applied-hash file on tst0 (and the state file that older
// descriptions kept). The CA that was added to the
// host's trust store stays (other descriptions use the same file name).
//
// The pools that RGW made (.rgw.root, default.rgw.*) and everything stored in them are KEPT, so a later
// rgw_single_node.scala finds its data again. Set RGW_PURGE_POOLS=yes in the environment of the run to delete
// the pools too; that DESTROYS ALL OBJECTS AND BUCKETS:
//   RGW_PURGE_POOLS=yes orphera apply manifests/rgw_teardown.scala
// The secret rgw/service stays in the secret store. Keep node and the names in step with rgw_single_node.
//
//   orphera apply manifests/rgw_teardown.scala --check
//   orphera apply manifests/rgw_teardown.scala
//
// The options are found by the name that starts with rgw_ in the row of `ceph config dump`: with no mask the
// second column is the level (advanced, basic), not the option, so taking column 2 removed nothing and every
// run found the options still there.
//
// Each step runs only while there is something to remove, so a second run
// changes nothing.
object rgw_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst0"
  private val serviceId = "orphera"
  private val helper = "/usr/local/sbin/orphera-rgw-api"
  private val purgePools = sys.env.get("RGW_PURGE_POOLS").contains("yes")

  override def nodes: List[String] = List(node)

  // Files and directories of this and earlier descriptions: the hash of the old step, the state file the
  // cluster playbook kept, the helper and the CA copy.
  private val oldPaths =
    List("/var/lib/orphera/rgw-applied-sha", "/var/lib/orphera/rgw-keystone-state", helper)

  private val poolsExist =
    """cephadm shell -- ceph osd pool ls 2>/dev/null | grep -qE '^(\.rgw\.root|default\.rgw\.)'"""
  private val purgeScript =
    """set -e
      |cephadm shell -- ceph config set mon mon_allow_pool_delete true
      |for p in $(cephadm shell -- ceph osd pool ls 2>/dev/null | grep -E '^(\.rgw\.root|default\.rgw\.)'); do
      |  cephadm shell -- ceph osd pool rm $p $p --yes-i-really-really-mean-it
      |done
      |echo "RGW pools removed (RGW_PURGE_POOLS=yes)"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val unregistered = absentKeystoneEntries("remove object storage from Keystone", "rgw")
    val service = absentCephOrchService("remove the RGW service", "rgw", serviceId).after(unregistered)
    val options = absentCephConfig(
      "remove the RGW options from the monitors' configuration",
      s"client.rgw.$serviceId",
      "rgw_"
    ).after(service)
    val files = oldPaths.map(absentFile(_).after(service))
    val caCopy = absentDirectory("/etc/orphera/rgw-ca", recursive = true).after(service)
    val removed = options :: caCopy :: files
    val keystoneHelperGone = absentFile(keystoneHelperPath("rgw")).after(unregistered)
    if purgePools then
      val purged = command(
        "delete the RGW pools and every object in them (RGW_PURGE_POOLS=yes)",
        purgeScript,
        onlyIf = poolsExist,
        timeout = 600
      ).after(options)
      List(unregistered, keystoneHelperGone, service) ++ removed ++ List(purged)
    else List(unregistered, keystoneHelperGone, service) ++ removed
