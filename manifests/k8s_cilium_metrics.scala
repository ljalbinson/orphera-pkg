// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 12 (addition): Cilium and Hubble metrics in Prometheus.
// Needs k8s_cilium.scala and k8s_monitoring.scala (the ServiceMonitor CRD and
// Prometheus, which picks up every ServiceMonitor). Safe to repeat.
//
// This changes only the Helm values of the existing release `cilium` (`helm upgrade
// --reuse-values`, same chart version), so the cluster network is not rebuilt. The
// Cilium agents restart one node at a time (the DaemonSet rolls); the datapath keeps
// running while an agent restarts. Switched on:
//   - agent metrics (port 9962), operator metrics (9963), Envoy metrics (9964)
//   - Hubble metrics (9965): dns, drop, tcp, flow, port-distribution, icmp
//   - a ServiceMonitor for each, so Prometheus scrapes them
//   - Grafana dashboards for Cilium, the operator and Hubble (ConfigMaps labelled
//     grafana_dashboard, picked up by Grafana's sidecar)
// Prometheus (namespace monitoring) reaches the node addresses through the `cluster`
// egress allowed by k8s_network_lockdown_monitoring.scala; kube-system has no network
// policies.
//
// Re-running k8s_cilium.scala installs Cilium from its own values file and drops these
// settings: run this playbook again afterwards.
//
// The test waits for the Cilium DaemonSet and Prometheus, then needs: every Cilium
// agent target and every Hubble target up (one per node), the operator up, flows
// counted by Hubble, and all nodes still Ready. Envoy targets are listed but do not
// fail the test.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The value names are from memory of the Cilium 1.18 chart; `helm upgrade`
// names the one that does not exist.
object k8s_cilium_metrics extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val nodeCount = 10

  private val applyScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |$kc get crd servicemonitors.monitoring.coreos.com >/dev/null \\
       |  || { echo "the ServiceMonitor CRD is missing; run k8s_monitoring.scala first" >&2; exit 1; }
       |CHART=$$(helm list -n kube-system --filter '^cilium$$' -o json | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["chart"])')
       |VER=$${CHART#cilium-}
       |echo "release cilium uses chart $$VER"
       |cat > $stateDir/cilium-metrics-values.yaml <<EOF
       |prometheus:
       |  enabled: true
       |  serviceMonitor:
       |    enabled: true
       |operator:
       |  prometheus:
       |    enabled: true
       |    serviceMonitor:
       |      enabled: true
       |envoy:
       |  prometheus:
       |    enabled: true
       |    serviceMonitor:
       |      enabled: true
       |hubble:
       |  metrics:
       |    enabled: [dns, drop, tcp, flow, port-distribution, icmp]
       |    serviceMonitor:
       |      enabled: true
       |    dashboards:
       |      enabled: true
       |dashboards:
       |  enabled: true
       |EOF
       |helm repo add cilium https://helm.cilium.io/ --force-update >/dev/null
       |helm repo update >/dev/null
       |helm upgrade cilium cilium/cilium --version "$$VER" --namespace kube-system \\
       |  --reuse-values -f $stateDir/cilium-metrics-values.yaml --wait --timeout 15m
       |$kc -n kube-system rollout status daemonset/cilium --timeout=900s
       |$kc -n kube-system get servicemonitors | grep -i -E 'cilium|hubble' || true
       |echo "Cilium and Hubble metrics switched on"
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |q() {
       |  $kc get --raw "/api/v1/namespaces/monitoring/services/kps-prometheus:9090/proxy/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$1")" \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
       |}
       |want() {  # description, query, minimum
       |  GOT=0
       |  for I in $$(seq 1 60); do GOT=$$(q "$$2" 2>/dev/null || echo 0); [ "$$GOT" -ge "$$3" ] && break; sleep 5; done
       |  if [ "$$GOT" -ge "$$3" ]; then echo "ok    $$1: $$GOT"; else echo "FAIL  $$1: $$GOT (wanted at least $$3)" >&2; FAIL=1; fi
       |}
       |READY=$$($kc get nodes --no-headers | grep -c ' Ready ' || true)
       |if [ "$$READY" -eq $nodeCount ]; then echo "ok    nodes Ready: $$READY"; else echo "FAIL  nodes Ready: $$READY (wanted $nodeCount)" >&2; FAIL=1; fi
       |want "Cilium agents scraped and up"   'count(up{job="cilium-agent"} == 1)' $nodeCount
       |want "Hubble targets up"              'count(up{job="hubble-metrics"} == 1)' $nodeCount
       |want "Cilium operator up"             'count(up{job="cilium-operator"} == 1)' 1
       |want "Hubble has processed flows"     'count(hubble_flows_processed_total)' 1
       |want "Cilium reports endpoints"       'count(cilium_endpoint_state)' 1
       |echo "Envoy targets up (informational): $$(q 'count(up{job="cilium-envoy"} == 1)')"
       |echo "Cilium and Hubble targets that are down:"
       |$kc get --raw "/api/v1/namespaces/monitoring/services/kps-prometheus:9090/proxy/api/v1/query?query=up%7Bjob%3D~%22cilium.*%7Chubble.*%22%7D%3D%3D0" \\
       |  | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; [print("  down: %s %s" % (x["metric"].get("job"), x["metric"].get("instance"))) for x in r]; print("  (none)" if not r else "")'
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-cilium-metrics")(
      stage("cilium-metrics-config", node)
        .task(
          "switch on Cilium and Hubble metrics (helm upgrade --reuse-values)"
        )(
          Task.RunCommand(List("sh", "-c", applyScript), timeoutSeconds = 1800)
        )
        .build,
      stage("cilium-metrics-test", node)
        .task("check the Prometheus targets")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("cilium metrics ready")(
          Task.Debug(
            "Prometheus scrapes the Cilium agents, the operator and Hubble; the dashboards are in Grafana (search for Cilium or Hubble)."
          )
        )
        .build
    )
