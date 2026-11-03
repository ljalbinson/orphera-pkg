// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster: upgrade with kubeadm, one node at a time. Needs a working cluster
// from k8s_cluster.scala. Run it from scala0 with environment variables:
//
//   ORPHERA_K8S_TARGET   what to upgrade to [1.35]
//                          "1.35"    the newest 1.35 patch in the pkgs.k8s.io repository
//                          "1.35.4"  exactly that patch
//                        kubeadm cannot skip a minor version: the target is the current
//                        minor or the next one (1.34 -> 1.35 -> 1.36), one step per run.
//   ORPHERA_K8S_SCOPE    how far to go [check]
//                          check         only the checks below; nothing is changed
//                          first         the checks, a backup, then tst30 only
//                          controlplane  ... then tst31 and tst32 (the three control-plane nodes)
//                          all           ... then the workers tst33-tst39, one at a time
//                        Run it again with a wider scope to carry on: nodes already at the
//                        target are skipped. A mixed cluster is fine (kubelets may be one
//                        minor behind the API servers), so stopping after `first` or
//                        `controlplane` and looking around is a normal way to do this.
//
// The checks (always run first, on tst30; any FAIL stops the playbook):
//   - the target is the current minor or the next, and exists in the repository
//   - every node is Ready and none is cordoned
//   - lists the PodDisruptionBudgets that allow no disruption (a drain would wait on them)
//   - lists deprecated APIs that clients used since the API server started, and those
//     removed in the target minor (a warning: look at them before going on)
//   - prints the Cilium chart in use: Cilium has to support the target Kubernetes
//     version (see the compatibility table in the Cilium documentation); upgrade Cilium
//     first with k8s_cilium.scala (ciliumMinor) if it does not
//
// Then, for scope other than check, an etcd backup on the three control-plane nodes
// (orphera-etcd-backup, from k8s_etcd_backup.scala), and for each node in turn:
//   1. drain it (daemonsets stay, emptyDir data goes; 10 minutes; if the drain fails the
//      node is uncordoned again and the playbook stops)
//   2. on the node: point apt at the repository of the target minor, install kubeadm,
//      then `kubeadm upgrade apply` (tst30) or `kubeadm upgrade node` (the others),
//      then install kubelet and kubectl, hold all three, restart kubelet
//   3. wait for the node to be Ready at the new version, uncordon it, and wait for the
//      API server, Cilium and CoreDNS to be healthy before the next node
// Any failure stops the playbook where it is; the node being worked on may be left
// cordoned. Fix the cause and run it again with the same settings.
//
// After the last node: run k8s_kubeadm_config.scala (its `kubeadm upgrade diff` check
// proves the Keycloak login, encryption and metrics settings survived) and
// manifests/test_k8s.sh. Newer components (Cilium, cert-manager, Gateway API CRDs,
// kube-prometheus-stack, ceph-csi) are not touched here.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The pkgs.k8s.io `Packages` file and the package version suffix (-1.1) are
// from memory; the check stage names the one that does not match.
object k8s_upgrade extends OrpheraClusterPlaybook:

  private def env(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private val spec = env("ORPHERA_K8S_TARGET", "1.35")
  private val scope = env("ORPHERA_K8S_SCOPE", "check")

  private val cpNodes = List("tst30", "tst31", "tst32")
  private val workerNodes =
    List("tst33", "tst34", "tst35", "tst36", "tst37", "tst38", "tst39")
  private val firstNode = "tst30"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  require(
    spec.matches("""1\.\d+(\.\d+)?"""),
    s"ORPHERA_K8S_TARGET must look like 1.35 or 1.35.4, not '$spec'"
  )

  private val upgradeNodes: List[String] = scope match
    case "check"        => Nil
    case "first"        => List(firstNode)
    case "controlplane" => cpNodes
    case "all"          => cpNodes ++ workerNodes
    case other          =>
      throw new IllegalArgumentException(
        s"ORPHERA_K8S_SCOPE must be check, first, controlplane or all, not '$other'"
      )

  // Finds the newest package version for the target and sets MINOR, REPO, PKGVER (1.35.4-1.1), SEMVER (1.35.4).
  private val resolve =
    s"""SPEC='$spec'
       |MINOR=$$(echo "$$SPEC" | cut -d. -f1-2)
       |REPO="https://pkgs.k8s.io/core:/stable:/v$$MINOR/deb"
       |if [ "$$(echo "$$SPEC" | tr -cd . | wc -c)" = 1 ]; then PAT="^$$SPEC\\\\."; else PAT="^$$SPEC-"; fi
       |PKGS=$$( (curl -fsSL --retry 3 "$$REPO/Packages" || curl -fsSL --retry 3 "$$REPO/Packages.gz" | gunzip) 2>/dev/null || true)
       |PKGVER=$$(printf '%s\\n' "$$PKGS" | awk '/^Package: kubeadm$$/{f=1} f&&/^Version:/{print $$2; f=0}' | grep "$$PAT" | sort -V | tail -1)
       |[ -n "$$PKGVER" ] || { echo "no kubeadm $$SPEC in $$REPO" >&2; exit 1; }
       |SEMVER=$${PKGVER%%-*}
       |""".stripMargin

  // The API VIP is haproxy with a TCP check every 3 s that takes a backend out after 3
  // failures, so for up to ~10 s after a kube-apiserver restarts a new connection through
  // the VIP can end in "Unable to connect to the server: EOF". Every kubectl call after a
  // control-plane change goes through this retry instead of failing the playbook.
  private val retry =
    """k() { for T in 1 2 3 4 5 6 7 8 9 10; do "$@" && return 0; echo "retrying in 6 s: $*" >&2; sleep 6; done; return 1; }
      |""".stripMargin

  private val checkScript =
    s"""set -e
       |FAIL=0
       |$resolve
       |echo "target: Kubernetes v$$SEMVER (package $$PKGVER), scope: $scope"
       |CUR=$$($kc version -o json | python3 -c 'import sys,json; print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')
       |echo "API server now: $$CUR"
       |CURMINOR=$$(echo "$${CUR#v}" | cut -d. -f2); TGTMINOR=$$(echo "$$MINOR" | cut -d. -f2)
       |if [ "$$TGTMINOR" -ge "$$CURMINOR" ] && [ "$$TGTMINOR" -le $$((CURMINOR + 1)) ]; then
       |  echo "ok    target minor $$MINOR is the current minor or the next"
       |else
       |  echo "FAIL  target minor $$MINOR: kubeadm upgrades one minor at a time (cluster is at 1.$$CURMINOR)" >&2; FAIL=1
       |fi
       |if [ "$${CUR#v}" = "$$SEMVER" ]; then echo "note  the API server is already at v$$SEMVER"; fi
       |$kc get nodes -o wide
       |NOTREADY=$$($kc get nodes --no-headers | grep -v ' Ready' | wc -l)
       |CORDONED=$$($kc get nodes --no-headers | grep -c SchedulingDisabled || true)
       |if [ "$$NOTREADY" = 0 ]; then echo "ok    all nodes Ready"; else echo "FAIL  $$NOTREADY node(s) not Ready" >&2; FAIL=1; fi
       |if [ "$$CORDONED" = 0 ]; then echo "ok    no node is cordoned"; else echo "FAIL  $$CORDONED node(s) cordoned" >&2; FAIL=1; fi
       |echo "PodDisruptionBudgets that allow no disruption (a drain waits on these):"
       |PDBS=$$($kc get pdb -A --no-headers 2>/dev/null | awk '$$5 == 0 {print "  " $$1 "/" $$2}' || true)
       |if [ -n "$$PDBS" ]; then echo "$$PDBS"; else echo "  (none)"; fi
       |echo "deprecated APIs used since the API server started (resource, removed in):"
       |$kc get --raw /metrics 2>/dev/null | grep '^apiserver_requested_deprecated_apis' \\
       |  | sed -E 's/.*group="([^"]*)".*removed_release="([^"]*)".*,resource="([^"]*)".*version="([^"]*)".*/  \\1\\/\\4 \\3 removed in \\2/' | sort -u || true
       |echo "(any removed in $$MINOR or earlier must be dealt with before going on)"
       |echo "Cilium: $$(helm list -n kube-system -f '^cilium$$' -o json --kubeconfig /etc/kubernetes/admin.conf | python3 -c 'import sys,json; r=json.load(sys.stdin); print(r[0]["chart"] if r else "not found")')"
       |echo "check that this Cilium supports Kubernetes $$MINOR: https://docs.cilium.io/en/stable/network/kubernetes/compatibility/"
       |[ "$$FAIL" -eq 0 ] || exit 1
       |echo "checks passed"
       |""".stripMargin

  private val backupScript =
    """test -x /usr/local/sbin/orphera-etcd-backup || { echo "orphera-etcd-backup is missing; run k8s_etcd_backup.scala first" >&2; exit 1; }
      |/usr/local/sbin/orphera-etcd-backup""".stripMargin

  private def drainScript(n: String) =
    s"""set -e
       |$resolve
       |$retry
       |CURK=$$(k $kc get node $n -o jsonpath='{.status.nodeInfo.kubeletVersion}')
       |if [ "$$CURK" = "v$$SEMVER" ]; then echo "$n already runs v$$SEMVER; nothing to drain"; exit 0; fi
       |echo "$n runs $$CURK; draining for v$$SEMVER"
       |OK=""
       |for T in 1 2 3; do
       |  if $kc drain $n --ignore-daemonsets --delete-emptydir-data --timeout=600s; then OK=1; break; fi
       |  echo "the drain of $n failed (attempt $$T of 3)" >&2; sleep 10
       |done
       |if [ -z "$$OK" ]; then
       |  echo "the drain of $n failed; uncordoning it again" >&2
       |  k $kc uncordon $n || true
       |  exit 1
       |fi
       |""".stripMargin

  private def nodeScript(n: String) =
    val control = cpNodes.contains(n)
    val apply =
      if n == firstNode then """kubeadm upgrade apply "v$SEMVER" -y"""
      else "kubeadm upgrade node"
    val skipCheck =
      if control then
        """grep -q "kube-apiserver:v$SEMVER" /etc/kubernetes/manifests/kube-apiserver.yaml"""
      else "false"
    s"""set -e
       |export DEBIAN_FRONTEND=noninteractive
       |$resolve
       |HAVE=$$(dpkg-query -W -f='$${Version}' kubelet 2>/dev/null || true)
       |if [ "$$HAVE" = "$$PKGVER" ] && [ "$$(kubeadm version -o short)" = "v$$SEMVER" ]; then
       |  echo "$n already has kubeadm and kubelet $$PKGVER"; exit 0
       |fi
       |if ! grep -q "stable:/v$$MINOR/deb" /etc/apt/sources.list.d/kubernetes.list; then
       |  install -m 0755 -d /etc/apt/keyrings
       |  curl -fsSL --retry 3 "$$REPO/Release.key" | gpg --dearmor --yes -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
       |  echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] $$REPO/ /" > /etc/apt/sources.list.d/kubernetes.list
       |fi
       |apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/kubernetes.list -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0
       |apt-mark unhold kubeadm kubelet kubectl
       |apt-get install -y --allow-change-held-packages "kubeadm=$$PKGVER"
       |[ "$$(kubeadm version -o short)" = "v$$SEMVER" ] || { echo "kubeadm is not v$$SEMVER after the install" >&2; exit 1; }
       |if $skipCheck; then
       |  echo "$n: the control plane manifests are already at v$$SEMVER"
       |else
       |  $apply
       |fi
       |apt-get install -y --allow-change-held-packages "kubelet=$$PKGVER" "kubectl=$$PKGVER"
       |apt-mark hold kubeadm kubelet kubectl
       |systemctl daemon-reload
       |systemctl restart kubelet
       |echo "$n: kubeadm, kubelet and kubectl are $$PKGVER"
       |""".stripMargin

  private def readyScript(n: String) =
    s"""set -e
       |$resolve
       |$retry
       |OK=""
       |for I in $$(seq 1 120); do
       |  V=$$($kc get node $n -o jsonpath='{.status.nodeInfo.kubeletVersion}' 2>/dev/null || true)
       |  R=$$($kc get node $n -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
       |  if [ "$$V" = "v$$SEMVER" ] && [ "$$R" = "True" ]; then OK=1; break; fi
       |  sleep 5
       |done
       |[ -n "$$OK" ] || { echo "$n is not Ready at v$$SEMVER (kubelet $$V, Ready=$$R)" >&2; exit 1; }
       |k $kc uncordon $n
       |# the VIP must answer five times in a row before the next step
       |STABLE=0
       |for I in $$(seq 1 90); do
       |  if $kc get --raw /readyz >/dev/null 2>&1; then STABLE=$$((STABLE + 1)); else STABLE=0; fi
       |  [ "$$STABLE" -ge 5 ] && break
       |  sleep 2
       |done
       |[ "$$STABLE" -ge 5 ] || { echo "the API server did not answer steadily through the VIP" >&2; exit 1; }
       |k $kc wait --for=condition=Ready node --all --timeout=300s
       |k $kc -n kube-system rollout status daemonset/cilium --timeout=600s
       |k $kc -n kube-system rollout status deployment/coredns --timeout=300s
       |echo "$n is at v$$SEMVER, uncordoned; the cluster is healthy"
       |""".stripMargin

  private def nodeStages(n: String): List[Stage] =
    List(
      stage(s"drain-$n", firstNode)
        .task(s"drain $n")(
          Task
            .RunCommand(List("sh", "-c", drainScript(n)), timeoutSeconds = 700)
        )
        .build,
      stage(s"upgrade-$n", n)
        .task(s"kubeadm, kubelet and kubectl on $n")(
          Task
            .RunCommand(List("sh", "-c", nodeScript(n)), timeoutSeconds = 1200)
        )
        .build,
      stage(s"ready-$n", firstNode)
        .task(s"$n Ready at the new version, uncordon, cluster healthy")(
          Task
            .RunCommand(List("sh", "-c", readyScript(n)), timeoutSeconds = 1500)
        )
        .build
    )

  private val summaryScript =
    s"""set -e
       |$kc get nodes -o wide
       |echo "run k8s_kubeadm_config.scala and manifests/test_k8s.sh next"
       |""".stripMargin

  private val stages: List[Stage] =
    List(
      stage("upgrade-checks", firstNode)
        .task(s"checks for the upgrade to $spec")(
          Task.RunCommand(List("sh", "-c", checkScript), timeoutSeconds = 180)
        )
        .build
    ) ++
      (if upgradeNodes.isEmpty then Nil
       else
         List(
           stage("etcd-backup", cpNodes*)
             .task("etcd snapshot before the upgrade")(
               Task.RunCommand(
                 List("sh", "-c", backupScript),
                 timeoutSeconds = 300
               )
             )
             .build
         ) ++ upgradeNodes.flatMap(nodeStages) ++
           List(
             stage("upgrade-summary", firstNode)
               .task("nodes after the upgrade")(
                 Task.RunCommand(
                   List("sh", "-c", summaryScript),
                   timeoutSeconds = 60
                 )
               )
               .build
           )) ++
      List(
        stage("upgrade-done", firstNode)
          .task("done")(
            Task.Debug(
              if upgradeNodes.isEmpty then
                s"Checks for Kubernetes $spec passed; nothing was changed. Run again with ORPHERA_K8S_SCOPE=first (then controlplane, then all) to upgrade."
              else
                s"Upgraded ${upgradeNodes.mkString(", ")} to Kubernetes $spec."
            )
          )
          .build
      )

  val playbook: ClusterPlaybook = clusterPlaybook("k8s-upgrade")(stages*)
