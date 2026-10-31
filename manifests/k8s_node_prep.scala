// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 2: prepares tst30-tst39 for kubeadm. No cluster is
// created here; k8s_cluster.scala does that. Run k8s_registry_mirrors.scala
// first (the mirrors are optional: containerd falls back to the upstream
// registries when a mirror is down, but the first image pulls are much slower
// and Docker Hub rate-limits anonymous pulls).
//
// On every node:
//   - swap off (kubelet refuses to start with swap) and removed from fstab;
//   - kernel modules overlay and br_netfilter, and the sysctls kubernetes
//     needs (bridged traffic through iptables, IPv4 forwarding);
//   - containerd from the Ubuntu archive (served by the apt cache on tst11),
//     with the systemd cgroup driver, which must match kubelet's;
//   - containerd registry mirrors: quay.io -> tst9:5000, registry.k8s.io ->
//     tst9:5001, docker.io -> tst9:5002, ghcr.io -> tst9:5003, each with the
//     upstream as fallback;
//   - kubeadm, kubelet and kubectl from the pkgs.k8s.io repository of the
//     minor version in `k8sMinor`, held with apt-mark so an unattended upgrade
//     cannot move the cluster; crictl pointed at containerd;
//   - kubelet's --node-ip set to the node's 10.10.3.x address.
//
// pkgs.k8s.io is HTTPS, which the apt cache cannot cache, so the packages
// (about 100 MB) come straight from the internet.
//
// Re-runnable: every step rewrites the same files and apt only installs what
// is missing.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. `k8sMinor` is a version I could not check against the live
// repository from where this was written; if the repository step reports
// that the version does not exist, change it to a current minor release.
object k8s_node_prep extends OrpheraClusterPlaybook:

  private val nodes =
    List(
      "tst30",
      "tst31",
      "tst32",
      "tst33",
      "tst34",
      "tst35",
      "tst36",
      "tst37",
      "tst38",
      "tst39"
    )

  private val k8sMinor = "v1.34"

  private val mirrorHost = "{{nodes.tst9.cluster_ip}}"

  // (directory name containerd looks up, upstream URL, mirror port on tst9)
  private val mirrors = List(
    ("quay.io", "https://quay.io", 5000),
    ("registry.k8s.io", "https://registry.k8s.io", 5001),
    ("docker.io", "https://registry-1.docker.io", 5002),
    ("ghcr.io", "https://ghcr.io", 5003)
  )

  private val kernelScript =
    """swapoff -a
      |sed -i -E '/\sswap\s/ s/^([^#])/#\1/' /etc/fstab
      |cat > /etc/modules-load.d/k8s.conf <<'EOF'
      |overlay
      |br_netfilter
      |EOF
      |modprobe overlay
      |modprobe br_netfilter
      |cat > /etc/sysctl.d/99-k8s.conf <<'EOF'
      |net.bridge.bridge-nf-call-iptables = 1
      |net.bridge.bridge-nf-call-ip6tables = 1
      |net.ipv4.ip_forward = 1
      |EOF
      |sysctl --system >/dev/null
      |if swapon --show | grep -q .; then echo "swap is still on" >&2; exit 1; fi
      |echo "swap off, overlay and br_netfilter loaded, forwarding on"""".stripMargin

  private val mirrorFiles: String =
    mirrors
      .map { case (dir, upstream, port) =>
        s"""mkdir -p /etc/containerd/certs.d/$dir
           |cat > /etc/containerd/certs.d/$dir/hosts.toml <<'EOF'
           |server = "$upstream"
           |
           |[host."http://$mirrorHost:$port"]
           |  capabilities = ["pull", "resolve"]
           |EOF""".stripMargin
      }
      .mkString("\n")

  // `containerd config default` is the version-appropriate template; two
  // values change: the systemd cgroup driver, and config_path so the
  // certs.d directories above are read.
  private val containerdScript =
    s"""mkdir -p /etc/containerd
       |containerd config default > /etc/containerd/config.toml
       |sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
       |sed -i 's|config_path = ""|config_path = "/etc/containerd/certs.d"|' /etc/containerd/config.toml
       |grep -q 'SystemdCgroup = true' /etc/containerd/config.toml || { echo "SystemdCgroup not set" >&2; exit 1; }
       |grep -q 'certs.d' /etc/containerd/config.toml || { echo "config_path not set" >&2; exit 1; }
       |$mirrorFiles
       |cat > /etc/crictl.yaml <<'EOF'
       |runtime-endpoint: unix:///run/containerd/containerd.sock
       |image-endpoint: unix:///run/containerd/containerd.sock
       |timeout: 20
       |EOF
       |systemctl enable containerd
       |systemctl restart containerd
       |sleep 2
       |systemctl is-active --quiet containerd || { echo "containerd failed to start" >&2; exit 1; }
       |echo "containerd running, systemd cgroups, mirrors configured"""".stripMargin

  private val repoScript =
    s"""set -e
       |install -m 0755 -d /etc/apt/keyrings
       |curl -fsSL --retry 3 https://pkgs.k8s.io/core:/stable:/$k8sMinor/deb/Release.key \\
       |  | gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
       |echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/$k8sMinor/deb/ /" \\
       |  > /etc/apt/sources.list.d/kubernetes.list
       |apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/kubernetes.list \\
       |  -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0
       |apt-cache policy kubeadm | grep -q 'pkgs.k8s.io' || { echo "kubeadm not offered by pkgs.k8s.io for $k8sMinor" >&2; exit 1; }
       |echo "kubernetes apt repository $k8sMinor configured"""".stripMargin

  private val kubeletScript =
    """apt-mark hold kubelet kubeadm kubectl
      |echo "KUBELET_EXTRA_ARGS=--node-ip={{cluster_ip}}" > /etc/default/kubelet
      |systemctl enable kubelet
      |echo "kubeadm $(kubeadm version -o short), kubelet $(kubelet --version | cut -d' ' -f2), held; node-ip {{cluster_ip}}"""".stripMargin

  private val verifyScript =
    """set -e
      |crictl info >/dev/null
      |echo "$(hostname): containerd answers crictl, $(kubeadm version -o short)"
      |sysctl -n net.ipv4.ip_forward | grep -qx 1
      |lsmod | grep -q br_netfilter""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-node-prep")(
      stage("prepare-kernel", nodes*)
        .task("swap off, kernel modules, sysctls")(
          Task.RunCommand(List("sh", "-c", kernelScript), timeoutSeconds = 60)
        )
        .build,
      stage("install-containerd", nodes*)
        .task("install containerd and curl")(
          Task.Install(
            packages = List("containerd", "curl", "gpg", "ca-certificates"),
            updateCache = true
          )
        )
        .task("configure containerd and the registry mirrors")(
          Task.RunCommand(List("sh", "-c", containerdScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-kubernetes-packages", nodes*)
        .task("add the pkgs.k8s.io repository")(
          Task.RunCommand(List("sh", "-c", repoScript), timeoutSeconds = 180)
        )
        .task("install kubeadm, kubelet, kubectl")(
          Task.Install(
            packages = List("kubelet", "kubeadm", "kubectl"),
            updateCache = false
          )
        )
        .task("hold the packages, set the kubelet node IP")(
          Task.RunCommand(List("sh", "-c", kubeletScript), timeoutSeconds = 60)
        )
        .build,
      stage("verify-node-prep", nodes*)
        .task("containerd and kubeadm answer")(
          Task.RunCommand(List("sh", "-c", verifyScript), timeoutSeconds = 60)
        )
        .build
    )
