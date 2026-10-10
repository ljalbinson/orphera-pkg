// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes kolla_cache.scala: stops and removes the cache container and unit and
// deletes the cached images (/var/lib/orphera/kolla-cache). podman stays
// installed. Nodes that were pointed at the cache fall back to quay.io; their
// mirror setting (/etc/containers/registries.conf.d/orphera-kolla-cache.conf)
// is left alone.
object kolla_cache_teardown extends OrpheraClusterPlaybook:

  private val node = "tst9"

  private val teardownScript =
    """systemctl disable --now orphera-kolla-cache 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-kolla-cache.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then podman rm -f kolla-cache 2>/dev/null || true; fi
      |rm -rf /var/lib/orphera/kolla-cache
      |echo "kolla cache container, unit and cached images removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kolla-cache-teardown")(
      stage("teardown-kolla-cache", node)
        .task("remove the kolla cache")(
          Task.RunCommand(List("sh", "-c", teardownScript), timeoutSeconds = 120)
        )
        .build
    )
