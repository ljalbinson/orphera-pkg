// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.effect.std.Semaphore
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*

/** Structured, machine-parseable event log for both PlaybookRunner and
  * ClusterPlaybookRunner — the gap flagged alongside restartability: output has
  * only ever been human-readable console lines (`[tst2] install cephadm
  * prerequisites: ...`), with nothing a script or dashboard could parse without
  * screen-scraping.
  *
  * One JSON Lines file per run
  * (`.orphera-logs/<kind>-<playbook-name>-<yyyyMMdd-HHmmss>.jsonl`), written
  * alongside — not instead of — the existing console output, so nothing about
  * current behavior (including the grep-based test scripts) changes.
  * Deliberately task/stage *lifecycle* only (start, result, duration) rather
  * than also capturing every streamed PROGRESS/OUTPUT line — this answers "how
  * long did apply-osd-spec take" / "which task failed and when", not "replay
  * everything a command printed", which the existing console output (and, in
  * the cephadm case, `ceph -s` itself) already covers.
  *
  * No JSON library dependency — same reasoning as Checkpoint.scala: this
  * project's `orchestrator` module doesn't actually have `circe-parser`
  * available (hit and fixed once already), and a dozen-or-so-key flat event
  * object doesn't need one. `toJson` below is a minimal, deliberately
  * non-general encoder: flat objects only,
  * String/Boolean/Int/Long/Double/Option values only — sufficient for every
  * event this file emits, not a general-purpose JSON writer.
  */
object RunLog:

  final class Handle private[RunLog] (file: Path, lock: Semaphore[IO]):
    /** Appends one JSON object as a single line, under a lock so concurrent
      * writes from parallel node fibers can't interleave and corrupt the file —
      * same concern, same fix, as Checkpoint.Handle.markDone.
      */
    def event(fields: (String, Any)*): IO[Unit] =
      lock.permit.use { _ =>
        IO.blocking {
          val line = toJson(fields :+ ("ts" -> Instant.now().toString))
          Files.write(
            file,
            List(line).asJava,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }
      }

  /** Starts a new run's log file. Always writes — there's no flag to turn this
    * off, since it only ever appends a separate file and never touches
    * stdout/console behavior.
    */
  def start(kind: String, playbookName: String): IO[Handle] =
    for
      dir <- IO.blocking(Files.createDirectories(Paths.get(".orphera-logs")))
      timestamp = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .format(java.time.LocalDateTime.now())
      file = dir.resolve(s"$kind-${sanitize(playbookName)}-$timestamp.jsonl")
      lock <- Semaphore[IO](1)
    yield new Handle(file, lock)

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9._-]", "-")

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
