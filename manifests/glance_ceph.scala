// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The `images` pool and the `client.glance` cephx user for Glance, on a Ceph
// node (tst0), as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It was a stage of glance_single_node.scala when that
// was a cluster playbook; the description for the Glance node needs this to
// have run first, and one description cannot order its nodes.
//
//   orphera apply manifests/glance_ceph.scala --check
//   orphera apply manifests/glance_ceph.scala
//
// The check is on the cluster itself: the pool exists, and client.glance has
// the key from the secret store and the capabilities for that pool. Where all
// three hold, nothing is changed. Otherwise the pool is created (an existing
// pool is left as it is), initialised for RBD, and the user is imported with the
// key, so a rotated key is applied by running this again.
//
// The key is a FIXED test key from the secret store (`glance/ceph-key`), as for
// Cinder: a description cannot capture another node's output. The cipher rule
// is the same as in cinder_single_node.scala: an old `aes` key needs `aes`
// allowed on the monitors (CephKeys.allowCiphersFor).
object glance_ceph extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst0")

  // Keep in step with glance_single_node.scala.
  private val cephKey = Secrets.secret("glance/ceph-key")
  private val cephPool = "images"

  private val createPoolAndUser =
    s"""set -e
       |${CephKeys.allowCiphersFor(cephKey.reveal, "client.glance")}
       |cephadm shell -- ceph osd pool create $cephPool
       |cephadm shell -- rbd pool init $cephPool
       |cat > /tmp/glance.keyring <<'KEYRING'
       |[client.glance]
       |    key = ${cephKey.reveal}
       |    caps mon = "profile rbd"
       |    caps osd = "profile rbd pool=$cephPool"
       |KEYRING
       |cephadm shell --mount /tmp/glance.keyring -- ceph auth import -i /mnt/glance.keyring
       |rm -f /tmp/glance.keyring
       |cephadm shell -- ceph auth get client.glance >/dev/null
       |echo "pool $cephPool and client.glance ready"""".stripMargin

  private val inPlace =
    s"""cephadm shell -- ceph osd pool ls 2>/dev/null | grep -qx $cephPool && [ "$$(cephadm shell -- ceph auth get-key client.glance 2>/dev/null)" = "${cephKey.reveal}" ] && cephadm shell -- ceph auth get client.glance 2>/dev/null | grep -q 'pool=$cephPool'"""

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        s"the $cephPool pool and the client.glance cephx user",
        createPoolAndUser,
        unless = inPlace,
        timeout = 180
      )
    )
