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
// The container and unit go with a command that runs only while there is something to remove (`onlyIf`); the
// cached images are removed with `absentDirectory`, so a second run changes nothing.
object kolla_cache_teardown extends OrpheraApply:
  import Resources.*

  private val node = "tst9"

  override def nodes: List[String] = List(node)

  def describe(target: Node): List[ApplyResource] =
    val stopped = absentContainerUnits(List("orphera-kolla-cache" -> "kolla-cache"))
    val cleaned =
      absentDirectory("/var/lib/orphera/kolla-cache", recursive = true)
        .after(stopped*)
    stopped ++ List(cleaned)
