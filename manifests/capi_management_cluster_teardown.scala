// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes capi_management_cluster.scala on tst23: k3s is uninstalled with its own script (which removes the
// cluster, Cluster API, CAPO, cert-manager and everything they created in it), and helm, clusterctl, the kubeconfig
// and /var/lib/orphera/capi are removed. Workload clusters that Magnum made are VMs in OpenStack; delete those
// clusters first (openstack coe cluster delete), because after this the janitor that cleans up their OpenStack
// resources is gone. Safe to re-run.
object capi_management_cluster_teardown extends OrpheraClusterPlaybook:

  private val script =
    """if [ -x /usr/local/bin/k3s-uninstall.sh ]; then
      |  /usr/local/bin/k3s-uninstall.sh
      |fi
      |rm -f /usr/local/bin/helm /usr/local/bin/clusterctl
      |rm -rf /var/lib/orphera/capi /root/.config/helm /root/.cache/helm /root/.cluster-api
      |echo "management cluster removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("capi-management-cluster-teardown")(
      stage("remove-capi-management-cluster", "tst23")
        .task("uninstall k3s and remove the tools and state")(
          Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 300)
        )
        .build
    )
