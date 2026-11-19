// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The `volumes` pool and the `client.cinder` cephx user for Cinder, on a Ceph
// node (tst0), as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It was a stage of cinder_single_node.scala when that
// was a cluster playbook; the description for the Cinder node needs this to
// have run first, and one description cannot order its nodes.
//
//   orphera apply manifests/cinder_ceph.scala --check
//   orphera apply manifests/cinder_ceph.scala
//
// The check is on the cluster itself: the pool exists, and client.cinder has
// the key from the secret store and the capabilities for that pool. Where all
// three hold, nothing is changed. Otherwise the pool is created (an existing
// pool is left as it is), initialised for RBD, and the user is imported with the
// key, so a rotated key is applied by running this again.
//
// The key is a FIXED test key from the secret store (`cinder/ceph-key`): a
// description cannot capture another node's output, and a key we already know
// lets the Cinder node be configured in the same run. The cipher rule: Ceph
// 19.2.6+ (CVE-2025-30156) defaults to aes256k and the monitors refuse an old
// `aes` key unless `aes` is still allowed; CephKeys.allowCiphersFor allows it
// for an `aes` key only, so a run after ceph_key_rotation.scala keeps aes256k
// only.
object cinder_ceph extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst0")

  // Keep in step with cinder_single_node.scala.
  private val cephKey = Secrets.secret("cinder/ceph-key")
  private val cephPool = "volumes"

  private val createPoolAndUser =
    s"""set -e
       |${CephKeys.allowCiphersFor(cephKey.reveal, "client.cinder")}
       |cephadm shell -- ceph osd pool create $cephPool
       |cephadm shell -- rbd pool init $cephPool
       |cat > /tmp/cinder.keyring <<'KEYRING'
       |[client.cinder]
       |    key = ${cephKey.reveal}
       |    caps mon = "profile rbd"
       |    caps osd = "profile rbd pool=$cephPool"
       |KEYRING
       |cephadm shell --mount /tmp/cinder.keyring -- ceph auth import -i /mnt/cinder.keyring
       |rm -f /tmp/cinder.keyring
       |cephadm shell -- ceph auth get client.cinder >/dev/null
       |echo "pool $cephPool and client.cinder ready"""".stripMargin

  private val inPlace =
    s"""cephadm shell -- ceph osd pool ls 2>/dev/null | grep -qx $cephPool && [ "$$(cephadm shell -- ceph auth get-key client.cinder 2>/dev/null)" = "${cephKey.reveal}" ] && cephadm shell -- ceph auth get client.cinder 2>/dev/null | grep -q 'pool=$cephPool'"""

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        s"the $cephPool pool and the client.cinder cephx user",
        createPoolAndUser,
        unless = inPlace,
        timeout = 180
      )
    )
