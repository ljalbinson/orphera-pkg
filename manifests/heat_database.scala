// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The Heat database and user on the Galera cluster, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It was a stage of
// heat_single_node.scala when that was a cluster playbook; the description for
// the Heat node needs this to have run first, since its db sync would fail
// without the database, and one description cannot order its nodes.
//
//   orphera apply manifests/heat_database.scala --check
//   orphera apply manifests/heat_database.scala
//
// The check is a real login as the Heat user, through the VIP, to the heat
// database: the path heat.conf uses. Where it works, nothing is changed.
// Otherwise the database and user are created (IF NOT EXISTS) and the user's
// password is set to the one in the secret store, so a rotated password is
// applied by running this again. The user is `@'%'` because haproxy's tcp mode
// hides the real client address (as in wordpress_site.scala).
//
// Runs on tst3, a Galera node: the root login is the unix socket.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object heat_database extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst3")

  // Keep in step with heat_single_node.scala.
  private val vip = "10.10.5.100"
  private val dbName = "heat"
  private val dbUser = "heat"
  private val dbPassword = Secrets.secret("heat/db")

  private val createDatabase =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ALTER USER '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // A real login as the heat user through the VIP.
  private val canLogIn =
    s"""MYSQL_PWD='${dbPassword.reveal}' mariadb -N -h $vip -u $dbUser $dbName -e 'SELECT 1' >/dev/null 2>&1"""

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        s"the $dbName database and the $dbUser user on the Galera cluster",
        createDatabase,
        unless = canLogIn
      )
    )
