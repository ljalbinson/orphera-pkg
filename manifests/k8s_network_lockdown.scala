// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 19, part 2: default-deny network policies on real
// namespaces. Needs k8s_network_policy.scala (run it again first: the helper
// gained the option `nodes`), k8s_external_dns.scala, k8s_argocd.scala and
// k8s_logging.scala. Safe to repeat.
//
// Each namespace is locked down with /usr/local/sbin/orphera-netpol, then its own
// work is tested end to end; if the test fails the policies of that namespace are
// removed again, the playbook stops, and the namespaces before it stay locked down.
//
//   external-dns   api world
//       Needs the API server and the DNS server tst40 (outside the cluster).
//       Test: an HTTPRoute with a new hostname still gets its record and its TXT
//       record on tst40, and both are removed again when the route goes.
//   argocd         api world gateway
//       Needs the API server, Keycloak and GitHub (outside the cluster) and the Gateway.
//       Test: /healthz and the OIDC settings through the Gateway, the local admin logs
//       in, and an Application is synced from GitHub, read through the API and pruned.
//   logging        api scrape nodes
//       Alloy reads logs through the API server and pushes to Loki (same namespace);
//       Grafana (namespace monitoring) queries Loki; the API server's service proxy
//       reaches Loki (that is how the test and k8s_logging.scala query it).
//       Test: kube-system logs keep arriving from all 10 nodes, and Grafana's check of
//       the Loki data source says OK.
//
// Left alone for now: monitoring (Prometheus scrapes every namespace and node, and the
// Prometheus operator's webhook is called by the API server: its own step), cert-manager,
// gateway, kube-system, ceph-csi-rbd.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_network_lockdown extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val np = "/usr/local/sbin/orphera-netpol"
  private val domain = "k8s.ljalbinson.com"
  private val dnsPrimary = "10.10.3.40"
  private val repo = "https://github.com/ljalbinson/orphera-pkg.git"
  private val issuer = "https://tst26.ljalbinson.com:8443/realms/k8s"
  private val grafanaAdminPassword = Secrets.secret("k8s/grafana-admin")

  private val prelude =
    s"""set -e
       |FAIL=0
       |NP=$np
       |CURL="curl -s -m 10 --cacert $stateDir/ca.crt"
       |grep -q 'nodes' $$NP || { echo "$$NP is old; run k8s_network_policy.scala first" >&2; exit 1; }
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |finish() {  # namespace
       |  if [ "$$FAIL" -ne 0 ]; then
       |    echo "the test of $$1 failed; removing its network policies again" >&2
       |    $$NP remove "$$1" >&2 || true
       |    exit 1
       |  fi
       |  echo "ok    $$1 is locked down and works"
       |}
       |""".stripMargin

  private val externalDnsScript =
    prelude +
      s"""$$NP apply external-dns api world
       |sleep 20
       |NAME=extdns-lock-$$(date +%s)
       |HOST=$$NAME.$domain
       |$kc create namespace extdns-lock --dry-run=client -o yaml | $kc apply -f - >/dev/null
       |cat <<EOF | $kc apply -f - >/dev/null
       |apiVersion: v1
       |kind: Service
       |metadata: {name: none, namespace: extdns-lock}
       |spec:
       |  ports: [{port: 80}]
       |---
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata: {name: $$NAME, namespace: extdns-lock}
       |spec:
       |  parentRefs: [{name: public, namespace: gateway, sectionName: https}]
       |  hostnames: ["$$HOST"]
       |  rules:
       |  - backendRefs: [{name: none, port: 80}]
       |EOF
       |TXT=""
       |for I in $$(seq 1 48); do TXT=$$(dig +short +norecurse @$dnsPrimary TXT a-$$HOST); [ -n "$$TXT" ] && break; sleep 5; done
       |if echo "$$TXT" | grep -q orphera-k8s; then echo "ok    external-dns still writes records to $dnsPrimary"; else echo "FAIL  no ownership TXT record for $$HOST: '$$TXT'" >&2; FAIL=1; fi
       |$kc delete namespace extdns-lock --wait=false >/dev/null
       |GONE="x"
       |for I in $$(seq 1 48); do GONE=$$(dig +short +norecurse @$dnsPrimary TXT a-$$HOST); [ -z "$$GONE" ] && break; sleep 5; done
       |chk "external-dns still removes records" "" "$$GONE"
       |finish external-dns
       |""".stripMargin

  private val argocdScript =
    prelude +
      s"""$$NP apply argocd api world gateway
       |sleep 20
       |HOST=argocd.$domain
       |CODE=""
       |for I in $$(seq 1 30); do CODE=$$($$CURL -o /dev/null -w '%{http_code}' https://$$HOST/healthz || true); [ "$$CODE" = "200" ] && break; sleep 3; done
       |chk "https://$$HOST/healthz through the Gateway" 200 "$$CODE"
       |OIDC=$$($$CURL https://$$HOST/api/v1/settings | python3 -c 'import sys,json; o=json.load(sys.stdin).get("oidcConfig",{}); print(o.get("name","") + " " + o.get("issuer",""))' 2>/dev/null || true)
       |chk "the settings show the Keycloak OIDC configuration" "Keycloak $issuer" "$$OIDC"
       |PW=$$($kc -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)
       |TOKEN=$$(printf '{"username":"admin","password":"%s"}' "$$PW" | $$CURL -H 'Content-Type: application/json' -d @- https://$$HOST/api/v1/session | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
       |if [ -n "$$TOKEN" ]; then echo "ok    the local admin can log in to the API"; else echo "FAIL  the local admin could not log in" >&2; FAIL=1; fi
       |cat <<EOF | $kc apply -f - >/dev/null
       |apiVersion: argoproj.io/v1alpha1
       |kind: Application
       |metadata:
       |  name: argocd-locktest
       |  namespace: argocd
       |  finalizers:
       |  - resources-finalizer.argocd.argoproj.io
       |spec:
       |  project: default
       |  source:
       |    repoURL: $repo
       |    targetRevision: main
       |    path: k8s/argocd-test
       |  destination:
       |    server: https://kubernetes.default.svc
       |    namespace: argocd-locktest
       |  syncPolicy:
       |    automated: {prune: true, selfHeal: true}
       |    syncOptions: [CreateNamespace=true]
       |EOF
       |ST=""
       |for I in $$(seq 1 60); do
       |  ST=$$($kc -n argocd get application argocd-locktest -o jsonpath='{.status.sync.status}/{.status.health.status}' 2>/dev/null || true)
       |  [ "$$ST" = "Synced/Healthy" ] && break
       |  sleep 5
       |done
       |chk "an Application is synced from GitHub" "Synced/Healthy" "$$ST"
       |[ "$$ST" = "Synced/Healthy" ] || $kc -n argocd get application argocd-locktest -o jsonpath='{.status.conditions}' >&2 || true
       |$kc -n argocd delete application argocd-locktest --wait=true --timeout=180s >/dev/null || true
       |$kc delete namespace argocd-locktest --wait=false >/dev/null 2>&1 || true
       |finish argocd
       |""".stripMargin

  private val loggingScript =
    prelude +
      s"""$$NP apply logging api scrape nodes
       |sleep 20
       |Q='count(sum by (node) (count_over_time({namespace="kube-system"}[2m])))'
       |GOT=0
       |for I in $$(seq 1 60); do
       |  GOT=$$($kc get --raw "/api/v1/namespaces/logging/services/loki:3100/proxy/loki/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$Q")" 2>/dev/null \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)' 2>/dev/null || echo 0)
       |  [ "$$GOT" -ge 10 ] && break
       |  sleep 5
       |done
       |if [ "$$GOT" -ge 10 ]; then echo "ok    logs of the last 2 minutes arrive from $$GOT nodes"; else echo "FAIL  logs of the last 2 minutes arrive from only $$GOT nodes" >&2; FAIL=1; fi
       |AUTH='admin:${grafanaAdminPassword.reveal}'
       |UID_=$$($$CURL -u "$$AUTH" https://grafana.$domain/api/datasources | python3 -c 'import sys,json; print(next((d["uid"] for d in json.load(sys.stdin) if d["type"] == "loki"), ""))' 2>/dev/null || true)
       |HEALTH=""
       |for I in $$(seq 1 12); do
       |  HEALTH=$$($$CURL -u "$$AUTH" https://grafana.$domain/api/datasources/uid/$$UID_/health | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)
       |  [ "$$HEALTH" = "OK" ] && break
       |  sleep 5
       |done
       |chk "Grafana's check of the Loki data source" OK "$$HEALTH"
       |finish logging
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-network-lockdown")(
      stage("lock-external-dns", node)
        .task("default deny for external-dns, then test it")(
          Task.RunCommand(List("sh", "-c", externalDnsScript), timeoutSeconds = 900)
        )
        .build,
      stage("lock-argocd", node)
        .task("default deny for argocd, then test it")(
          Task.RunCommand(List("sh", "-c", argocdScript), timeoutSeconds = 900)
        )
        .build,
      stage("lock-logging", node)
        .task("default deny for logging, then test it")(
          Task.RunCommand(List("sh", "-c", loggingScript), timeoutSeconds = 900)
        )
        .task("namespaces locked down")(
          Task.Debug(
            "external-dns, argocd and logging have default-deny network policies (orphera-netpol show NAMESPACE lists them). Next: monitoring."
          )
        )
        .build
    )
