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
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing.
object rgw_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst0"
  private val serviceId = "orphera"
  private val helper = "/usr/local/sbin/orphera-rgw-api"
  private val purgePools = sys.env.get("RGW_PURGE_POOLS").contains("yes")

  override def nodes: List[String] = List(node)

  private val serviceListed =
    s"cephadm shell -- ceph orch ls rgw 2>/dev/null | grep -q 'rgw.$serviceId'"
  private val optionsSet =
    s"""cephadm shell -- ceph config dump 2>/dev/null | awk '$$1 == "client.rgw.$serviceId"' | grep -q ."""
  private val paths =
    s"/var/lib/orphera/rgw-applied-sha /var/lib/orphera/rgw-keystone-state $helper /etc/orphera/rgw-ca"
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  // True when the service, any of its options or any of its files is still there.
  private val somethingToRemove =
    s"""$serviceListed && exit 0
       |$optionsSet && exit 0
       |$filesExist""".stripMargin
  private val removeScript =
    s"""set -e
       |if $serviceListed; then
       |  cephadm shell -- ceph orch rm rgw.$serviceId
       |fi
       |for o in $$(cephadm shell -- ceph config dump 2>/dev/null | awk '$$1 == "client.rgw.$serviceId" {for (i = 2; i <= NF; i++) if ($$i ~ /^rgw_/) { print $$i; break }}'); do
       |  cephadm shell -- ceph config rm client.rgw.$serviceId $$o
       |done
       |rm -rf $paths
       |echo "rgw service, options, helper and CA copy removed"""".stripMargin

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
    val removed = command(
      "remove the RGW service, its options, the helpers and the CA copy",
      removeScript,
      onlyIf = somethingToRemove,
      timeout = 600
    ).after(unregistered)
    val keystoneHelperGone = absentFile(keystoneHelperPath("rgw")).after(unregistered)
    if purgePools then
      val purged = command(
        "delete the RGW pools and every object in them (RGW_PURGE_POOLS=yes)",
        purgeScript,
        onlyIf = poolsExist,
        timeout = 600
      ).after(removed)
      List(unregistered, keystoneHelperGone, removed, purged)
    else List(unregistered, keystoneHelperGone, removed)
