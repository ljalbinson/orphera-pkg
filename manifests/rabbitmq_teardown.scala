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

  def describe(target: Node): List[ApplyResource] =
    val stopped = absentContainerUnits(List(unit -> "rabbitmq"))
    val cleaned = absentDirectory(dataDir, recursive = true).after(stopped*)
    stopped ++ List(cleaned)
