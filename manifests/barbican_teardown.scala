// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes barbican_database.scala, barbican_rabbitmq.scala and barbican_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst20
// removes Barbican from Keystone (the service of type key-manager, its endpoints
// and user barbican) through the helper if it is still there, then the two
// containers, units, config, state, helper and Keystone CA copy; on tst10
// removes the `barbican` vhost and user from RabbitMQ (the broker itself and the
// other services' use of it are untouched); on tst3 drops the barbican database
// and user. The roles creator, observer and audit stay, as do podman, the images,
// project `service` and Heat on the same node. EVERY SECRET STORED IN BARBICAN IS
// DESTROYED with the database; the key-encrypting key (secret barbican/kek) stays
// in the secret store. Keep `node` in step with barbican_single_node.
//
//   orphera apply manifests/barbican_teardown.scala --check
//   orphera apply manifests/barbican_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object barbican_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "barbican"
  private val dbUser = "barbican"
  private val helper = "/usr/local/sbin/orphera-barbican-api"
  private val units = List("barbican-api", "barbican-worker")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst20 ----
  private val registeredInKeystone =
    s"[ -x $helper ] && $helper registered >/dev/null 2>&1"
  private val unregisterScript =
    s"""$helper unregister || echo "keystone cleanup failed (continuing)""""

  // /etc/kolla/barbican is where the cluster playbook kept barbican.conf; it is listed
  // so that a node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/barbican",
    "/etc/kolla/barbican-api",
    "/etc/kolla/barbican-worker",
    "/etc/kolla/barbican-ca",
    "/var/lib/orphera/barbican",
    "/var/log/kolla/barbican"
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
          "remove the barbican vhost and user from RabbitMQ",
          "barbican",
          "barbican"
        )
      )
    else
      val unregistered = command(
        "remove Barbican from Keystone",
        unregisterScript,
        onlyIf = registeredInKeystone,
        timeout = 120
      )
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u)).map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
        absentFile(helper)).map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
