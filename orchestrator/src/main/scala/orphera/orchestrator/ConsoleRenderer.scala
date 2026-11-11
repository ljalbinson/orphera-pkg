// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.*

import orphera.common.*

object ConsoleRenderer:

  // Number of unsuccessful RESULT events rendered since the last
  // resetFailures(). The commands that return IO[Unit] (Main.withTargets)
  // read this to report a node-level failure instead of always exiting 0.
  // The commands that change a node (install, run, copy, ...) run through
  // TaskExecutor and take their exit code from its result instead; they use
  // render/renderQuiet only for the output.
  private val failures = new AtomicInteger(0)

  def resetFailures(): Unit = failures.set(0)

  def failureCount: Int = failures.get()

  /** Counts a node that failed without an unsuccessful RESULT (it could not be
    * reached, or `fetch` found no file), so the command still exits non-zero.
    */
  def nodeFailed(node: Node, line: String): IO[Unit] =
    IO.delay(failures.incrementAndGet()) >> IO.println(s"[${node.name}] $line")

  /** Terse rendering for `orphera run --quiet`: only the command's own output
    * (still prefixed with the node name, so several nodes stay readable). The
    * "Running: ..." progress line and the "exit=0 success=true" line are
    * dropped. A failure is NOT silent: it is counted (so the CLI exit code is
    * non-zero) and printed with its exit code and any "FAILED: ..." reason.
    */
  def renderQuiet(node: Node, event: Event): IO[Unit] =
    event.kind match
      case Event.Kind.OUTPUT =>
        IO.println(s"[${node.name}] ${event.message}")
      case Event.Kind.RESULT if !event.success =>
        val reason =
          if event.message.startsWith("FAILED: ") then event.message + " "
          else ""
        IO.delay(failures.incrementAndGet()).void >>
          IO.println(
            s"[${node.name}] ${reason}exit=${event.exitCode} success=false"
          )
      case _ =>
        IO.unit

  def render(node: Node, event: Event): IO[Unit] =
    event.kind match
      case Event.Kind.PROGRESS =>
        IO.println(s"[${node.name}] ${event.message}")
      case Event.Kind.OUTPUT =>
        IO.println(s"[${node.name}] ${event.message}")
      case Event.Kind.RESULT =>
        IO.delay {
          if !event.success then
            failures.incrementAndGet()
            ()
        } >>
          // A failed RESULT carries the reason (e.g. "FAILED: Cannot run
          // program ..."); show it, otherwise a spawn failure is
          // indistinguishable from a command that returned non-zero.
          (if !event.success && event.message.startsWith("FAILED: ") then
             IO.println(s"[${node.name}] ${event.message}")
           else IO.unit) >>
          IO.println(
            s"[${node.name}] exit=${event.exitCode} success=${event.success}"
          )
      case _ =>
        IO.unit
