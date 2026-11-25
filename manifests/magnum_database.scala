// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The Magnum database and user on the Galera cluster, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It was a stage of
// magnum_single_node.scala when that was a cluster playbook; the description for
// the Magnum node needs this to have run first, since its database sync would fail
// without the database, and one description cannot order its nodes.
//
//   orphera apply manifests/magnum_database.scala --check
//   orphera apply manifests/magnum_database.scala
//
// The check is a real login as the Magnum user, through the VIP, to the magnum
// database: the path magnum.conf uses. Where it works, nothing is changed.
// Otherwise the database and user are created (IF NOT EXISTS) and the user's
// password is set to the one in the secret store, so a rotated password is
// applied by running this again. The user is `@'%'` because haproxy's tcp mode
// hides the real client address (as in wordpress_site.scala).
//
// Runs on tst3, a Galera node: the root login is the unix socket.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object magnum_database extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst3")

  // Keep in step with magnum_single_node.scala.
  private val vip = "10.10.5.100"
  private val dbName = "magnum"
  private val dbUser = "magnum"
  private val dbPassword = Secrets.secret("magnum/db")

  def describe(node: Node): List[ApplyResource] =
    List(
      sqlDatabase(
        s"the $dbName database and the $dbUser user on the Galera cluster",
        List(dbName),
        dbUser,
        dbPassword,
        vip
      )
    )
