// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes designate_database.scala, designate_rabbitmq.scala and designate_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst16
// removes Designate from Keystone (the service of type `dns`, its endpoints and
// user `designate`) through the helper if it is still there, then the six
// containers (the five Designate services and BIND9), units, config, state
// (including the zones BIND9 held and the rndc key), helper and Keystone CA copy;
// on tst10 removes the `designate` vhost and user from RabbitMQ (the broker itself
// and the other services' use of it are untouched); on tst3 drops the designate
// database and user. podman, bind9-dnsutils, the images and project `service`
// stay. Every zone and record is destroyed with the database. Keep `node` in step
// with designate_single_node.
//
//   orphera apply manifests/designate_teardown.scala --check
//   orphera apply manifests/designate_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object designate_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst16"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "designate"
  private val dbUser = "designate"
  private val helper = "/usr/local/sbin/orphera-designate-api"
  private val units = List(
    "designate-worker",
    "designate-producer",
    "designate-api",
    "designate-mdns",
    "designate-central",
    "designate-backend-bind9"
  )

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst16 ----

  // /etc/kolla/designate is where the cluster playbook kept designate.conf, pools.yaml, named.conf and the
  // rndc key; it is listed so that a node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/designate",
    "/etc/kolla/designate-central",
    "/etc/kolla/designate-mdns",
    "/etc/kolla/designate-api",
    "/etc/kolla/designate-producer",
    "/etc/kolla/designate-worker",
    "/etc/kolla/designate-backend-bind9",
    "/etc/kolla/designate-ca",
    "/var/lib/orphera/designate",
    "/var/log/kolla/designate"
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
          "remove the designate vhost and user from RabbitMQ",
          "designate",
          "designate"
        )
      )
    else
      val unregistered =
        absentKeystoneEntries("remove Designate from Keystone", "designate")
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u))
        .map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper) :+ absentFile(keystoneHelperPath("designate")))
        .map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
