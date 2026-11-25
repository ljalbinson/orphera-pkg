// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The `aodh` and `ceilometer` vhosts and users on the RabbitMQ that rabbitmq_single_node.scala runs on tst10,
// as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). They were a stage of
// telemetry_single_node.scala when that was a cluster playbook; the descriptions for the telemetry nodes need
// this to have run first, and one description cannot order its nodes.
//
//   orphera apply manifests/telemetry_rabbitmq.scala --check
//   orphera apply manifests/telemetry_rabbitmq.scala
//
// The check is on the broker, for each of the two: the vhost exists, the user can authenticate with the
// password from the secret store (`rabbitmqctl authenticate_user`), and has full permissions on the vhost.
// Where all of it holds, nothing is changed. Otherwise the vhosts are made if missing, the users are made or
// their passwords set, and their permissions are set, so a rotated password is applied by running this again.
// The broker itself (and the other services' use of it) is untouched: with no running `rabbitmq` container on
// the node the step fails and says to apply rabbitmq_single_node.scala first.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object telemetry_rabbitmq extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst10")

  // Keep in step with telemetry_single_node.scala. One vhost and one user of the same name each.
  private val accounts = List(
    "aodh" -> Secrets.secret("aodh/rabbit"),
    "ceilometer" -> Secrets.secret("ceilometer/rabbit")
  )

  def describe(node: Node): List[ApplyResource] =
    accounts.map((name, password) =>
      rabbitAccount(
        s"the $name vhost and the $name user on RabbitMQ",
        name,
        name,
        password
      )
    )
