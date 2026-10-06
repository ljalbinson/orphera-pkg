// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import orphera.common.*

/** Powers the host off (not halt, not reboot) after a short delay. Detached via
  * systemd-run for the same reason as Rebooter: the agent's own unit is
  * stopped by the shutdown, so the command has to outlive it and the ack has to
  * reach the orchestrator first. There is no remote way to power the host back
  * on from here.
  */
object Shutdowner:

  def trigger(request: ShutdownRequest): IO[ShutdownAck] =
    val delay = if request.delaySeconds > 0 then request.delaySeconds else 5

    for _ <- IO.blocking {
        new ProcessBuilder(
          "systemd-run",
          "--no-block",
          "--collect",
          "--unit",
          s"orphera-shutdown-${System.currentTimeMillis()}",
          "sh",
          "-c",
          s"sleep $delay && shutdown -P now"
        ).inheritIO().start()
      }
    yield ShutdownAck(s"Shutdown (power off) scheduled in ${delay}s")
