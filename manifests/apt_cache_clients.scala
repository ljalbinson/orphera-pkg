// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Points apt on the listed nodes at the apt cache on tst11 (apt_cache.scala),
// as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md): a re-run
// changes nothing unless a file differs, and `--check` shows the difference
// first. (It replaced a cluster playbook of the same name, which wrote the
// same two files with the same content, on 2026-11-13.)
//
//   orphera apply apt_cache_clients.scala --check
//   orphera apply apt_cache_clients.scala [--nodes tst5]
//
// Two files on each node:
//   - /usr/local/sbin/orphera-apt-proxy prints the cache's URL if tst11:3142
//     answers within 2 seconds, otherwise DIRECT;
//   - /etc/apt/apt.conf.d/02orphera-apt-proxy tells apt to run it for
//     plain-HTTP repositories, to fetch HTTPS ones directly (a proxy cannot
//     cache those), and turns off HTTP pipelining with a 30 s timeout, because
//     a half-closed connection to the cache can otherwise leave apt hanging.
// So a node uses the cache whenever it is up and works without it when it is
// not.
//
// Which nodes: Ubuntu nodes on the 10.10.5.0/24 test network and the
// Kubernetes nodes tst30-tst39; tst11 itself is left out (the cache must not
// fetch through itself) and so is tst8 (Rocky, uses dnf). Edit `nodes` to add
// or remove nodes; every listed node must be reachable and have a running agent.
object apt_cache_clients extends OrpheraApply:
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
