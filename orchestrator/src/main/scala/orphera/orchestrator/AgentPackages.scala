// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** The agent package files available to push: a `.deb` for apt hosts and/or an
  * `.rpm` for dnf hosts. Which one a node gets is decided per node (bootstrap
  * asks the host over SSH, deploy-agent uses the node's reported OS), so one
  * command can serve a mixed Ubuntu/Rocky fleet.
  */
final case class AgentPackages(deb: Option[String], rpm: Option[String]):

  def isEmpty: Boolean = deb.isEmpty && rpm.isEmpty

  def paths: List[String] = deb.toList ++ rpm.toList

  /** The file for an rpm host (`rpmHost`) or an apt host. */
  def pick(rpmHost: Boolean): Either[String, String] =
    if rpmHost then
      rpm.toRight(
        "this host uses dnf/rpm but no orphera-agent-*.rpm was given or found — run 'make rpm' or pass --file <path>.rpm"
      )
    else
      deb.toRight(
        "this host uses apt/dpkg but no orphera-agent_*.deb was given or found — run 'make deb' or pass --file <path>.deb"
      )

object AgentPackages:

  /** `os_id` values (from /etc/os-release, as reported by the agent) of
    * dnf/rpm-based hosts.
    */
  val rpmHostOsIds: Set[String] =
    Set("rocky", "rhel", "almalinux", "centos", "fedora", "ol", "amzn")

  def isRpm(path: String): Boolean = path.endsWith(".rpm")

  /** The remote file name to copy a package to: the requested path with its
    * extension matching the package (`dnf install <file>` needs `.rpm`).
    */
  def remotePathFor(rpm: Boolean, requested: String): String =
    if rpm && requested.endsWith(".deb") then
      requested.stripSuffix(".deb") + ".rpm"
    else if !rpm && requested.endsWith(".rpm") then
      requested.stripSuffix(".rpm") + ".deb"
    else requested

  /** An explicit `--file` selects one package (by extension); otherwise the
    * newest `orphera-agent_*_amd64.deb` and `orphera-agent-*.rpm` in `dir` are
    * both offered. Whether a file really is the agent package is checked later
    * (Orchestrator.requireOrpheraAgentPackage), not here.
    */
  def resolve(
      explicit: Option[String],
      dir: String = "."
  ): Either[String, AgentPackages] =
    explicit match
      case Some(path) if isRpm(path) => Right(AgentPackages(None, Some(path)))
      case Some(path)                => Right(AgentPackages(Some(path), None))
      case None                      =>
        val found = AgentPackages(
          latest(dir, "orphera-agent_", "_amd64.deb"),
          latest(dir, "orphera-agent-", ".rpm")
        )
        if found.isEmpty then
          Left(
            "No orphera-agent_*.deb or orphera-agent-*.rpm found in the current directory, and no --file given. " +
              "Run 'make deb' (or 'make release'; 'make rpm' for Rocky hosts) first, or pass --file <path>."
          )
        else Right(found)

  // By modification time, not name: "0.1.9" sorts after "0.1.10" as a string.
  private def latest(
      dir: String,
      prefix: String,
      suffix: String
  ): Option[String] =
    val base = new java.io.File(dir)
    if !base.isDirectory then None
    else
      Option(base.listFiles()).toList.flatten
        .filter(f =>
          f.isFile && f.getName.startsWith(prefix) && f.getName.endsWith(suffix)
        )
        .sortBy(_.lastModified())
        .lastOption
        .map(_.getAbsolutePath)
