// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes magnum_database.scala, magnum_rabbitmq.scala and magnum_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst23, through the helper if it is still there, deletes every Magnum
// cluster (and waits for them to go), the cluster template and the Kubernetes node image through it, the flavor
// orphera.k8s and the key pair through their own helpers, then removes Magnum from Keystone (service type
// container-infra, its endpoints, user magnum, domain magnum); then the two containers, their units, the config, state (with the downloaded image file and the
// installed driver) and the helper. On tst10 it removes the `magnum` vhost and user from RabbitMQ (the broker
// itself and the other services' use of it are untouched); on tst3 it drops the magnum database and user.
// The management cluster (capi_management_cluster.scala) is left alone; run capi_management_cluster_teardown
// separately. podman, helm, the trusted test CA and the other packages stay. The secrets (magnum/*) stay in the
// secret store. Keep `node` and the names in step with magnum_single_node.
//
//   orphera apply manifests/magnum_teardown.scala --check
//   orphera apply manifests/magnum_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run changes
// nothing. A database that cannot be queried fails the step instead of being skipped. Deleting the clusters can
// take a long time (a step waits up to an hour).
object magnum_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst23"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "magnum"
  private val dbUser = "magnum"
  private val rabbitVhost = "magnum"
  private val rabbitUser = "magnum"
  private val helper = "/usr/local/sbin/orphera-magnum-api"
  private val flavorId = "orphera-k8s"
  private val keypair = "orphera-magnum"
  private val units = List("magnum-api", "magnum-conductor")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst23 ----
  // The helper deletes the clusters, template and image; it is there until the last step removes it. The flavor
  // and the key pair have helpers of their own (openstackFlavor, openstackKeypair). The Keystone entries are
  // removed by their own helper after them.
  private val hasHelper = s"[ -x $helper ]"
  private val openstackScript =
    s"""$helper teardown || echo "openstack cleanup failed (continuing)""""

  // /etc/kolla/magnum is where the cluster playbook kept magnum.conf and the kubeconfig; it is listed so that a
  // node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/magnum",
    "/etc/kolla/magnum-api",
    "/etc/kolla/magnum-conductor",
    "/etc/kolla/magnum-ca",
    "/var/lib/orphera/magnum",
    "/var/log/kolla/magnum"
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
    else if target.name == rabbitNode then
      List(
        absentRabbitAccount(
          "remove the magnum vhost and user from RabbitMQ",
          rabbitVhost,
          rabbitUser
        )
      )
    else
      val openstack = command(
        "delete the clusters, template and image",
        openstackScript,
        onlyIf = hasHelper,
        timeout = 3600
      )
      val flavorGone = absentOpenstackFlavor("delete the Kubernetes node flavor", flavorId).after(openstack)
      val keypairGone = absentOpenstackKeypair("delete the Kubernetes node key pair", keypair).after(openstack)
      val unregistered =
        absentKeystoneEntries("remove Magnum from Keystone", "magnum").after(openstack, flavorGone, keypairGone)
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) ++
        List(
          absentFile(helper),
          absentFile(keystoneHelperPath("magnum")),
          absentFile(openstackFlavorHelperPath(flavorId)),
          absentFile(openstackKeypairHelperPath(keypair))
        )).map(_.after(stopped*))
      List(openstack, flavorGone, keypairGone, unregistered) ++ stopped ++ cleaned
