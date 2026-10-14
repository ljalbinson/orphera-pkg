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

final case class Image(
    service: String,
    release: Release,
    base: Base,
    registry: String = Image.DefaultRegistry
):
  /** The reference podman is given, e.g.
    * `quay.io/openstack.kolla/placement-api:2025.1-ubuntu-noble`.
    */
  def ref: String = s"$registry/$service:${release.tag}-${base.tag}"

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

final case class Container(
    name: String,
    image: Image,
    mounts: List[Mount] = Nil,
    // A List, not a Map: the order is part of the text that gets written.
    env: List[(String, String)] = Nil,
    // Agents that manage the host's networking need the host's process table and
    // every capability; the API servers do not.
    privileged: Boolean = false,
    hostPid: Boolean = false,
    // Extra units to start after, besides network-online.target.
    after: List[String] = Nil,
    // Commands run (as ExecStartPre) before the old container is removed.
    preStart: List[String] = Nil,
    stopGraceSeconds: Int = 30,
    restartSeconds: Int = 5,
    startTimeoutSeconds: Int = 300,
    settleSeconds: Int = 8
):
  require(
    name.matches("[a-z0-9][a-z0-9-]*"),
    s"container name must be lowercase letters, digits and dashes: $name"
  )

  def unitName: String = s"orphera-$name.service"
  def unitPath: String = s"/etc/systemd/system/$unitName"

  /** Everything after `podman run --rm --name <name> --network host`. */
  def runArguments: String =
    (List("--privileged").filter(_ => privileged) ++
      List("--pid host").filter(_ => hostPid) ++
      env.map((k, v) => s"-e $k=$v") ++ mounts.map(_.flag) :+ image.ref)
      .mkString(" ")

  /** The systemd unit, exactly as it is written to the node. Pure: it can be
    * compared with what a node has, or asserted in a test, with no machine.
    */
  def render: String =
    val afterUnits = ("network-online.target" :: after).mkString(" ")
    val preLines = preStart.map(c => s"ExecStartPre=$c\n").mkString
    s"""[Unit]
       |Description=$name (Kolla container) managed by Orphera
       |After=$afterUnits
       |Wants=network-online.target
       |
       |[Service]
       |${preLines}ExecStartPre=-/usr/bin/podman rm -f $name
       |ExecStart=/usr/bin/podman run --rm --name $name --network host $runArguments
       |ExecStop=/usr/bin/podman stop -t $stopGraceSeconds $name
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
    val unit = s"orphera-$name"
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
           |echo "$name running"""".stripMargin
      ),
      timeoutSeconds = startTimeoutSeconds
    )
