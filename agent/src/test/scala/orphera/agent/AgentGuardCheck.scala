// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.file.Files

import cats.effect.unsafe.implicits.global

import io.grpc.{Status, StatusRuntimeException}

import orphera.common.*

/** Checks that an agent on a host with the never-reboot marker refuses to reboot
  * or power it off. Run with
  * `sbt "agent/Test/runMain orphera.agent.AgentGuardCheck"`.
  *
  * Only the PROTECTED path is called. The unprotected path would really schedule
  * a reboot or shutdown of the machine running this check, so it is never
  * reached here; that the refusal comes before anything is started is what the
  * checks below rely on.
  */
object AgentGuardCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name${if detail.nonEmpty then s" — $detail" else ""}")

  private def failedPrecondition(e: Throwable): Boolean =
    e match
      case s: StatusRuntimeException =>
        s.getStatus.getCode == Status.Code.FAILED_PRECONDITION
      case _ => false

  def main(args: Array[String]): Unit =
    val dir = Files.createTempDirectory("agent-guard-check")
    val marker = dir.resolve("never-reboot")
    val env = Map(NeverReboot.EnvVar -> marker.toString)

    check(
      "the marker defaults to /etc/orphera-agent/never-reboot",
      NeverReboot.markerPath(Map.empty).toString == "/etc/orphera-agent/never-reboot"
    )
    check(
      "ORPHERA_NEVER_REBOOT_FILE overrides the marker path",
      NeverReboot.markerPath(env) == marker
    )
    check("no marker file: the host is not protected", !NeverReboot.isProtected(marker))

    Files.createFile(marker)
    check("a marker file protects the host", NeverReboot.isProtected(marker))

    val reboot = Rebooter.trigger(RebootRequest(0), env).attempt.unsafeRunSync()
    check(
      "the agent refuses a reboot on a protected host (FAILED_PRECONDITION)",
      reboot.left.exists(failedPrecondition),
      reboot.toString
    )
    check(
      "the refusal names the marker and says how to lift it",
      reboot.left.exists(e => e.getMessage.contains(marker.toString) && e.getMessage.contains("never-reboot")),
      reboot.toString
    )

    val shutdown = Shutdowner.trigger(ShutdownRequest(0), env).attempt.unsafeRunSync()
    check(
      "the agent refuses a power off on a protected host (FAILED_PRECONDITION)",
      shutdown.left.exists(failedPrecondition),
      shutdown.toString
    )
    check(
      "the power-off refusal says so",
      shutdown.left.exists(_.getMessage.contains("power off")),
      shutdown.toString
    )

    Files.delete(marker)
    check("removing the marker lifts the protection", !NeverReboot.isProtected(marker))
    Files.delete(dir)

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"AgentGuardCheck: all $passes checks passed")
