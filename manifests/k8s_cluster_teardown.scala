// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes k8s_cluster.scala on all ten nodes: `kubeadm reset`, removes the CNI
// configuration, the Orphera state directory and the contents of /var/lib/etcd.
// The etcd disk's file system and its fstab line stay (the next run reuses
// the mount). The node preparation, the VIP and the mirrors are left alone.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_cluster_teardown extends OrpheraClusterPlaybook:

  private val allNodes = (30 to 39).map(n => s"tst$n").toList

  private val resetScript =
    """kubeadm reset -f || true
      |rm -rf /etc/cni/net.d /etc/kubernetes/orphera /root/.kube /home/localadmin/.kube
      |if mountpoint -q /var/lib/etcd; then find /var/lib/etcd -mindepth 1 -maxdepth 1 ! -name lost+found -exec rm -rf {} +; fi
      |systemctl restart containerd
      |echo "$(hostname) reset"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-cluster-teardown")(
      stage("reset", allNodes*)
        .task("kubeadm reset and clean up")(
          Task.RunCommand(List("sh", "-c", resetScript), timeoutSeconds = 300)
        )
        .build
    )
