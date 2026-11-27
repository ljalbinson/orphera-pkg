// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 7: persistent volumes on the existing Ceph cluster
// (tst0-tst2) with ceph-csi (RBD). Needs k8s_cilium.scala.
//
// On tst0: the pool `k8s-rbd` and the Ceph user `client.k8s` (a pool-scoped
// RBD user; the key is the secret `k8s/ceph-key` and is written on tst30 as a
// root-only file, never written in this repository). On all ten nodes: the `rbd` kernel
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
  // legacy `aes` key. The key comes from the secret `k8s/ceph-key`: a whole
  // cephx key (as ceph_key_rotation.scala would store) is used as it is,
  // anything else (a name with no value is made at random on first use) is the
  // text an old `aes` key is derived from: base64 of a 12 byte header (type 1,
  // a creation time, the key length 16) and 16 key bytes from a SHA-256 of the
  // text, the same on every run. The monitors accept it while
  // `auth_allowed_ciphers` still lists `aes`, which cephUser arranges for an
  // `aes` key. TEST CLUSTER COMPROMISE, as noted in cinder_single_node.scala.
  private val cephKey: String =
    val stored = Secrets.secret("k8s/ceph-key").reveal
    if CephKeys.isKey(stored) then stored
    else
      val keyBytes = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .take(16)
      val header = java.nio.ByteBuffer
        .allocate(12)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putShort(1.toShort)
        .putInt(1700000000)
        .putInt(0)
        .putShort(16.toShort)
        .array()
      val derived =
        java.util.Base64.getEncoder.encodeToString(header ++ keyBytes)
      // Not a stored secret itself, so it is registered to be masked in output.
      Secrets.register(derived)
      derived

  // The pool and the user on the Ceph node; the key file for the Helm values on tst30.
  private val cephPlan: ApplyPlan =
    import Resources.*
    val rbdPool = cephPool(s"the $pool pool", pool)
    val user = cephUser(
      s"the client.$cephUser cephx user and its key",
      s"client.$cephUser",
      Secret(cephKey),
      List("mon" -> "profile rbd", "mgr" -> "allow rw", "osd" -> s"profile rbd pool=$pool")
    ).after(rbdPool)
    ApplyPlan.orFail("ceph-pool-and-user", List(rbdPool, user))

  private val keyFilePlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "ceph-key-file",
      List(secretFile(keyFile, Secret(cephKey), mode = "0600", owner = "root", group = "root"))
    )

  // The kernel RBD client, loaded now and at boot (a `kernelModule`, which keeps
  // /etc/modules-load.d/orphera-rbd.conf). The earlier version wrote rbd.conf there by hand; that file
  // is removed, so the module is not listed twice.
  private val modulePlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "rbd-module",
      List(
        kernelModule("rbd"),
        absentFile("/etc/modules-load.d/rbd.conf")
      )
    )

  // The monitor addresses come from the inventory (`{{nodes.<n>.cluster_ip}}`, rendered on the node), so
  // they are a templated file that the install passes after its own values.
  private val monitorsFile = s"$stateDir/ceph-csi-monitors.yaml"

  private val monitorsText =
    s"""csiConfig:
       |- clusterID: "$clusterId"
       |  monitors:
       |  - "{{nodes.tst0.cluster_ip}}:6789"
       |  - "{{nodes.tst1.cluster_ip}}:6789"
       |  - "{{nodes.tst2.cluster_ip}}:6789"
       |""".stripMargin

  private val keyFingerprint: String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(cephKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(6)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // The key is put in at the line `@@KEY@@` when the helper runs; `|-` keeps the value free of a newline.
  private val cephCsiValues =
    s"""# key fingerprint: $keyFingerprint (a changed key changes the description, so the release is upgraded)
       |secret:
       |  create: true
       |  name: csi-rbd-secret
       |  userID: $cephUser
       |  userKey: |-
       |    @@KEY@@
       |storageClass:
       |  create: true
       |  name: ceph-rbd
       |  annotations:
       |    storageclass.kubernetes.io/is-default-class: "true"
       |  clusterID: "$clusterId"
       |  pool: $pool
       |  imageFeatures: layering
       |  reclaimPolicy: Delete
       |  allowVolumeExpansion: true
       |provisioner:
       |  replicaCount: 2
       |""".stripMargin

  private val installPlan: ApplyPlan =
    import Resources.*
    val (helper, installed) = helmRelease(
      "helm install ceph-csi-rbd",
      "ceph-csi-rbd",
      "ceph-csi/ceph-csi-rbd",
      "https://ceph.github.io/csi-charts",
      "ceph-csi-rbd",
      values = Some(Secret(cephCsiValues)),
      minutes = 10,
      valuesFrom = List("KEY" -> keyFile),
      valuesFiles = List(monitorsFile)
    )
    val monitors = fileTemplate(monitorsFile, monitorsText, mode = "0644")
    ApplyPlan.orFail(
      "ceph-csi",
      List(monitors, helper, installed.after(monitors))
    )

  private val testScript =
    s"""set -e
       |DEFAULT=$$($kc get storageclass -o jsonpath='{.items[?(@.metadata.annotations.storageclass\\.kubernetes\\.io/is-default-class=="true")].metadata.name}')
       |echo "default StorageClass: $${DEFAULT:-none}"
       |[ "$$DEFAULT" = "ceph-rbd" ] || { echo "ceph-rbd is not the default StorageClass" >&2; exit 1; }
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
          Task.Apply(cephPlan)
        )
        .build,
      stage("send-key", node)
        .task("write the key on tst30")(
          Task.Apply(keyFilePlan)
        )
        .build,
      stage("rbd-module", allNodes*)
        .task("load the rbd kernel module")(
          Task.Apply(modulePlan)
        )
        .build,
      stage("install-ceph-csi", node)
        .task(
          "helm install ceph-csi-rbd, with ceph-rbd as the default StorageClass"
        )(
          Task.Apply(installPlan)
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
