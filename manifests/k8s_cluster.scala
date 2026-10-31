// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 4: kubeadm. Needs k8s_node_prep.scala and
// k8s_api_vip.scala to have run.
//
//   - tst30, tst31, tst32: control plane with stacked etcd. The separate 20 GB
//     disk of each (config/tst30.yaml) becomes an ext4 file system mounted at
//     /var/lib/etcd; the playbook looks for the one unused disk of about 20 GB
//     and stops if there is not exactly one (it never guesses a device name).
//   - tst33-tst35: workers labelled pool=infra; tst36-tst39: workers labelled
//     pool=workload. Nothing is tainted yet; the infra taint comes with the
//     platform layers.
//
// The API endpoint is api.k8s.ljalbinson.com:8443 (the VIP from
// k8s_api_vip.scala). The API servers' certificate names that address, the VIP
// and every node. kube-proxy is skipped (`skipPhases: addon/kube-proxy`):
// Cilium takes over its job in k8s_cilium.scala, so until then the nodes stay
// NotReady and CoreDNS stays Pending. That is expected here; this playbook
// only waits for all ten nodes to register.
//
// Pod network 10.244.0.0/16, service network 10.96.0.0/12.
//
// The controller manager, the scheduler and etcd are told to serve their metrics on
// all addresses (kubeadm's default is 127.0.0.1), so Prometheus can scrape them.
// A cluster made before this was added gets the same through k8s_kubeadm_config.scala.
//
// The join token and the certificate key come from the secret store
// (k8s/bootstrap-token, k8s/certificate-key; made on first use) and are put in
// the kubeadm configuration, so every node uses the same values. The CA hash
// the joining nodes pin is read on tst30 after `kubeadm init` and copied to the
// other nodes. The token lasts 24 hours and the uploaded control-plane
// certificates 2 hours: to add a node later, use `kubeadm token create
// --print-join-command` on tst30 instead of re-running this playbook after
// that time. Re-running while the time is not up skips the nodes that already
// joined.
//
// Control-plane nodes join one at a time, because each joining etcd member
// needs the existing ones healthy.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_cluster extends OrpheraClusterPlaybook:

  private val cpNodes = List("tst30", "tst31", "tst32")
  private val infraNodes = List("tst33", "tst34", "tst35")
  private val workloadNodes = List("tst36", "tst37", "tst38", "tst39")
  private val allNodes = cpNodes ++ infraNodes ++ workloadNodes
  private val otherNodes = allNodes.filterNot(_ == "tst30")

  private val endpoint = "api.k8s.ljalbinson.com:8443"
  private val vip = "10.10.3.20"
  private val podCidr = "10.244.0.0/16"
  private val serviceCidr = "10.96.0.0/12"
  private val stateDir = "/etc/kubernetes/orphera"

  // kubeadm token: 6 + 16 lowercase letters or digits.
  private val token =
    val raw = Secrets
      .get("k8s/bootstrap-token")
      .toLowerCase
      .filter(c => c.isLetterOrDigit && c < 128)
    require(
      raw.length >= 22,
      "k8s/bootstrap-token is too short to make a kubeadm token"
    )
    val t = s"${raw.take(6)}.${raw.slice(6, 22)}"
    Secrets.register(t)
    t

  // kubeadm certificate key: 64 hex digits (32 bytes).
  private val certificateKey =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Secrets.get("k8s/certificate-key").getBytes("UTF-8"))
    val k = digest.map(b => f"${b & 0xff}%02x").mkString
    Secrets.register(k)
    k

  private val sans = (List("api.k8s.ljalbinson.com", vip, "10.96.0.1") ++
    allNodes.flatMap(n => List(n, s"$n.ljalbinson.com")) ++
    cpNodes.map(n => s"{{nodes.$n.cluster_ip}}"))
    .map(s => s"  - \"$s\"")
    .mkString("\n")

  private val prepareDirsScript =
    s"mkdir -p $stateDir && chmod 700 $stateDir && echo \"$stateDir ready\""

  // The one disk of 19-21 GiB with no partitions, no file system and no mount.
  private val etcdDiskScript =
    s"""set -e
       |if mountpoint -q /var/lib/etcd; then echo "/var/lib/etcd is already a mount point"; exit 0; fi
       |CAND=""
       |for D in $$(lsblk -dnpo NAME,TYPE | awk '$$2=="disk"{print $$1}'); do
       |  SIZE=$$(lsblk -bdno SIZE $$D)
       |  [ $$SIZE -ge 19327352832 ] && [ $$SIZE -le 22548578304 ] || continue
       |  [ $$(lsblk -nro NAME $$D | wc -l) -eq 1 ] || continue
       |  [ -z "$$(blkid -p -o value -s TYPE $$D 2>/dev/null)" ] || continue
       |  CAND="$$CAND $$D"
       |done
       |set -- $$CAND
       |if [ $$# -ne 1 ]; then echo "expected exactly one unused disk of about 20 GB for etcd, found: $${CAND:-none}" >&2; lsblk >&2; exit 1; fi
       |DEV=$$1
       |mkfs.ext4 -q -L etcd $$DEV
       |mkdir -p /var/lib/etcd
       |grep -q 'LABEL=etcd' /etc/fstab || echo 'LABEL=etcd /var/lib/etcd ext4 defaults,noatime 0 2' >> /etc/fstab
       |systemctl daemon-reload
       |mount /var/lib/etcd
       |chmod 700 /var/lib/etcd
       |echo "etcd disk $$DEV mounted at /var/lib/etcd"
       |""".stripMargin

  private val pullImagesScript =
    """set -e
      |VER=$(kubeadm version -o short)
      |kubeadm config images pull --kubernetes-version "$VER"
      |echo "control-plane images for $VER pulled"""".stripMargin

  private val initScript =
    s"""set -e
       |if [ -f /etc/kubernetes/admin.conf ]; then echo "cluster already initialised on this node"; else
       |VER=$$(kubeadm version -o short)
       |cat > $stateDir/kubeadm-init.yaml <<EOF
       |apiVersion: kubeadm.k8s.io/v1beta4
       |kind: InitConfiguration
       |bootstrapTokens:
       |- token: "$token"
       |  ttl: 24h0m0s
       |  usages:
       |  - signing
       |  - authentication
       |  groups:
       |  - system:bootstrappers:kubeadm:default-node-token
       |certificateKey: "$certificateKey"
       |localAPIEndpoint:
       |  advertiseAddress: {{cluster_ip}}
       |  bindPort: 6443
       |nodeRegistration:
       |  name: tst30
       |  criSocket: unix:///run/containerd/containerd.sock
       |skipPhases:
       |- addon/kube-proxy
       |---
       |apiVersion: kubeadm.k8s.io/v1beta4
       |kind: ClusterConfiguration
       |clusterName: k8s
       |kubernetesVersion: $$VER
       |controlPlaneEndpoint: "$endpoint"
       |networking:
       |  podSubnet: $podCidr
       |  serviceSubnet: $serviceCidr
       |  dnsDomain: cluster.local
       |apiServer:
       |  certSANs:
       |$sans
       |controllerManager:
       |  extraArgs:
       |  - name: bind-address
       |    value: 0.0.0.0
       |scheduler:
       |  extraArgs:
       |  - name: bind-address
       |    value: 0.0.0.0
       |etcd:
       |  local:
       |    dataDir: /var/lib/etcd
       |    extraArgs:
       |    - name: listen-metrics-urls
       |      value: http://0.0.0.0:2381
       |EOF
       |chmod 600 $stateDir/kubeadm-init.yaml
       |kubeadm init --config $stateDir/kubeadm-init.yaml --upload-certs \\
       |  --ignore-preflight-errors=DirAvailable--var-lib-etcd
       |fi
       |openssl x509 -pubkey -in /etc/kubernetes/pki/ca.crt | openssl rsa -pubin -outform der 2>/dev/null \\
       |  | openssl dgst -sha256 -hex | sed 's/^.* //' > $stateDir/ca-hash
       |chmod 644 $stateDir/ca-hash
       |for U in localadmin; do
       |  H=$$(getent passwd $$U | cut -d: -f6)
       |  mkdir -p $$H/.kube
       |  cp /etc/kubernetes/admin.conf $$H/.kube/config
       |  chown -R $$U:$$U $$H/.kube
       |done
       |echo "kubeadm init done; CA hash $$(cat $stateDir/ca-hash)"
       |""".stripMargin

  private def joinScript(controlPlane: Boolean): String =
    val cpFlags =
      if controlPlane then
        s"--control-plane --certificate-key $certificateKey --apiserver-advertise-address {{cluster_ip}} " +
          "--ignore-preflight-errors=DirAvailable--var-lib-etcd"
      else ""
    s"""set -e
       |if [ -f /etc/kubernetes/kubelet.conf ]; then echo "$$(hostname) has already joined"; exit 0; fi
       |HASH=$$(cat $stateDir/ca-hash)
       |kubeadm join $endpoint --token $token \\
       |  --discovery-token-ca-cert-hash sha256:$$HASH $cpFlags
       |echo "$$(hostname) joined"
       |""".stripMargin

  private val allRegisteredScript =
    s"""[ "$$(kubectl --kubeconfig /etc/kubernetes/admin.conf get nodes --no-headers | wc -l)" -eq ${allNodes.size} ]"""

  private val labelScript =
    s"""set -e
       |K="kubectl --kubeconfig /etc/kubernetes/admin.conf"
       |for N in ${infraNodes.mkString(" ")}; do
       |  $$K label node $$N pool=infra node-role.kubernetes.io/infra= --overwrite
       |done
       |for N in ${workloadNodes.mkString(" ")}; do
       |  $$K label node $$N pool=workload node-role.kubernetes.io/workload= --overwrite
       |done
       |$$K get nodes -o wide
       |""".stripMargin

  // The pods of the nodes that joined last appear a few seconds after
  // `kubeadm join` returns, so the counts are retried for two minutes.
  private val checkScript =
    s"""K="kubectl --kubeconfig /etc/kubernetes/admin.conf"
       |ETCD=0; API=0
       |for I in $$(seq 1 24); do
       |  ETCD=$$($$K -n kube-system get pods -l component=etcd --no-headers | grep -c ' Running ' || true)
       |  API=$$($$K -n kube-system get pods -l component=kube-apiserver --no-headers | grep -c ' Running ' || true)
       |  [ "$$ETCD" -eq 3 ] && [ "$$API" -eq 3 ] && break
       |  sleep 5
       |done
       |echo "etcd pods running: $$ETCD, kube-apiserver pods running: $$API"
       |[ "$$ETCD" -eq 3 ] && [ "$$API" -eq 3 ] || { echo "expected 3 etcd and 3 kube-apiserver pods running" >&2; exit 1; }
       |LIVEZ=$$(curl -sk https://$vip:8443/livez)
       |echo "https://$vip:8443/livez: $$LIVEZ"
       |[ "$$LIVEZ" = "ok" ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-cluster")(
      stage("prepare-state-directory", allNodes*)
        .task("create the Orphera state directory")(
          Task.RunCommand(List("sh", "-c", prepareDirsScript))
        )
        .build,
      stage("etcd-disk", cpNodes*)
        .task("format and mount the etcd disk")(
          Task
            .RunCommand(List("sh", "-c", etcdDiskScript), timeoutSeconds = 180)
        )
        .build,
      stage("pull-control-plane-images", cpNodes*)
        .task("kubeadm config images pull")(
          Task.RunCommand(
            List("sh", "-c", pullImagesScript),
            timeoutSeconds = 900
          )
        )
        .build,
      stage("kubeadm-init", "tst30")
        .task("kubeadm init, CA hash, kubeconfig for localadmin")(
          Task.RunCommand(List("sh", "-c", initScript), timeoutSeconds = 900)
        )
        .build,
      stage("distribute-ca-hash", otherNodes*)
        .task("copy the CA hash from tst30")(
          Task.DistributeFile(
            "tst30",
            s"$stateDir/ca-hash",
            s"$stateDir/ca-hash",
            "root",
            "root",
            420
          )
        )
        .build,
      stage("join-control-plane-tst31", "tst31")
        .task("kubeadm join --control-plane")(
          Task.RunCommand(
            List("sh", "-c", joinScript(true)),
            timeoutSeconds = 900
          )
        )
        .build,
      stage("join-control-plane-tst32", "tst32")
        .task("kubeadm join --control-plane")(
          Task.RunCommand(
            List("sh", "-c", joinScript(true)),
            timeoutSeconds = 900
          )
        )
        .build,
      stage("join-workers", (infraNodes ++ workloadNodes)*)
        .task("kubeadm join")(
          Task.RunCommand(
            List("sh", "-c", joinScript(false)),
            timeoutSeconds = 600
          )
        )
        .build,
      stage("confirm-nodes-registered", "tst30")
        .waitFor(
          HealthCheck.Command(
            onNode = "tst30",
            command = List("sh", "-c", allRegisteredScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 180
          )
        )
        .task("label the worker pools")(
          Task.RunCommand(List("sh", "-c", labelScript), timeoutSeconds = 120)
        )
        .task("check etcd, the API servers and the VIP")(
          Task.RunCommand(List("sh", "-c", checkScript), timeoutSeconds = 180)
        )
        .task("cluster created")(
          Task.Debug(
            s"kubeadm cluster up: 3 control-plane and 7 worker nodes registered, API at https://$endpoint. " +
              "Nodes are NotReady until the network (k8s_cilium.scala) is installed."
          )
        )
        .build
    )
