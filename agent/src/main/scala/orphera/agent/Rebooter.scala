// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*

import orphera.common.*

object Rebooter:

  /** Refuses, without starting anything, if this host carries the never-reboot
    * marker (see `NeverReboot`). `env` is a parameter so a check can point it at
    * a temporary marker; the agent itself uses the process environment.
    */
  def trigger(
      request: RebootRequest,
      env: Map[String, String] = sys.env
  ): IO[RebootAck] =
    val marker = NeverReboot.markerPath(env)
    IO.blocking(NeverReboot.isProtected(marker)).flatMap { guarded =>
      if guarded then IO.raiseError(NeverReboot.refusal("reboot", marker))
      else schedule(request)
    }

  private def schedule(request: RebootRequest): IO[RebootAck] =
    val delay = if request.delaySeconds > 0 then request.delaySeconds else 5

    for _ <- IO.blocking {
        new ProcessBuilder(
          "systemd-run",
          "--no-block",
          "--collect",
          "--unit",
          s"orphera-reboot-${System.currentTimeMillis()}",
          "sh",
          "-c",
          s"sleep $delay && shutdown -r now"
        ).inheritIO().start()
      }
    yield RebootAck(s"Reboot scheduled in ${delay}s")
