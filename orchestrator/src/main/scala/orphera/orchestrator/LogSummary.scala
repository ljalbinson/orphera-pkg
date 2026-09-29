// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.IO
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** `orphera log-summary <target>` — renders one run's
  * `.orphera-logs` directory's `.jsonl` run-log file as a human-readable table: one row per
  * task (node, stage if present, task name, status, duration), with
  * failures called out, plus a run-level header/footer.
  *
  * Also home to `orphera audit-log` (see `runAuditLog` below): a
  * sibling reporting command over a different file
  * (`.orphera-audit/audit.jsonl`, written by `AuditLog.scala`) with a
  * different shape — a flat, ever-growing trail of every mutating CLI
  * invocation rather than one run's task lifecycle — kept in this same
  * object so it can reuse the JSON parser below instead of a third
  * copy of it.
  *
  * `<target>` is either a direct path to a `.jsonl` file, or a playbook
  * name — resolved to that playbook's most-recently-modified log file
  * under `.orphera-logs/`, the same "latest by mtime" approach
  * `test_observability.sh` uses, for the same reason: `RunLog`
  * filenames only have one-second resolution, so this is more reliable
  * than assuming a fixed name.
  *
  * No JSON library dependency, same reasoning as `RunLog.toJson`
  * itself and `Checkpoint.scala`: this module doesn't have
  * `circe-parser` available, and `RunLog.toJson` only ever emits a
  * flat object of String/Boolean/Int/Long/Double/null values, never
  * nested objects or arrays — so a minimal parser tailored to exactly
  * that grammar (mirroring `RunLog.toJson`'s own escaping) is enough,
  * rather than a general-purpose JSON reader.
  */
object LogSummary:

  private enum JsonValue:
    case JStr(value: String)
    case JBool(value: Boolean)
    case JNum(value: String)
    case JNull

  import JsonValue.*

  private type Event = Map[String, JsonValue]

  extension (e: Event)
    private def str(key: String): Option[String] =
      e.get(key).collect { case JStr(s) => s }
    private def boolField(key: String): Option[Boolean] =
      e.get(key).collect { case JBool(b) => b }
    private def longField(key: String): Option[Long] =
      e.get(key).collect { case JNum(n) => n.toLongOption }.flatten
    private def intField(key: String): Option[Int] =
      e.get(key).collect { case JNum(n) => n.toIntOption }.flatten

  /** Returns whether the summary itself could be produced (file found,
    * parsed, and printed) — deliberately NOT whether the underlying
    * run succeeded, since this is a read-only reporting command and a
    * failed run is a completely valid, successfully-summarized thing
    * to look at.
    */
  def run(target: String): IO[Boolean] =
    resolveLogFile(target) match
      case Left(err) =>
        IO.println(s"Error: $err") >> IO.pure(false)
      case Right(file) =>
        IO.blocking(Files.readAllLines(file).asScala.toList).attempt.flatMap {
          case Left(err) =>
            IO.println(s"Error: could not read $file — ${err.getMessage}") >> IO.pure(false)
          case Right(rawLines) =>
            val allEvents = rawLines.map(_.trim).filter(_.nonEmpty).flatMap(parseLine)
            if allEvents.isEmpty then
              IO.println(s"No parseable events found in $file") >> IO.pure(false)
            else
              val (segment, priorRunCount) = lastRunSegment(allEvents)
              IO.println(render(file, segment, priorRunCount)) >> IO.pure(true)
        }

  private def resolveLogFile(target: String): Either[String, Path] =
    if target.endsWith(".jsonl") then
      val p = Paths.get(target)
      if Files.exists(p) then Right(p) else Left(s"No such file: $target")
    else
      val dir = Paths.get(".orphera-logs")
      if !Files.isDirectory(dir) then
        Left(
          "No .orphera-logs directory found here — run this from the same " +
            "working directory a playbook/cluster-playbook run was launched " +
            "from, or pass a .jsonl path directly"
        )
      else
        val sanitized = sanitize(target)
        val pattern =
          ("^(playbook|cluster)-" + java.util.regex.Pattern.quote(sanitized) + "-\\d{8}-\\d{6}\\.jsonl$").r
        val matches = Option(dir.toFile.listFiles()).toList.flatten
          .filter(f => f.isFile && pattern.matches(f.getName))
        if matches.isEmpty then
          Left(
            s"No log files found for '$target' under .orphera-logs/ " +
              s"(looked for playbook-$sanitized-*.jsonl / cluster-$sanitized-*.jsonl)"
          )
        else
          Right(matches.maxBy(_.lastModified()).toPath())

  // Mirrors RunLog.sanitize exactly, so a playbook name resolves to the
  // same filename RunLog itself would have written.
  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9._-]", "-")

  // A log file can hold more than one run's events end-to-end if two
  // runs landed in the same one-second-resolution filename — same
  // reasoning as test_observability.sh's latest_run_segment. Returns
  // the events from the LAST run_start onward, plus how many earlier
  // runs' events were dropped (for the "note:" line in the output).
  private def lastRunSegment(events: List[Event]): (List[Event], Int) =
    val startIndices = events.zipWithIndex.collect {
      case (e, i) if e.str("event").contains("run_start") => i
    }
    startIndices.lastOption match
      case None      => (events, 0)
      case Some(idx) => (events.drop(idx), startIndices.size - 1)

  private def render(file: Path, events: List[Event], priorRunCount: Int): String =
    val sb = new StringBuilder

    val runStart = events.find(_.str("event").contains("run_start"))
    val runEnd = events.find(_.str("event").contains("run_end"))
    val playbookName = runStart.flatMap(_.str("playbook")).orElse(runEnd.flatMap(_.str("playbook"))).getOrElse("(unknown)")
    val targetCount = runStart.flatMap(_.intField("target_count"))

    sb.append(s"Run: $playbookName")
    targetCount.foreach(n => sb.append(s"  ($n target${if n == 1 then "" else "s"})"))
    sb.append('\n')
    sb.append(s"Log file: $file\n")
    if priorRunCount > 0 then
      sb.append(
        s"Note: this file also contains $priorRunCount earlier run(s) that landed in the " +
          "same second — showing only the most recent.\n"
      )

    runEnd match
      case Some(e) =>
        val ok = e.boolField("success").getOrElse(false)
        val dur = e.longField("duration_ms").map(d => s"${d}ms").getOrElse("?")
        sb.append(s"Status: ${if ok then "SUCCESS" else "FAILED"}   Duration: $dur\n")
      case None =>
        sb.append("Status: (no run_end event found — run may still be in progress, or was interrupted)\n")

    sb.append('\n')

    // One row per task_start/task_end/task_skipped event, in log order.
    // task_start rows are only shown if no matching task_end/skipped
    // follows for that (node, task) — normally every task_start is
    // paired, so in practice this only surfaces a task that was still
    // running when the log ends (an interrupted run).
    case class Row(stage: String, node: String, task: String, status: String, duration: String, error: Option[String])

    val hasStages = events.exists(_.get("stage").isDefined)

    val taskEvents = events.filter(e => e.str("event").exists(ev => ev == "task_start" || ev == "task_end" || ev == "task_skipped"))

    val rows = scala.collection.mutable.ListBuffer.empty[Row]
    val started = scala.collection.mutable.Set.empty[(String, String)]

    for e <- taskEvents do
      val node = e.str("node").getOrElse("?")
      val task = e.str("task").getOrElse("?")
      val stage = e.str("stage").getOrElse("")
      e.str("event") match
        case Some("task_start") =>
          started += (node -> task)
        case Some("task_end") =>
          started -= (node -> task)
          val ok = e.boolField("success").getOrElse(false)
          val dur = e.longField("duration_ms").map(d => s"${d}ms").getOrElse("?")
          val status = if ok then "OK" else "FAILED"
          rows += Row(stage, node, task, status, dur, e.str("error"))
        case Some("task_skipped") =>
          started -= (node -> task)
          val reason = e.str("reason").getOrElse("?")
          rows += Row(stage, node, task, s"SKIPPED ($reason)", "-", None)
        case _ => ()

    // Anything left in `started` never got a matching end/skip — the
    // run was interrupted mid-task.
    for (node, task) <- started do
      rows += Row("", node, task, "IN PROGRESS / INTERRUPTED", "-", None)

    if rows.isEmpty then
      sb.append("(no task events in this run)\n")
    else
      val headers =
        if hasStages then List("Stage", "Node", "Task", "Status", "Duration")
        else List("Node", "Task", "Status", "Duration")

      val dataRows = rows.toList.map { r =>
        if hasStages then List(r.stage, r.node, r.task, r.status, r.duration)
        else List(r.node, r.task, r.status, r.duration)
      }

      val widths = headers.indices.map { i =>
        (headers(i).length :: dataRows.map(_(i).length)).max
      }

      def formatRow(cells: List[String]): String =
        cells.zip(widths).map { case (c, w) => c.padTo(w, ' ') }.mkString("  ")

      sb.append(formatRow(headers)).append('\n')
      sb.append(widths.map(w => "-" * w).mkString("  ")).append('\n')
      for (row, data) <- rows.zip(dataRows) do
        sb.append(formatRow(data)).append('\n')
        row.error.foreach(err => sb.append(s"    error: $err\n"))

      val failed = rows.count(_.status == "FAILED")
      val skipped = rows.count(_.status.startsWith("SKIPPED"))
      val succeeded = rows.count(_.status == "OK")
      sb.append('\n')
      sb.append(s"$succeeded succeeded, $failed failed, $skipped skipped\n")

    sb.toString

  // --- `audit-log` — lists recent mutating-command invocations from
  //     .orphera-audit/audit.jsonl (AuditLog.scala). Distinct from
  //     `run`/`render` above: that's one playbook run's task-by-task
  //     detail read from .orphera-logs/, this is a flat, ever-growing
  //     trail of every mutating CLI invocation, one command_start /
  //     command_end pair per invocation correlated by invocation_id.
  //     Reuses this object's JSON parser/Event type below rather than
  //     a third copy of it, since AuditLog.toJson emits the exact same
  //     flat-object grammar RunLog.toJson does. ---

  private val auditFile: Path = Paths.get(".orphera-audit", "audit.jsonl")

  // Fields every audit event already surfaces as their own named
  // columns below — excluded from `detail` so it isn't just the row
  // repeated back as key=value pairs.
  private val auditCoreFields =
    Set("event", "invocation_id", "command", "nodes", "ts", "exit_code", "success", "duration_ms")

  private case class Invocation(
      invocationId: String,
      command: String,
      nodes: String,
      startTs: String,
      detail: String,
      exitCode: Option[Int],
      success: Option[Boolean],
      durationMs: Option[Long],
      ended: Boolean
  )

  /** Same "own return value is about whether the report itself could
    * be produced, not about what it reports" contract as `run` above
    * — a report full of FAILED rows is still a successfully rendered
    * report.
    */
  def runAuditLog(limit: Int): IO[Boolean] =
    if !Files.exists(auditFile) then
      IO.println(
        s"No audit log found at $auditFile — no auditable commands have run here yet " +
          "(install, remove, copy, playbook, run, ... — see AuditLog.isAuditable)."
      ) >> IO.pure(true)
    else
      IO.blocking(Files.readAllLines(auditFile).asScala.toList).attempt.flatMap {
        case Left(err) =>
          IO.println(s"Error: could not read $auditFile — ${err.getMessage}") >> IO.pure(false)
        case Right(rawLines) =>
          val events = rawLines.map(_.trim).filter(_.nonEmpty).flatMap(parseLine)
          if events.isEmpty then
            IO.println(s"No parseable events found in $auditFile") >> IO.pure(false)
          else
            val invocations = correlateAudit(events).sortBy(_.startTs)
            IO.println(renderAudit(invocations, limit)) >> IO.pure(true)
      }

  // Pairs each command_start with the command_end sharing its
  // invocation_id, if any — unmatched starts (the log ends mid-command:
  // still running, or the process was killed before it could record
  // its end) are kept and shown as such rather than dropped.
  private def correlateAudit(events: List[Event]): List[Invocation] =
    val ends = events
      .filter(_.str("event").contains("command_end"))
      .flatMap(e => e.str("invocation_id").map(_ -> e))
      .toMap

    events
      .filter(_.str("event").contains("command_start"))
      .flatMap { s =>
        s.str("invocation_id").map { id =>
          val end = ends.get(id)
          Invocation(
            invocationId = id,
            command = s.str("command").getOrElse("?"),
            nodes = s.str("nodes").getOrElse("?"),
            startTs = s.str("ts").getOrElse("?"),
            detail = extraDetail(s),
            exitCode = end.flatMap(_.intField("exit_code")),
            success = end.flatMap(_.boolField("success")),
            durationMs = end.flatMap(_.longField("duration_ms")),
            ended = end.isDefined
          )
        }
      }

  private def extraDetail(e: Event): String =
    e.toList
      .filterNot { case (k, _) => auditCoreFields.contains(k) }
      .sortBy(_._1)
      .map { case (k, v) => s"$k=${jsonValueDisplay(v)}" }
      .mkString(" ")

  private def jsonValueDisplay(v: JsonValue): String = v match
    case JStr(s)  => s
    case JBool(b) => b.toString
    case JNum(n)  => n
    case JNull    => "null"

  // "2026-09-29T17:15:32.123456789Z" -> "2026-09-29 17:15:32" — audit
  // timestamps carry full Instant precision, which is more than a
  // table column needs.
  private def formatTs(ts: String): String =
    if ts.length >= 19 then ts.substring(0, 19).replace('T', ' ') else ts

  private def renderAudit(invocations: List[Invocation], limit: Int): String =
    val sb = new StringBuilder
    sb.append(s"Audit log: $auditFile\n")

    val shown = invocations.takeRight(limit)
    if shown.size < invocations.size then
      sb.append(s"Showing the last ${shown.size} of ${invocations.size} recorded invocation(s).\n")
    sb.append('\n')

    if shown.isEmpty then
      sb.append("(no invocations recorded)\n")
    else
      val headers = List("Time", "Command", "Nodes", "Status", "Duration", "Detail")
      val dataRows = shown.map { inv =>
        val status =
          if !inv.ended then "IN PROGRESS / NO END RECORD"
          else if inv.success.contains(true) then "OK"
          else s"FAILED${inv.exitCode.map(c => s" (exit $c)").getOrElse("")}"
        val duration = inv.durationMs.map(d => s"${d}ms").getOrElse("-")
        List(formatTs(inv.startTs), inv.command, inv.nodes, status, duration, inv.detail)
      }

      val widths = headers.indices.map { i =>
        (headers(i).length :: dataRows.map(_(i).length)).max
      }

      def formatRow(cells: List[String]): String =
        cells.zip(widths).map { case (c, w) => c.padTo(w, ' ') }.mkString("  ")

      sb.append(formatRow(headers)).append('\n')
      sb.append(widths.map(w => "-" * w).mkString("  ")).append('\n')
      for data <- dataRows do sb.append(formatRow(data)).append('\n')

      val ok = shown.count(inv => inv.success.contains(true))
      val failed = shown.count(inv => inv.ended && !inv.success.contains(true))
      val pending = shown.count(!_.ended)
      sb.append('\n')
      sb.append(s"$ok succeeded, $failed failed, $pending without a recorded end\n")

    sb.toString

  // --- Minimal single-line JSON object parser, tailored to exactly
  //     what RunLog.toJson emits: a flat {"key":value,...} object,
  //     values are a quoted string, true/false, null, or a bare
  //     number. No nested objects/arrays are ever produced by RunLog,
  //     so none are handled here. ---

  private def parseLine(line: String): Option[Event] =
    scala.util.Try(parseObject(line)).toOption

  private def parseObject(s: String): Event =
    val trimmed = s.trim
    require(trimmed.startsWith("{") && trimmed.endsWith("}"), s"not a JSON object: $s")
    val inner = trimmed.substring(1, trimmed.length - 1)
    if inner.isBlank then Map.empty[String, JsonValue]
    else splitTopLevel(inner).map(parsePair).toMap

  // Splits on top-level commas — i.e. not inside a quoted string.
  private def splitTopLevel(s: String): List[String] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    var inString = false
    var escaped = false
    for c <- s do
      if escaped then
        current.append(c); escaped = false
      else if inString && c == '\\' then
        current.append(c); escaped = true
      else if c == '"' then
        inString = !inString; current.append(c)
      else if c == ',' && !inString then
        parts += current.toString; current.clear()
      else
        current.append(c)
    if current.nonEmpty then parts += current.toString
    parts.toList

  private def parsePair(pair: String): (String, JsonValue) =
    val colonIdx = findTopLevelColon(pair)
    val rawKey = pair.substring(0, colonIdx).trim
    val rawValue = pair.substring(colonIdx + 1).trim
    (unquote(rawKey), parseValue(rawValue))

  private def findTopLevelColon(s: String): Int =
    var inString = false
    var escaped = false
    var i = 0
    var found = -1
    while i < s.length && found == -1 do
      val c = s(i)
      if escaped then escaped = false
      else if inString && c == '\\' then escaped = true
      else if c == '"' then inString = !inString
      else if c == ':' && !inString then found = i
      i += 1
    if found == -1 then throw new IllegalArgumentException(s"no top-level ':' found in: $s")
    found

  private def parseValue(raw: String): JsonValue =
    if raw.startsWith("\"") then JStr(unquote(raw))
    else if raw == "true" then JBool(true)
    else if raw == "false" then JBool(false)
    else if raw == "null" then JNull
    else JNum(raw)

  // Reverses RunLog.quote exactly: \" \\ \n \r \t \uXXXX.
  private def unquote(raw: String): String =
    val body = raw.stripPrefix("\"").stripSuffix("\"")
    val sb = new StringBuilder(body.length)
    var i = 0
    while i < body.length do
      val c = body(i)
      if c == '\\' && i + 1 < body.length then
        body(i + 1) match
          case '"'  => sb.append('"'); i += 2
          case '\\' => sb.append('\\'); i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case 'u' if i + 6 <= body.length =>
            val hex = body.substring(i + 2, i + 6)
            sb.append(Integer.parseInt(hex, 16).toChar)
            i += 6
          case other =>
            sb.append(other); i += 2
      else
        sb.append(c); i += 1
    sb.toString
