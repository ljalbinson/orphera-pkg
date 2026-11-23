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
// Re-runnable: the kernel modules and sysctls, the containerd mirror files,
// crictl's config and the kubelet defaults are apply descriptions, so a node
// that is already right is left alone; config.toml is replaced only when the
// generated text differs, and apt only installs what is missing.
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

  private val k8sMinor = "v1.35"

  private val mirrorHost = "{{nodes.tst9.cluster_ip}}"

  // (directory name containerd looks up, upstream URL, mirror port on tst9)
  private val mirrors = List(
    ("quay.io", "https://quay.io", 5000),
    ("registry.k8s.io", "https://registry.k8s.io", 5001),
    ("docker.io", "https://registry-1.docker.io", 5002),
    ("ghcr.io", "https://ghcr.io", 5003)
  )

  // Swap stays a command: commenting out the swap line of fstab is a
  // selective edit that no apply kind describes.
  private val swapScript =
    """swapoff -a
      |sed -i -E '/\sswap\s/ s/^([^#])/#\1/' /etc/fstab
      |if swapon --show | grep -q .; then echo "swap is still on" >&2; exit 1; fi
      |echo "swap off"""".stripMargin

  // The modules and sysctls as a declarative description (apply kinds
  // kernelModule and sysctl): changed only where the node differs, and listed
  // in modules-load.d and sysctl.d under orphera- names. The files this
  // manifest used to write (k8s.conf, 99-k8s.conf) are removed once the new
  // ones are in place.
  private val kernelPlan: ApplyPlan =
    import Resources.*
    val overlay = kernelModule("overlay")
    val bridge = kernelModule("br_netfilter")
    val iptables =
      sysctl("net.bridge.bridge-nf-call-iptables", "1").after(bridge)
    val ip6tables =
      sysctl("net.bridge.bridge-nf-call-ip6tables", "1").after(bridge)
    val forward = sysctl("net.ipv4.ip_forward", "1")
    ApplyPlan.orFail(
      "k8s-kernel",
      List(
        overlay,
        bridge,
        iptables,
        ip6tables,
        forward,
        absentFile("/etc/modules-load.d/k8s.conf")
          .after(overlay, bridge),
        absentFile("/etc/sysctl.d/99-k8s.conf")
          .after(iptables, ip6tables, forward)
      )
    )

  // `containerd config default` is the version-appropriate template; two
  // values change: the systemd cgroup driver, and config_path so the
  // certs.d directories below are read. The text comes from the installed
  // containerd, so it stays a script, but it replaces config.toml (and
  // restarts containerd) only when the result differs from what is there.
  private val containerdConfigScript =
    """set -e
      |mkdir -p /etc/containerd
      |NEW=/etc/containerd/config.toml.new
      |containerd config default > $NEW
      |sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' $NEW
      |sed -i 's|config_path = ""|config_path = "/etc/containerd/certs.d"|' $NEW
      |grep -q 'SystemdCgroup = true' $NEW || { echo "SystemdCgroup not set" >&2; rm -f $NEW; exit 1; }
      |grep -q 'certs.d' $NEW || { echo "config_path not set" >&2; rm -f $NEW; exit 1; }
      |if cmp -s $NEW /etc/containerd/config.toml; then
      |  rm -f $NEW
      |  echo "containerd config.toml already right"
      |else
      |  mv $NEW /etc/containerd/config.toml
      |  systemctl restart containerd
      |  sleep 2
      |  systemctl is-active --quiet containerd || { echo "containerd failed to start" >&2; exit 1; }
      |  echo "containerd config.toml written (systemd cgroups, certs.d), containerd restarted"
      |fi""".stripMargin

  // The mirror files, crictl's config and the service as an apply
  // description. containerd reads certs.d on each pull, so a changed mirror
  // file needs no restart.
  private val containerdPlan: ApplyPlan =
    import Resources.*
    val certsDir = directory("/etc/containerd/certs.d", mode = "0755")
    val mirrorResources = mirrors.flatMap { case (dir, upstream, port) =>
      val d = directory(s"/etc/containerd/certs.d/$dir", mode = "0755")
        .after(certsDir)
      List(
        d,
        fileTemplate(
          s"/etc/containerd/certs.d/$dir/hosts.toml",
          s"""server = "$upstream"
             |
             |[host."http://$mirrorHost:$port"]
             |  capabilities = ["pull", "resolve"]
             |""".stripMargin,
          mode = "0644",
          owner = "root",
          group = "root"
        ).after(d)
      )
    }
    ApplyPlan.orFail(
      "containerd-mirrors",
      List(certsDir) ++ mirrorResources ++ List(
        file(
          "/etc/crictl.yaml",
          """runtime-endpoint: unix:///run/containerd/containerd.sock
            |image-endpoint: unix:///run/containerd/containerd.sock
            |timeout: 20
            |""".stripMargin,
          mode = "0644",
          owner = "root",
          group = "root"
        ),
        service("containerd")
      )
    )

  private val repoScript =
    s"""set -e
       |install -m 0755 -d /etc/apt/keyrings
       |curl -fsSL --retry 3 https://pkgs.k8s.io/core:/stable:/$k8sMinor/deb/Release.key \\
       |  | gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
       |echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/$k8sMinor/deb/ /" \\
       |  > /etc/apt/sources.list.d/kubernetes.list
       |for try in 1 2 3; do timeout -k 10 180 apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/kubernetes.list \\
       |  -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0 && break; done
       |apt-cache policy kubeadm | grep -q 'pkgs.k8s.io' || { echo "kubeadm not offered by pkgs.k8s.io for $k8sMinor" >&2; exit 1; }
       |echo "kubernetes apt repository $k8sMinor configured"""".stripMargin

  // Only when one of them is missing. The packages are held (apt-mark hold,
  // below) so that nothing moves the cluster's version; a second run with
  // Task.Install failed on the nodes already prepared ("Held packages were
  // changed and -y was used"), because the repository had a newer cri-tools.
  private val installKubernetesScript =
    """set -e
      |PKGS="kubelet kubeadm kubectl cri-tools"
      |if dpkg -s $PKGS >/dev/null 2>&1; then
      |  echo "already installed (held): $(dpkg-query -W -f='${Package} ${Version}; ' $PKGS)"
      |else
      |  DEBIAN_FRONTEND=noninteractive apt-get install -y $PKGS
      |fi""".stripMargin

  // No apply kind holds a package yet, so the hold stays a command
  // (apt-mark hold on a held package changes nothing).
  private val holdScript =
    """apt-mark hold kubelet kubeadm kubectl cri-tools
      |echo "kubeadm $(kubeadm version -o short), kubelet $(kubelet --version | cut -d' ' -f2), held"""".stripMargin

  // kubelet's --node-ip and enabling it (kubeadm starts it later).
  private val kubeletPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "kubelet-defaults",
      List(
        fileTemplate(
          "/etc/default/kubelet",
          "KUBELET_EXTRA_ARGS=--node-ip={{cluster_ip}}\n",
          mode = "0644",
          owner = "root",
          group = "root"
        ),
        service("kubelet", enabled = Some(true), running = None)
      )
    )

  private val verifyScript =
    """set -e
      |crictl info >/dev/null
      |echo "$(hostname): containerd answers crictl, $(kubeadm version -o short)"
      |sysctl -n net.ipv4.ip_forward | grep -qx 1
      |lsmod | grep -q br_netfilter""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-node-prep")(
      stage("prepare-kernel", nodes*)
        .task("swap off")(
          Task.RunCommand(List("sh", "-c", swapScript), timeoutSeconds = 60)
        )
        .task("kernel modules and sysctls")(Task.Apply(kernelPlan))
        .build,
      stage("install-containerd", nodes*)
        .task("install containerd and curl")(
          Task.Install(
            packages = List("containerd", "curl", "gpg", "ca-certificates"),
            updateCache = true
          )
        )
        .task("write containerd's config.toml")(
          Task.RunCommand(
            List("sh", "-c", containerdConfigScript),
            timeoutSeconds = 120
          )
        )
        .task("the registry mirrors, crictl and the containerd service")(
          Task.Apply(containerdPlan)
        )
        .build,
      stage("install-kubernetes-packages", nodes*)
        .task("add the pkgs.k8s.io repository")(
          Task.RunCommand(List("sh", "-c", repoScript), timeoutSeconds = 720)
        )
        .task("install kubeadm, kubelet, kubectl, crictl")(
          Task.RunCommand(
            List("sh", "-c", installKubernetesScript),
            timeoutSeconds = 900
          )
        )
        .task("hold the packages")(
          Task.RunCommand(List("sh", "-c", holdScript), timeoutSeconds = 60)
        )
        .task("set the kubelet node IP, enable kubelet")(
          Task.Apply(kubeletPlan)
        )
        .build,
      stage("verify-node-prep", nodes*)
        .task("containerd and kubeadm answer")(
          Task.RunCommand(List("sh", "-c", verifyScript), timeoutSeconds = 60)
        )
        .build
    )
