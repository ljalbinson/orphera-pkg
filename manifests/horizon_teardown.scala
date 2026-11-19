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

  // True when the unit file exists, or the unit is active or enabled.
  private val unitExists =
    s"""[ -f /etc/systemd/system/$unit.service ] || systemctl is-active --quiet $unit || systemctl is-enabled --quiet $unit"""
  private val stopScript =
    s"""systemctl disable --now $unit 2>/dev/null || true
       |rm -f /etc/systemd/system/$unit.service
       |if command -v podman >/dev/null 2>&1; then podman rm -f horizon 2>/dev/null || true; fi
       |systemctl daemon-reload
       |echo "horizon container and unit removed"""".stripMargin

  private val paths =
    "/etc/kolla/horizon /etc/kolla/horizon-web /etc/kolla/horizon-ca /var/log/kolla/horizon " + helper
  // True when ANY of them exists.
  private val filesExist =
    s"for p in $paths; do [ -e \"$$p\" ] && exit 0; done; exit 1"
  private val cleanupScript =
    s"""rm -rf $paths
       |echo "horizon config, logs and helper removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val stopped = command(
      "stop and remove the horizon container and unit",
      stopScript,
      onlyIf = unitExists,
      timeout = 240
    )
    val cleaned = command(
      "remove horizon files, logs and the helper",
      cleanupScript,
      onlyIf = filesExist,
      timeout = 120
    ).after(stopped)
    List(stopped, cleaned)
