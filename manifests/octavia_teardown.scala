// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes octavia_database.scala, octavia_rabbitmq.scala and octavia_single_node.scala, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md): on tst19, through the helper if it is still there, deletes
// the amphora VMs (Nova servers named amphora-*), the health manager port, the network lb-mgmt-net, the two
// security groups, the flavor orphera.amphora and the amphora image, then removes Octavia from Keystone (service
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

  // /etc/kolla/octavia is where the config and the certificates are kept.
  private val directories =
    List(
      "/etc/kolla/octavia",
      "/etc/kolla/octavia-ca",
      "/var/lib/orphera/octavia",
      "/var/log/kolla/octavia"
    ) ++ units.map(u => s"/etc/kolla/$u")

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
          "remove the octavia vhost and user from RabbitMQ",
          rabbitVhost,
          rabbitUser
        )
      )
    else
      val openstack = command(
        "delete the amphorae, network, security groups, flavor and image",
        openstackScript,
        onlyIf = hasHelper,
        timeout = 900
      )
      val unregistered = absentKeystoneEntries("remove Octavia from Keystone", "octavia").after(openstack)
      val stopped = command(
        "stop and remove the octavia containers and units",
        stopScript,
        onlyIf = anyUnit,
        timeout = 240
      ).after(unregistered)
      val unplugged = absentOvsPort("remove the health manager port o-hm0 from br-int", "br-int", "o-hm0")
        .after(stopped)
      // The helper goes with the files: the first step above needed it, and runs before them.
      val cleaned =
        (directories.map(absentDirectory(_, recursive = true)) ++
          List(
            absentFile(helper),
            absentFile(keystoneHelperPath("octavia")),
            absentFile(openstackImageHelperPath("amphora-x64-haproxy-test"))
          )).map(_.after(unplugged))
      List(openstack, unregistered, stopped, unplugged) ++ cleaned
