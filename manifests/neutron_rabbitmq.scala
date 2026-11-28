// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The `neutron` vhost and user on the RabbitMQ that rabbitmq_single_node.scala runs
// on tst10, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md).
// It was a stage of neutron_single_node.scala when that was a cluster playbook; the
// description for the Neutron node needs this to have run first, and one
// description cannot order its nodes.
//
//   orphera apply manifests/neutron_rabbitmq.scala --check
//   orphera apply manifests/neutron_rabbitmq.scala
//
// The check is on the broker: the vhost exists, the user can authenticate with
// the password from the secret store (`rabbitmqctl authenticate_user`), and has
// full permissions on the vhost. Where all three hold, nothing is changed.
// Otherwise the vhost is made if missing, the user is made or its password is
// set, and its permissions are set, so a rotated password is applied by running
// this again. The broker itself (and Cinder's use of it) is untouched: with no
// running `rabbitmq` container on the node the step fails and says to apply
// rabbitmq_single_node.scala first.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object neutron_rabbitmq extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst10")

  // Keep in step with neutron_single_node.scala.
  private val rabbitUser = "neutron"
  private val rabbitPassword = Secrets.secret("neutron/rabbit")
  private val rabbitVhost = "neutron"

  def describe(node: Node): List[ApplyResource] =
    List(
      rabbitAccount(
        s"the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ",
        rabbitVhost,
        rabbitUser,
        rabbitPassword
      )
    )
