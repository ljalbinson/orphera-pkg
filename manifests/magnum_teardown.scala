// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes magnum_database.scala, magnum_rabbitmq.scala and magnum_single_node.scala, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md): on tst23, through the helper if it is still there, deletes every Magnum
// cluster (and waits for them to go), the cluster template, the flavor orphera.k8s, the key pair, the Kubernetes
// node image, and removes Magnum from Keystone (service type container-infra, its endpoints, user magnum, domain
// magnum); then the two containers, their units, the config, state (with the downloaded image file and the
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
  private val units = List("magnum-api", "magnum-conductor")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst23 ----
  // The helper deletes the clusters, template, flavor, key pair, image and the Keystone entries; it is there
  // until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val openstackScript =
    s"""$helper teardown || echo "openstack cleanup failed (continuing)""""

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
       |echo "magnum containers and units removed"""".stripMargin

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

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running broker there is nothing to remove.
  private val rabbitHasMagnum =
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
          "remove the magnum vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasMagnum,
          timeout = 60
        )
      )
    else
      val openstack = command(
        "delete the clusters, template, flavor, key pair, image and the Keystone entries",
        openstackScript,
        onlyIf = hasHelper,
        timeout = 3600
      )
      val stopped = command(
        "stop and remove the magnum containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(openstack)
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped))
      List(openstack, stopped) ++ cleaned
