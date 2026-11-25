// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes manila_database.scala, manila_rabbitmq.scala, manila_ceph.scala and manila_single_node.scala, as a
// declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst22 removes Manila from Keystone (the
// service of type `sharev2`, its endpoints and user `manila`) through the helper if it is still there, then the
// three containers, units, config, state, helper and Keystone CA copy; on tst10 removes the `manila` vhost and
// user from RabbitMQ (the broker itself and the other services' use of it are untouched); on tst3 drops the
// manila database and user; on tst0 deletes the cephx user client.manila. The share type and every share are
// in the database, so they go with it. podman, nfs-common and project `service` stay. Keep `node` in step with
// manila_single_node.
//
// The CephFS filesystem and the NFS cluster are KEPT, because shares made by Manila live in them (as
// subvolumes that nothing now tracks) and the filesystem may have other uses. Set MANILA_PURGE_CEPH=yes in
// the environment of the run to remove the NFS cluster and the filesystem too; that DESTROYS ALL DATA in the
// filesystem:
//   MANILA_PURGE_CEPH=yes orphera apply manifests/manila_teardown.scala
// The secrets (manila/db, manila/service, manila/rabbit, manila/ceph-key) stay in the secret store.
//
//   orphera apply manifests/manila_teardown.scala --check
//   orphera apply manifests/manila_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. A database or a Ceph cluster that cannot be queried fails the step instead of being
// skipped.
object manila_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst22"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val cephNode = "tst0"
  private val dbName = "manila"
  private val dbUser = "manila"
  private val rabbitVhost = "manila"
  private val rabbitUser = "manila"
  private val cephFs = "cephfs"
  private val nfsCluster = "manila"
  private val helper = "/usr/local/sbin/orphera-manila-api"
  private val mountDir = "/mnt/orphera-manila-test"
  private val units = List("manila-api", "manila-scheduler", "manila-share")
  private val purgeCeph = sys.env.get("MANILA_PURGE_CEPH").contains("yes")

  override def nodes: List[String] = List(dbNode, rabbitNode, cephNode, node)

  // ---- tst22 ----

  // /etc/kolla/manila is where the cluster playbook kept manila.conf and the Ceph client files; it is listed
  // so that a node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/manila",
    "/etc/kolla/manila-api",
    "/etc/kolla/manila-scheduler",
    "/etc/kolla/manila-share",
    "/etc/kolla/manila-ca",
    "/var/lib/orphera/manila",
    "/var/log/kolla/manila"
  )

  // ---- tst0 ----
  // True when `ceph auth ls` lists client.manila. When the cluster cannot be queried this is also true, so
  // that the removal runs and fails with Ceph's own message.
  private val clientExists =
    """OUT=$(cephadm shell -- ceph auth ls 2>/dev/null) || exit 0; echo "$OUT" | tr -d '\r' | grep -qx 'client.manila'"""
  private val clientScript =
    """cephadm shell -- ceph auth del client.manila
      |echo "cephx user client.manila removed"""".stripMargin

  private val nfsExists =
    s"""OUT=$$(cephadm shell -- ceph nfs cluster ls 2>/dev/null) || exit 0; echo "$$OUT" | tr -d '\\r' | grep -qw '$nfsCluster'"""
  private val nfsScript =
    s"""cephadm shell -- ceph nfs cluster rm $nfsCluster
       |echo "NFS cluster $nfsCluster removed"""".stripMargin

  private val fsExists =
    s"""OUT=$$(cephadm shell -- ceph fs ls 2>/dev/null) || exit 0; echo "$$OUT" | grep -q '^name: $cephFs,'"""
  private val fsScript =
    s"""cephadm shell -- ceph config set mon mon_allow_pool_delete true
       |cephadm shell -- ceph fs volume rm $cephFs --yes-i-really-mean-it
       |echo "filesystem $cephFs removed"""".stripMargin

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
          "remove the manila vhost and user from RabbitMQ",
          rabbitVhost,
          rabbitUser
        )
      )
    else if target.name == cephNode then
      val client = command(
        "remove the cephx user client.manila",
        clientScript,
        onlyIf = clientExists,
        timeout = 120
      )
      // CephFS and the NFS cluster are removed only when asked (MANILA_PURGE_CEPH=yes): shares live there.
      val purge: List[ApplyResource] =
        if purgeCeph then
          val nfs = command(
            s"remove the NFS cluster $nfsCluster (MANILA_PURGE_CEPH=yes)",
            nfsScript,
            onlyIf = nfsExists,
            timeout = 300
          )
          val fs = command(
            s"remove the filesystem $cephFs and all its data (MANILA_PURGE_CEPH=yes)",
            fsScript,
            onlyIf = fsExists,
            timeout = 600
          ).after(nfs)
          List(nfs, fs)
        else Nil
      client :: purge
    else
      val unregistered = absentKeystoneEntries("remove Manila from Keystone", "manila")
      // A test mount or its directory left by a failed share round trip: unmounted if mounted, no fstab entry
      // (none is made), then the directory removed if it is empty.
      val unmounted = absentMount(mountDir)
      val testDir = absentDirectory(mountDir).after(unmounted)
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered, testDir))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("manila"))).map(_.after(stopped*))
      List(unregistered, unmounted, testDir) ++ stopped ++ cleaned
