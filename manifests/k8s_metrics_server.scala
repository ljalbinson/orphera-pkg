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

  // The chart as a `helmRelease`: a helper on tst30 installs it with `helm upgrade --install --wait`, skipped
  // while the description last installed is the one below and Helm lists the release as deployed. No version is
  // given, so the newest chart is installed the first time and again only when this description changes.
  private val installPlan: ApplyPlan =
    import Resources.*
    val (helper, installed) = helmRelease(
      "helm install metrics-server",
      "metrics-server",
      "metrics-server/metrics-server",
      "https://kubernetes-sigs.github.io/metrics-server/",
      "kube-system",
      set = List("replicas" -> "2", "args={--kubelet-insecure-tls}")
    )
    ApplyPlan.orFail("metrics-server", List(helper, installed))

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
          Task.Apply(installPlan)
        )
        .task("kubectl top nodes answers")(
          Task.RunCommand(List("sh", "-c", topScript), timeoutSeconds = 200)
        )
        .build
    )
