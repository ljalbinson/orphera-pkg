// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes heat_database.scala, heat_rabbitmq.scala and heat_single_node.scala, as
// a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on tst20
// removes Heat from Keystone (the services `orchestration` and `cloudformation`,
// their endpoints, user `heat`, and the stack user domain `heat` with every user
// and project Heat made in it) through the Keystone helper if it is still there, then the
// three containers, units, config, state, helpers and Keystone CA copy; on tst10
// removes the `heat` vhost and user from RabbitMQ (the broker itself and the
// other services' use of it are untouched); on tst3 drops the heat database and
// user. The roles `heat_stack_owner` and `heat_stack_user` stay, as do podman,
// the images and project `service`. Stacks that still exist are not deleted
// first: delete them with `openstack stack delete` before this, or the servers
// and networks they made stay behind. Keep `node` in step with heat_single_node.
//
//   orphera apply manifests/heat_teardown.scala --check
//   orphera apply manifests/heat_teardown.scala
//
// Each step is a command that runs only while there is something to remove
// (`onlyIf`), so a second run changes nothing. A database that cannot be queried
// fails the step instead of being skipped.
object heat_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val rabbitNode = "tst10"
  private val dbNode = "tst3"
  private val dbName = "heat"
  private val dbUser = "heat"
  private val helper = "/usr/local/sbin/orphera-heat-api"
  private val units =
    List("heat-engine", "heat-api", "heat-api-cfn")

  override def nodes: List[String] = List(dbNode, rabbitNode, node)

  // ---- tst20 ----
  private val dirs = List(
    "/etc/kolla/heat",
    "/etc/kolla/heat-engine",
    "/etc/kolla/heat-api",
    "/etc/kolla/heat-api-cfn",
    "/etc/kolla/heat-ca",
    "/var/lib/orphera/heat",
    "/var/log/kolla/heat"
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
          "remove the heat vhost and user from RabbitMQ",
          "heat",
          "heat"
        )
      )
    else
      val unregistered =
        absentKeystoneEntries("remove Heat from Keystone", "heat")
      val stopped = absentContainerUnits(units.map(u => s"orphera-$u" -> u))
        .map(_.after(unregistered))
      // Typed removal, no script: each directory with its contents, then the helper.
      val cleaned = (dirs.map(absentDirectory(_, recursive = true)) ++
        List(absentFile(helper), absentFile(keystoneHelperPath("heat"))))
        .map(_.after(stopped*))
      List(unregistered) ++ stopped ++ cleaned
