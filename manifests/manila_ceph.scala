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
// Every step is a command with a guard that asks the cluster, so a second run changes nothing:
//   - the filesystem is created (`ceph fs volume create`, which also starts the MDS daemons) only if
//     `ceph fs ls` does not list it;
//   - the run then waits for an active MDS, only while there is none;
//   - the NFS cluster is created on the orchestrator host that has the address `nfsIp` (or the name `nfsNode`)
//     only if `ceph nfs cluster ls` does not list it;
//   - the run then waits for NFS-Ganesha to answer on `nfsIp`:2049, only while it does not;
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

  // ---- the filesystem ----------------------------------------------------

  private val fsListed =
    s"""cephadm shell -- ceph fs ls 2>/dev/null | grep -q '^name: $cephFs,'"""
  private val fsScript =
    s"""set -e
       |cephadm shell -- ceph fs volume create $cephFs
       |echo "filesystem $cephFs created"""".stripMargin

  private val mdsActive =
    """cephadm shell -- ceph mds stat 2>/dev/null | grep -q 'up:active'"""
  private val mdsScript =
    s"""for i in $$(seq 1 60); do
       |  if $mdsActive; then echo "an MDS is active for $cephFs"; exit 0; fi
       |  sleep 5
       |done
       |echo "no active MDS for $cephFs after 5 minutes - check 'ceph fs status'" >&2
       |exit 1""".stripMargin

  // ---- the NFS cluster ---------------------------------------------------

  private val nfsListed =
    // cephadm shell may return carriage returns or a JSON list, so the name is matched as a word.
    s"""cephadm shell -- ceph nfs cluster ls 2>/dev/null | tr -d '\\r' | grep -qw '$nfsCluster'"""
  private val nfsScript =
    s"""set -e
       |HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
       |for h in json.load(sys.stdin):
       |    if h.get('addr') == '$nfsIp' or h.get('hostname', '').split('.')[0] == '$nfsNode':
       |        print(h['hostname'])
       |        break")
       |if [ -z "$$HOST" ]; then
       |  echo "no Ceph orchestrator host with address $nfsIp or name $nfsNode (ceph orch host ls)" >&2
       |  exit 1
       |fi
       |cephadm shell -- ceph nfs cluster create $nfsCluster "$$HOST"
       |echo "NFS cluster $nfsCluster created on $$HOST"""".stripMargin

  private val nfsUp =
    s"""python3 -c "import socket; socket.create_connection(('$nfsIp', 2049), 3).close()" 2>/dev/null"""
  private val nfsWaitScript =
    s"""for i in $$(seq 1 60); do
       |  if $nfsUp; then echo "NFS-Ganesha answers on $nfsIp:2049"; exit 0; fi
       |  sleep 5
       |done
       |echo "NFS-Ganesha does not answer on $nfsIp:2049 after 5 minutes - check 'ceph nfs cluster info $nfsCluster'" >&2
       |exit 1""".stripMargin

  // ---- client.manila -----------------------------------------------------

  private val clientScript =
    s"""set -e
       |# Same cephx cipher rule as cinder_ceph.scala: `aes` is allowed only for an `aes` key.
       |${CephKeys.allowCiphersFor(cephKey, "client.manila")}
       |cat > /tmp/manila.keyring <<'KEYRING'
       |[client.manila]
       |    key = $cephKey
       |    caps mon = "allow r"
       |    caps mgr = "allow rw"
       |KEYRING
       |cephadm shell --mount /tmp/manila.keyring -- ceph auth import -i /mnt/manila.keyring
       |rm -f /tmp/manila.keyring
       |cephadm shell -- ceph auth get client.manila >/dev/null
       |echo "client.manila ready"""".stripMargin

  // True when the key in Ceph is the one wanted and both capabilities are there.
  private val clientInPlace =
    s"""[ "$$(cephadm shell -- ceph auth get-key client.manila 2>/dev/null)" = "$cephKey" ] && CAPS=$$(cephadm shell -- ceph auth get client.manila 2>/dev/null) && echo "$$CAPS" | grep -q 'caps mon = "allow r"' && echo "$$CAPS" | grep -q 'caps mgr = "allow rw"'"""

  def describe(target: Node): List[ApplyResource] =
    val fs = command(
      s"create the CephFS filesystem $cephFs",
      fsScript,
      unless = fsListed,
      timeout = 300
    )
    val mds = command(
      s"wait for an active MDS of $cephFs",
      mdsScript,
      unless = mdsActive,
      timeout = 360
    ).after(fs)
    val nfs = command(
      s"create the NFS cluster $nfsCluster",
      nfsScript,
      unless = nfsListed,
      timeout = 300
    ).after(mds)
    val nfsWait = command(
      s"wait for NFS-Ganesha on $nfsIp:2049",
      nfsWaitScript,
      unless = nfsUp,
      timeout = 360
    ).after(nfs)
    val client = command(
      "the client.manila cephx user and its key",
      clientScript,
      unless = clientInPlace,
      timeout = 180
    )
    List(fs, mds, nfs, nfsWait, client)
