// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.kolla

import orphera.orchestrator.Task

// A Kolla service run as a rootful podman container under a systemd unit.
//
// This is the pattern every OpenStack manifest in this repository repeats: pull
// `quay.io/openstack.kolla/<service>:<release>-<base>`, run it on the host
// network with `/etc/kolla/<service>` as its config, and keep it alive with a
// unit called `orphera-<name>.service`. Writing it as data instead of a shell
// string means the compiler checks the pieces (an image is not a mount, a
// release is one of the known releases), `render` can be tested without a
// machine, and the same description can later drive a check mode that diffs
// the unit on a node against what it should be.

/** OpenStack release a Kolla image is built for. A new release is a deliberate
  * code change here, not a string edited in one manifest and forgotten in
  * another.
  */
enum Release(val tag: String):
  case R2025_1 extends Release("2025.1")

/** The distribution a Kolla image is built on. */
enum Base(val tag: String):
  case UbuntuNoble extends Base("ubuntu-noble")

/** Anything podman can be given to run. */
sealed trait ImageRef:
  def ref: String

/** A Kolla image. */
final case class Image(
    service: String,
    release: Release,
    base: Base,
    registry: String = Image.DefaultRegistry
) extends ImageRef:
  /** The reference podman is given, e.g.
    * `quay.io/openstack.kolla/placement-api:2025.1-ubuntu-noble`.
    */
  def ref: String = s"$registry/$service:${release.tag}-${base.tag}"

/** An image that is not a Kolla image, e.g. `docker.io/library/rabbitmq:3.13`. */
final case class ExternalImage(ref: String) extends ImageRef

object Image:
  val DefaultRegistry = "quay.io/openstack.kolla"

/** How a host path is mounted into the container. `:z` relabels for SELinux
  * and is harmless where SELinux is off. The last three are for host paths that
  * must stay as they are: an Open vSwitch socket directory, a network-namespace
  * directory that has to propagate both ways (`rshared`), the kernel modules.
  */
enum Access(val suffix: String):
  case ReadOnly extends Access("ro,z")
  case ReadWrite extends Access("z")
  case Unlabelled extends Access("")
  case ReadOnlyUnlabelled extends Access("ro")
  case Shared extends Access("rshared")

final case class Mount(
    host: String,
    inside: String,
    access: Access = Access.ReadOnly
):
  def flag: String =
    if access.suffix.isEmpty then s"-v $host:$inside"
    else s"-v $host:$inside:${access.suffix}"

/** How the container is attached to the network. Every OpenStack service uses the
  * host's network; a plain registry or similar publishes ports instead.
  */
enum Network:
  case Host
  case Published

/** A published port, `-p host:container`. */
final case class Port(host: TcpPort, container: TcpPort):
  def flag: String = s"-p ${host.value}:${container.value}"

object Port:
  /** From plain numbers; each is checked (1 to 65535). */
  def apply(host: Int, container: Int): Port = Port(TcpPort(host), TcpPort(container))

final case class Container(
    name: ContainerName,
    image: ImageRef,
    mounts: List[Mount] = Nil,
    // A List, not a Map: the order is part of the text that gets written.
    env: List[(String, String)] = Nil,
    // Agents that manage the host's networking need the host's process table and
    // every capability; the API servers do not.
    privileged: Boolean = false,
    hostPid: Boolean = false,
    // Linux capabilities to add, e.g. "SYS_ADMIN"; a narrower alternative to `privileged`.
    capabilities: List[String] = Nil,
    // Host network by default; `Published` needs at least one port.
    network: Network = Network.Host,
    ports: List[Port] = Nil,
    // Unit description; the default says it is a Kolla container.
    description: Option[String] = None,
    // Extra units to start after, besides network-online.target.
    after: List[String] = Nil,
    // Extra units the service wants (pulls in), besides network-online.target.
    wants: List[String] = Nil,
    // Commands run (as ExecStartPre) before the old container is removed.
    preStart: List[String] = Nil,
    stopGraceSeconds: Int = 30,
    restartSeconds: Int = 5,
    startTimeoutSeconds: Int = 300,
    settleSeconds: Int = 8
):
  require(
    (network == Network.Host && ports.isEmpty) ||
      (network == Network.Published && ports.nonEmpty),
    "ports go with Network.Published; the host network publishes nothing"
  )

  def unitName: String = s"orphera-${name.value}.service"
  def unitPath: String = s"/etc/systemd/system/$unitName"

  /** Everything after `podman run --rm --name <name>` (and `--network host`, when on the host network). */
  def runArguments: String =
    (ports.map(_.flag) ++
      List("--privileged").filter(_ => privileged) ++
      List("--pid host").filter(_ => hostPid) ++
      capabilities.map(c => s"--cap-add=$c") ++
      env.map((k, v) => s"-e $k=$v") ++ mounts.map(_.flag) :+ image.ref)
      .mkString(" ")

  /** The systemd unit, exactly as it is written to the node. Pure: it can be
    * compared with what a node has, or asserted in a test, with no machine.
    */
  def render: String =
    // "--network host" for the host network; published ports are part of runArguments.
    val runLine = network match
      case Network.Host => s"--network host $runArguments"
      case Network.Published => runArguments
    val unitDescription =
      description.getOrElse(s"${name.value} (Kolla container) managed by Orphera")
    val wantedUnits = ("network-online.target" :: wants).mkString(" ")
    val afterUnits = ("network-online.target" :: after).mkString(" ")
    val preLines = preStart.map(c => s"ExecStartPre=$c\n").mkString
    s"""[Unit]
       |Description=$unitDescription
       |After=$afterUnits
       |Wants=$wantedUnits
       |
       |[Service]
       |${preLines}ExecStartPre=-/usr/bin/podman rm -f ${name.value}
       |ExecStart=/usr/bin/podman run --rm --name ${name.value} $runLine
       |ExecStop=/usr/bin/podman stop -t $stopGraceSeconds ${name.value}
       |Restart=on-failure
       |RestartSec=$restartSeconds
       |TimeoutStartSec=$startTimeoutSeconds
       |
       |[Install]
       |WantedBy=multi-user.target
       |""".stripMargin

  /** Writes the unit file. */
  def writeUnit: Task =
    Task.WriteFile(
      content = render,
      dest = unitPath,
      owner = "root",
      group = "root",
      mode = 420 // 0644
    )

  /** Reloads systemd, enables and restarts the unit, and fails loudly (with the
    * journal command to run) if it is not active after `settleSeconds`. The
    * timeout is part of the task, so it cannot be left at the 60 s default.
    */
  def start: Task =
    val logHint = mounts
      .find(_.access == Access.ReadWrite)
      .map(m => s" and ${m.host}")
      .getOrElse("")
    val unit = s"orphera-${name.value}"
    Task.RunCommand(
      List(
        "sh",
        "-c",
        s"""set -e
           |systemctl daemon-reload
           |systemctl enable $unit
           |systemctl restart $unit
           |sleep $settleSeconds
           |if ! systemctl is-active --quiet $unit; then
           |  journalctl -u $unit -n 40 --no-pager >&2 || true
           |  echo "$unit failed to start - see 'journalctl -xeu $unit'$logHint" >&2
           |  exit 1
           |fi
           |echo "${name.value} running"""".stripMargin
      ),
      timeoutSeconds = startTimeoutSeconds
    )
