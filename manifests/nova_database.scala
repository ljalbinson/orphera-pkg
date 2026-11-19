// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// The Nova databases (nova_api, nova, nova_cell0) and user on the Galera cluster, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It was the first stage of
// nova_single_node.scala when that was a cluster playbook; the description
// for the Nova node (nova_single_node.scala) needs this to have run
// first, since its db_sync would fail without the database, and one description
// cannot order its nodes.
//
//   orphera apply manifests/nova_database.scala --check
//   orphera apply manifests/nova_database.scala
//
// The check is a real login as the Nova user, through the VIP, to each of the
// three databases (nova_api, nova, nova_cell0): the paths nova.conf uses. Where it works, nothing is
// changed. Otherwise the database and user are created (IF NOT EXISTS) and the
// user's password is set to the one in the secret store, so a rotated password
// is applied by running this again. The user is `@'%'` because haproxy's tcp
// mode hides the real client address (as in wordpress_site.scala).
//
// Runs on tst3, a Galera node: the root login is the unix socket.
//
// Test-only credentials from the secret store (`orphera secrets`), as before.
object nova_database extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst3")

  // Keep in step with nova_single_node.scala.
  private val vip = "10.10.5.100"
  private val dbs = List("nova_api", "nova", "nova_cell0")
  private val dbUser = "nova"
  private val dbPassword = Secrets.secret("nova/db")

  private val createStatements =
    dbs.map(d => "CREATE DATABASE IF NOT EXISTS " + d + "; ").mkString
  private val grantStatements =
    dbs.map(d => "GRANT ALL PRIVILEGES ON " + d + ".* TO '" + dbUser + "'@'%'; ").mkString
  private val createDatabases =
    s"""mariadb -N -e "${createStatements}CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ALTER USER '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ${grantStatements}FLUSH PRIVILEGES;""""

  // A real login as the nova user through the VIP to each of the three databases.
  private val canLogIn =
    dbs
      .map(d =>
        "MYSQL_PWD='" + dbPassword.reveal + "' mariadb -N -h " + vip + " -u " + dbUser + " " + d + " -e 'SELECT 1' >/dev/null 2>&1"
      )
      .mkString(" && ")

  def describe(node: Node): List[ApplyResource] =
    List(
      command(
        "the " + dbs.mkString(", ") + " databases and the " + dbUser + " user on the Galera cluster",
        createDatabases,
        unless = canLogIn
      )
    )
