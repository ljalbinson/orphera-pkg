// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Sixth infrastructure exercise, and a genuinely different shape from the
// last two (etcd_cluster.scala, ceph_mon_quorum.scala, mariadb_galera_cluster.scala,
// mariadb_haproxy_keepalived.scala): those were all about building the
// clustered infrastructure itself. This one is the first "ordinary app on
// top of already-working infrastructure" exercise — a single WordPress
// node (tst7, deliberately NOT one of tst3/tst4/tst5) using the existing
// Galera cluster fronted by mariadb_haproxy_keepalived.scala's floating IP
// (10.10.5.100) as its database, rather than standing up its own.
//
// Prerequisite, not checked by this file: mariadb_galera_cluster.scala AND
// mariadb_haproxy_keepalived.scala must already be applied and healthy on
// tst3/tst4/tst5 — this playbook only ever talks to the cluster through
// the VIP, on the write listener (port 3306, pinned to tst3 with tst4/tst5
// as backup — see that file's header comment), never the round-robin read
// listener on 3307, since WordPress uses one connection for both reads and
// writes and needs write capability throughout, not just for the initial
// install.
//
// Three stages, asymmetric in a new way from anything earlier in this
// project: not "one node bootstraps, others join" (mariadb_galera_cluster.scala)
// or "one node's output feeds the others" (ceph_mon_quorum.scala), but
// "one stage prepares shared state on a DIFFERENT cluster than the one
// this manifest is actually building":
//   - create-wordpress-database (tst3 only): creates the `wordpress`
//     database and `wordpress_user`, run locally via unix-socket root auth
//     on tst3 — reusing the exact same trusted-root assumption
//     mariadb_galera_cluster.scala's own bootstrapScript/joinScript
//     depend on. Because Galera replicates DDL/DML synchronously, this one
//     write on tst3 is immediately visible on tst4/tst5 too; no need to
//     repeat it on all three.
//   - install-wordpress (tst7 only): installs nginx + php-fpm, downloads
//     WordPress, and writes wp-config.php pointing at the VIP.
//   - confirm-wordpress-healthy (tst7 only): a SEPARATE stage for the
//     `wait_for` health check and its confirm task, not more tasks on
//     install-wordpress above — `ClusterPlaybookRunner.runStage` checks a
//     stage's `waitFor` BEFORE that stage's own tasks run, not after, so a
//     health check sharing a stage with the tasks that bring the thing up
//     gets evaluated before those tasks ever ran. Found on a real first
//     run: the health check timed out every time because nginx/php-fpm
//     weren't installed yet when it started polling.
//
// wp-config.php, the nginx site and the two services are apply descriptions
// (Task.Apply). wp-config.php is a `secretFile` (it holds the database
// password and the salts, so its content is never shown). Its authentication
// salts are no longer fetched from api.wordpress.org on every run, which made
// the file different each time (and logged every WordPress user out): the
// eight values are random secrets kept in the orchestrator's secret store
// (`wordpress/salt-*`, made on first use), so they are the same on every run
// and need no network access. The step that downloads WordPress still
// replaces /var/www/wordpress on every run, so wp-config.php is written
// ("created") on every run until that step is converted as well. Nothing in
// nginx's configuration depends on it, so nginx is not restarted.
object wordpress_site extends OrpheraClusterPlaybook:

  // Test-only hardcoded credentials, same convention (and same caveat) as
  // sstPassword/clustercheckPassword in the two files this one builds on:
  // fine for disposable test infrastructure, not a pattern to reuse
  // anywhere real credentials matter.
  private val dbName = "wordpress"
  private val dbUser = "wordpress_user"
  private val dbPassword = Secrets.secret("wordpress/db")

  // The floating IP mariadb_haproxy_keepalived.scala adds to whichever of
  // tst3/tst4/tst5 currently holds it — WordPress never needs to know
  // which real node is actually serving it.
  private val vip = "10.10.5.100"

  // tst3 only. `IF NOT EXISTS`/re-GRANTing is a no-op on a re-run, same
  // idempotency convention as createClustercheckUserScript in
  // mariadb_haproxy_keepalived.scala. Deliberately `@'%'`, not
  // `@'10.10.5.19'`: WordPress's own traffic arrives at whichever node
  // HAProxy is routing to as a plain TCP connection from that node's own
  // address, not from tst7 directly (haproxy's `mode tcp` doesn't forward
  // the original client address to the backend leg — the exact same
  // reason mariadb_haproxy_keepalived.scala's own clustercheck user needed
  // `@'%'` rather than a specific host).
  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // tst7 only. Ubuntu 24.04's default PHP is 8.3; the packages below are
  // the minimum WordPress core actually needs (mysqli driver, image
  // handling for media uploads, multibyte-string handling, XML for
  // XML-RPC/import, zip for plugin/theme installs/updates).
  private val wordpressPackages = List(
    "nginx",
    "php8.3-fpm",
    "php8.3-mysql",
    "php8.3-curl",
    "php8.3-gd",
    "php8.3-mbstring",
    "php8.3-xml",
    "php8.3-zip",
    "unzip",
    "wget"
  )

  // Downloads and unpacks WordPress fresh every run (idempotent by
  // replacement, same "just rewritten identically" convention as
  // etcd_cluster.scala's systemd unit) rather than trying to detect and
  // skip an existing install — this is a fresh single-node exercise, not
  // an in-place-upgrade tool.
  private val downloadWordpressScript =
    raw"""rm -rf /tmp/wordpress /tmp/latest.tar.gz
         |wget -q -O /tmp/latest.tar.gz https://wordpress.org/latest.tar.gz
         |tar xzf /tmp/latest.tar.gz -C /tmp
         |rm -rf /var/www/wordpress
         |mv /tmp/wordpress /var/www/wordpress
         |chown -R www-data:www-data /var/www/wordpress
         |find /var/www/wordpress -type d -exec chmod 755 {} \;
         |find /var/www/wordpress -type f -exec chmod 644 {} \;
         |echo "WordPress downloaded and extracted to /var/www/wordpress"""".stripMargin

  // The eight authentication keys and salts WordPress wants, one stored secret
  // each (24 random letters and digits: nothing PHP would need escaped).
  private val saltNames = List(
    "AUTH_KEY",
    "SECURE_AUTH_KEY",
    "LOGGED_IN_KEY",
    "NONCE_KEY",
    "AUTH_SALT",
    "SECURE_AUTH_SALT",
    "LOGGED_IN_SALT",
    "NONCE_SALT"
  )

  private def saltLines: String =
    saltNames
      .map { n =>
        val v = Secrets.get("wordpress/salt-" + n.toLowerCase.replace('_', '-'))
        s"define( '$n', '$v' );"
      }
      .mkString("\n")

  // wp-config.php: database settings (the VIP, the write listener), the salts,
  // and WordPress's own boilerplate. `$$table_prefix` is the PHP variable.
  private val wpConfigPlan: ApplyPlan =
    import Resources.*
    val text =
      s"""<?php
         |define( 'DB_NAME', '$dbName' );
         |define( 'DB_USER', '$dbUser' );
         |define( 'DB_PASSWORD', '${dbPassword.reveal}' );
         |define( 'DB_HOST', '$vip:3306' );
         |define( 'DB_CHARSET', 'utf8mb4' );
         |define( 'DB_COLLATE', '' );
         |
         |$saltLines
         |
         |$$table_prefix = 'wp_';
         |
         |define( 'WP_DEBUG', false );
         |
         |if ( ! defined( 'ABSPATH' ) ) {
         |\tdefine( 'ABSPATH', __DIR__ . '/' );
         |}
         |require_once ABSPATH . 'wp-settings.php';
         |""".stripMargin
    ApplyPlan.orFail(
      "wordpress-config",
      List(
        secretFile(
          "/var/www/wordpress/wp-config.php",
          Secret(text),
          mode = "0640",
          owner = "www-data",
          group = "www-data"
        )
      )
    )

  // The nginx site, its link in sites-enabled, nginx's stock default site
  // removed, nginx's configuration checked (`nginx -t`, so a bad file never
  // reaches a restart: the check fails and nginx is left as it is), and the two
  // services enabled and running, nginx restarted when any of its three files
  // changed. The site file is plain text (`$uri` and the other nginx variables
  // are not rendered). php8.3-fpm needs only to be running: the package starts
  // it, and nothing here changes its configuration.
  private val webPlan: ApplyPlan =
    import Resources.*
    val nginx = service("nginx")
    val php = service("php8.3-fpm")
    val site = file(
      "/etc/nginx/sites-available/wordpress",
      """server {
         |    listen 80;
         |    server_name _;
         |    root /var/www/wordpress;
         |    index index.php index.html;
         |
         |    location / {
         |        try_files $uri $uri/ /index.php?$args;
         |    }
         |
         |    location ~ \.php$ {
         |        include snippets/fastcgi-php.conf;
         |        fastcgi_pass unix:/run/php/php8.3-fpm.sock;
         |    }
         |
         |    location ~ /\.ht {
         |        deny all;
         |    }
         |}
""".stripMargin,
      mode = "0644"
    ).notifies(nginx)
    val enabled = symlink(
      "/etc/nginx/sites-enabled/wordpress",
      "/etc/nginx/sites-available/wordpress"
    ).after(site).notifies(nginx)
    val noDefault =
      absentFile("/etc/nginx/sites-enabled/default").notifies(nginx)
    val configOk = command(
      "check the nginx configuration",
      "nginx -t",
      unless = "nginx -t >/dev/null 2>&1"
    ).after(site, enabled, noDefault)
    ApplyPlan.orFail(
      "wordpress-web",
      List(site, enabled, noDefault, configOk, php, nginx.after(configOk))
    )

  // Not just an HTTP-200 check: a DB-connection failure (wrong VIP, no
  // grant reaching this node's path through haproxy, etc.) still often
  // renders as a 200 OK page with "Error establishing a database
  // connection" in the body, so that phrase is checked for too — same
  // "prove the real thing, don't just trust a status flag" reasoning as
  // test_mariadb_galera.sh's replicated-row check.
  //
  // Real run finding: a fresh install (database reachable, but no
  // WordPress tables yet) doesn't serve `/` as 200 at all — WordPress's
  // own `wp-settings.php` redirects it 302 to `/wp-admin/install.php` to
  // run the setup wizard, which is the CORRECT, expected first-run
  // behavior, not a failure. The original check only ever accepted a bare
  // 200 on `/`, so it rejected a perfectly healthy fresh install. Fixed
  // by following the redirect (`curl -sL`) before judging the final page
  // — a DB-connection failure still won't produce a clean 200 after
  // following redirects, so the "don't trust a status flag alone" check
  // below still holds.
  private val siteHealthScript =
    """CODE=$(curl -s -L -o /tmp/wp-check.html -w '%{http_code}' http://localhost/)
      |if [ "$CODE" = "200" ] && ! grep -qi "Error establishing a database connection" /tmp/wp-check.html; then
      |  exit 0
      |else
      |  exit 1
      |fi""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("wordpress-site")(
      stage("create-wordpress-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-wordpress", "tst7")
        .task("install nginx, php-fpm and required PHP extensions")(
          Task.Install(packages = wordpressPackages, updateCache = true)
        )
        .task("download and extract WordPress")(
          Task.RunCommand(
            List("sh", "-c", downloadWordpressScript),
            timeoutSeconds = 120
          )
        )
        .task("write wp-config.php")(
          Task.Apply(wpConfigPlan)
        )
        .task("write the nginx site; run nginx and php8.3-fpm")(
          Task.Apply(webPlan)
        )
        .build,

      // A separate stage, not more tasks tacked onto install-wordpress
      // above: `ClusterPlaybookRunner.runStage` checks a stage's `waitFor`
      // BEFORE running that same stage's own tasks (it's a precondition
      // gate on entry, not a postcondition on exit) — confirmed the hard
      // way on a real first run, where `siteHealthScript` was being polled
      // against tst7 before nginx/php-fpm were even installed, so it just
      // timed out every time. `mariadb_galera_cluster.scala`'s own
      // confirm-cluster-healthy stage happens to already follow this
      // correctly (nothing else in that stage depends on running before
      // its own waitFor), which is exactly why this same mistake was easy
      // to make here without noticing until a real run caught it.
      stage("confirm-wordpress-healthy", "tst7")
        .waitFor(
          HealthCheck.Command(
            onNode = "tst7",
            command = List("sh", "-c", siteHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("wordpress site confirmed")(
          Task.Debug(
            s"WordPress reachable at http://tst7.ljalbinson.com/ (10.10.5.19), database on the Galera cluster via $vip:3306 — open it in a browser to run the WordPress install wizard."
          )
        )
        .build
    )
