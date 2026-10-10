// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Points apt on the listed nodes at the apt cache on tst11 (apt_cache.scala).
//
// It installs two things on each node:
//   - /usr/local/sbin/orphera-apt-proxy: prints the cache's URL if tst11:3142
//     answers within 2 seconds, otherwise DIRECT.
//   - /etc/apt/apt.conf.d/02orphera-apt-proxy: tells apt to run that script
//     for plain-HTTP repositories (Acquire::http::Proxy-Auto-Detect) and to
//     fetch HTTPS repositories directly (a proxy cannot cache those).
// So a node uses the cache whenever it is up and quietly works without it when
// it is not; nothing breaks if tst11 is rebuilt or switched off.
//
// Which nodes: Ubuntu nodes on the 10.10.5.0/24 test network. tst11 itself is
// left out (the cache must not fetch through itself) and so is tst8 (Rocky,
// uses dnf). Edit `clients` to add or remove nodes; every listed node must be
// reachable and have a running agent.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object apt_cache_clients extends OrpheraClusterPlaybook:

  private val clients =
    List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5", "tst6", "tst7", "tst9", "tst10")

  private val cacheHost = "tst11.ljalbinson.com"
  private val port = 3142

  // /dev/tcp is a bash feature; the script says bash explicitly. `timeout`
  // bounds the connect attempt so a dead cache costs apt two seconds, not a
  // TCP timeout.
  private val setupScript =
    s"""cat > /usr/local/sbin/orphera-apt-proxy <<'DETECT'
       |#!/bin/bash
       |# Managed by Orphera (apt_cache_clients.scala).
       |# Prints the apt cache URL when it is reachable, DIRECT otherwise.
       |if timeout 2 bash -c 'exec 3<>/dev/tcp/$cacheHost/$port' 2>/dev/null; then
       |  echo "http://$cacheHost:$port"
       |else
       |  echo "DIRECT"
       |fi
       |DETECT
       |chmod 755 /usr/local/sbin/orphera-apt-proxy
       |cat > /etc/apt/apt.conf.d/02orphera-apt-proxy <<'APTCONF'
       |// Managed by Orphera (apt_cache_clients.scala).
       |Acquire::http::Proxy-Auto-Detect "/usr/local/sbin/orphera-apt-proxy";
       |Acquire::https::Proxy "DIRECT";
       |APTCONF
       |echo "apt on $$(hostname) uses the cache at $cacheHost:$port when it is reachable: $$(/usr/local/sbin/orphera-apt-proxy)"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("apt-cache-clients")(
      stage("point-apt-at-the-cache", clients*)
        .task("install the proxy auto-detect script and apt config")(
          Task.RunCommand(List("sh", "-c", setupScript), timeoutSeconds = 60)
        )
        .build
    )
