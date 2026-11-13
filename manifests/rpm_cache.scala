// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// rpm package cache on tst12 (10.10.5.24): an nginx caching mirror of the Rocky
// Linux repositories (and EPEL), for the dnf nodes (tst8, Rocky 10), as a
// declarative description (OrpheraApply, docs/APPLY-DESIGN.md).
//
//   orphera apply rpm_cache.scala --check
//   orphera apply rpm_cache.scala
//
// A second run changes nothing and does not restart nginx: it is restarted
// only when its configuration file changed, and only after `nginx -t` accepted
// the new file (if it does not, the restart is skipped and the run fails).
// (It replaced a cluster playbook of the same name, which restarted nginx on
// every run, on 2026-11-13.)
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
// The last two resources are checks, written as guarded commands: "unchanged"
// while the cache answers and serves each Rocky repository's repomd.xml (which
// proves the upstream paths, DNS and TLS verification work); otherwise they
// wait for it, or fail with the HTTP codes.
//
// Disk: /var/cache/nginx/rpm, limited to 80 GB by nginx (the VM has 120 GB).
// Only files that were requested are stored.
object rpm_cache extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst12")

  private val port = 8080
  private val release = "10"
  private val arch = "x86_64"
  private val repos = List("BaseOS", "AppStream", "extras", "CRB")

  // Plain triple-quoted strings (no interpolation): nginx variables use `$`.
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

  private val healthy = s"""[ "$$(curl -s http://localhost:$port/healthz)" = "ok" ]"""

  // Up to a minute for nginx to answer after a (re)start.
  private val waitHealthy =
    s"""for i in $$(seq 1 20); do $healthy && exit 0; sleep 3; done
       |echo "nginx does not answer on port $port - see 'journalctl -xeu nginx'" >&2
       |exit 1""".stripMargin

  private def code(repo: String): String =
    s"$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/rocky/$release/$repo/$arch/os/repodata/repomd.xml)"

  // Every repository's repomd.xml through the cache answers 200.
  private val repomdOk = repos.map(r => s"""[ "${code(r)}" = "200" ]""").mkString(" && ")

  private val repomdReport =
    (repos.map(r => s"""echo "rocky $release $r repomd.xml through the cache: HTTP ${code(r)}"""") :+ "exit 1").mkString("\n")

  def describe(node: Node): List[ApplyResource] =
    val nginx = pkg("nginx", updateCache = true)
    val confPath = "/etc/nginx/conf.d/orphera-rpm-cache.conf"
    val checkConf = command("check the nginx configuration", "nginx -t", onChangeOnly = true)
    val server = service("nginx").after(checkConf)
    val answering = command("the cache answers", waitHealthy, unless = healthy).after(server)
    List(
      nginx,
      pkg("curl"),
      directory("/var/cache/nginx/rpm", owner = "www-data", group = "www-data").after(nginx),
      file(confPath, nginxConf + "\n", mode = "0644").after(nginx).notifies(checkConf, server),
      checkConf,
      server,
      answering,
      command("each Rocky repository is served through the cache", repomdReport, unless = repomdOk, timeout = 300)
        .after(answering)
    )
