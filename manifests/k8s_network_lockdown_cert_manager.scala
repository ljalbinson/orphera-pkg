// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 19, part 4: default-deny network policies on the
// namespace cert-manager. Needs k8s_cert_manager.scala and k8s_network_policy.scala
// (run it again first if /usr/local/sbin/orphera-netpol on tst30 predates the option
// `nodes`). Safe to repeat.
//
// cert-manager gets `api nodes`:
//   api    the controller, the cainjector and the webhook all work through the API server
//   nodes  the API server calls the cert-manager webhook (admission of Certificates,
//          Issuers and so on) on port 10250 of its pod
// Everything else, in either direction, is denied. The ClusterIssuer orphera-ca signs
// with a CA key held in a Secret, so nothing outside the cluster is needed: no `world`.
// Nothing scrapes cert-manager (no ServiceMonitor), so no `scrape`.
//
// The test (a failure removes the policies of cert-manager again and stops):
//   - the three Deployments (cert-manager, cainjector, webhook) are available
//   - a Certificate for orphera-ca in a throw-away namespace becomes Ready, and its
//     chain verifies against the cluster CA (the webhook admitted it, the controller
//     signed it)
//   - the webhook refuses an invalid Certificate (a server-side dry run: no names)
//   - the ClusterIssuer is still Ready
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_network_lockdown_cert_manager extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ns = "cert-manager"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val np = "/usr/local/sbin/orphera-netpol"

  private val script =
    s"""set -e
       |FAIL=0
       |NP=$np
       |grep -q 'nodes' $$NP || { echo "$$NP is old; run k8s_network_policy.scala first" >&2; exit 1; }
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |$kc get namespace $ns >/dev/null
       |$kc wait --for=condition=Ready clusterissuer/orphera-ca --timeout=60s >/dev/null
       |$$NP apply $ns api nodes
       |sleep 30
       |for D in cert-manager cert-manager-cainjector cert-manager-webhook; do
       |  AV=$$($kc -n $ns get deployment $$D -o jsonpath='{.status.availableReplicas}' 2>/dev/null || true)
       |  [ -n "$$AV" ] && [ "$$AV" -ge 1 ] && AV=available || AV="not available ($$AV)"
       |  chk "deployment $$D" available "$$AV"
       |done
       |# a Certificate must be admitted by the webhook and signed by the controller
       |$kc delete namespace cm-lock --ignore-not-found --wait=true --timeout=120s >/dev/null 2>&1 || true
       |$kc create namespace cm-lock >/dev/null
       |cat <<EOF | $kc apply -f - >/dev/null 2>&1 || true
       |apiVersion: cert-manager.io/v1
       |kind: Certificate
       |metadata: {name: test, namespace: cm-lock}
       |spec:
       |  secretName: test-tls
       |  dnsNames: [lock.k8s.ljalbinson.com]
       |  issuerRef: {name: orphera-ca, kind: ClusterIssuer}
       |EOF
       |READY=""
       |for I in $$(seq 1 40); do
       |  READY=$$($kc -n cm-lock get certificate test -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
       |  [ "$$READY" = "True" ] && break
       |  sleep 5
       |done
       |chk "a Certificate is admitted and issued" True "$$READY"
       |VERIFY=""
       |if [ "$$READY" = "True" ]; then
       |  $kc -n cm-lock get secret test-tls -o jsonpath='{.data.tls\\.crt}' | base64 -d > /tmp/cm-lock.crt
       |  VERIFY=$$(cd $stateDir && openssl verify -CAfile ca.crt -untrusted subca.crt /tmp/cm-lock.crt 2>&1 | tail -1 | grep -c ': OK' || true)
       |  rm -f /tmp/cm-lock.crt
       |fi
       |chk "the issued certificate verifies against the cluster CA" 1 "$$VERIFY"
       |# an invalid Certificate (no names) must be refused by the webhook
       |cat > /tmp/cm-bad.yaml <<EOF
       |apiVersion: cert-manager.io/v1
       |kind: Certificate
       |metadata: {name: bad, namespace: cm-lock}
       |spec:
       |  secretName: bad-tls
       |  issuerRef: {name: orphera-ca, kind: ClusterIssuer}
       |EOF
       |OUT=$$($kc apply --dry-run=server -f /tmp/cm-bad.yaml 2>&1 || true)
       |rm -f /tmp/cm-bad.yaml
       |case "$$OUT" in *'admission webhook'*|*'denied the request'*|*'at least one of'*) chk "the webhook refuses an invalid Certificate" a a ;; *) chk "the webhook refuses an invalid Certificate" refused "$$OUT" ;; esac
       |ISS=$$($kc get clusterissuer orphera-ca -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
       |chk "ClusterIssuer orphera-ca is Ready" True "$$ISS"
       |$kc delete namespace cm-lock --wait=false >/dev/null 2>&1 || true
       |if [ "$$FAIL" -ne 0 ]; then
       |  echo "the test of $ns failed; removing its network policies again" >&2
       |  $$NP remove $ns >&2 || true
       |  exit 1
       |fi
       |echo "ok    $ns is locked down and works"
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-network-lockdown-cert-manager")(
      stage("lock-cert-manager", node)
        .task("default deny for cert-manager, then test it")(
          Task.RunCommand(List("sh", "-c", script), timeoutSeconds = 900)
        )
        .task("cert-manager locked down")(
          Task.Debug(
            "cert-manager has default-deny network policies (orphera-netpol show cert-manager lists them): certificates are still issued."
          )
        )
        .build
    )
