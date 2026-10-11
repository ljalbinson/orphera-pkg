// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes rpm_cache.scala and rpm_cache_clients.scala: first stops the clients
// using the cache (removes the cache repository file and renames the stock
// Rocky repository files back), then purges nginx on tst12 and deletes the
// cached files (/var/cache/nginx/rpm). Keep `clients` in step with
// rpm_cache_clients.scala.
object rpm_cache_teardown extends OrpheraClusterPlaybook:

  private val node = "tst12"

  private val clients = List("tst8")

  private val clientScript =
    """rm -f /etc/yum.repos.d/orphera-rpm-cache.repo
      |for f in /etc/yum.repos.d/*.repo.orphera-off; do
      |  [ -e "$f" ] || continue
      |  mv "$f" "${f%.orphera-off}"
      |done
      |dnf clean metadata -q || true
      |echo "dnf on this node no longer uses the cache"""".stripMargin

  private val cacheScript =
    """systemctl disable --now nginx 2>/dev/null || true
      |rm -f /etc/nginx/conf.d/orphera-rpm-cache.conf
      |DEBIAN_FRONTEND=noninteractive apt-get purge -y nginx nginx-common 2>/dev/null || true
      |rm -rf /var/cache/nginx/rpm /var/log/nginx/rpm-cache.log
      |echo "nginx and the rpm cache removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("rpm-cache-teardown")(
      stage("unpoint-dnf", clients*)
        .task("remove the rpm cache settings")(
          Task.RunCommand(List("sh", "-c", clientScript), timeoutSeconds = 120)
        )
        .build,

      stage("remove-rpm-cache", node)
        .task("remove nginx and the cache")(
          Task.RunCommand(List("sh", "-c", cacheScript), timeoutSeconds = 180)
        )
        .build
    )
