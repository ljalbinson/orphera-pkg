// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes rabbitmq_single_node.scala, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): on tst10 stops and removes the `rabbitmq` container and
// unit and deletes its data directory. Everything in the broker goes with it:
// the vhosts and users of Nova, Neutron and every other service. Apply their
// *_rabbitmq.scala descriptions again after rebuilding the broker. podman and the
// image stay. Keep `node` in step with rabbitmq_single_node.
//
//   orphera apply manifests/rabbitmq_teardown.scala --check
//   orphera apply manifests/rabbitmq_teardown.scala
//
// The container and unit go with a command that runs only while there is something to remove
// (`onlyIf`); the data directory is removed with `absentDirectory`, so a second run changes nothing.
object rabbitmq_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst10"
  private val unit = "orphera-rabbitmq"
  private val dataDir = "/var/lib/orphera/rabbitmq"

  override def nodes: List[String] = List(node)

  // True when the unit file exists, or the unit is active or enabled.
  private val unitExists =
    s"""[ -f /etc/systemd/system/$unit.service ] || systemctl is-active --quiet $unit || systemctl is-enabled --quiet $unit"""
  private val stopScript =
    s"""systemctl disable --now $unit 2>/dev/null || true
       |rm -f /etc/systemd/system/$unit.service
       |if command -v podman >/dev/null 2>&1; then podman rm -f rabbitmq 2>/dev/null || true; fi
       |systemctl daemon-reload
       |echo "rabbitmq container and unit removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val stopped = command(
      "stop and remove the rabbitmq container and unit",
      stopScript,
      onlyIf = unitExists,
      timeout = 240
    )
    val cleaned = absentDirectory(dataDir, recursive = true).after(stopped)
    List(stopped, cleaned)
