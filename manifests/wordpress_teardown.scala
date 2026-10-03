// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Tears down what wordpress_site.scala (above) builds: the wordpress
// database/user on the Galera cluster, and the nginx + php8.3-fpm +
// WordPress install on tst5. Two stages, mirroring that file's own
// asymmetric shape — one targets tst0 (the Galera cluster), the other
// targets tst5 (the standalone WordPress node) — rather than one shared
// stage, since they're two different machines doing two unrelated kinds
// of cleanup.
//
// Same full-purge convention as mariadb_galera_teardown.scala, not
// cephadm_teardown.scala's leave-packages-in-place approach: nginx,
// php8.3-fpm and friends are normal, fast apt packages (not
// slow-to-reinstall cephadm/podman), and tst5 exists solely for this one
// exercise (see inventory.yaml's own comment on tst5), so there's no
// reason to leave anything behind between runs.
object wordpress_teardown extends OrpheraClusterPlaybook:

  // Must match wordpress_site.scala's own dbName/dbUser exactly — not
  // imported from that file since manifests in this project are each
  // standalone-compiled scripts (see mariadb_galera_rolling_restart.scala's
  // own header comment for why duplicating rather than sharing a module is
  // the established convention here).
  private val dbName = "wordpress"
  private val dbUser = "wordpress_user"

  // tst0 only. `DROP ... IF EXISTS` is idempotent — safe to run again even
  // if wordpress_site.scala's create-wordpress-database stage never ran
  // (or already failed) on this cluster. Galera replicates this
  // synchronously to tst1/tst2 as well, same reasoning as the CREATE on
  // the way up.
  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // Not package-owned, so purging nginx doesn't reliably clean these up:
  // /var/www/wordpress was never shipped by any package, and
  // sites-available/sites-enabled/wordpress are files this project wrote
  // directly, not packaged conffiles nginx's own postrm knows to remove —
  // same "don't trust purge's own behavior for anything this project
  // wrote itself" reasoning as mariadb_galera_teardown.scala's
  // haproxyKeepalivedCleanupScript.
  private val wordpressCleanupScript =
    """rm -rf /var/www/wordpress
      |rm -f /etc/nginx/sites-available/wordpress /etc/nginx/sites-enabled/wordpress
      |echo 'WordPress site files and nginx site config removed'""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("wordpress-teardown")(
      stage("drop-wordpress-database", "tst0")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build,

      stage("teardown-tst5", "tst5")
        .task("stop nginx and php8.3-fpm")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "systemctl stop nginx php8.3-fpm 2>/dev/null || true"
            )
          )
        )
        .task("purge nginx, php8.3-fpm and PHP extensions")(
          Task.Remove(
            packages = List(
              "nginx",
              "php8.3-fpm",
              "php8.3-mysql",
              "php8.3-curl",
              "php8.3-gd",
              "php8.3-mbstring",
              "php8.3-xml",
              "php8.3-zip"
            ),
            purge = true
          )
        )
        .task("autoremove now-unneeded dependencies")(
          Task.AutoRemove(purge = true)
        )
        .task("remove leftover WordPress files and nginx site config")(
          Task.RunCommand(List("sh", "-c", wordpressCleanupScript))
        )
        .build
    )
