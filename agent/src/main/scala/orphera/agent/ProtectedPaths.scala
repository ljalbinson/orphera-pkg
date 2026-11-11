// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.file.{Files, Path, Paths}

import scala.util.Try

/** Paths that `copy` and `fetch` (and the content check before a copy) do not
  * touch: the agent's own directory, `/etc/orphera-agent`, which holds its
  * token (`agent.env`) and its TLS key and CA certificate (`tls/`). Those are
  * set over SSH (`orphera issue-certs`, `utils/set-agent-token.sh`), never
  * through the agent, so a caller cannot read the token or key back, or
  * replace them, by accident or otherwise.
  *
  * This is not a sandbox: `run` executes any command as root, so whoever holds
  * the token can still reach any file. The token and mutual TLS are what keep
  * others out.
  */
object ProtectedPaths:

  val Dir: Path = Paths.get("/etc/orphera-agent")

  /** Why `path` is refused, or None. The path is made absolute, `..` is
    * resolved, and symbolic links are followed (for a file that does not exist
    * yet, those in its directory), so another name for the same place is
    * refused too.
    */
  def refusal(path: String): Option[String] =
    Option.when(isProtected(path, Dir))(
      s"Refused: $path is in $Dir, which holds this agent's token and TLS key; " +
        "copy and fetch do not touch it (the token and certificates are set over SSH: " +
        "utils/set-agent-token.sh, orphera issue-certs)"
    )

  /** Whether `path` is `dir` or under it. Separate from `Dir` for testing. */
  def isProtected(path: String, dir: Path): Boolean =
    val protectedDir = real(dir)
    resolve(path).startsWith(protectedDir)

  private def resolve(path: String): Path =
    val abs = Paths.get(path).toAbsolutePath.normalize
    if Files.exists(abs) then real(abs)
    else
      Option(abs.getParent) match
        case Some(parent) => real(parent).resolve(abs.getFileName)
        case None         => abs

  // The path with symbolic links followed, as far as it exists.
  private def real(path: Path): Path =
    Try(path.toRealPath()).getOrElse(path.toAbsolutePath.normalize)
