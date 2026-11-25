// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The Keycloak database and user on the Galera cluster, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It was a stage of
// keycloak_single_node.scala when that was a cluster playbook; the description for
// the Keycloak node needs this to have run first, since Keycloak fails at its first start
// without the database, and one description cannot order its nodes. The database is utf8mb4.
//
//   orphera apply manifests/keycloak_database.scala --check
//   orphera apply manifests/keycloak_database.scala
//
// The check is a real login as the Keycloak user, through the VIP, to the keycloak
// database: the path keycloak.conf uses. Where it works, nothing is changed.
// Otherwise the database and user are created (IF NOT EXISTS) and the user's
// password is set to the one in the secret store, so a rotated password is
// applied by running this again. The user is `@'%'` because haproxy's tcp mode
// hides the real client address (as in wordpress_site.scala).
//
// Runs on tst3, a Galera node: the root login is the unix socket.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object keycloak_database extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst3")

  // Keep in step with keycloak_single_node.scala.
  private val vip = "10.10.5.100"
  private val dbName = "keycloak"
  private val dbUser = "keycloak"
  private val dbPassword = Secrets.secret("keycloak/db")

  def describe(node: Node): List[ApplyResource] =
    List(
      sqlDatabase(
        s"the $dbName database and the $dbUser user on the Galera cluster",
        List(dbName),
        dbUser,
        dbPassword,
        vip,
        charset = "utf8mb4",
        collation = "utf8mb4_unicode_ci"
      )
    )
