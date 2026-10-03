// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.IO
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** A single, continuously-appended audit trail of every mutating CLI invocation
  * — `.orphera-audit/audit.jsonl`, one command_start / command_end pair per
  * invocation, correlated by a random `invocation_id`. Answers "what ran,
  * against which nodes, when, and did it succeed" after the fact — there was no
  * record of this at all before now.
  *
  * Deliberately separate from RunLog.scala's per-playbook-run `.orphera-logs/`
  * files: those cover ordered task lifecycle inside one
  * playbook/cluster-playbook run. This covers every mutating CLI command,
  * including the ones that have nothing to do with playbooks at all (install,
  * copy, bootstrap, teardown, the raw `run` command, ...), as one flat,
  * always-growing trail rather than one file per run — so it needs its own
  * small JSON-Lines writer rather than reusing RunLog's (tuned for
  * one-file-per-run), and that writer is a deliberate duplicate of
  * RunLog.toJson's encoding rather than a shared extraction, to avoid touching
  * that already-tested file for this. Same "no circe-parser available"
  * constraint as RunLog/Checkpoint.
  *
  * Read-only/informational commands (facts, version, uptime, log-summary, help,
  * fetch) are NOT recorded — this is an audit trail of changes, not a general
  * access log.
  *
  * Real limitation, worth being upfront about: this project has no per-operator
  * identity, only the shared `ORPHERA_TOKEN`. This records WHAT ran, WHEN,
  * against WHICH nodes, and its OUTCOME — not reliably WHO ran it, beyond
  * "someone with the token from this control host."
  *
  * Rotated by size (see `maxSizeBytes`/`maxRotatedFiles` below) rather than
  * growing `audit.jsonl` forever — classic logrotate-style numbering:
  * `audit.jsonl` is always the current file being appended to; `audit.jsonl.1`
  * is the most recently rotated-out file, `.2` the one before that, up to
  * `maxRotatedFiles`, beyond which the oldest is deleted. Checked (and rotated,
  * if needed) once per `append` call, before writing that call's line — see
  * `rotateIfNeeded`. A `command_start`/`command_end` pair CAN legitimately end
  * up split across `audit.jsonl` and `audit.jsonl.1` if rotation happens to
  * fall between the two writes (the command's own duration, not something this
  * file controls) — `LogSummary.runAuditLog` reads every audit file,
  * oldest-to-newest, specifically so a split pair still correlates correctly;
  * rotation only bounds the size of any one file on disk, never the correlation
  * window.
  *
  * Best-effort: a failure to write here never fails the command it's auditing —
  * see `append`'s error handling. Rotation failures are the same: best-effort,
  * logged as a warning, never fail the command being audited.
  */
object AuditLog:

  private val auditDir: Path = Paths.get(".orphera-audit")
  private val auditFile: Path = auditDir.resolve("audit.jsonl")

  // Rotate once the current file would exceed ~5MB (audit lines are
  // small — a few hundred bytes each — so this is on the order of tens
  // of thousands of invocations per file, a reasonable size to page
  // through by hand if ever needed) and keep at most 5 rotated files
  // beyond the current one, deleting the oldest past that. Both are
  // deliberately simple constants rather than configurable — this is a
  // small ops tool's audit trail, not a production log-shipping
  // pipeline; revisit if real usage ever actually needs something more.
  private val maxSizeBytes: Long = 5L * 1024 * 1024
  private val maxRotatedFiles: Int = 5

  private def rotatedFile(n: Int): Path = auditDir.resolve(s"audit.jsonl.$n")

  def isAuditable(command: Command): Boolean =
    command match
      case Command.Install(_, _, _)            => true
      case Command.Remove(_, _, _)             => true
      case Command.AutoRemove(_, _)            => true
      case Command.Copy(_, _, _, _, _, _)      => true
      case Command.WriteFile(_, _, _, _, _, _) => true
      case Command.NetworkApply(_, _)          => true
      case Command.DeployAgent(_, _, _)        => true
      case Command.Bootstrap(_, _, _, _, _)    => true
      case Command.Teardown(_, _, _, _, _)     => true
      case Command.RunPlaybook(_, _)           => true
      case Command.Reboot(_, _, _, _)          => true
      case Command.RunClusterPlaybook(_, _)    => true
      case Command.RunCommand(_, _, _)         => true
      case Command.Fetch(_, _, _)              => false
      case Command.Facts(_)                    => false
      case Command.Version(_)                  => false
      case Command.Uptime(_)                   => false
      case Command.LogSummary(_)               => false
      case Command.ShowAuditLog(_)             => false
      case Command.Help                        => false

  def recordStart(command: Command, invocationId: String): IO[Unit] =
    append(
      ("event" -> "command_start") :: ("invocation_id" -> invocationId) :: describe(
        command
      )
    )

  def recordEnd(
      command: Command,
      invocationId: String,
      exitCode: Int,
      durationMs: Long
  ): IO[Unit] =
    append(
      ("event" -> "command_end") :: ("invocation_id" -> invocationId) ::
        ("exit_code" -> exitCode) :: ("success" -> (exitCode == 0)) ::
        ("duration_ms" -> durationMs) :: describe(command)
    )

  private def describe(command: Command): List[(String, Any)] =
    command match
      case Command.Install(packages, nodes, updateCache) =>
        List(
          "command" -> "install",
          "packages" -> packages.mkString(","),
          "nodes" -> nodesField(nodes),
          "update_cache" -> updateCache
        )
      case Command.Remove(packages, nodes, purge) =>
        List(
          "command" -> "remove",
          "packages" -> packages.mkString(","),
          "nodes" -> nodesField(nodes),
          "purge" -> purge
        )
      case Command.AutoRemove(nodes, purge) =>
        List(
          "command" -> "autoremove",
          "nodes" -> nodesField(nodes),
          "purge" -> purge
        )
      case Command.Copy(localPath, destPath, nodes, owner, group, mode) =>
        List(
          "command" -> "copy",
          "local_path" -> localPath,
          "dest_path" -> destPath,
          "nodes" -> nodesField(nodes)
        )
      case Command.WriteFile(
            destPath,
            contentSource,
            nodes,
            owner,
            group,
            mode
          ) =>
        // Deliberately never logs the literal --content string itself —
        // same reasoning Copy only logs local_path/dest_path and never
        // peeks at a file's contents. A written string could easily be
        // a credential (see every hardcoded test password elsewhere in
        // this project); the audit trail records WHAT ran and WHERE,
        // not a copy of whatever was written.
        List(
          "command" -> "write-file",
          "dest_path" -> destPath,
          "content_source" -> (contentSource match
            case Left(_)     => "inline"
            case Right(path) => s"file:$path"),
          "nodes" -> nodesField(nodes)
        )
      case Command.NetworkApply(nodes, timeoutSeconds) =>
        List(
          "command" -> "network-apply",
          "nodes" -> nodesField(nodes),
          "timeout_seconds" -> timeoutSeconds
        )
      case Command.DeployAgent(localPath, remotePath, nodes) =>
        List(
          "command" -> "deploy-agent",
          "local_path" -> localPath.getOrElse("(auto-discovered)"),
          "remote_path" -> remotePath,
          "nodes" -> nodesField(nodes)
        )
      case Command.Bootstrap(
            localPath,
            nodes,
            sshUser,
            sshKeyPath,
            remotePath
          ) =>
        List(
          "command" -> "bootstrap",
          "local_path" -> localPath.getOrElse("(auto-discovered)"),
          "ssh_user" -> sshUser,
          "nodes" -> nodesField(nodes)
        )
      case Command.Teardown(nodes, sshUser, sshKeyPath, purge, confirmed) =>
        List(
          "command" -> "teardown",
          "nodes" -> nodes.mkString(","),
          "ssh_user" -> sshUser,
          "purge" -> purge
        )
      case Command.RunPlaybook(path, resume) =>
        List("command" -> "playbook", "path" -> path, "resume" -> resume)
      case Command.Reboot(
            nodes,
            delaySeconds,
            waitForReturn,
            waitTimeoutSeconds
          ) =>
        List(
          "command" -> "reboot",
          "nodes" -> nodesField(nodes),
          "delay_seconds" -> delaySeconds
        )
      case Command.RunClusterPlaybook(paths, resume) =>
        List(
          "command" -> "cluster-playbook",
          "path" -> paths.mkString(","),
          "resume" -> resume
        )
      case Command.RunCommand(cmd, nodes, timeoutSeconds) =>
        List(
          "command" -> "run",
          "shell_command" -> cmd.mkString(" "),
          "nodes" -> nodesField(nodes),
          "timeout_seconds" -> timeoutSeconds
        )
      case _ =>
        // Unreachable in practice — isAuditable gates every call into
        // recordStart/recordEnd to the 12 cases above. Kept exhaustive
        // (rather than partial) so a newly added Command case that's
        // marked auditable but has no description here fails loudly
        // instead of silently logging "command: unknown".
        List("command" -> "unknown")

  private def nodesField(nodes: Option[List[String]]): String =
    nodes.map(_.mkString(",")).getOrElse("(all)")

  private def append(fields: List[(String, Any)]): IO[Unit] =
    IO.blocking {
      Files.createDirectories(auditDir)
      rotateIfNeeded()
      val line = toJson(fields :+ ("ts" -> Instant.now().toString))
      Files.write(
        auditFile,
        List(line).asJava,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      ()
    }.handleErrorWith { err =>
      IO.println(
        s"Warning: could not write to the audit log (${err.getMessage})"
      )
    }

  // Shifts audit.jsonl.<n> -> audit.jsonl.<n+1> for n = maxRotatedFiles-1
  // down to 1 (deleting whatever's currently at maxRotatedFiles, if
  // anything, since it's aging out), then moves the current audit.jsonl
  // to audit.jsonl.1 — classic logrotate order, done from the HIGHEST
  // number down so an in-progress shift never overwrites a file it
  // hasn't moved yet. No-ops (and returns immediately) if audit.jsonl
  // doesn't exist yet or hasn't reached maxSizeBytes — the common case
  // on every call, so this is deliberately a cheap `Files.size` check
  // first rather than doing any of the above unconditionally.
  private def rotateIfNeeded(): Unit =
    if Files.exists(auditFile) && Files.size(auditFile) >= maxSizeBytes then
      val oldest = rotatedFile(maxRotatedFiles)
      if Files.exists(oldest) then Files.delete(oldest)
      for n <- (maxRotatedFiles - 1) to 1 by -1 do
        val src = rotatedFile(n)
        if Files.exists(src) then
          Files.move(
            src,
            rotatedFile(n + 1),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
      Files.move(
        auditFile,
        rotatedFile(1),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )

  private def toJson(fields: Seq[(String, Any)]): String =
    fields
      .map { case (k, v) => s"${quote(k)}:${jsonValue(v)}" }
      .mkString("{", ",", "}")

  private def jsonValue(v: Any): String = v match
    case s: String   => quote(s)
    case b: Boolean  => b.toString
    case i: Int      => i.toString
    case l: Long     => l.toString
    case d: Double   => d.toString
    case None        => "null"
    case Some(inner) => jsonValue(inner)
    case other       => quote(other.toString)

  private def quote(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    s.foreach {
      case '"'              => sb.append("\\\"")
      case '\\'             => sb.append("\\\\")
      case '\n'             => sb.append("\\n")
      case '\r'             => sb.append("\\r")
      case '\t'             => sb.append("\\t")
      case c if c.isControl => sb.append(f"\\u${c.toInt}%04x")
      case c                => sb.append(c)
    }
    sb.append('"')
    sb.toString
