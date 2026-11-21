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
  // The helper removes the Keystone entries; it is there until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  // A test mount or its directory left by a failed share round trip.
  private val hasTestMount = s"[ -d $mountDir ]"
  private val unmountScript =
    s"""if mountpoint -q $mountDir 2>/dev/null; then umount $mountDir || true; fi
       |rmdir $mountDir 2>/dev/null || true
       |echo "test mount removed"""".stripMargin

  private val unitList = units.mkString(" ")
  // True when ANY of the units has a unit file, or is active or enabled.
  private val anyUnit =
    s"""for u in $unitList; do if [ -f /etc/systemd/system/orphera-$$u.service ] || systemctl is-active --quiet orphera-$$u || systemctl is-enabled --quiet orphera-$$u; then exit 0; fi; done; exit 1"""
  private val stopScript =
    s"""for u in $unitList; do
       |  systemctl disable --now orphera-$$u 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$u.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$u 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |echo "manila containers and units removed"""".stripMargin

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

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running broker there is nothing to
  // remove.
  private val rabbitHasManila =
    s"""$rabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost' || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; }"""
  private val rabbitScript =
    s"""podman exec rabbitmq rabbitmqctl delete_vhost '$rabbitVhost' || true
       |podman exec rabbitmq rabbitmqctl delete_user '$rabbitUser' || true
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser removed"""".stripMargin

  // ---- tst3 ----
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""
  // True when the database or the user exists. When MariaDB cannot be queried this is also true, so that the
  // drop runs and fails with MariaDB's own message: a database that is down is not "nothing to remove".
  private val databaseExists =
    s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$dbName' UNION SELECT 1 FROM mysql.user WHERE User='$dbUser'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""

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
        command(
          s"drop the $dbName database and the $dbUser user on the Galera cluster",
          dropDatabaseScript,
          onlyIf = databaseExists
        )
      )
    else if target.name == rabbitNode then
      List(
        command(
          "remove the manila vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasManila,
          timeout = 60
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
      val unregistered = command(
        "remove Manila from Keystone",
        unregisterScript,
        onlyIf = hasHelper,
        timeout = 120
      )
      val unmounted = command(
        "remove a test mount left by a share round trip",
        unmountScript,
        onlyIf = hasTestMount,
        timeout = 120
      )
      val stopped = command(
        "stop and remove the manila containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered, unmounted)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped))
      List(unregistered, unmounted, stopped) ++ cleaned
