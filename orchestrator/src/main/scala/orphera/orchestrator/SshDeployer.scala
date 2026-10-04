// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import java.io.*

object SshDeployer:

  def bootstrap(
      node: Node,
      localDebPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      _ <- scp(node, localDebPath, sshUser, sshKeyPath, remotePath, onLine)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        s"sudo dpkg -i $remotePath",
        onLine
      )
    yield ()

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
    val purgeOrRemove = if purgeConfig then "purge" else "remove"
    sshRun(
      node,
      sshUser,
      sshKeyPath,
      s"sudo dpkg --$purgeOrRemove orphera-agent",
      onLine
    )

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
