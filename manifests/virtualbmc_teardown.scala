// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes virtualbmc.scala: deletes every VirtualBMC endpoint, stops and removes orphera-vbmcd.service,
// /opt/virtualbmc, /etc/orphera-vbmc.conf and the records in /root/.vbmc on the hypervisors (default gs1, gs2,
// gs3, st0; VBMC_HOSTS chooses others). The VMs are not touched. The packages and the secret vbmc/password stay.
object virtualbmc_teardown extends OrpheraClusterPlaybook:

  private val hosts: List[String] =
    sys.env
      .get("VBMC_HOSTS")
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("gs1,gs2,gs3,st0")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private val script =
    """export HOME=/root
      |if [ -x /opt/virtualbmc/bin/vbmc ]; then
      |  for d in $(/opt/virtualbmc/bin/vbmc list -f value -c 'Domain name' 2>/dev/null); do
      |    /opt/virtualbmc/bin/vbmc delete "$d" >/dev/null 2>&1 || true
      |  done
      |fi
      |systemctl disable --now orphera-vbmcd.service 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-vbmcd.service /etc/orphera-vbmc.conf
      |systemctl daemon-reload
      |rm -rf /opt/virtualbmc /root/.vbmc
      |echo "virtualbmc removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("virtualbmc-teardown")(
      stage("teardown-virtualbmc", hosts*)
        .task("delete the endpoints, stop vbmcd and remove it")(
          Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 240)
        )
        .build
    )
