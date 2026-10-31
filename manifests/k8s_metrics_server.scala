// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 8: metrics-server, so `kubectl top` and
// horizontal pod autoscaling work. Needs k8s_cilium.scala.
//
// kubeadm's kubelets serve a self-signed certificate, which metrics-server
// would reject, so it runs with `--kubelet-insecure-tls`. That is accepted
// here because the traffic stays on the node network; the proper fix is
// kubelet serving certificates signed by the cluster CA (kubelet
// `serverTLSBootstrap`), which belongs with the security step.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_metrics_server extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  private val installScript =
    """set -e
      |export KUBECONFIG=/etc/kubernetes/admin.conf
      |helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ --force-update >/dev/null
      |helm repo update >/dev/null
      |VER=$(helm show chart metrics-server/metrics-server | awk '/^version:/{print $2}')
      |echo "installing metrics-server chart $VER"
      |helm upgrade --install metrics-server metrics-server/metrics-server --version "$VER" \
      |  --namespace kube-system --set replicas=2 --set 'args={--kubelet-insecure-tls}' --wait --timeout 10m
      |echo "metrics-server $VER installed"
      |""".stripMargin

  private val topScript =
    s"""for I in $$(seq 1 30); do
       |  if $kc top nodes >/tmp/top.out 2>/dev/null; then cat /tmp/top.out; rm -f /tmp/top.out; exit 0; fi
       |  sleep 5
       |done
       |echo "kubectl top nodes did not work within 150 s" >&2
       |exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-metrics-server")(
      stage("install-metrics-server", node)
        .task("helm install metrics-server")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 900)
        )
        .task("kubectl top nodes answers")(
          Task.RunCommand(List("sh", "-c", topScript), timeoutSeconds = 200)
        )
        .build
    )
