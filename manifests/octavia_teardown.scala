// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes octavia_database.scala, octavia_rabbitmq.scala and octavia_single_node.scala, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md): on tst19, through the helper if it is still there, deletes
// the amphora VMs (Nova servers named amphora-*), the health manager port, the network lb-mgmt-net, the two
// security groups, the flavor orphera.amphora and the amphora image, and removes Octavia from Keystone (service
// type load-balancer, its endpoints, user octavia). Then it stops and removes the four containers, their units
// and the o-hm0 unit, deletes the o-hm0 port from br-int, and removes the config (with the certificates), the
// state (with the downloaded image file) and the helper. On tst10 it removes the `octavia` vhost and user from
// RabbitMQ (the broker itself and the other services' use of it are untouched); on tst3 it drops the octavia
// database and user. Load balancers are in the database, so they go with it; run it only when none is needed.
// podman and the other packages stay. The secrets (octavia/db, octavia/service, octavia/rabbit,
// octavia/ca-passphrase, octavia/heartbeat-key) stay in the secret store. Keep `node` and the names in step
// with octavia_single_node.
//
//   orphera apply manifests/octavia_teardown.scala --check
//   orphera apply manifests/octavia_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run changes
// nothing. A database that cannot be queried fails the step instead of being skipped.
object octavia_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst19"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "octavia"
  private val dbUser = "octavia"
  private val rabbitVhost = "octavia"
  private val rabbitUser = "octavia"
  private val helper = "/usr/local/sbin/orphera-octavia-api"
  private val units =
    List(
      "octavia-api",
      "octavia-worker",
      "octavia-health-manager",
      "octavia-housekeeping"
    )

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst19 ----
  // The helper removes the OpenStack side; it is there until the last step removes it.
  private val hasHelper = s"[ -x $helper ]"
  private val openstackScript =
    s"""$helper teardown || echo "openstack cleanup failed (continuing)""""

  private val unitList = (units :+ "octavia-hm-port").mkString(" ")
  // True when ANY of the units has a unit file, or is active or enabled.
  private val anyUnit =
    s"""for u in $unitList; do if [ -f /etc/systemd/system/orphera-$$u.service ] || systemctl is-active --quiet orphera-$$u || systemctl is-enabled --quiet orphera-$$u; then exit 0; fi; done; exit 1"""
  private val stopScript =
    s"""for u in $unitList; do
       |  systemctl disable --now orphera-$$u 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$u.service
       |done
       |for u in ${units.mkString(" ")}; do
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$u 2>/dev/null || true; fi
       |done
       |rm -f /usr/local/sbin/orphera-octavia-hm-port
       |systemctl daemon-reload
       |echo "octavia containers and units removed"""".stripMargin

  // True when o-hm0 is a port of br-int.
  private val hasHmPort =
    "command -v ovs-vsctl >/dev/null 2>&1 && ovs-vsctl list-ports br-int 2>/dev/null | grep -qx o-hm0"
  private val unplugScript =
    """ovs-vsctl --if-exists del-port br-int o-hm0
      |echo "o-hm0 removed from br-int"""".stripMargin

  // /etc/kolla/octavia is where the config and the certificates are kept.
  private val paths =
    (List(
      "/etc/kolla/octavia",
      "/etc/kolla/octavia-ca",
      "/var/lib/orphera/octavia",
      "/var/log/kolla/octavia",
      helper
    ) ++
      units.map(u => s"/etc/kolla/$u")).mkString(" ")
  // True when ANY of them exists.
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  private val cleanupScript =
    s"""rm -rf $paths
       |echo "octavia config, certificates, state and helper removed"""".stripMargin

  // ---- tst10 ----
  private val rabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."
  // True when the vhost or user exists in a running broker; with no running broker there is nothing to remove.
  private val rabbitHasOctavia =
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
          "remove the octavia vhost and user from RabbitMQ",
          rabbitScript,
          onlyIf = rabbitHasOctavia,
          timeout = 60
        )
      )
    else
      val openstack = command(
        "delete the amphorae, network, security groups, flavor, image and the Keystone entries",
        openstackScript,
        onlyIf = hasHelper,
        timeout = 900
      )
      val stopped = command(
        "stop and remove the octavia containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(openstack)
      val unplugged = command(
        "remove the health manager port o-hm0 from br-int",
        unplugScript,
        onlyIf = hasHmPort,
        timeout = 60
      ).after(stopped)
      val cleaned = command(
        "remove octavia files, certificates, state and the helper",
        cleanupScript,
        onlyIf = filesExist,
        timeout = 120
      ).after(unplugged)
      List(openstack, stopped, unplugged, cleaned)
