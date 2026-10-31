// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 5: the network. Installs Cilium with Helm from
// tst30 (which has the admin kubeconfig) and turns the ten NotReady nodes
// Ready. Needs k8s_cluster.scala.
//
// What Cilium does here:
//   - replaces kube-proxy (`kubeProxyReplacement`); it reaches the API server
//     through the VIP, api.k8s.ljalbinson.com:8443, because there is no
//     kube-proxy to provide the in-cluster service address yet;
//   - carries pod traffic in VXLAN tunnels (8472/udp between the nodes), pod
//     addresses 10.244.0.0/16 handed out per node by Cilium (/24 each);
//   - announces LoadBalancer addresses on the LAN with ARP (L2 announcements):
//     the pool 10.10.3.100-10.10.3.119 is given to Services of type
//     LoadBalancer and the infra nodes (tst33-tst35) answer for them, so the
//     address moves to another infra node if one fails;
//   - Gateway API: the Gateway API CRDs are installed first (the standard
//     channel of the release in `gatewayApiVersion`), then Cilium's
//     GatewayClass `cilium` serves Gateways, which is the ingress of this
//     cluster (the wildcard *.k8s.ljalbinson.com points at the first pool
//     address, 10.10.3.100);
//   - Hubble (flow visibility) with the relay and the UI.
//
// The chart version is the newest patch of `ciliumMinor`, found with Helm,
// and the images come from quay.io through the mirror on tst9. Helm itself
// is installed from the project's install script if it is missing.
//
// The last stage proves the result with a real Service: an nginx Deployment
// of two pods, exposed as a LoadBalancer, must get an address from the pool
// and answer on it. The test objects are removed afterwards.
//
// Versions I could not check where this was written: `ciliumMinor` (1.18),
// `gatewayApiVersion` (v1.3.0) and the API version of the two pool objects
// (cilium.io/v2alpha1). If a step reports that one does not exist or is not
// served, change that value.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_cilium extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ciliumMinor = "1.18"
  private val gatewayApiVersion = "v1.3.0"
  private val apiHost = "api.k8s.ljalbinson.com"
  private val apiPort = 8443
  private val podCidr = "10.244.0.0/16"
  private val poolStart = "10.10.3.100"
  private val poolStop = "10.10.3.119"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val stateDir = "/etc/kubernetes/orphera"

  private val helmScript =
    """set -e
      |if command -v helm >/dev/null; then echo "helm already installed: $(helm version --short)"; exit 0; fi
      |curl -fsSL --retry 3 https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 -o /tmp/get-helm-3
      |bash /tmp/get-helm-3
      |rm -f /tmp/get-helm-3
      |echo "helm installed: $(helm version --short)"""".stripMargin

  private val gatewayCrdsScript =
    s"""set -e
       |$kc apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/$gatewayApiVersion/standard-install.yaml
       |$kc get crd gateways.gateway.networking.k8s.io httproutes.gateway.networking.k8s.io gatewayclasses.gateway.networking.k8s.io
       |echo "Gateway API $gatewayApiVersion CRDs installed"""".stripMargin

  private val valuesYaml =
    s"""cat > $stateDir/cilium-values.yaml <<EOF
       |kubeProxyReplacement: true
       |k8sServiceHost: $apiHost
       |k8sServicePort: $apiPort
       |routingMode: tunnel
       |tunnelProtocol: vxlan
       |ipam:
       |  mode: cluster-pool
       |  operator:
       |    clusterPoolIPv4PodCIDRList:
       |    - $podCidr
       |    clusterPoolIPv4MaskSize: 24
       |l2announcements:
       |  enabled: true
       |k8sClientRateLimit:
       |  qps: 32
       |  burst: 60
       |devices: enp1s0
       |gatewayAPI:
       |  enabled: true
       |operator:
       |  replicas: 2
       |hubble:
       |  enabled: true
       |  relay:
       |    enabled: true
       |  ui:
       |    enabled: true
       |EOF""".stripMargin

  private val installScript =
    s"""set -e
       |$valuesYaml
       |helm repo add cilium https://helm.cilium.io/ --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart cilium/cilium --version '~$ciliumMinor.0' | awk '/^version:/{print $$2}')
       |[ -n "$$VER" ] || { echo "no Cilium chart of minor $ciliumMinor found" >&2; exit 1; }
       |echo "installing Cilium chart $$VER"
       |helm upgrade --install cilium cilium/cilium --version "$$VER" --namespace kube-system \\
       |  -f $stateDir/cilium-values.yaml --kubeconfig /etc/kubernetes/admin.conf --wait --timeout 10m
       |echo "Cilium $$VER installed"""".stripMargin

  private val poolScript =
    s"""set -e
       |cat <<EOF | $kc apply -f -
       |apiVersion: cilium.io/v2alpha1
       |kind: CiliumLoadBalancerIPPool
       |metadata:
       |  name: lan-pool
       |spec:
       |  blocks:
       |  - start: "$poolStart"
       |    stop: "$poolStop"
       |---
       |apiVersion: cilium.io/v2alpha1
       |kind: CiliumL2AnnouncementPolicy
       |metadata:
       |  name: lan-l2
       |spec:
       |  nodeSelector:
       |    matchLabels:
       |      pool: infra
       |  interfaces:
       |  - ^enp1s0$$
       |  loadBalancerIPs: true
       |EOF
       |$kc get ciliumloadbalancerippools,ciliuml2announcementpolicies""".stripMargin

  private val allReadyScript =
    s"""[ "$$($kc get nodes --no-headers | awk '$$2=="Ready"' | wc -l)" -eq 10 ]"""

  // nginx from Docker Hub (through the mirror on :5002). The Service must get
  // an address in the pool and answer on it.
  private val testScript =
    s"""set -e
       |$kc create namespace orphera-test --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: apps/v1
       |kind: Deployment
       |metadata:
       |  name: web
       |  namespace: orphera-test
       |spec:
       |  replicas: 2
       |  selector:
       |    matchLabels: {app: web}
       |  template:
       |    metadata:
       |      labels: {app: web}
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
       |  name: web
       |  namespace: orphera-test
       |spec:
       |  type: LoadBalancer
       |  selector: {app: web}
       |  ports:
       |  - port: 80
       |EOF
       |$kc -n orphera-test rollout status deployment/web --timeout=300s
       |IP=""
       |for I in $$(seq 1 30); do
       |  IP=$$($kc -n orphera-test get svc web -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
       |  [ -n "$$IP" ] && break
       |  sleep 2
       |done
       |[ -n "$$IP" ] || { echo "the Service got no LoadBalancer address" >&2; exit 1; }
       |echo "the Service got $$IP"
       |OK=""
       |for I in $$(seq 1 20); do
       |  if curl -s -m 3 -o /dev/null -w '%{http_code}' http://$$IP/ | grep -qx 200; then OK=yes; break; fi
       |  sleep 2
       |done
       |$kc delete namespace orphera-test --wait=false >/dev/null
       |[ -n "$$OK" ] || { echo "http://$$IP/ did not answer 200" >&2; exit 1; }
       |echo "http://$$IP/ answers 200 (nginx behind a Cilium LoadBalancer address)"
       |""".stripMargin

  private val statusScript =
    s"""$kc -n kube-system get pods -l k8s-app=cilium -o wide
       |$kc get nodes
       |$kc get gatewayclass""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-cilium")(
      stage("install-helm", node)
        .task("install helm")(
          Task.RunCommand(List("sh", "-c", helmScript), timeoutSeconds = 120)
        )
        .build,
      stage("gateway-api-crds", node)
        .task("install the Gateway API CRDs")(
          Task.RunCommand(List("sh", "-c", gatewayCrdsScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-cilium", node)
        .task("helm install Cilium")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 900)
        )
        .build,
      stage("confirm-nodes-ready", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", allReadyScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 300
          )
        )
        .task("show Cilium pods, nodes, GatewayClass")(
          Task.RunCommand(List("sh", "-c", statusScript), timeoutSeconds = 60)
        )
        .build,
      stage("load-balancer-pool", node)
        .task("create the address pool and the L2 announcement policy")(
          Task.RunCommand(List("sh", "-c", poolScript), timeoutSeconds = 60)
        )
        .build,
      stage("test-load-balancer", node)
        .task("nginx behind a LoadBalancer Service")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("network ready")(
          Task.Debug(
            "Cilium is installed: 10 nodes Ready, kube-proxy replaced, LoadBalancer addresses " +
              s"$poolStart-$poolStop announced by the infra nodes, GatewayClass cilium available."
          )
        )
        .build
    )
