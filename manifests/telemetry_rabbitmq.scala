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

  // No {{...}} in any command: Orphera renders those as a template before running the command, so
  // `podman ps --format '{{.Names}}'` arrives empty. Use filters.
  private def createPart(name: String, password: String): String =
    s"""if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$name'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$name'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$name'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$name' '$password'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$name' '$password'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$name' '$name' '.*' '.*' '.*'
       |echo "rabbitmq vhost $name and user $name ready"""".stripMargin

  private val createVhostsAndUsers =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - apply rabbitmq_single_node.scala first" >&2
       |  exit 1
       |fi
       |${accounts.map((n, p) => createPart(n, p.reveal)).mkString("\n")}""".stripMargin

  private def inPlace(name: String, password: String): String =
    val vhostExists =
      s"""podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$name'"""
    val canAuthenticate =
      s"""podman exec rabbitmq rabbitmqctl authenticate_user '$name' '$password' >/dev/null 2>&1"""
    val hasFullPermissions =
      s"""podman exec rabbitmq rabbitmqctl list_permissions -p '$name' -q | awk -F'\t' '$$1=="$name" && $$2==".*" && $$3==".*" && $$4==".*" {found=1} END {exit !found}'"""
    s"$vhostExists && $canAuthenticate && $hasFullPermissions"
  private val allInPlace =
    accounts.map((n, p) => s"( ${inPlace(n, p.reveal)} )").mkString(" && ")

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        "the aodh and ceilometer vhosts and users on RabbitMQ",
        createVhostsAndUsers,
        unless = allInPlace,
        timeout = 120
      )
    )
