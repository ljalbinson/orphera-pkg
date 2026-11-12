// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// apt_cache_clients.scala as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): the same two files, with the same content, so on a
// node that apt_cache_clients.scala has set up, `--check` reports both
// unchanged. What it adds: a re-run changes nothing unless a file differs, and
// `--check` shows the difference first.
//
//   orphera apply manifests/apt_cache_clients_apply.scala --check
//   orphera apply manifests/apt_cache_clients_apply.scala [--nodes tst5]
//
// The proxy script prints the cache's URL if tst11:3142 answers within 2
// seconds, otherwise DIRECT; the apt config runs it for plain-HTTP
// repositories and fetches HTTPS directly. See apt_cache_clients.scala, which
// stays until this has been used on the lab.
object apt_cache_clients_apply extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] =
    List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5", "tst6", "tst7", "tst9", "tst10") ++
      (30 to 39).map(n => s"tst$n")

  private val cacheHost = "tst11.ljalbinson.com"
  private val port = 3142

  private val detectScript =
    s"""#!/bin/bash
       |# Managed by Orphera (apt_cache_clients.scala).
       |# Prints the apt cache URL when it is reachable, DIRECT otherwise.
       |if timeout 2 bash -c 'exec 3<>/dev/tcp/$cacheHost/$port' 2>/dev/null; then
       |  echo "http://$cacheHost:$port"
       |else
       |  echo "DIRECT"
       |fi
       |""".stripMargin

  private val aptConf =
    """// Managed by Orphera (apt_cache_clients.scala).
      |Acquire::http::Proxy-Auto-Detect "/usr/local/sbin/orphera-apt-proxy";
      |Acquire::https::Proxy "DIRECT";
      |// apt-cacher-ng closes idle connections; with pipelining apt's download
      |// workers can then wait forever on a half-closed socket (seen on tst5: an
      |// `apt-get update` stuck for 7 minutes with the sockets in CLOSE-WAIT).
      |Acquire::http::Pipeline-Depth "0";
      |Acquire::http::Timeout "30";
      |Acquire::Retries "3";
      |""".stripMargin

  def describe(node: Node): List[ApplyResource] =
    val detect = file("/usr/local/sbin/orphera-apt-proxy", detectScript, mode = "0755")
    List(
      detect,
      file("/etc/apt/apt.conf.d/02orphera-apt-proxy", aptConf, mode = "0644").after(detect)
    )
