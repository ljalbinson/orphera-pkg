// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Sixth infrastructure exercise, and a genuinely different shape from the
// last two (etcd_cluster.scala, ceph_mon_quorum.scala, mariadb_galera_cluster.scala,
// mariadb_haproxy_keepalived.scala): those were all about building the
// clustered infrastructure itself. This one is the first "ordinary app on
// top of already-working infrastructure" exercise — a single WordPress
// node (tst5, deliberately NOT one of tst0/tst1/tst2) using the existing
// Galera cluster fronted by mariadb_haproxy_keepalived.scala's floating IP
// (10.10.5.100) as its database, rather than standing up its own.
//
// Prerequisite, not checked by this file: mariadb_galera_cluster.scala AND
// mariadb_haproxy_keepalived.scala must already be applied and healthy on
// tst0/tst1/tst2 — this playbook only ever talks to the cluster through
// the VIP, on the write listener (port 3306, pinned to tst0 with tst1/tst2
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
//   - create-wordpress-database (tst0 only): creates the `wordpress`
//     database and `wordpress_user`, run locally via unix-socket root auth
//     on tst0 — reusing the exact same trusted-root assumption
//     mariadb_galera_cluster.scala's own bootstrapScript/joinScript
//     depend on. Because Galera replicates DDL/DML synchronously, this one
//     write on tst0 is immediately visible on tst1/tst2 too; no need to
//     repeat it on all three.
//   - install-wordpress (tst5 only): installs nginx + php-fpm, downloads
//     WordPress, and writes wp-config.php pointing at the VIP.
//   - confirm-wordpress-healthy (tst5 only): a SEPARATE stage for the
//     `wait_for` health check and its confirm task, not more tasks on
//     install-wordpress above — `ClusterPlaybookRunner.runStage` checks a
//     stage's `waitFor` BEFORE that stage's own tasks run, not after, so a
//     health check sharing a stage with the tasks that bring the thing up
//     gets evaluated before those tasks ever ran. Found on a real first
//     run: the health check timed out every time because nginx/php-fpm
//     weren't installed yet when it started polling.
//
// A real gap found by reading ClusterPlaybookRunner.scala's actual
// Task.Copy handling before using it here (not assumed): the cluster
// runner discards Task.Copy's `vars` field entirely
// (`case Task.Copy(src, dest, owner, group, mode, _) =>` — the mustache
// vars map is thrown away), unlike the flat PlaybookRunner.scala, which
// does render `.mustache` templates. So wp-config.php and the nginx site
// config are written here the same way every other config file in this
// project is — a RunCommand heredoc script with values already
// interpolated at Scala-compile-time — rather than Task.Copy with a
// `.mustache` template, which would silently ship the literal
// `{{placeholders}}` unrendered in a ClusterPlaybook.
object wordpress_site extends OrpheraClusterPlaybook:

  // Test-only hardcoded credentials, same convention (and same caveat) as
  // sstPassword/clustercheckPassword in the two files this one builds on:
  // fine for disposable test infrastructure, not a pattern to reuse
  // anywhere real credentials matter.
  private val dbName = "wordpress"
  private val dbUser = "wordpress_user"
  private val dbPassword = "orphera-test-wordpress-password"

  // The floating IP mariadb_haproxy_keepalived.scala adds to whichever of
  // tst0/tst1/tst2 currently holds it — WordPress never needs to know
  // which real node is actually serving it.
  private val vip = "10.10.5.100"

  // tst0 only. `IF NOT EXISTS`/re-GRANTing is a no-op on a re-run, same
  // idempotency convention as createClustercheckUserScript in
  // mariadb_haproxy_keepalived.scala. Deliberately `@'%'`, not
  // `@'10.10.5.17'`: WordPress's own traffic arrives at whichever node
  // HAProxy is routing to as a plain TCP connection from that node's own
  // address, not from tst5 directly (haproxy's `mode tcp` doesn't forward
  // the original client address to the backend leg — the exact same
  // reason mariadb_haproxy_keepalived.scala's own clustercheck user needed
  // `@'%'` rather than a specific host).
  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // tst5 only. Ubuntu 24.04's default PHP is 8.3; the packages below are
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

  // wp-config.php's own DB_* constants are plain Scala-interpolated
  // literals (known at build time), not shell variables — only the
  // authentication salts genuinely need a runtime value (fetched from
  // WordPress's own salt API), so that's the one piece of this heredoc
  // left unquoted for shell expansion. `raw"""..."""` rather than
  // `s"""..."""`, same distinction as mariadb_haproxy_keepalived.scala's
  // clustercheckScript: needs both literal `$`-prefixed shell/PHP tokens
  // (`$SALTS`, `$table_prefix`) AND Scala's own `$dbName`-style
  // interpolation in the same string, which plain `s"""..."""` can't do
  // without every literal `$` doubled into total illegibility.
  //
  // `\$$table_prefix` (three characters after the backslash in the
  // generated shell source: `$`, `$`, then `table_prefix`) collapses to
  // `\$table_prefix` once Scala's `$$` → `$` substitution runs — the
  // backslash then tells the *shell* (this heredoc is unquoted, `<<PHP`,
  // so the salts variable below can expand) not to expand `$table_prefix`
  // itself, so the literal PHP `$table_prefix = 'wp_';` reaches the file
  // untouched rather than being replaced by the shell with an empty
  // string.
  private val writeWpConfigScript =
    raw"""SALTS=$$(curl -s https://api.wordpress.org/secret-key/1.1/salt/)
         |if [ -z "$$SALTS" ]; then
         |  echo "WARNING: could not fetch unique auth salts from api.wordpress.org — wp-config.php will ship without them (WordPress falls back to its own defaults; functional but not unique/secure, acceptable for a disposable test site, not for anything real)"
         |fi
         |cat > /var/www/wordpress/wp-config.php <<PHP
         |<?php
         |define( 'DB_NAME', '$dbName' );
         |define( 'DB_USER', '$dbUser' );
         |define( 'DB_PASSWORD', '$dbPassword' );
         |define( 'DB_HOST', '$vip:3306' );
         |define( 'DB_CHARSET', 'utf8mb4' );
         |define( 'DB_COLLATE', '' );
         |
         |$$SALTS
         |
         |\$$table_prefix = 'wp_';
         |
         |define( 'WP_DEBUG', false );
         |
         |if ( ! defined( 'ABSPATH' ) ) {
         |	define( 'ABSPATH', __DIR__ . '/' );
         |}
         |require_once ABSPATH . 'wp-settings.php';
         |PHP
         |chown www-data:www-data /var/www/wordpress/wp-config.php
         |chmod 640 /var/www/wordpress/wp-config.php
         |echo "wp-config.php written, pointing at $vip:3306"""".stripMargin

  // A plain, static nginx site — no Scala-interpolated or shell-expanded
  // values anywhere in it, so this heredoc is deliberately QUOTED
  // (`<<'NGINX'`), unlike the two scripts above: nginx's own `$uri`/`$args`
  // variables must reach the file untouched, and a quoted heredoc delimiter
  // is what stops the shell trying (and failing) to expand them itself —
  // a much simpler fix than escaping every nginx `$` individually the way
  // wp-config.php's single `$table_prefix` needed above.
  private val nginxSiteScript =
    """rm -f /etc/nginx/sites-enabled/default
      |cat > /etc/nginx/sites-available/wordpress <<'NGINX'
      |server {
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
      |NGINX
      |ln -sf /etc/nginx/sites-available/wordpress /etc/nginx/sites-enabled/wordpress
      |echo "nginx site config written"""".stripMargin

  // Applies this session's own lesson from mariadb_haproxy_keepalived.scala's
  // masked-restart bug proactively, rather than waiting to hit the same
  // failure a fourth time: `nginx -t` is checked BEFORE reloading (a bad
  // config must never take down an already-running nginx), and both
  // services' actual active state is checked AFTER restarting, with a
  // loud `exit 1` if either isn't — not yet exercised against a real
  // failure the way that file's checks were, so treat this as reasoned
  // through rather than battle-tested.
  private val startServicesScript =
    """systemctl enable --now php8.3-fpm
      |if ! systemctl is-active --quiet php8.3-fpm; then
      |  echo "php8.3-fpm failed to start — see 'journalctl -xeu php8.3-fpm' for the real reason" >&2
      |  exit 1
      |fi
      |if ! nginx -t; then
      |  echo "nginx config is invalid — see the nginx -t output above" >&2
      |  exit 1
      |fi
      |systemctl enable --now nginx
      |systemctl restart nginx
      |if ! systemctl is-active --quiet nginx; then
      |  echo "nginx failed to start — see 'journalctl -xeu nginx' for the real reason" >&2
      |  exit 1
      |fi
      |echo "nginx and php8.3-fpm running"""".stripMargin

  // Not just an HTTP-200 check: a DB-connection failure (wrong VIP, no
  // grant reaching this node's path through haproxy, etc.) still often
  // renders as a 200 OK page with "Error establishing a database
  // connection" in the body, so that phrase is checked for too — same
  // "prove the real thing, don't just trust a status flag" reasoning as
  // test_mariadb_galera.sh's replicated-row check.
  private val siteHealthScript =
    """CODE=$(curl -s -o /tmp/wp-check.html -w '%{http_code}' http://localhost/)
      |if [ "$CODE" = "200" ] && ! grep -qi "Error establishing a database connection" /tmp/wp-check.html; then
      |  exit 0
      |else
      |  exit 1
      |fi""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("wordpress-site")(

      stage("create-wordpress-database", "tst0")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-wordpress", "tst5")
        .task("install nginx, php-fpm and required PHP extensions")(
          Task.Install(packages = wordpressPackages, updateCache = true)
        )
        .task("download and extract WordPress")(
          Task.RunCommand(List("sh", "-c", downloadWordpressScript), timeoutSeconds = 120)
        )
        .task("write wp-config.php")(
          Task.RunCommand(List("sh", "-c", writeWpConfigScript))
        )
        .task("write nginx site config")(
          Task.RunCommand(List("sh", "-c", nginxSiteScript))
        )
        .task("start nginx and php8.3-fpm")(
          Task.RunCommand(List("sh", "-c", startServicesScript))
        )
        .build,

      // A separate stage, not more tasks tacked onto install-wordpress
      // above: `ClusterPlaybookRunner.runStage` checks a stage's `waitFor`
      // BEFORE running that same stage's own tasks (it's a precondition
      // gate on entry, not a postcondition on exit) — confirmed the hard
      // way on a real first run, where `siteHealthScript` was being polled
      // against tst5 before nginx/php-fpm were even installed, so it just
      // timed out every time. `mariadb_galera_cluster.scala`'s own
      // confirm-cluster-healthy stage happens to already follow this
      // correctly (nothing else in that stage depends on running before
      // its own waitFor), which is exactly why this same mistake was easy
      // to make here without noticing until a real run caught it.
      stage("confirm-wordpress-healthy", "tst5")
        .waitFor(
          HealthCheck.Command(
            onNode = "tst5",
            command = List("sh", "-c", siteHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("wordpress site confirmed")(
          Task.Debug(s"WordPress reachable at http://tst5.ljalbinson.com/ (10.10.5.17), database on the Galera cluster via $vip:3306 — open it in a browser to run the WordPress install wizard.")
        )
        .build
    )
