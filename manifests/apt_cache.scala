// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// apt package cache on tst11 (10.10.5.23): apt-cacher-ng, the Debian/Ubuntu
// caching proxy, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). Nodes pointed at it (apt_cache_clients.scala) fetch
// .deb files and package indexes through it; the first request goes to the
// Ubuntu mirror and is stored on tst11, later requests from any node are served
// locally. If the cache is down, clients fall back to fetching directly (see
// the proxy auto-detect script in apt_cache_clients.scala).
//
//   orphera apply apt_cache.scala --check
//   orphera apply apt_cache.scala
//
// A second run changes nothing and does not restart apt-cacher-ng: it is
// restarted only when its configuration file changed. (It replaced a cluster
// playbook of the same name, which restarted it on every run, on 2026-11-13.)
//
// Plain HTTP repositories (archive.ubuntu.com, security.ubuntu.com) are
// cached. HTTPS repositories (download.ceph.com, ...) cannot be cached by a
// proxy that does not terminate TLS; clients are told to fetch those directly,
// and the tunnelling pass-through is only a safety net.
//
// The last three resources are checks, written as guarded commands: each is
// "unchanged" while what it checks holds, and otherwise waits for it or fails
// saying what is wrong (the cache answering, tunnelling allowed), or does it
// (storing the package indexes, once, on an empty cache).
//
// VfileUseRangeOps: 0 works around a race on concurrent Release-file requests
// (see the CHANGELOG). DontCacheRequested for Release files was tried and makes
// apt-cacher-ng 3.7.4 answer 400 Bad Request; do not use it.
//
// Disk: the cache lives in /var/cache/apt-cacher-ng. config/tst11.yaml gives
// the VM a 120 GB disk. apt-cacher-ng expires files that are no longer in any
// index (daily maintenance), so it does not grow without bound.
object apt_cache extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst11")

  private val port = 3142

  // A drop-in file (apt-cacher-ng reads /etc/apt-cacher-ng/*.conf after
  // acng.conf). PassThroughPattern allows CONNECT tunnels so a client that does
  // send HTTPS through the proxy is not refused.
  private val conf =
    """# Managed by Orphera (apt_cache.scala)
      |PassThroughPattern: .*
      |# When many clients ask for the same stale Release file at once, the default
      |# (VfileUseRangeOps: 1, validate it with a Range request) made apt-cacher-ng
      |# 3.7.4 log "storage error [Checked size beyond EOF] ... File exists" and
      |# drop a client's connection, which left apt hung. 0 uses only
      |# If-Modified-Since and refetches the whole file if it changed.
      |VfileUseRangeOps: 0
      |""".stripMargin

  private val healthy =
    s"""[ "$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/acng-report.html)" = "200" ]"""

  // Up to a minute for the cache to answer after a (re)start.
  private val waitHealthy =
    s"""for i in $$(seq 1 20); do $healthy && exit 0; sleep 3; done
       |echo "apt-cacher-ng does not answer on port $port - see 'journalctl -xeu apt-cacher-ng'" >&2
       |exit 1""".stripMargin

  // Through the proxy with CONNECT: any HTTP status from the far side counts; a
  // proxy refusal makes curl fail.
  private val tunnels =
    s"curl -sS -o /dev/null --max-time 30 -x http://localhost:$port https://download.ceph.com/"

  // This node uses the cache for itself too, so the Ubuntu index files are
  // stored before any other node asks. Its own apt configuration is left
  // untouched: the proxy is given on the command line only.
  private val warm =
    s"""set -e
       |apt-get -qq -o Acquire::http::Proxy=http://localhost:$port update
       |find /var/cache/apt-cacher-ng -type f | wc -l | sed 's/^/files in the cache: /'""".stripMargin

  def describe(node: Node): List[ApplyResource] =
    val acng = pkg("apt-cacher-ng", updateCache = true)
    val cacher = service("apt-cacher-ng").after(acng)
    val answering = command("the cache answers", waitHealthy, unless = healthy).after(cacher)
    val tunnelling = command(
      "tunnelling through the cache is allowed",
      "echo 'a CONNECT tunnel through apt-cacher-ng failed: refused (check PassThroughPattern), or download.ceph.com unreachable' >&2; exit 1",
      unless = tunnels
    ).after(answering)
    List(
      acng,
      pkg("curl"),
      file("/etc/apt-cacher-ng/zz_orphera.conf", conf, mode = "0644").after(acng).notifies(cacher),
      cacher,
      answering,
      tunnelling,
      command(
        "store the package indexes in the cache",
        warm,
        unless = "find /var/cache/apt-cacher-ng -type f -name 'InRelease' | grep -q .",
        timeout = 600
      ).after(tunnelling)
    )
