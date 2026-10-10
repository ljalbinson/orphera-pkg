// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// rpm package cache on tst12 (10.10.5.24): an nginx caching mirror of the Rocky
// Linux repositories (and EPEL), for the dnf nodes (tst8, Rocky 10).
//
// Why not a plain proxy like the apt cache: Rocky's default repository setup
// finds a mirror over HTTPS (mirrorlist), and an HTTP proxy cannot cache
// HTTPS. So tst12 acts as a mirror instead. It answers plain HTTP on port 8080
// under /rocky/ and /epel/, fetches from the official download hosts
// (dl.rockylinux.org, dl.fedoraproject.org) over verified HTTPS on a miss, and
// stores what it fetched. rpm_cache_clients.scala points dnf at it with the
// official host as a second baseurl, so dnf falls back to the internet if
// tst12 is down.
//
// Cache lifetimes: package files and the hash-named repodata files never change
// under one name, so they are kept for 30 days; repomd.xml (the file that
// names the current metadata) is kept for only 5 minutes.
//
// Stages:
//   - install-rpm-cache (tst12): nginx, the cache config, restart.
//   - confirm-rpm-cache-healthy (tst12): waits for /healthz, then fetches the
//     repomd.xml of each Rocky repository through the cache, which proves the
//     upstream paths and TLS verification work and stores them.
//
// Disk: /var/cache/nginx/rpm, limited to 80 GB by nginx (the VM has 120 GB).
// Only files that were requested are stored.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object rpm_cache extends OrpheraClusterPlaybook:

  private val node = "tst12"
  private val port = 8080
  private val release = "10"
  private val arch = "x86_64"
  private val repos = List("BaseOS", "AppStream", "extras", "CRB")

  // Plain triple-quoted strings (no interpolation): nginx variables use `$`.
  // @PORT@ is substituted below.
  private val nginxConf =
    """proxy_cache_path /var/cache/nginx/rpm levels=1:2 keys_zone=rpm:50m max_size=80g inactive=365d use_temp_path=off;
      |
      |log_format rpmcache '$remote_addr [$time_local] "$request" $status $body_bytes_sent $upstream_cache_status';
      |
      |server {
      |    listen @PORT@;
      |    server_name _;
      |    access_log /var/log/nginx/rpm-cache.log rpmcache;
      |
      |    # Resolve upstream names at request time (CDN addresses change) through
      |    # the local systemd-resolved stub.
      |    resolver 127.0.0.53 valid=300s ipv6=off;
      |
      |    proxy_ssl_server_name on;
      |    proxy_ssl_verify on;
      |    proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
      |    proxy_ssl_verify_depth 3;
      |    proxy_http_version 1.1;
      |    proxy_set_header Connection "";
      |
      |    proxy_cache rpm;
      |    proxy_cache_key $request_uri;
      |    proxy_cache_lock on;
      |    proxy_cache_use_stale error timeout updating http_500 http_502 http_503 http_504;
      |    proxy_cache_valid 200 30d;
      |    proxy_cache_valid 301 302 10m;
      |    proxy_cache_valid 404 1m;
      |    add_header X-Cache-Status $upstream_cache_status always;
      |
      |    location = /healthz {
      |        access_log off;
      |        default_type text/plain;
      |        return 200 "ok\n";
      |    }
      |
      |    # Metadata that names the current repository state: short lifetime.
      |    location ~ ^/rocky/(.*/repodata/repomd\.xml(\.asc)?)$ {
      |        set $up https://dl.rockylinux.org;
      |        rewrite ^/rocky/(.*)$ /pub/rocky/$1 break;
      |        proxy_set_header Host dl.rockylinux.org;
      |        proxy_cache_valid 200 5m;
      |        proxy_pass $up;
      |    }
      |    location /rocky/ {
      |        set $up https://dl.rockylinux.org;
      |        rewrite ^/rocky/(.*)$ /pub/rocky/$1 break;
      |        proxy_set_header Host dl.rockylinux.org;
      |        proxy_pass $up;
      |    }
      |
      |    location ~ ^/epel/(.*/repodata/repomd\.xml(\.asc)?)$ {
      |        set $up https://dl.fedoraproject.org;
      |        rewrite ^/epel/(.*)$ /pub/epel/$1 break;
      |        proxy_set_header Host dl.fedoraproject.org;
      |        proxy_cache_valid 200 5m;
      |        proxy_pass $up;
      |    }
      |    location /epel/ {
      |        set $up https://dl.fedoraproject.org;
      |        rewrite ^/epel/(.*)$ /pub/epel/$1 break;
      |        proxy_set_header Host dl.fedoraproject.org;
      |        proxy_pass $up;
      |    }
      |}""".stripMargin.replace("@PORT@", port.toString)

  // The config is written to conf.d (included inside nginx's http block).
  // nginx -t checks it before the restart, so a typo cannot take nginx down.
  private val configScript =
    ("""set -e
      |mkdir -p /var/cache/nginx/rpm
      |chown -R www-data:www-data /var/cache/nginx/rpm
      |cat > /etc/nginx/conf.d/orphera-rpm-cache.conf <<'NGINXCONF'
      |""".stripMargin + nginxConf +
      """
      |NGINXCONF
      |nginx -t
      |systemctl enable nginx
      |systemctl restart nginx
      |sleep 2
      |if ! systemctl is-active --quiet nginx; then
      |  echo "nginx failed to start - see 'journalctl -xeu nginx'" >&2
      |  exit 1
      |fi
      |echo "nginx rpm cache running on port @PORT@"""".stripMargin.replace("@PORT@", port.toString))

  private val healthScript =
    """[ "$(curl -s http://localhost:@PORT@/healthz)" = "ok" ]""".replace("@PORT@", port.toString)

  // Each fetch must return 200; a bad upstream path, a TLS verification
  // failure or a DNS problem shows up here rather than on the first client.
  private val warmScript =
    ("""set -e
      |for repo in @REPOS@; do
      |  CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:@PORT@/rocky/@RELEASE@/$repo/@ARCH@/os/repodata/repomd.xml)
      |  echo "rocky @RELEASE@ $repo repomd.xml through the cache: HTTP $CODE"
      |  [ "$CODE" = "200" ]
      |done
      |du -sh /var/cache/nginx/rpm""".stripMargin)
      .replace("@REPOS@", repos.mkString(" "))
      .replace("@PORT@", port.toString)
      .replace("@RELEASE@", release)
      .replace("@ARCH@", arch)

  val playbook: ClusterPlaybook =
    clusterPlaybook("rpm-cache")(
      stage("install-rpm-cache", node)
        .task("install nginx")(
          Task.Install(packages = List("nginx", "curl"), updateCache = true)
        )
        .task("configure and start the caching mirror")(
          Task.RunCommand(List("sh", "-c", configScript), timeoutSeconds = 120)
        )
        .build,

      stage("confirm-rpm-cache-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 3,
            timeoutSeconds = 60
          )
        )
        .task("fetch each Rocky repository's repomd.xml through the cache")(
          Task.RunCommand(List("sh", "-c", warmScript), timeoutSeconds = 300)
        )
        .build
    )
