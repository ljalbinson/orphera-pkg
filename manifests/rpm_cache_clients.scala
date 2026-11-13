// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Points dnf on the listed Rocky nodes at the rpm cache on tst12
// (rpm_cache.scala), as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): the stock Rocky repository files set aside (renamed
// *.orphera-off, so the mirrorlist cannot bypass the cache; the teardown
// renames them back), the cache repository file written, and dnf's metadata
// cleared only when that file changed. Each repository has two baseurls, the
// cache first and the official Rocky host second, so a node works without the
// cache; signatures are still checked (gpgcheck=1, Rocky's key). (It replaced a
// cluster playbook of the same name, which wrote the same file, on 2026-11-13.)
//
//   orphera apply rpm_cache_clients.scala --check
//   orphera apply rpm_cache_clients.scala [--nodes tst8]
//
// Which nodes: the Rocky nodes (tst8). Edit `nodes` to add more.
object rpm_cache_clients extends OrpheraApply:
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
