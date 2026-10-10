// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// apt package cache on tst11 (10.10.5.23): apt-cacher-ng, the Debian/Ubuntu
// caching proxy. Nodes pointed at it (apt_cache_clients.scala) fetch .deb files
// and package indexes through it; the first request goes to the Ubuntu mirror
// and is stored on tst11, later requests from any node are served locally.
// If the cache is down, clients fall back to fetching directly (see the
// proxy auto-detect script in apt_cache_clients.scala).
//
// Plain HTTP repositories (archive.ubuntu.com, security.ubuntu.com) are
// cached. HTTPS repositories (download.ceph.com, ...) cannot be cached by a
// proxy that does not terminate TLS; clients are told to fetch those directly,
// and the tunnelling pass-through below is only a safety net.
//
// Stages:
//   - install-apt-cache (tst11): apt-cacher-ng, a drop-in config, restart.
//   - confirm-apt-cache-healthy (tst11): waits for the report page, checks that
//     tunnelling works, then refreshes this node's own package indexes through
//     the cache so the indexes are stored before any other node asks.
//
// Disk: the cache lives in /var/cache/apt-cacher-ng. config/tst11.yaml gives
// the VM a 120 GB disk. apt-cacher-ng expires files that are no longer in any
// index (daily maintenance), so it does not grow without bound.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object apt_cache extends OrpheraClusterPlaybook:

  private val node = "tst11"
  private val port = 3142

  // A drop-in file (apt-cacher-ng reads /etc/apt-cacher-ng/*.conf after
  // acng.conf). PassThroughPattern allows CONNECT tunnels so a client that does
  // send HTTPS through the proxy is not refused.
  private val configScript =
    s"""cat > /etc/apt-cacher-ng/zz_orphera.conf <<'CONF'
       |# Managed by Orphera (apt_cache.scala)
       |PassThroughPattern: .*
       |CONF
       |systemctl enable apt-cacher-ng
       |systemctl restart apt-cacher-ng
       |sleep 3
       |if ! systemctl is-active --quiet apt-cacher-ng; then
       |  echo "apt-cacher-ng failed to start - see 'journalctl -xeu apt-cacher-ng'" >&2
       |  exit 1
       |fi
       |echo "apt-cacher-ng running on port $port"""".stripMargin

  private val healthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/acng-report.html)
       |[ "$$CODE" = "200" ]""".stripMargin

  // Fetch through the proxy with CONNECT: proves the pass-through setting was
  // read. Any HTTP status from the far side counts; a proxy refusal makes curl
  // fail with a non-zero exit.
  private val tunnelScript =
    s"""curl -sS -o /dev/null -w 'tunnel to download.ceph.com: HTTP %{http_code}\\n' --max-time 30 -x http://localhost:$port https://download.ceph.com/""".stripMargin

  // This node uses the cache for itself too, so the Ubuntu index files are
  // stored now. Its own apt configuration is left untouched: the proxy is given
  // on the command line only.
  private val warmScript =
    s"""set -e
       |apt-get -qq -o Acquire::http::Proxy=http://localhost:$port update
       |echo "package indexes cached"
       |find /var/cache/apt-cacher-ng -type f | wc -l | sed 's/^/files in the cache: /'""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("apt-cache")(
      stage("install-apt-cache", node)
        .task("install apt-cacher-ng")(
          Task.Install(packages = List("apt-cacher-ng", "curl"), updateCache = true)
        )
        .task("configure and start apt-cacher-ng")(
          Task.RunCommand(List("sh", "-c", configScript), timeoutSeconds = 120)
        )
        .build,

      stage("confirm-apt-cache-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 3,
            timeoutSeconds = 60
          )
        )
        .task("check that tunnelling through the cache is allowed")(
          Task.RunCommand(List("sh", "-c", tunnelScript), timeoutSeconds = 60)
        )
        .task("refresh this node's package indexes through the cache")(
          Task.RunCommand(List("sh", "-c", warmScript), timeoutSeconds = 600)
        )
        .build
    )
