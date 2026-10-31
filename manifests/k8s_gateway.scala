// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 10: the cluster's front door. Needs
// k8s_cilium.scala (GatewayClass `cilium`, the address pool) and
// k8s_cert_manager.scala (ClusterIssuer `orphera-ca`).
//
// In the namespace `gateway`:
//   - a wildcard Certificate for *.k8s.ljalbinson.com (and k8s.ljalbinson.com),
//     issued by cert-manager from the sub-CA, in the Secret wildcard-k8s-tls;
//   - a Gateway `public` of the class `cilium` with a port 80 listener and a
//     port 443 listener (TLS terminated with that Secret) for hostnames under
//     k8s.ljalbinson.com, open to routes from every namespace. Cilium creates a
//     LoadBalancer Service for it, and the playbook pins its address to
//     10.10.3.100 (annotation lbipam.cilium.io/ips), which is where the
//     wildcard DNS record *.k8s.ljalbinson.com points;
//   - an HTTPRoute that redirects plain HTTP to HTTPS.
// To publish an application, create an HTTPRoute with
// `parentRefs: [{name: public, namespace: gateway, sectionName: https}]` and a
// hostname under k8s.ljalbinson.com.
//
// The last stage proves the whole path with a throwaway application: nginx
// behind an HTTPRoute for hello.k8s.ljalbinson.com, requested over HTTPS by
// name (so the wildcard DNS record is tested too) with the root CA as the
// only trust, and over HTTP to see the redirect.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_gateway extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val domain = "k8s.ljalbinson.com"
  private val lbIp = "10.10.3.100"
  private val stateDir = "/etc/kubernetes/orphera"

  // The Cilium operator checks for the Gateway API CRDs once, at start. If the
  // API server was unreachable at that moment (seen after the API servers were
  // restarted by k8s_oidc.scala) it logs "Required GatewayAPI resources are not
  // found" and never starts the Gateway controller; the Gateway then sits at
  // "Waiting for controller". The CRDs are checked here and the operator is
  // restarted if it gave up.
  private val controllerScript =
    s"""set -e
       |for CRD in gatewayclasses gateways httproutes referencegrants grpcroutes; do
       |  $kc get crd $$CRD.gateway.networking.k8s.io >/dev/null || { echo "CRD $$CRD.gateway.networking.k8s.io is missing; run k8s_cilium.scala" >&2; exit 1; }
       |done
       |if $kc -n kube-system logs -l name=cilium-operator --tail=3000 2>/dev/null | grep -q 'Required GatewayAPI resources are not found'; then
       |  echo "the Cilium operator started without the Gateway controller; restarting it"
       |  $kc -n kube-system rollout restart deployment/cilium-operator
       |  $kc -n kube-system rollout status deployment/cilium-operator --timeout=300s
       |  sleep 20
       |else
       |  echo "the Cilium operator's Gateway controller was not disabled at start"
       |fi
       |""".stripMargin

  private val gatewayScript =
    s"""set -e
       |$kc create namespace gateway --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: cert-manager.io/v1
       |kind: Certificate
       |metadata:
       |  name: wildcard-k8s
       |  namespace: gateway
       |spec:
       |  secretName: wildcard-k8s-tls
       |  dnsNames:
       |  - "*.$domain"
       |  - "$domain"
       |  issuerRef:
       |    name: orphera-ca
       |    kind: ClusterIssuer
       |---
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: Gateway
       |metadata:
       |  name: public
       |  namespace: gateway
       |spec:
       |  gatewayClassName: cilium
       |  infrastructure:
       |    annotations:
       |      lbipam.cilium.io/ips: "$lbIp"
       |  listeners:
       |  - name: http
       |    protocol: HTTP
       |    port: 80
       |    hostname: "*.$domain"
       |    allowedRoutes:
       |      namespaces:
       |        from: All
       |  - name: https
       |    protocol: HTTPS
       |    port: 443
       |    hostname: "*.$domain"
       |    tls:
       |      mode: Terminate
       |      certificateRefs:
       |      - name: wildcard-k8s-tls
       |    allowedRoutes:
       |      namespaces:
       |        from: All
       |---
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: http-to-https
       |  namespace: gateway
       |spec:
       |  parentRefs:
       |  - name: public
       |    sectionName: http
       |  hostnames:
       |  - "*.$domain"
       |  rules:
       |  - filters:
       |    - type: RequestRedirect
       |      requestRedirect:
       |        scheme: https
       |        statusCode: 301
       |EOF
       |$kc -n gateway wait --for=condition=Ready certificate/wildcard-k8s --timeout=180s
       |$kc -n gateway wait --for=condition=Programmed gateway/public --timeout=180s
       |IP=""
       |for I in $$(seq 1 30); do
       |  IP=$$($kc -n gateway get gateway public -o jsonpath='{.status.addresses[0].value}')
       |  [ -n "$$IP" ] && break
       |  sleep 2
       |done
       |echo "Gateway address: $${IP:-none}"
       |[ "$$IP" = "$lbIp" ] || { echo "the Gateway got $${IP:-no address}, expected $lbIp (the address the wildcard DNS record points at)" >&2; exit 1; }
       |$kc get gateway -n gateway
       |""".stripMargin

  private val testScript =
    s"""set -e
       |$kc create namespace gw-test --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata:
       |  name: hello
       |  namespace: gw-test
       |spec:
       |  replicas: 2
       |  selector:
       |    matchLabels: {app: hello}
       |  template:
       |    metadata:
       |      labels: {app: hello}
       |    spec:
       |      containers:
       |      - name: nginx
       |        image: docker.io/library/nginx:stable
       |        ports:
       |        - containerPort: 80
       |---
       |apiVersion: v1
       |kind: Service
       |metadata:
       |  name: hello
       |  namespace: gw-test
       |spec:
       |  selector: {app: hello}
       |  ports:
       |  - port: 80
       |---
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: hello
       |  namespace: gw-test
       |spec:
       |  parentRefs:
       |  - name: public
       |    namespace: gateway
       |    sectionName: https
       |  hostnames:
       |  - hello.$domain
       |  rules:
       |  - backendRefs:
       |    - name: hello
       |      port: 80
       |EOF
       |$kc -n gw-test rollout status deployment/hello --timeout=300s
       |# a route's conditions are per parent, under status.parents
       |$kc -n gw-test wait --for=jsonpath='{.status.parents[0].conditions[?(@.type=="Accepted")].status}'=True httproute/hello --timeout=120s
       |CODE=""
       |for I in $$(seq 1 30); do
       |  CODE=$$(curl -s -m 5 --cacert $stateDir/ca.crt -o /dev/null -w '%{http_code}' https://hello.$domain/ || true)
       |  [ "$$CODE" = "200" ] && break
       |  sleep 2
       |done
       |REDIRECT=$$(curl -s -m 5 -o /dev/null -w '%{http_code} %{redirect_url}' http://hello.$domain/ || true)
       |$kc delete namespace gw-test --wait=false >/dev/null
       |echo "https://hello.$domain/ -> HTTP $$CODE; http://hello.$domain/ -> $$REDIRECT"
       |[ "$$CODE" = "200" ] || { echo "the HTTPS request did not return 200" >&2; exit 1; }
       |# Cilium writes the port into the Location header (https://host:443/); both forms are a redirect to HTTPS
       |case "$$REDIRECT" in
       |  "301 https://hello.$domain/"|"301 https://hello.$domain:443/") ;;
       |  *) echo "unexpected redirect: $$REDIRECT" >&2; exit 1 ;;
       |esac
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-gateway")(
      stage("gateway-controller", node)
        .task("make sure Cilium's Gateway controller is running")(
          Task.RunCommand(List("sh", "-c", controllerScript), timeoutSeconds = 400)
        )
        .build,
      stage("create-gateway", node)
        .task("wildcard certificate, Gateway and HTTP redirect")(
          Task.RunCommand(List("sh", "-c", gatewayScript), timeoutSeconds = 600)
        )
        .build,
      stage("test-gateway", node)
        .task("publish a test application and request it")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("gateway ready")(
          Task.Debug(
            s"Gateway gateway/public serves *.$domain on $lbIp: HTTP redirects to HTTPS, " +
              "HTTPS uses the cert-manager wildcard certificate."
          )
        )
        .build
    )
