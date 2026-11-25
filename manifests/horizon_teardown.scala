// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes horizon_single_node.scala, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): on
// tst13 stops and removes the container and its unit, config, logs, Keystone CA copy and the check helper.
// Horizon keeps no database and registers nothing in Keystone, so that is all there is. The certificate in
// /etc/orphera/tls stays (Skyline uses it too; dashboard_tls.scala makes it), and so do Glance and Placement
// on the same node and podman. Keep `node` in step with horizon_single_node.
//
//   orphera apply manifests/horizon_teardown.scala --check
//   orphera apply manifests/horizon_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing.
object horizon_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val unit = "orphera-horizon"
  private val helper = "/usr/local/sbin/orphera-horizon-check"

  override def nodes: List[String] = List(node)

  private val dirs = List(
    "/etc/kolla/horizon",
    "/etc/kolla/horizon-web",
    "/etc/kolla/horizon-ca",
    "/var/log/kolla/horizon"
  )

  def describe(target: Node): List[ApplyResource] =
    val stopped = absentContainerUnits(List(unit -> "horizon"))
    // Typed removal, no script: each directory with its contents, then the helper.
    val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
      absentFile(helper)).map(_.after(stopped*))
    stopped ++ cleaned
