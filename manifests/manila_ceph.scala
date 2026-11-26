// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// What Manila needs on the Ceph cluster, on tst0, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): the CephFS filesystem `cephfs`, the NFS cluster `manila` (one NFS-Ganesha daemon on the
// Ceph host with the address `nfsIp`) and the cephx user `client.manila`. It was a stage of
// manila_single_node.scala when that was a cluster playbook; the description for the Manila node needs this to
// have run first, and one description cannot order its nodes.
//
//   orphera apply manifests/manila_ceph.scala --check
//   orphera apply manifests/manila_ceph.scala
//
// Every step asks the cluster first, so a second run changes nothing:
//   - cephFilesystem creates the filesystem (`ceph fs volume create`, which also starts the MDS daemons) only if
//     `ceph fs ls` does not list it, and waits for an active MDS, only while there is none;
//   - cephNfsCluster creates the NFS cluster on the orchestrator host that has the address `nfsIp` (or the name
//     `nfsNode`) only if `ceph nfs cluster ls` does not list it, and waits for NFS-Ganesha to answer on
//     `nfsIp`:2049, only while it does not;
//   - client.manila is imported only when the key in Ceph differs from the one in the secret store or the
//     capabilities (mon "allow r", mgr "allow rw") are missing, so a rotated key is applied by running this
//     again.
//
// The key: the secret `manila/ceph-key` holds either a whole cephx key, as ceph_key_rotation.scala stores after
// rotating client.manila to aes256k, used as it is, or (from before) any text, from which an old `aes` key is
// derived: base64 of a 12 byte header (type 1 = AES as a little-endian u16, a creation time as two u32, the key
// length 16 as a u16) and 16 key bytes from a SHA-256 of the text, the same on every run. Test infrastructure
// only. The cipher rule is the one in cinder_ceph.scala: CephKeys.allowCiphersFor allows `aes` for an `aes` key
// only, so a run after ceph_key_rotation.scala keeps aes256k only.
//
// Keep the names, `nfsIp` and the key in step with manila_single_node.scala.
object manila_ceph extends OrpheraApply:
  import Resources.*

  private val node = "tst0"
  // The Ceph host that runs NFS-Ganesha; its address is what clients mount. Keep in step with inventory.yaml.
  private val nfsNode = "tst0"
  private val nfsIp = "10.10.5.12"
  private val cephFs = "cephfs"
  private val nfsCluster = "manila"

  override def nodes: List[String] = List(node)

  private val cephKey: String =
    val stored = Secrets.secret("manila/ceph-key").reveal
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

  def describe(target: Node): List[ApplyResource] =
    val fs = cephFilesystem(s"create the CephFS filesystem $cephFs and wait for an active MDS", cephFs)
    val nfs = cephNfsCluster(
      s"create the NFS cluster $nfsCluster and wait for NFS-Ganesha on $nfsIp:2049",
      nfsCluster,
      nfsIp,
      nfsNode
    ).after(fs)
    val client = cephUser(
      "the client.manila cephx user and its key",
      "client.manila",
      Secret(cephKey),
      List("mon" -> "allow r", "mgr" -> "allow rw")
    )
    List(fs, nfs, client)
