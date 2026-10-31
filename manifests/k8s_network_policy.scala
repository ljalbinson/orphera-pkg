// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 19, part 1: network policy tooling and its test. Needs
// k8s_cilium.scala (Cilium enforces the policies), k8s_gateway.scala and
// k8s_pod_security.scala. This playbook changes NO existing namespace; it only
// installs a helper on tst30 and proves the rules on throw-away namespaces.
//
// The helper /usr/local/sbin/orphera-netpol (run as root on tst30) is the one way
// namespaces are locked down in the following steps:
//
//     orphera-netpol apply NAMESPACE [api] [gateway] [world] [scrape]
//     orphera-netpol remove NAMESPACE
//     orphera-netpol show NAMESPACE
//
// `apply` is declarative: it creates the CiliumNetworkPolicies listed and removes
// the optional ones that are not. A namespace gets
//   orphera-base     always: the pods of the namespace may talk to each other
//                    (ingress and egress) and to CoreDNS (port 53); everything
//                    else is denied in both directions
//   orphera-api      `api`: egress to the Kubernetes API server (entity kube-apiserver)
//   orphera-gateway  `gateway`: ingress from the Gateway (entity ingress), so
//                    HTTPRoutes to the namespace keep working
//   orphera-world    `world`: egress to anything outside the cluster (entity world)
//   orphera-scrape   `scrape`: ingress from the namespace monitoring (Prometheus)
// Kubelet health probes are not affected (Cilium always lets the node reach its
// own pods). Pods in kube-system, gateway and ceph-csi-rbd are not to be locked down.
//
// The test creates the namespaces np-test and np-other, each with an nginx
// Deployment and Service (web) and a curl pod (client), and checks, retrying while
// Cilium applies each change:
//   - before any policy everything reaches everything
//   - with orphera-base on np-test: np-other cannot reach np-test, np-test cannot reach
//     np-other, np-test pods still reach each other and resolve names
//   - with `api`: a pod in np-test reaches the API server, and could not before
//   - with `world`: it reaches github.com, and could not before
//   - with `gateway`: an HTTPRoute to np-test works through the Gateway, and
//     returned an error before
//   - after `remove` everything is open again
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The policy field names (fromEntities: ingress, toEntities:
// kube-apiserver) are from memory of Cilium 1.18; if a check fails, the output
// says which, and `orphera-netpol show` and `cilium-dbg` on the node help.
object k8s_network_policy extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val helper = "/usr/local/sbin/orphera-netpol"
  private val domain = "k8s.ljalbinson.com"

  private val helperText =
    """#!/bin/bash
# Managed by Orphera (k8s_network_policy.scala).
# orphera-netpol apply NAMESPACE [api] [gateway] [world] [scrape] | remove NAMESPACE | show NAMESPACE
set -e
KC="kubectl --kubeconfig /etc/kubernetes/admin.conf"
CMD=${1:-}; NS=${2:-}
[ -n "$CMD" ] && [ -n "$NS" ] || { sed -n '3p' "$0" >&2; exit 2; }
$KC get namespace "$NS" >/dev/null
case "$NS" in kube-system|kube-public|kube-node-lease|gateway|ceph-csi-rbd) echo "refusing to lock down $NS" >&2; exit 2 ;; esac
OPTS="api gateway world scrape"
case "$CMD" in
  show) $KC -n "$NS" get ciliumnetworkpolicies; exit 0 ;;
  remove)
    $KC -n "$NS" delete ciliumnetworkpolicy orphera-base orphera-api orphera-gateway orphera-world orphera-scrape --ignore-not-found
    exit 0 ;;
  apply) shift 2 ;;
  *) echo "unknown command $CMD" >&2; exit 2 ;;
esac
WANT=" $* "
for O in $*; do case " $OPTS " in *" $O "*) ;; *) echo "unknown option $O" >&2; exit 2 ;; esac; done
cat <<EOF | $KC apply -f -
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: orphera-base
  namespace: $NS
spec:
  endpointSelector: {}
  ingress:
  - fromEndpoints:
    - {}
  egress:
  - toEndpoints:
    - {}
  - toEndpoints:
    - matchLabels:
        "k8s:io.kubernetes.pod.namespace": kube-system
        "k8s:k8s-app": kube-dns
    toPorts:
    - ports:
      - port: "53"
        protocol: ANY
EOF
policy() {  # option, name, rules
  if echo "$WANT" | grep -q " $1 "; then
    printf 'apiVersion: cilium.io/v2\nkind: CiliumNetworkPolicy\nmetadata:\n  name: orphera-%s\n  namespace: %s\nspec:\n  endpointSelector: {}\n%s' "$1" "$NS" "$2" | $KC apply -f -
  else
    $KC -n "$NS" delete ciliumnetworkpolicy "orphera-$1" --ignore-not-found >/dev/null
  fi
}
policy api '  egress:
  - toEntities:
    - kube-apiserver
'
policy gateway '  ingress:
  - fromEntities:
    - ingress
'
policy world '  egress:
  - toEntities:
    - world
'
policy scrape '  ingress:
  - fromEndpoints:
    - matchLabels:
        "k8s:io.kubernetes.pod.namespace": monitoring
'
echo "network policies for $NS: base $*"
"""

  private val installScript =
    s"""set -e
       |cat > $helper <<'HELPEREOF'
       |$helperText
       |HELPEREOF
       |chmod 755 $helper
       |bash -n $helper
       |$kc get crd ciliumnetworkpolicies.cilium.io >/dev/null
       |echo "$helper installed"
       |""".stripMargin

  // After the API servers restart (Pod Security, Keycloak login) the Cilium operator's
  // Gateway controller can give up ("timed out waiting for cache to be synced for Kind
  // *v1.Gateway") and routes are never accepted. A restart of the operator cures it.
  private val operatorScript =
    s"""set -e
       |LOGS=$$($kc -n kube-system logs -l name=cilium-operator --tail=500 2>/dev/null || true)
       |if echo "$$LOGS" | grep -q 'timed out waiting for cache to be synced for Kind'; then
       |  echo "the Cilium operator's Gateway controller gave up after an API server restart; restarting the operator"
       |  $kc -n kube-system rollout restart deployment/cilium-operator
       |  $kc -n kube-system rollout status deployment/cilium-operator --timeout=300s
       |  sleep 20
       |else
       |  echo "the Cilium operator is healthy"
       |fi
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |NP=$helper
       |HOST=np-test.$domain
       |$kc delete namespace np-test np-other --ignore-not-found --wait=true --timeout=180s >/dev/null 2>&1 || true
       |for N in np-test np-other; do
       |  $kc create namespace $$N >/dev/null
       |  cat <<EOF | $kc apply -f - >/dev/null
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: {name: web, namespace: $$N}
       |spec:
       |  replicas: 1
       |  selector: {matchLabels: {app: web}}
       |  template:
       |    metadata: {labels: {app: web}}
       |    spec:
       |      containers:
       |      - {name: web, image: "docker.io/library/nginx:1.27-alpine", ports: [{containerPort: 80}]}
       |---
       |apiVersion: v1
       |kind: Service
       |metadata: {name: web, namespace: $$N}
       |spec:
       |  selector: {app: web}
       |  ports: [{port: 80}]
       |---
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata: {name: client, namespace: $$N}
       |spec:
       |  replicas: 1
       |  selector: {matchLabels: {app: client}}
       |  template:
       |    metadata: {labels: {app: client}}
       |    spec:
       |      containers:
       |      - {name: client, image: "docker.io/curlimages/curl:8.10.1", command: ["sleep", "3600"]}
       |EOF
       |done
       |for N in np-test np-other; do
       |  $kc -n $$N rollout status deploy/web --timeout=300s >/dev/null
       |  $kc -n $$N rollout status deploy/client --timeout=300s >/dev/null
       |done
       |c_exec() { $kc -n "$$1" exec deploy/client -- curl -s -m 6 -o /dev/null -w '%{http_code}' "$$2" 2>/dev/null || true; }
       |c_api() { $kc -n "$$1" exec deploy/client -- sh -c 'curl -sk -m 6 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)" https://kubernetes.default.svc/version' 2>/dev/null || true; }
       |c_gw() { curl -s -m 8 --cacert $stateDir/ca.crt -o /dev/null -w '%{http_code}' https://$$HOST/ 2>/dev/null || true; }
       |want() {  # description, regex, command...
       |  D=$$1; R=$$2; shift 2; G=""
       |  for I in $$(seq 1 12); do G=$$("$$@"); echo "$$G" | grep -Eq "$$R" && break; sleep 3; done
       |  if echo "$$G" | grep -Eq "$$R"; then echo "ok    $$D ($$G)"; else echo "FAIL  $$D: got '$$G', wanted $$R" >&2; FAIL=1; fi
       |}
       |want "no policy: np-other reaches np-test"      '^200$$' c_exec np-other http://web.np-test
       |want "no policy: np-test reaches np-other"      '^200$$' c_exec np-test http://web.np-other
       |$$NP apply np-test
       |want "base: np-other cannot reach np-test"      '^000$$' c_exec np-other http://web.np-test
       |want "base: np-test cannot reach np-other"      '^000$$' c_exec np-test http://web.np-other
       |want "base: np-test still reaches itself, with DNS" '^200$$' c_exec np-test http://web
       |want "base: np-test cannot reach the API server" '^000$$' c_api np-test
       |$$NP apply np-test api
       |want "api: np-test reaches the API server"      '^200$$' c_api np-test
       |want "api: still no way out to the Internet"    '^000$$' c_exec np-test https://github.com
       |$$NP apply np-test api world
       |want "world: np-test reaches github.com"        '^[23][0-9][0-9]$$' c_exec np-test https://github.com
       |cat <<EOF | $kc apply -f - >/dev/null
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata: {name: web, namespace: np-test}
       |spec:
       |  parentRefs: [{name: public, namespace: gateway, sectionName: https}]
       |  hostnames: ["$$HOST"]
       |  rules:
       |  - backendRefs: [{name: web, port: 80}]
       |EOF
       |ACC=""
       |for I in $$(seq 1 60); do
       |  ACC=$$($kc -n np-test get httproute web -o jsonpath='{.status.parents[0].conditions[?(@.type=="Accepted")].status}' 2>/dev/null || true)
       |  [ "$$ACC" = "True" ] && break
       |  sleep 3
       |done
       |if [ "$$ACC" != "True" ]; then
       |  echo "FAIL  the HTTPRoute in np-test was not accepted; its status:" >&2
       |  $kc -n np-test get httproute web -o jsonpath='{.status}' >&2 || true
       |  echo >&2
       |  $kc -n gateway get gateway public -o jsonpath='{.status.conditions}' >&2 || true
       |  echo >&2
       |  $kc -n kube-system logs deploy/cilium-operator --tail=15 >&2 || true
       |  exit 1
       |fi
       |want "no gateway rule: the Gateway cannot reach np-test" '^(000|50[0-9])$$' c_gw
       |$$NP apply np-test api world gateway
       |want "gateway: the route works through the Gateway" '^200$$' c_gw
       |$$NP remove np-test
       |want "removed: np-other reaches np-test again"  '^200$$' c_exec np-other http://web.np-test
       |want "removed: np-test reaches np-other again"  '^200$$' c_exec np-test http://web.np-other
       |$kc delete namespace np-test np-other --wait=false >/dev/null
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-network-policy")(
      stage("install-netpol-helper", node)
        .task("install orphera-netpol")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 60)
        )
        .build,
      stage("check-cilium-operator", node)
        .task("restart the operator if its Gateway controller gave up")(
          Task.RunCommand(List("sh", "-c", operatorScript), timeoutSeconds = 400)
        )
        .build,
      stage("test-network-policy", node)
        .task("lock down a test namespace step by step")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 1200)
        )
        .task("network policy tooling ready")(
          Task.Debug(
            s"$helper apply NAMESPACE [api] [gateway] [world] [scrape] locks a namespace down; the next steps use it on argocd, external-dns, logging and monitoring."
          )
        )
        .build
    )
