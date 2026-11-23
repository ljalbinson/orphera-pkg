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

  // The client side as a description: the cache repository file removed (`absentFile`), the stock Rocky
  // repository files renamed back (a command, only when some are set aside), and dnf's metadata cleared
  // only when one of those changed something.
  private val clientPlan: ApplyPlan =
    import Resources.*
    val clean = command(
      "clear dnf's metadata",
      "dnf clean metadata -q || true",
      onChangeOnly = true
    )
    val restore = command(
      "put the stock Rocky repository files back",
      """for f in /etc/yum.repos.d/*.repo.orphera-off; do
        |  [ -e "$f" ] || continue
        |  mv "$f" "${f%.orphera-off}"
        |done""".stripMargin,
      onlyIf = "ls /etc/yum.repos.d/*.repo.orphera-off >/dev/null 2>&1"
    ).notifies(clean)
    val repoFile = absentFile("/etc/yum.repos.d/orphera-rpm-cache.repo")
      .notifies(clean)
    ApplyPlan.orFail(
      "rpm-cache-unpoint",
      List(repoFile, restore.after(repoFile), clean.after(repoFile, restore))
    )

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
          Task.Apply(clientPlan)
        )
        .build,

      stage("remove-rpm-cache", node)
        .task("remove nginx and the cache")(
          Task.RunCommand(List("sh", "-c", cacheScript), timeoutSeconds = 180)
        )
        .build
    )
