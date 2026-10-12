// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes horizon_single_node.scala: stops and removes the container and
// its unit, config, logs, CA copy and the check helper on the node. Horizon
// keeps no database and registers nothing in Keystone, so that is all there is.
// Glance and Placement on the same node are untouched; podman stays installed.
// Keep `node` in step with horizon_single_node. Safe to re-run when nothing is
// installed.
object horizon_teardown extends OrpheraClusterPlaybook:

  private val node = "tst13"

  private val nodeScript =
    """systemctl disable --now orphera-horizon 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-horizon.service
      |if command -v podman >/dev/null 2>&1; then podman rm -f horizon 2>/dev/null || true; fi
      |systemctl daemon-reload
      |rm -rf /etc/kolla/horizon /etc/kolla/horizon-web /etc/kolla/horizon-ca /var/log/kolla/horizon
      |rm -f /usr/local/sbin/orphera-horizon-check
      |echo "horizon container, unit, config and logs removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("horizon-teardown")(
      stage("teardown-horizon", node)
        .task("remove the horizon container, unit and config")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 120)
        )
        .build
    )
