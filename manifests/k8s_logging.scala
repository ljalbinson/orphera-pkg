// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 13: logs. Needs k8s_monitoring.scala (Grafana and
// the namespace monitoring) and k8s_ceph_csi.scala.
//
// Loki (Helm chart grafana/loki, single-binary mode, namespace `logging`): one
// pod on an infra node with a 30 GiB Ceph volume, filesystem storage, logs kept
// 7 days (the compactor removes older chunks). No authentication, no
// replication: a lab setup, as the monitoring stack is. The Service is
// loki.logging.svc.cluster.local:3100.
//
// Alloy (Helm chart grafana/alloy) runs on every node, control plane included.
// Each Alloy lists the pods on its own node through the Kubernetes API, reads
// their container logs through the API (`loki.source.kubernetes`, so it needs no
// access to the node's files) and pushes them to Loki with the labels
// namespace, pod, container, node and app.
//
// Grafana learns about Loki through a ConfigMap with the label
// `grafana_datasource` in the namespace monitoring, which the kube-prometheus-stack
// Grafana's sidecar picks up; no restart.
//
// The last stage queries Loki through the API server's service proxy: logs from
// kube-system arrive from all 10 nodes; and asks Grafana (with the local admin
// account) whether the data source Loki exists.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The chart value names are from memory of the Loki and Alloy charts
// and the Loki chart's location may have moved; if `helm show chart` or the
// install complains, the output says which.
object k8s_logging extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ns = "logging"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val grafanaHost = "grafana.k8s.ljalbinson.com"
  private val grafanaAdminPassword = Secrets.secret("k8s/grafana-admin")
  private val lokiUrl = "http://loki.logging.svc.cluster.local:3100"

  private val valuesScript =
    s"""set -e
       |$kc create namespace $ns --dry-run=client -o yaml | $kc apply -f -
       |umask 077
       |cat > $stateDir/loki-values.yaml <<'EOF'
       |deploymentMode: SingleBinary
       |loki:
       |  auth_enabled: false
       |  commonConfig:
       |    replication_factor: 1
       |  storage:
       |    type: filesystem
       |  schemaConfig:
       |    configs:
       |    - from: "2024-04-01"
       |      store: tsdb
       |      object_store: filesystem
       |      schema: v13
       |      index:
       |        prefix: loki_index_
       |        period: 24h
       |  limits_config:
       |    retention_period: 168h
       |  compactor:
       |    retention_enabled: true
       |    delete_request_store: filesystem
       |singleBinary:
       |  replicas: 1
       |  nodeSelector:
       |    pool: infra
       |  persistence:
       |    enabled: true
       |    size: 30Gi
       |    storageClass: ceph-rbd
       |backend:
       |  replicas: 0
       |read:
       |  replicas: 0
       |write:
       |  replicas: 0
       |gateway:
       |  enabled: false
       |chunksCache:
       |  enabled: false
       |resultsCache:
       |  enabled: false
       |lokiCanary:
       |  enabled: false
       |test:
       |  enabled: false
       |minio:
       |  enabled: false
       |EOF
       |cat > $stateDir/alloy-values.yaml <<'EOF'
       |controller:
       |  type: daemonset
       |  tolerations:
       |  - operator: Exists
       |alloy:
       |  configMap:
       |    content: |
       |      discovery.kubernetes "pods" {
       |        role = "pod"
       |        selectors {
       |          role  = "pod"
       |          field = "spec.nodeName=" + coalesce(sys.env("HOSTNAME"), constants.hostname)
       |        }
       |      }
       |      discovery.relabel "pods" {
       |        targets = discovery.kubernetes.pods.targets
       |        rule {
       |          source_labels = ["__meta_kubernetes_namespace"]
       |          target_label  = "namespace"
       |        }
       |        rule {
       |          source_labels = ["__meta_kubernetes_pod_name"]
       |          target_label  = "pod"
       |        }
       |        rule {
       |          source_labels = ["__meta_kubernetes_pod_container_name"]
       |          target_label  = "container"
       |        }
       |        rule {
       |          source_labels = ["__meta_kubernetes_pod_node_name"]
       |          target_label  = "node"
       |        }
       |        rule {
       |          source_labels = ["__meta_kubernetes_pod_label_app_kubernetes_io_name"]
       |          target_label  = "app"
       |        }
       |      }
       |      loki.source.kubernetes "pods" {
       |        targets    = discovery.relabel.pods.output
       |        forward_to = [loki.write.default.receiver]
       |      }
       |      loki.write "default" {
       |        endpoint {
       |          url = "$lokiUrl/loki/api/v1/push"
       |        }
       |      }
       |EOF
       |echo "namespace and Helm values written"
       |""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |helm repo add grafana https://grafana.github.io/helm-charts --force-update >/dev/null
       |helm repo update >/dev/null
       |LV=$$(helm show chart grafana/loki | awk '/^version:/{print $$2}')
       |[ -n "$$LV" ] || { echo "the chart grafana/loki was not found; its repository may have moved" >&2; exit 1; }
       |echo "installing loki chart $$LV"
       |helm upgrade --install loki grafana/loki --version "$$LV" --namespace $ns -f $stateDir/loki-values.yaml --wait --timeout 15m
       |AV=$$(helm show chart grafana/alloy | awk '/^version:/{print $$2}')
       |[ -n "$$AV" ] || { echo "the chart grafana/alloy was not found" >&2; exit 1; }
       |echo "installing alloy chart $$AV"
       |helm upgrade --install alloy grafana/alloy --version "$$AV" --namespace $ns -f $stateDir/alloy-values.yaml --wait --timeout 15m
       |$kc -n $ns get pods -o wide
       |""".stripMargin

  private val datasourceScript =
    s"""set -e
       |cat <<EOF | $kc apply -f -
       |apiVersion: v1
       |kind: ConfigMap
       |metadata:
       |  name: grafana-datasource-loki
       |  namespace: monitoring
       |  labels:
       |    grafana_datasource: "1"
       |data:
       |  loki.yaml: |
       |    apiVersion: 1
       |    datasources:
       |    - name: Loki
       |      type: loki
       |      access: proxy
       |      url: $lokiUrl
       |      isDefault: false
       |EOF
       |echo "Loki data source ConfigMap applied"
       |""".stripMargin

  // Loki through the API server's service proxy; Grafana over HTTPS with the local admin account.
  private val testScript =
    s"""set -e
       |FAIL=0
       |Q='count(sum by (node) (count_over_time({namespace="kube-system"}[10m])))'
       |GOT=0
       |for I in $$(seq 1 40); do
       |  GOT=$$($kc get --raw "/api/v1/namespaces/$ns/services/loki:3100/proxy/loki/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$Q")" 2>/dev/null \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)' 2>/dev/null || echo 0)
       |  [ "$$GOT" -ge 10 ] && break
       |  sleep 5
       |done
       |if [ "$$GOT" -ge 10 ]; then echo "ok    kube-system logs arrive from $$GOT nodes"; else echo "FAIL  kube-system logs arrive from only $$GOT nodes" >&2; FAIL=1; fi
       |DS=""
       |for I in $$(seq 1 20); do
       |  DS=$$(curl -s --cacert $stateDir/ca.crt -u 'admin:${grafanaAdminPassword.reveal}' https://$grafanaHost/api/datasources | python3 -c 'import sys,json; print(" ".join(d["name"] + ":" + d["type"] for d in json.load(sys.stdin)))' 2>/dev/null || true)
       |  case "$$DS" in *Loki:loki*) break ;; esac
       |  sleep 5
       |done
       |case "$$DS" in *Loki:loki*) echo "ok    Grafana lists the data source Loki ($$DS)" ;; *) echo "FAIL  Grafana data sources: '$$DS'" >&2; FAIL=1 ;; esac
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-logging")(
      stage("logging-config", node)
        .task("namespace and Helm values")(
          Task.RunCommand(List("sh", "-c", valuesScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-loki-and-alloy", node)
        .task("helm install Loki and Alloy")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 2000)
        )
        .build,
      stage("grafana-datasource", node)
        .task("add Loki to Grafana")(
          Task.RunCommand(List("sh", "-c", datasourceScript), timeoutSeconds = 60)
        )
        .build,
      stage("test-logging", node)
        .task("query Loki and ask Grafana")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("logging ready")(
          Task.Debug(
            "Loki (7 days, Ceph volume) and Alloy on every node are running; Grafana has the data source Loki (Explore > Loki, e.g. {namespace=\"kube-system\"})."
          )
        )
        .build
    )
