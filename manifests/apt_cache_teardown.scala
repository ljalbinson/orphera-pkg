// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Undoes apt_cache.scala and apt_cache_clients.scala: first stops the clients
// using the cache (removes the apt config and the auto-detect script), then
// purges apt-cacher-ng on tst11 and deletes the cached files
// (/var/cache/apt-cacher-ng). Keep `clients` in step with
// apt_cache_clients.scala.
object apt_cache_teardown extends OrpheraClusterPlaybook:

  private val node = "tst11"

  private val clients =
    List(
      "tst0",
      "tst1",
      "tst2",
      "tst3",
      "tst4",
      "tst5",
      "tst6",
      "tst7",
      "tst9",
      "tst10"
    )

  // The two files apt_cache_clients.scala puts on a client; a client that
  // was already unpointed reports both as unchanged (before: a script that
  // removed them and printed a line every run).
  private val clientPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "apt-cache-unpoint",
      List(
        absentFile("/etc/apt/apt.conf.d/02orphera-apt-proxy"),
        absentFile("/usr/local/sbin/orphera-apt-proxy")
      )
    )

  private val cacheScript =
    """systemctl disable --now apt-cacher-ng 2>/dev/null || true
      |DEBIAN_FRONTEND=noninteractive apt-get purge -y apt-cacher-ng 2>/dev/null || true
      |rm -rf /var/cache/apt-cacher-ng /var/log/apt-cacher-ng /etc/apt-cacher-ng/zz_orphera.conf
      |echo "apt-cacher-ng and its cache removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("apt-cache-teardown")(
      stage("unpoint-apt", clients*)
        .task("remove the apt cache settings")(
          Task.Apply(clientPlan)
        )
        .build,

      stage("remove-apt-cache", node)
        .task("remove apt-cacher-ng and the cache")(
          Task.RunCommand(List("sh", "-c", cacheScript), timeoutSeconds = 180)
        )
        .build
    )
