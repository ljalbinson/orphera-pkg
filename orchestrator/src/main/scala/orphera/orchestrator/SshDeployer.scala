// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import java.io.*

object SshDeployer:

  /** apt-get invocation shared by bootstrap's two prerequisite steps. Same
    * options the agent's own AptInstaller uses (lock timeout, no pty, no
    * colour, assume-yes) plus DEBIAN_FRONTEND=noninteractive set explicitly,
    * since an ssh command has no tty to answer a prompt.
    */
  private def aptGet(args: String): String =
    "sudo env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 " +
      s"-o Dpkg::Use-Pty=0 -o APT::Color=0 -o APT::Get::Assume-Yes=true $args"

  /** First-time agent install. The .deb Depends on a Java runtime
    * (default-jre-headless >= 17), but `dpkg -i` never resolves dependencies —
    * on a fresh node with no JRE it leaves the package unconfigured and the
    * agent unable to start. So the node's package index is refreshed and
    * `default-jre` installed BEFORE the .deb is copied over and installed; a
    * failure at either step aborts the bootstrap there rather than pushing a
    * package that can't work.
    */
  def bootstrap(
      node: Node,
      packages: AgentPackages,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit],
      forgetHostKey: Boolean = false
  ): IO[Unit] =
    def fail(reason: String): IO[Unit] =
      IO.raiseError(new RuntimeException(s"${node.name}: $reason"))

    for
      _ <- if forgetHostKey then forgetKnownHost(node, onLine) else IO.unit
      pm <- remotePackageManager(node, sshUser, sshKeyPath)
      _ <- pm match
        case RemotePm.Apt =>
          packages.pick(rpmHost = false) match
            case Left(reason) => fail(reason)
            case Right(deb)   =>
              bootstrapApt(
                node,
                deb,
                sshUser,
                sshKeyPath,
                AgentPackages.remotePathFor(false, remotePath),
                onLine
              )
        case RemotePm.Dnf =>
          packages.pick(rpmHost = true) match
            case Left(reason) => fail(reason)
            case Right(rpm)   =>
              bootstrapDnf(
                node,
                rpm,
                sshUser,
                sshKeyPath,
                AgentPackages.remotePathFor(true, remotePath),
                onLine
              )
    yield ()

  private enum RemotePm:
    case Apt, Dnf

  /** Asks the host which package manager it has, over SSH (there is no agent to
    * ask yet during bootstrap).
    */
  private def remotePackageManager(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String]
  ): IO[RemotePm] =
    for
      seen <- Ref.of[IO, List[String]](Nil)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        "if command -v apt-get >/dev/null 2>&1; then echo ORPHERA_PM=apt; " +
          "elif command -v dnf >/dev/null 2>&1; then echo ORPHERA_PM=dnf; " +
          "else echo ORPHERA_PM=none; fi",
        line => seen.update(line :: _)
      ).handleErrorWith { err =>
        // The ssh output (e.g. "Permission denied") was collected, not
        // printed, so put the last lines into the error or it is invisible.
        seen.get.flatMap { lines =>
          val tail = lines.reverse.takeRight(5).mkString("; ")
          IO.raiseError(
            new RuntimeException(
              s"${err.getMessage}" +
                (if tail.isEmpty then "" else s" — ssh said: $tail") +
                s" (ssh user: $sshUser; pass --ssh-user if it is not $sshUser)"
            )
          )
        }
      }
      lines <- seen.get
      pm <-
        if lines.exists(_.trim == "ORPHERA_PM=apt") then IO.pure(RemotePm.Apt)
        else if lines.exists(_.trim == "ORPHERA_PM=dnf") then
          IO.pure(RemotePm.Dnf)
        else
          IO.raiseError(
            new RuntimeException(
              s"${node.name}: found neither apt-get nor dnf on the host"
            )
          )
    yield pm

  private def bootstrapApt(
      node: Node,
      localDebPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      _ <- sshRun(node, sshUser, sshKeyPath, aptGet("update"), onLine)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        aptGet("install -y default-jre"),
        onLine
      )
      _ <- scp(node, localDebPath, sshUser, sshKeyPath, remotePath, onLine)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        s"sudo dpkg -i $remotePath",
        onLine
      )
    yield ()

  /** The dnf path. `dnf install <file>` resolves the package's Java dependency
    * (`java-headless`) from the repositories itself, so unlike `dpkg -i` it
    * needs no prerequisite step. The package name is checked on the host from
    * the rpm's own metadata before installing — as for the .deb, this
    * client-side check is the only enforcement during bootstrap.
    */
  private def bootstrapDnf(
      node: Node,
      localRpmPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      _ <- scp(node, localRpmPath, sshUser, sshKeyPath, remotePath, onLine)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        s"""test "$$(rpm -qp --queryformat '%{NAME}' $remotePath)" = orphera-agent """ +
          """|| { echo "Refusing to install: not the orphera-agent package"; exit 1; }""",
        onLine
      )
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        s"sudo dnf -y install $remotePath",
        onLine
      )
    yield ()

  /** `ssh-keygen -R <host>` against the operator's known_hosts, so a rebuilt VM
    * (cloud-init regenerates host keys every boot) doesn't trip "REMOTE HOST
    * IDENTIFICATION HAS CHANGED". Opt-in only (`bootstrap --forget-host-key`):
    * the normal path still refuses a changed key. Failure (no known_hosts, no
    * entry, ssh-keygen missing) is ignored — nothing to forget is fine.
    */
  private def forgetKnownHost(
      node: Node,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    IO.blocking {
      try
        val p = new ProcessBuilder("ssh-keygen", "-R", node.host)
          .redirectErrorStream(true)
          .start()
        val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
        p.waitFor()
        out.linesIterator.map(_.trim).filter(_.nonEmpty).toList
      catch case _: java.io.IOException => List("ssh-keygen not available")
    }.flatMap(lines =>
      onLine(s"forgetting any known_hosts entry for ${node.host}") >>
        lines.foldLeft(IO.unit)((acc, l) => acc >> onLine(l))
    )

  /** Runs `remoteCommand` over plain SSH and reports true/false on
    * exit-0/non-zero, swallowing the failure rather than raising it — unlike
    * bootstrap/teardown above, which legitimately want to blow up loudly on
    * failure, this is a liveness POLL: connection refused (sshd not up yet),
    * auth failure, and a non-zero remote command are all just "not ready yet,
    * try again on the next interval" to a caller like
    * ClusterPlaybookRunner.checkOnce. Built for HealthCheck.Ssh — confirming a
    * brand-new VM is reachable before any Orphera agent could possibly be
    * running on it yet, see that case's doc comment.
    *
    * Real run finding: this is the ONE ssh path that deliberately does NOT
    * pin/persist the remote host key (see sshBaseArgs' `persistHostKey` flag
    * below). HealthCheck.Ssh exists specifically for a VM that gets destroyed
    * and recreated at the same IP across runs (the whole point — a test VM
    * under active development), and cloud-init regenerates a fresh host key on
    * every boot. Recording that key in the operator's real known_hosts the
    * first time just means every SUBSEQUENT recreation at that IP hits
    * "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED" / "Host key verification
    * failed" — not a timeout, but checkAlive's .attempt swallows the real
    * reason, so it LOOKS like a timeout from the runner's "Health check timed
    * out after Ns" message. Confirmed by hand: `ssh -o
    * StrictHostKeyChecking=accept-new -o BatchMode=yes localadmin@<ip> true`
    * failed with exactly that until `ssh-keygen -R <ip>` purged the stale
    * entry. Using UserKnownHostsFile=/dev/null here means every poll (and every
    * run) starts from a clean slate — appropriate for THIS check only; real
    * trust-on-first-use pinning still applies to bootstrap/teardown/scp above,
    * which target already-provisioned, persistent nodes.
    */
  def checkAlive(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      remoteCommand: String = "true"
  ): IO[Boolean] =
    for
      // Real run finding: this used to be a one-liner
      // (sshRun(...).attempt.map(_.isRight)) that discarded every poll's
      // actual output. That's exactly why the stale-host-key failure
      // above looked identical to a plain timeout — ssh's own
      // "Host key verification failed" text was being read line-by-line
      // and then thrown away. Collecting it here and printing it on
      // failure means the NEXT time something like this happens, it's
      // visible on the first failed poll instead of requiring a manual
      // `ssh ... true` reproduction to find out why.
      lines <- Ref.of[IO, List[String]](Nil)
      result <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        remoteCommand,
        line => lines.update(line :: _),
        persistHostKey = false
      ).attempt
      ok <- result match
        case Right(_)  => IO.pure(true)
        case Left(err) =>
          lines.get.flatMap { collected =>
            val detail = collected.reverse.mkString(" | ")
            val reason = if detail.nonEmpty then detail else err.getMessage
            IO.println(
              s"[ssh-check] $sshUser@${node.host} not ready yet: $reason"
            ) >> IO.pure(false)
          }
    yield ok

  def teardown(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      purgeConfig: Boolean,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    remotePackageManager(node, sshUser, sshKeyPath).flatMap {
      case RemotePm.Apt =>
        val purgeOrRemove = if purgeConfig then "purge" else "remove"
        sshRun(
          node,
          sshUser,
          sshKeyPath,
          s"sudo dpkg --$purgeOrRemove orphera-agent",
          onLine
        )
      case RemotePm.Dnf =>
        // rpm has no purge: after removal, --purge also deletes the config
        // directory, which is what dpkg --purge does for conffiles.
        val purge = if purgeConfig then " && sudo rm -rf /etc/orphera-agent" else ""
        sshRun(
          node,
          sshUser,
          sshKeyPath,
          s"sudo dnf -y remove orphera-agent$purge",
          onLine
        )
    }

  private def sshBaseArgs(
      sshKeyPath: Option[String],
      persistHostKey: Boolean = true
  ): List[String] =
    val keyArgs = sshKeyPath.toList.flatMap(k => List("-i", k))
    val hostKeyArgs =
      if persistHostKey then List("-o", "StrictHostKeyChecking=accept-new")
      else
        // See checkAlive's doc comment: this target's host key is
        // expected to change across runs (VM destroyed/recreated at the
        // same IP), so don't compare against or write to the real
        // known_hosts at all — every connection starts trusting whatever
        // key is presented right now.
        List(
          "-o",
          "StrictHostKeyChecking=accept-new",
          "-o",
          "UserKnownHostsFile=/dev/null"
        )
    List("-o", "BatchMode=yes") ++ hostKeyArgs ++ keyArgs

  private def scp(
      node: Node,
      localPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    runProcess(
      List("scp") ++ sshBaseArgs(sshKeyPath) ++ List(
        localPath,
        s"$sshUser@${node.host}:$remotePath"
      ),
      onLine
    )

  private def sshRun(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      remoteCommand: String,
      onLine: String => IO[Unit],
      persistHostKey: Boolean = true
  ): IO[Unit] =
    runProcess(
      List("ssh") ++ sshBaseArgs(sshKeyPath, persistHostKey) ++ List(
        s"$sshUser@${node.host}",
        remoteCommand
      ),
      onLine
    )

  private def runProcess(
      command: List[String],
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      process <- IO.blocking {
        new ProcessBuilder(command*).redirectErrorStream(true).start()
      }
      reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      _ <- read(reader, onLine)
      exit <- IO.interruptible(process.waitFor())
      _ <-
        if exit != 0 then
          IO.raiseError(
            new RuntimeException(
              s"Command failed (exit $exit): ${command.mkString(" ")}"
            )
          )
        else IO.unit
    yield ()

  private def read(
      reader: BufferedReader,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    IO.interruptible(reader.readLine()).flatMap {
      case null => IO.unit
      case line => onLine(line) >> read(reader, onLine)
    }
