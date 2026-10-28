// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. A Cluster API management cluster on tst23 (10.10.5.35): a single-node k3s with Cluster API, the
// OpenStack infrastructure provider (CAPO), the Azimuth addon provider and the Azimuth OpenStack janitor. It is
// the first half of Magnum with the Cluster API Helm driver (magnum_single_node.scala is the second): Magnum
// does not build Kubernetes clusters itself, it asks this cluster to, through the Helm chart `openstack-cluster`.
//
// What it installs on tst23:
//   - k3s (one server, no traefik, no servicelb), version K3S_VERSION
//   - helm 3 and clusterctl (binaries, from get.helm.sh and the Cluster API GitHub release)
//   - Cluster API core, the kubeadm bootstrap and control-plane providers, cert-manager and CAPO, installed by
//     `clusterctl init --infrastructure openstack`
//   - cluster-api-addon-provider and cluster-api-janitor-openstack, from the Azimuth Helm repositories
//   - /var/lib/orphera/capi/kubeconfig: the kubeconfig of this cluster for Magnum, with the server address
//     https://tst23.ljalbinson.com:6443 (mode 0600)
//
// Versions are NOT pinned by default: clusterctl and the charts take the newest release. They are written to
// /var/lib/orphera/capi/versions.txt. The Magnum driver's Helm chart (default 0.26.0) was built against particular
// Cluster API and CAPO API versions; if clusters do not build, pin the versions that chart expects with these
// environment variables when running the playbook (each empty means the newest):
//   K3S_VERSION (e.g. v1.31.4+k3s1)   HELM_VERSION (default v3.16.4)
//   CAPI_VERSION (e.g. v1.9.5)         CAPO_VERSION (e.g. v0.11.5)
//   ADDON_PROVIDER_VERSION             JANITOR_VERSION                (chart versions)
//
// Network: the node needs to reach get.k3s.io, github.com (release downloads), ghcr.io, registry.k8s.io, quay.io and
// azimuth-cloud.github.io. If it cannot, the playbook stops at the first download with the address it could not
// reach.
//
// Resources: tst23 has 2 vCPU and 4 GB (config/tst23.yaml). k3s plus the Cluster API controllers use about 2 GB;
// Magnum's own containers go on the same node. Give the VM more memory if it swaps.
//
// The playbook is repeatable: components already installed are left alone (clusterctl init and helm upgrade
// --install are idempotent). Undo it with capi_management_cluster_teardown.scala. Check it with
// manifests/test_capi_management_cluster.sh. Not compiled where this was written.
object capi_management_cluster extends OrpheraClusterPlaybook:

  private val node = "tst23"
  private val fqdn = "tst23.ljalbinson.com"
  private val nodeIp = "10.10.5.35"
  private val stateDir = "/var/lib/orphera/capi"

  private def env(name: String, default: String = ""): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private val k3sVersion = env("K3S_VERSION")
  private val helmVersion = env("HELM_VERSION", "v3.16.4")
  private val capiVersion = env("CAPI_VERSION")
  private val capoVersion = env("CAPO_VERSION")
  private val addonVersion = env("ADDON_PROVIDER_VERSION")
  private val janitorVersion = env("JANITOR_VERSION")

  private def fill(s: String): String =
    s.replace("@@STATE@@", stateDir)
      .replace("@@FQDN@@", fqdn)
      .replace("@@IP@@", nodeIp)
      .replace("@@K3S@@", k3sVersion)
      .replace("@@HELM@@", helmVersion)
      .replace("@@CAPI@@", capiVersion)
      .replace("@@CAPO@@", capoVersion)
      .replace("@@ADDON@@", addonVersion)
      .replace("@@JANITOR@@", janitorVersion)

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes" >&2
      |exit 1""".stripMargin

  private val k3sScript =
    fill("""set -e
      |mkdir -p @@STATE@@
      |if [ -x /usr/local/bin/k3s ] && systemctl is-active --quiet k3s; then
      |  echo "k3s is running: $(k3s --version | head -n 1)"
      |else
      |  if [ -n "@@K3S@@" ]; then export INSTALL_K3S_VERSION="@@K3S@@"; fi
      |  export INSTALL_K3S_EXEC="server --disable traefik --disable servicelb --write-kubeconfig-mode 0600 --tls-san @@FQDN@@ --tls-san @@IP@@"
      |  if ! curl -fsSL --retry 3 --connect-timeout 30 https://get.k3s.io -o @@STATE@@/k3s-install.sh; then
      |    echo "cannot download https://get.k3s.io from this node" >&2
      |    exit 1
      |  fi
      |  sh @@STATE@@/k3s-install.sh
      |fi
      |for i in $(seq 1 60); do
      |  if k3s kubectl get nodes 2>/dev/null | grep -qw Ready; then break; fi
      |  sleep 5
      |done
      |k3s kubectl get nodes | grep -qw Ready || { echo "the k3s node is not Ready after 5 minutes" >&2; exit 1; }
      |k3s kubectl get nodes
      |""".stripMargin)

  private val toolsScript =
    fill("""set -e
      |if ! command -v helm >/dev/null 2>&1; then
      |  curl -fsSL --retry 3 --connect-timeout 30 -o @@STATE@@/helm.tgz https://get.helm.sh/helm-@@HELM@@-linux-amd64.tar.gz || { echo "cannot download helm @@HELM@@ from get.helm.sh" >&2; exit 1; }
      |  tar -xzf @@STATE@@/helm.tgz -C @@STATE@@ linux-amd64/helm
      |  install -m 0755 @@STATE@@/linux-amd64/helm /usr/local/bin/helm
      |  rm -rf @@STATE@@/linux-amd64 @@STATE@@/helm.tgz
      |fi
      |if ! command -v clusterctl >/dev/null 2>&1; then
      |  if [ -n "@@CAPI@@" ]; then URL=https://github.com/kubernetes-sigs/cluster-api/releases/download/@@CAPI@@/clusterctl-linux-amd64; else URL=https://github.com/kubernetes-sigs/cluster-api/releases/latest/download/clusterctl-linux-amd64; fi
      |  curl -fsSL --retry 3 --connect-timeout 30 -o @@STATE@@/clusterctl "$URL" || { echo "cannot download clusterctl from $URL" >&2; exit 1; }
      |  install -m 0755 @@STATE@@/clusterctl /usr/local/bin/clusterctl
      |  rm -f @@STATE@@/clusterctl
      |fi
      |helm version --short
      |clusterctl version -o short
      |""".stripMargin)

  // The kubeconfig for Magnum and for the commands below. k3s writes 127.0.0.1; Magnum is on this node too, but
  // the address is the name the certificate carries (--tls-san), so it also works from other hosts.
  private val kubeconfigScript =
    fill("""set -e
      |sed "s|https://127.0.0.1:6443|https://@@FQDN@@:6443|" /etc/rancher/k3s/k3s.yaml > @@STATE@@/kubeconfig.new
      |chmod 600 @@STATE@@/kubeconfig.new
      |mv @@STATE@@/kubeconfig.new @@STATE@@/kubeconfig
      |KUBECONFIG=@@STATE@@/kubeconfig k3s kubectl get nodes >/dev/null || { echo "the kubeconfig does not work against https://@@FQDN@@:6443" >&2; exit 1; }
      |echo "kubeconfig written to @@STATE@@/kubeconfig"
      |""".stripMargin)

  private val capiScript =
    fill("""set -e
      |export KUBECONFIG=@@STATE@@/kubeconfig
      |INFRA=openstack
      |if [ -n "@@CAPO@@" ]; then INFRA=openstack:@@CAPO@@; fi
      |ARGS="--infrastructure $INFRA"
      |if [ -n "@@CAPI@@" ]; then ARGS="$ARGS --core cluster-api:@@CAPI@@ --bootstrap kubeadm:@@CAPI@@ --control-plane kubeadm:@@CAPI@@"; fi
      |echo "clusterctl init $ARGS"
      |clusterctl init $ARGS 2>&1 | tail -n 25
      |for ns in cert-manager capi-system capi-kubeadm-bootstrap-system capi-kubeadm-control-plane-system capo-system; do
      |  k3s kubectl -n $ns wait --for=condition=Available deployment --all --timeout=600s || { echo "deployments in $ns are not available" >&2; k3s kubectl -n $ns get pods >&2; exit 1; }
      |done
      |echo "Cluster API, CAPO and cert-manager are available"
      |""".stripMargin)

  private val addonsScript =
    fill("""set -e
      |export KUBECONFIG=@@STATE@@/kubeconfig
      |helm repo add azimuth-addon https://azimuth-cloud.github.io/cluster-api-addon-provider >/dev/null
      |helm repo add azimuth-janitor https://azimuth-cloud.github.io/cluster-api-janitor-openstack >/dev/null
      |if ! helm repo update >/dev/null; then
      |  echo 'cannot reach azimuth-cloud.github.io' >&2
      |  exit 1
      |fi
      |ADDON_ARGS=
      |JAN_ARGS=
      |if [ -n '@@ADDON@@' ]; then ADDON_ARGS='--version @@ADDON@@'; fi
      |if [ -n '@@JANITOR@@' ]; then JAN_ARGS='--version @@JANITOR@@'; fi
      |helm upgrade --install cluster-api-addon-provider azimuth-addon/cluster-api-addon-provider --namespace capi-addon-system --create-namespace $ADDON_ARGS --wait --timeout 10m
      |helm upgrade --install cluster-api-janitor-openstack azimuth-janitor/cluster-api-janitor-openstack --namespace capi-janitor-system --create-namespace $JAN_ARGS --wait --timeout 10m
      |{
      |  echo "k3s: $(k3s --version | head -n 1)"
      |  echo "helm: $(helm version --short)"
      |  echo "clusterctl: $(clusterctl version -o short)"
      |  clusterctl upgrade plan 2>/dev/null | sed -n '/Providers/,$p' | head -n 12 || true
      |  helm list -A
      |  k3s kubectl get deployments -A -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image
      |} > @@STATE@@/versions.txt 2>&1
      |echo "addon provider and janitor installed; versions in @@STATE@@/versions.txt"
      |""".stripMargin)

  private val checkScript =
    fill("""set -e
      |export KUBECONFIG=@@STATE@@/kubeconfig
      |for crd in clusters.cluster.x-k8s.io openstackclusters.infrastructure.cluster.x-k8s.io kubeadmcontrolplanes.controlplane.cluster.x-k8s.io; do
      |  k3s kubectl get crd $crd >/dev/null || { echo "CRD $crd is missing" >&2; exit 1; }
      |done
      |k3s kubectl get crd | grep -c addons.stackhpc.com | sed 's/^/addon CRDs: /'
      |k3s kubectl get pods -A | grep -vE 'Running|Completed|NAME' || true
      |echo "management cluster ready at https://@@FQDN@@:6443"
      |""".stripMargin)

  private def run(script: String, seconds: Int = 120): Task =
    Task.RunCommand(List("sh", "-c", script), timeoutSeconds = seconds)

  val playbook: ClusterPlaybook =
    clusterPlaybook("capi-management-cluster")(
      stage("install-capi-management-cluster", node)
        .task("wait for apt and dpkg to be idle")(run(waitAptIdleScript, 960))
        .task("install curl and tar")(
          Task.Install(
            packages = List("curl", "ca-certificates", "tar"),
            updateCache = true
          )
        )
        .task("install k3s")(run(k3sScript, 900))
        .task("install helm and clusterctl")(run(toolsScript, 600))
        .task("write the kubeconfig for Magnum")(run(kubeconfigScript))
        .task("install Cluster API, CAPO and cert-manager")(
          run(capiScript, 1500)
        )
        .task("install the addon provider and the OpenStack janitor")(
          run(addonsScript, 1500)
        )
        .task("check the management cluster")(run(checkScript, 120))
        .build
    )
