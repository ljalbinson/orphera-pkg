// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 7: persistent volumes on the existing Ceph cluster
// (tst0-tst2) with ceph-csi (RBD). Needs k8s_cilium.scala.
//
// On tst0: the pool `k8s-rbd` and the Ceph user `client.k8s` (a pool-scoped
// RBD user; the key is made on tst0 and copied to tst30 as a root-only file,
// never written in this repository). On all ten nodes: the `rbd` kernel
// module (ceph-csi maps volumes with it). On tst30: the Helm chart
// ceph-csi-rbd in the namespace ceph-csi-rbd with the three monitors, the
// Secret for the user and a StorageClass `ceph-rbd`, which is made the
// default class (ReadWriteOnce block volumes, expandable, reclaim Delete).
//
// ceph-csi's cluster id here is the arbitrary string `orphera-ceph`; it only
// has to match between the chart's configuration and the StorageClass.
//
// CephFS (ReadWriteMany) is not set up; it can be added later as a second
// chart release.
//
// The last stage proves it with a 1 GiB claim and a pod that writes a file to
// it and reads it back; the test objects are removed afterwards.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_ceph_csi extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val cephNode = "tst0"
  private val allNodes = (30 to 39).map(n => s"tst$n").toList
  private val pool = "k8s-rbd"
  private val cephUser = "k8s"
  private val clusterId = "orphera-ceph"
  private val stateDir = "/etc/kubernetes/orphera"
  private val keyFile = s"$stateDir/ceph-k8s.key"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  // The kernel RBD client (`rbd map`, which ceph-csi uses on the nodes) cannot
  // load an `aes256k` key, the default since Ceph 19.2.6 ("failed to add secret
  // to kernel", error 524). So, as for Cinder and Glance, the user gets a
  // legacy `aes` key: base64 of a header (type 1, timestamp, length 16) plus 16
  // random bytes, made here on tst0 and imported with `ceph auth import`. The
  // monitors accept it while `auth_allowed_ciphers` still lists `aes`, which
  // the Cinder playbook already arranges (set again here). An existing user
  // whose key is already of type 1 is kept, so a re-run changes nothing.
  // TEST CLUSTER COMPROMISE, as noted in cinder_single_node.scala.
  private val cephScript =
    s"""set -e
       |cephadm shell -- ceph mon set auth_allowed_ciphers aes,aes256k
       |cephadm shell -- ceph osd pool create $pool
       |cephadm shell -- rbd pool init $pool
       |KEY=$$(cephadm shell -- ceph auth get-key client.$cephUser 2>/dev/null | tail -n 1 || true)
       |TYPE=$$(printf '%s' "$$KEY" | base64 -d 2>/dev/null | head -c 1 | od -An -tu1 | tr -d ' ')
       |if [ "$$TYPE" != "1" ]; then
       |  KEY=$$(python3 -c "import os,struct,base64,time;print(base64.b64encode(struct.pack('<HIIH',1,int(time.time()),0,16)+os.urandom(16)).decode())")
       |  umask 077
       |  cat > /tmp/$cephUser.keyring <<KEYRING
       |[client.$cephUser]
       |    key = $$KEY
       |    caps mon = "profile rbd"
       |    caps mgr = "allow rw"
       |    caps osd = "profile rbd pool=$pool"
       |KEYRING
       |  cephadm shell --mount /tmp/$cephUser.keyring -- ceph auth import -i /mnt/$cephUser.keyring
       |  rm -f /tmp/$cephUser.keyring
       |  echo "client.$cephUser given a new key of the legacy aes type"
       |fi
       |umask 077
       |printf '%s' "$$KEY" > /etc/ceph/ceph.client.$cephUser.key
       |echo "pool $pool and client.$cephUser ready"
       |""".stripMargin

  private val moduleScript =
    """modprobe rbd
      |echo rbd > /etc/modules-load.d/rbd.conf
      |lsmod | grep -q '^rbd' && echo "$(hostname): rbd module loaded"""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |[ -s $keyFile ] || { echo "$keyFile is missing or empty" >&2; exit 1; }
       |KEY=$$(cat $keyFile)
       |umask 077
       |cat > $stateDir/ceph-csi-values.yaml <<EOF
       |csiConfig:
       |- clusterID: "$clusterId"
       |  monitors:
       |  - "{{nodes.tst0.cluster_ip}}:6789"
       |  - "{{nodes.tst1.cluster_ip}}:6789"
       |  - "{{nodes.tst2.cluster_ip}}:6789"
       |secret:
       |  create: true
       |  name: csi-rbd-secret
       |  userID: $cephUser
       |  userKey: $$KEY
       |storageClass:
       |  create: true
       |  name: ceph-rbd
       |  clusterID: "$clusterId"
       |  pool: $pool
       |  imageFeatures: layering
       |  reclaimPolicy: Delete
       |  allowVolumeExpansion: true
       |provisioner:
       |  replicaCount: 2
       |EOF
       |helm repo add ceph-csi https://ceph.github.io/csi-charts --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart ceph-csi/ceph-csi-rbd | awk '/^version:/{print $$2}')
       |echo "installing ceph-csi-rbd chart $$VER"
       |helm upgrade --install ceph-csi-rbd ceph-csi/ceph-csi-rbd --version "$$VER" \\
       |  --namespace ceph-csi-rbd --create-namespace -f $stateDir/ceph-csi-values.yaml --wait --timeout 10m
       |$kc patch storageclass ceph-rbd -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
       |$kc get storageclass
       |echo "ceph-csi-rbd $$VER installed"
       |""".stripMargin

  private val testScript =
    s"""set -e
       |$kc create namespace csi-test --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: v1
       |kind: PersistentVolumeClaim
       |metadata:
       |  name: data
       |  namespace: csi-test
       |spec:
       |  accessModes: [ReadWriteOnce]
       |  resources:
       |    requests:
       |      storage: 1Gi
       |---
       |apiVersion: v1
       |kind: Pod
       |metadata:
       |  name: writer
       |  namespace: csi-test
       |spec:
       |  restartPolicy: Never
       |  containers:
       |  - name: busybox
       |    image: docker.io/library/busybox:stable
       |    command: ["sh", "-c", "echo orphera-csi-ok > /data/probe && cat /data/probe"]
       |    volumeMounts:
       |    - {name: data, mountPath: /data}
       |  volumes:
       |  - name: data
       |    persistentVolumeClaim:
       |      claimName: data
       |EOF
       |$kc -n csi-test wait --for=jsonpath='{.status.phase}'=Succeeded pod/writer --timeout=300s
       |OUT=$$($kc -n csi-test logs writer)
       |$kc -n csi-test get pvc data
       |$kc delete namespace csi-test --wait=false >/dev/null
       |[ "$$OUT" = "orphera-csi-ok" ] || { echo "unexpected pod output: $$OUT" >&2; exit 1; }
       |echo "a pod wrote to and read back a Ceph RBD volume: $$OUT"
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-ceph-csi")(
      stage("ceph-pool-and-user", cephNode)
        .task("create the pool and the client.k8s user")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build,
      stage("send-key", node)
        .task("copy the key to tst30")(
          Task.DistributeFile(
            cephNode,
            s"/etc/ceph/ceph.client.$cephUser.key",
            keyFile,
            "root",
            "root",
            384
          )
        )
        .build,
      stage("rbd-module", allNodes*)
        .task("load the rbd kernel module")(
          Task.RunCommand(List("sh", "-c", moduleScript), timeoutSeconds = 60)
        )
        .build,
      stage("install-ceph-csi", node)
        .task("helm install ceph-csi-rbd and the default StorageClass")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 900)
        )
        .build,
      stage("test-volume", node)
        .task("write to and read from a volume")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 420)
        )
        .task("ceph-csi ready")(
          Task.Debug(
            s"ceph-csi (RBD) is installed; StorageClass ceph-rbd is the default, backed by pool $pool on the lab Ceph cluster."
          )
        )
        .build
    )
