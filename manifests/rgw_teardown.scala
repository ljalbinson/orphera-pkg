// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes rgw_single_node.scala: removes object storage from Keystone (the services of type
// object-store and s3, their endpoints and user rgw) through the helper installed by the main playbook if it
// is still there, removes the cephadm service rgw.orphera (the RGW daemon stops), clears its options from the
// monitors' configuration database, and removes the helper, the Keystone CA copy and the applied-hash file on
// tst0. The CA that was added to the host's trust store stays (other playbooks use the same file name).
//
// The pools that RGW made (.rgw.root, default.rgw.*) and everything stored in them are KEPT, so a later
// rgw_single_node.scala finds its data again. Set RGW_PURGE_POOLS=yes in the environment of the run to delete
// the pools too; that DESTROYS ALL OBJECTS AND BUCKETS:
//   RGW_PURGE_POOLS=yes orphera cluster-playbook rgw_teardown.scala
// The secret rgw/service stays in the secret store. Keep node and the names in step with rgw_single_node.
// Safe to re-run when nothing is installed.
object rgw_teardown extends OrpheraClusterPlaybook:

  private val node = "tst0"
  private val serviceId = "orphera"
  private val purgePools = sys.env.get("RGW_PURGE_POOLS").contains("yes")

  private val keystoneScript =
    """if [ -x /usr/local/sbin/orphera-rgw-api ]; then
      |  /usr/local/sbin/orphera-rgw-api unregister || echo "keystone cleanup failed (continuing)"
      |fi
      |echo "keystone cleanup done"""".stripMargin

  private val cephScript: String =
    val common =
      s"""if cephadm shell -- ceph orch ls rgw 2>/dev/null | grep -q 'rgw.$serviceId'; then
         |  cephadm shell -- ceph orch rm rgw.$serviceId
         |fi
         |for o in $$(cephadm shell -- ceph config dump 2>/dev/null | awk '$$1 == "client.rgw.$serviceId" {print $$2}'); do
         |  cephadm shell -- ceph config rm client.rgw.$serviceId $$o
         |done
         |rm -f /var/lib/orphera/rgw-applied-sha /usr/local/sbin/orphera-rgw-api
         |rm -rf /etc/orphera/rgw-ca
         |echo "rgw service and options removed"""".stripMargin
    if purgePools then
      s"""$common
         |cephadm shell -- ceph config set mon mon_allow_pool_delete true
         |for p in $$(cephadm shell -- ceph osd pool ls 2>/dev/null | grep -E '^(\\.rgw\\.root|default\\.rgw\\.)'); do
         |  cephadm shell -- ceph osd pool rm $$p $$p --yes-i-really-really-mean-it
         |done
         |echo "RGW pools removed (RGW_PURGE_POOLS=yes)"""".stripMargin
    else
      s"""$common
         |echo "RGW pools and their data kept (RGW_PURGE_POOLS=yes deletes them)"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("rgw-teardown")(
      stage("teardown-rgw", node)
        .task("unregister from Keystone")(
          Task.RunCommand(
            List("sh", "-c", keystoneScript),
            timeoutSeconds = 120
          )
        )
        .task("remove the RGW service, its options and the helper")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 600)
        )
        .build
    )
