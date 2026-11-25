// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// The RabbitMQ broker the OpenStack services share, on tst10, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It was part of
// cinder_single_node.scala when that was a cluster playbook, and was removed
// with it by cinder_teardown.scala, which took the `nova`, `neutron`, ... vhosts
// down too. It is a description of its own now, so Cinder can be torn down and
// rebuilt without touching the broker.
//
//   orphera apply manifests/rabbitmq_single_node.scala --check
//   orphera apply manifests/rabbitmq_single_node.scala
//
// Apply it before cinder_single_node.scala and before nova_rabbitmq.scala,
// neutron_rabbitmq.scala and the other services' vhosts.
//
// What it does: the official docker.io/library/rabbitmq image (not a Kolla one:
// its first-run user setup is a single environment variable), run as the unit
// orphera-rabbitmq.service with host networking and its data in
// /var/lib/orphera/rabbitmq. The unit holds the password (the container's
// RABBITMQ_DEFAULT_PASS), so it is a secret file. That variable is read only
// when the data directory is first initialised, so a last step makes sure the
// `openstack` user has the password in the secret store and full permissions on
// the vhost `/`, and sets them otherwise (a rotated password is applied by
// running this again).
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object rabbitmq_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst10"

  override def nodes: List[String] = List(node)

  private val rabbitUser = "openstack"
  // Keep in step with cinder_single_node.scala.
  private val rabbitPassword = Secrets.secret("cinder/rabbit")
  private val dataDir = "/var/lib/orphera/rabbitmq"

  private val rabbitContainer = Container(
    name = ContainerName("rabbitmq"),
    image = ExternalImage("docker.io/library/rabbitmq:3.13"),
    mounts = List(Mount(dataDir, "/var/lib/rabbitmq", Access.ReadWrite)),
    env = List(
      "RABBITMQ_DEFAULT_USER" -> rabbitUser,
      "RABBITMQ_DEFAULT_PASS" -> rabbitPassword.reveal
    ),
    description = Some("RabbitMQ for OpenStack managed by Orphera"),
    settleSeconds = 3
  )
  private val rabbitImage = rabbitContainer.image.ref

  // No {{...}} in any command: Orphera renders those as a template before
  // running the command. Use filters, not --format.
  // `ping` passes as soon as the Erlang node is up, before the `rabbit` application
  // has started; `check_running` passes only when it is running, which the
  // `rabbitmqctl` commands below need.
  private val ping =
    "podman exec rabbitmq rabbitmq-diagnostics -q check_running >/dev/null 2>&1"
  private val waitScript =
    s"""for i in $$(seq 1 30); do
       |  if $ping; then
       |    echo "rabbitmq up"
       |    exit 0
       |  fi
       |  sleep 3
       |done
       |echo "rabbitmq did not come up - see 'journalctl -xeu orphera-rabbitmq'" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val podman = pkg("podman", updateCache = true)
    val pulled = pullImages(
      "pull the RabbitMQ image",
      List(rabbitImage),
      hint = "the ExternalImage in rabbitContainer in rabbitmq_single_node.scala",
      timeout = 1200
    ).after(podman)
    val data = directory(dataDir).after(podman)
    val unitService = service(rabbitContainer.unitName)
    val unitFile = secretFile(
      rabbitContainer.unitPath,
      Secret(rabbitContainer.render), // it holds the broker's password
      mode = "0600",
      owner = "root",
      group = "root"
    ).notifies(unitService)
    val running = unitService.after(pulled, data, unitFile)
    val answering = command(
      "RabbitMQ answers",
      waitScript,
      unless = ping,
      timeout = 150
    ).after(running)
    val user = rabbitAccount(
      s"the $rabbitUser user and its permissions on RabbitMQ",
      "/",
      rabbitUser,
      rabbitPassword
    ).after(answering)
    List(podman, pulled, data, unitFile, running, answering, user)
