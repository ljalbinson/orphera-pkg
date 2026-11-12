// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// rpm_cache_clients.scala as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): the stock Rocky repository files set aside, the cache
// repository file written, and dnf's metadata cleared only when that file
// changed. The file's content is the same as rpm_cache_clients.scala writes, so
// on a node it has set up, `--check` reports everything unchanged.
//
//   orphera apply manifests/rpm_cache_clients_apply.scala --check
//   orphera apply manifests/rpm_cache_clients_apply.scala [--nodes tst8]
//
// rpm_cache_clients.scala stays until this has been used on the lab.
object rpm_cache_clients_apply extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List("tst8")

  private val cacheHost = "tst12.ljalbinson.com"
  private val port = 8080

  private def repo(id: String, name: String, path: String, enabled: Int): String =
    s"""[orphera-$id]
       |name=Rocky Linux $$releasever - $name (via rpm cache)
       |baseurl=http://$cacheHost:$port/rocky/$$releasever/$path/$$basearch/os/
       |        https://dl.rockylinux.org/pub/rocky/$$releasever/$path/$$basearch/os/
       |gpgcheck=1
       |enabled=$enabled
       |countme=1
       |timeout=15
       |gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-$$releasever
       |""".stripMargin

  private val repoFile =
    s"""# Managed by Orphera (rpm_cache_clients.scala).
       |# Cache first ($cacheHost), the official Rocky host as the fallback.
       |""".stripMargin +
      List(
        repo("baseos", "BaseOS", "BaseOS", 1),
        repo("appstream", "AppStream", "AppStream", 1),
        repo("extras", "Extras", "extras", 1),
        repo("crb", "CRB", "CRB", 0)
      ).mkString("\n")

  def describe(node: Node): List[ApplyResource] =
    // dnf reads only *.repo: renamed, the stock files cannot bypass the cache through the mirrorlist.
    val setAside = command(
      "set aside the stock Rocky repository files",
      """for f in /etc/yum.repos.d/rocky*.repo; do [ -e "$f" ] && mv "$f" "$f.orphera-off"; done""",
      onlyIf = "ls /etc/yum.repos.d/rocky*.repo"
    )
    val clean = command("clear dnf's metadata", "dnf clean metadata -q", onChangeOnly = true)
    List(
      setAside,
      file("/etc/yum.repos.d/orphera-rpm-cache.repo", repoFile, mode = "0644").after(setAside).notifies(clean),
      clean
    )
