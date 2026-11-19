// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes kolla_cache.scala, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): stops and
// removes the cache container and unit and deletes the cached images (/var/lib/orphera/kolla-cache). podman
// stays installed. Nodes that were pointed at the cache fall back to quay.io; their mirror setting
// (/etc/containers/registries.conf.d/orphera-kolla-cache.conf) is left alone.
//
//   orphera apply manifests/kolla_cache_teardown.scala --check
//   orphera apply manifests/kolla_cache_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing.
object kolla_cache_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst9"

  override def nodes: List[String] = List(node)

  private val unitExists =
    """[ -f /etc/systemd/system/orphera-kolla-cache.service ] || systemctl is-active --quiet orphera-kolla-cache || systemctl is-enabled --quiet orphera-kolla-cache || { command -v podman >/dev/null 2>&1 && podman container exists kolla-cache; }"""
  private val stopScript =
    """systemctl disable --now orphera-kolla-cache 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-kolla-cache.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then podman rm -f kolla-cache 2>/dev/null || true; fi
      |echo "kolla cache container and unit removed"""".stripMargin

  private val dataExists = "[ -e /var/lib/orphera/kolla-cache ]"
  private val dataScript =
    """rm -rf /var/lib/orphera/kolla-cache
      |echo "cached images removed"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val stopped = command(
      "stop and remove the Kolla cache container and unit",
      stopScript,
      onlyIf = unitExists,
      timeout = 120
    )
    val cleaned = command(
      "delete the cached images",
      dataScript,
      onlyIf = dataExists,
      timeout = 120
    ).after(stopped)
    List(stopped, cleaned)
