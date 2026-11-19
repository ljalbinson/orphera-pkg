// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The `heat` vhost and user on the RabbitMQ that rabbitmq_single_node.scala runs
// on tst10, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md).
// It was a stage of heat_single_node.scala when that was a cluster playbook; the
// description for the Heat node needs this to have run first, and one
// description cannot order its nodes.
//
//   orphera apply manifests/heat_rabbitmq.scala --check
//   orphera apply manifests/heat_rabbitmq.scala
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
object heat_rabbitmq extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst10")

  // Keep in step with heat_single_node.scala.
  private val rabbitUser = "heat"
  private val rabbitPassword = Secrets.secret("heat/rabbit")
  private val rabbitVhost = "heat"

  // No {{...}} in any command: Orphera renders those as a template before running
  // the command, so `podman ps --format '{{.Names}}'` arrives empty. Use filters.
  private val createVhostAndUser =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - apply rabbitmq_single_node.scala first" >&2
       |  exit 1
       |fi
       |if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$rabbitVhost'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$rabbitUser' '${rabbitPassword.reveal}'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$rabbitUser' '${rabbitPassword.reveal}'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$rabbitVhost' '$rabbitUser' '.*' '.*' '.*'
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser ready"""".stripMargin

  private val vhostExists =
    s"""podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'"""
  private val canAuthenticate =
    s"""podman exec rabbitmq rabbitmqctl authenticate_user '$rabbitUser' '${rabbitPassword.reveal}' >/dev/null 2>&1"""
  private val hasFullPermissions =
    s"""podman exec rabbitmq rabbitmqctl list_permissions -p '$rabbitVhost' -q | awk -F'\t' '$$1=="$rabbitUser" && $$2==".*" && $$3==".*" && $$4==".*" {found=1} END {exit !found}'"""
  private val inPlace = s"$vhostExists && $canAuthenticate && $hasFullPermissions"

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        s"the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ",
        createVhostAndUser,
        unless = inPlace,
        timeout = 120
      )
    )
