// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import java.util.concurrent.atomic.AtomicInteger
import orphera.common.*

object ConsoleRenderer:

  // Number of unsuccessful RESULT events rendered since the last
  // resetFailures(). The ad-hoc verbs (install, run, copy, ...) return
  // IO[Unit], so the CLI's exit code (Main.withTargets) reads this to
  // report a node-level failure instead of always exiting 0.
  private val failures = new AtomicInteger(0)

  def resetFailures(): Unit = failures.set(0)

  def failureCount: Int = failures.get()

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
