// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Points dnf on the listed Rocky nodes at the rpm cache on tst12 (rpm_cache.scala).
//
// On each node it:
//   - writes /etc/yum.repos.d/orphera-rpm-cache.repo with the Rocky repositories
//     (BaseOS, AppStream, extras, CRB), each with two baseurls: the cache first,
//     the official Rocky download host second. dnf tries the baseurls in order,
//     so a node works without the cache when tst12 is down or rebuilt;
//   - sets aside the stock Rocky repository files by renaming them to
//     *.orphera-off (dnf reads only *.repo), so the cache is not bypassed
//     through the mirrorlist. The teardown renames them back.
//
// The package signatures are still checked (gpgcheck=1, Rocky's own key), so the
// cache cannot alter packages undetected.
//
// Which nodes: the Rocky nodes (currently tst8). Edit `clients` to add more;
// every listed node must be reachable and have a running agent.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object rpm_cache_clients extends OrpheraClusterPlaybook:

  private val clients = List("tst8")

  private val cacheHost = "tst12.ljalbinson.com"
  private val port = 8080

  // Plain string: $releasever and $basearch are for dnf, not for Scala or sh
  // (the heredoc is quoted). Placeholders are replaced below.
  private val setupScript =
    ("""set -e
      |for f in /etc/yum.repos.d/rocky*.repo; do
      |  [ -e "$f" ] || continue
      |  mv "$f" "$f.orphera-off"
      |done
      |cat > /etc/yum.repos.d/orphera-rpm-cache.repo <<'REPO'
      |# Managed by Orphera (rpm_cache_clients.scala).
      |# Cache first (@HOST@), the official Rocky host as the fallback.
      |[orphera-baseos]
      |name=Rocky Linux $releasever - BaseOS (via rpm cache)
      |baseurl=http://@HOST@:@PORT@/rocky/$releasever/BaseOS/$basearch/os/
      |        https://dl.rockylinux.org/pub/rocky/$releasever/BaseOS/$basearch/os/
      |gpgcheck=1
      |enabled=1
      |countme=1
      |timeout=15
      |gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$releasever
      |
      |[orphera-appstream]
      |name=Rocky Linux $releasever - AppStream (via rpm cache)
      |baseurl=http://@HOST@:@PORT@/rocky/$releasever/AppStream/$basearch/os/
      |        https://dl.rockylinux.org/pub/rocky/$releasever/AppStream/$basearch/os/
      |gpgcheck=1
      |enabled=1
      |countme=1
      |timeout=15
      |gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$releasever
      |
      |[orphera-extras]
      |name=Rocky Linux $releasever - Extras (via rpm cache)
      |baseurl=http://@HOST@:@PORT@/rocky/$releasever/extras/$basearch/os/
      |        https://dl.rockylinux.org/pub/rocky/$releasever/extras/$basearch/os/
      |gpgcheck=1
      |enabled=1
      |countme=1
      |timeout=15
      |gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$releasever
      |
      |[orphera-crb]
      |name=Rocky Linux $releasever - CRB (via rpm cache)
      |baseurl=http://@HOST@:@PORT@/rocky/$releasever/CRB/$basearch/os/
      |        https://dl.rockylinux.org/pub/rocky/$releasever/CRB/$basearch/os/
      |gpgcheck=1
      |enabled=0
      |countme=1
      |timeout=15
      |gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$releasever
      |REPO
      |dnf clean metadata -q
      |echo "dnf on $(hostname) uses the rpm cache at @HOST@:@PORT@ (official Rocky host as fallback)"
      |dnf repolist -q""".stripMargin)
      .replace("@HOST@", cacheHost)
      .replace("@PORT@", port.toString)

  val playbook: ClusterPlaybook =
    clusterPlaybook("rpm-cache-clients")(
      stage("point-dnf-at-the-cache", clients*)
        .task("write the cache repository file and set the stock repos aside")(
          Task.RunCommand(List("sh", "-c", setupScript), timeoutSeconds = 180)
        )
        .build
    )
