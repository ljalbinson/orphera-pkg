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
// The check is on the cluster itself: the pool exists (cephPool), and client.cinder
// has the key from the secret store and the capabilities for that pool
// (cephUser). Where both hold, nothing is changed. Otherwise the pool is created
// (an existing pool is left as it is) and initialised for RBD, and the user is
// imported with the key, so a rotated key is applied by running this again.
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
  private val volumesPool = "volumes"

  def describe(node: Node): List[ApplyResource] =
    val pool = cephPool(s"the $volumesPool pool", volumesPool)
    val user = cephUser(
      "the client.cinder cephx user and its key",
      "client.cinder",
      cephKey,
      List("mon" -> "profile rbd", "osd" -> s"profile rbd pool=$volumesPool")
    ).after(pool)
    List(pool, user)
