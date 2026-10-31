// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 19, part 3: default-deny network policies on the
// namespace monitoring. Needs k8s_network_lockdown.scala (logging already allows
// Grafana in) and k8s_network_policy.scala (run it again first: the helper gained
// the option `cluster`). Safe to repeat.
//
// monitoring gets `api gateway world nodes cluster`:
//   api      kube-state-metrics, the operator and Grafana's sidecars use the API server
//   gateway  Grafana is published through the Gateway
//   world    Grafana signs people in with Keycloak on tst26 (outside the cluster)
//   nodes    the API server calls the Prometheus operator's admission webhook and
//            reaches Prometheus and Alertmanager through its service proxy
//   cluster  Prometheus scrapes every node (node-exporter, kubelet, etcd, scheduler,
//            controller manager) and Grafana reaches Loki in logging
// Everything else, in either direction, is denied.
//
// The test (a failure removes the policies of monitoring again and stops):
//   - Prometheus targets: node-exporter on all 10 nodes, 3 API servers, 10 kubelets,
//     kube-state-metrics, and etcd, scheduler and controller manager on 3 nodes each
//   - Grafana: /api/health through the Gateway, the data sources Prometheus and Loki
//     report OK, and the Grafana pod reaches Keycloak's discovery document
//   - the operator's webhook: a valid PrometheusRule is accepted and an invalid
//     one is refused by the webhook (server-side dry runs)
//   - Alertmanager answers through the API server's service proxy
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_network_lockdown_monitoring extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ns = "monitoring"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val np = "/usr/local/sbin/orphera-netpol"
  private val domain = "k8s.ljalbinson.com"
  private val keycloak = "https://tst26.ljalbinson.com:8443/realms/k8s/.well-known/openid-configuration"
  private val grafanaAdminPassword = Secrets.secret("k8s/grafana-admin")

  private val script =
    s"""set -e
       |FAIL=0
       |NP=$np
       |CURL="curl -s -m 10 --cacert $stateDir/ca.crt"
       |grep -q 'cluster' $$NP || { echo "$$NP is old; run k8s_network_policy.scala first" >&2; exit 1; }
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |q() {
       |  $kc get --raw "/api/v1/namespaces/$ns/services/kps-prometheus:9090/proxy/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$1")" \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
       |}
       |want() {  # description, query, minimum
       |  GOT=0
       |  for I in $$(seq 1 60); do GOT=$$(q "$$2" 2>/dev/null || echo 0); [ "$$GOT" -ge "$$3" ] && break; sleep 5; done
       |  if [ "$$GOT" -ge "$$3" ]; then echo "ok    $$1: $$GOT"; else echo "FAIL  $$1: $$GOT (wanted at least $$3)" >&2; FAIL=1; fi
       |}
       |$$NP apply $ns api gateway world nodes cluster
       |sleep 30
       |# Prometheus keeps its old results for a while, so ask for series that were up in the last minute
       |want "nodes reporting node-exporter metrics"   'count(up{job="node-exporter"} == 1)' 10
       |want "API servers up"                           'count(up{job="apiserver"} == 1)' 3
       |want "kubelets up"                              'count(up{job="kubelet"} == 1)' 10
       |want "kube-state-metrics up"                    'count(up{job="kube-state-metrics"} == 1)' 1
       |want "etcd up"                                  'count(up{job="kube-etcd"} == 1)' 3
       |want "scheduler up"                             'count(up{job="kube-scheduler"} == 1)' 3
       |want "controller manager up"                    'count(up{job="kube-controller-manager"} == 1)' 3
       |# Grafana
       |HEALTH=""
       |for I in $$(seq 1 30); do HEALTH=$$($$CURL https://grafana.$domain/api/health | python3 -c 'import sys,json; print(json.load(sys.stdin).get("database",""))' 2>/dev/null || true); [ "$$HEALTH" = "ok" ] && break; sleep 3; done
       |chk "Grafana /api/health through the Gateway: database" ok "$$HEALTH"
       |AUTH='admin:${grafanaAdminPassword.reveal}'
       |DSLIST=$$($$CURL -u "$$AUTH" https://grafana.$domain/api/datasources | python3 -c 'import sys,json; print(" ".join(d["uid"] + ":" + d["type"] for d in json.load(sys.stdin)))' 2>/dev/null || true)
       |for ENTRY in $$DSLIST; do
       |  UID_=$${ENTRY%%:*}; TYPE=$${ENTRY##*:}
       |  case "$$TYPE" in prometheus|loki) ;; *) continue ;; esac
       |  ST=""
       |  for I in $$(seq 1 12); do
       |    ST=$$($$CURL -u "$$AUTH" https://grafana.$domain/api/datasources/uid/$$UID_/health | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)
       |    [ "$$ST" = "OK" ] && break
       |    sleep 5
       |  done
       |  chk "Grafana's check of the $$TYPE data source" OK "$$ST"
       |done
       |KC=$$($kc -n $ns exec deploy/grafana -c grafana -- sh -c 'wget -q -T 8 -O - --no-check-certificate $keycloak | head -c 200' 2>/dev/null | grep -c issuer || true)
       |chk "the Grafana pod reaches Keycloak's discovery document" 1 "$$KC"
       |# the operator's admission webhook, called by the API server
       |cat > /tmp/rule-ok.yaml <<EOF
       |apiVersion: monitoring.coreos.com/v1
       |kind: PrometheusRule
       |metadata: {name: netpol-test, namespace: $ns}
       |spec:
       |  groups:
       |  - name: netpol-test
       |    rules:
       |    - alert: NetpolTest
       |      expr: vector(1) > 2
       |EOF
       |sed 's/vector(1) > 2/this is not ( valid/' /tmp/rule-ok.yaml > /tmp/rule-bad.yaml
       |OUT=$$($kc apply --dry-run=server -f /tmp/rule-ok.yaml 2>&1 || true)
       |case "$$OUT" in *'(server dry run)'*) chk "a valid PrometheusRule is accepted by the webhook" a a ;; *) chk "a valid PrometheusRule is accepted by the webhook" accepted "$$OUT" ;; esac
       |OUT=$$($kc apply --dry-run=server -f /tmp/rule-bad.yaml 2>&1 || true)
       |case "$$OUT" in *'admission webhook'*|*'denied the request'*) chk "an invalid PrometheusRule is refused by the webhook" a a ;; *) chk "an invalid PrometheusRule is refused by the webhook" refused "$$OUT" ;; esac
       |rm -f /tmp/rule-ok.yaml /tmp/rule-bad.yaml
       |AM=$$($kc get --raw "/api/v1/namespaces/$ns/services/kps-alertmanager:9093/proxy/api/v2/status" 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin)["cluster"]["status"])' 2>/dev/null || true)
       |chk "Alertmanager answers through the API server's proxy: cluster status" ready "$$AM"
       |if [ "$$FAIL" -ne 0 ]; then
       |  echo "the test of $ns failed; removing its network policies again" >&2
       |  $$NP remove $ns >&2 || true
       |  exit 1
       |fi
       |echo "ok    $ns is locked down and works"
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-network-lockdown-monitoring")(
      stage("lock-monitoring", node)
        .task("default deny for monitoring, then test it")(
          Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 1500)
        )
        .task("monitoring locked down")(
          Task.Debug(
            "monitoring has default-deny network policies (orphera-netpol show monitoring lists them): Prometheus, Grafana and Alertmanager work."
          )
        )
        .build
    )
