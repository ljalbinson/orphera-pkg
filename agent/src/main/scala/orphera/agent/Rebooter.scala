// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import orphera.common.*

object Rebooter:

  def trigger(request: RebootRequest): IO[RebootAck] =
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
