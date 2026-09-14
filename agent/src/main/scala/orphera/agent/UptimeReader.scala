// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import java.nio.file.{Files, Paths}
import orphera.common.*

object UptimeReader:

  def read(): IO[UptimeInfo] =
    IO.blocking {
      val uptimeSeconds = parseUptimeFile()
      val loadAvg = parseLoadAvgFile()
      UptimeInfo(uptimeSeconds, loadAvg)
    }.handleError(_ => UptimeInfo(0, 0.0))

  private def parseUptimeFile(): Long =
    val content = Files.readString(Paths.get("/proc/uptime"))
    content.trim
      .split("\\s+")
      .headOption
      .flatMap(_.toDoubleOption)
      .map(_.toLong)
      .getOrElse(0L)

  private def parseLoadAvgFile(): Double =
    val content = Files.readString(Paths.get("/proc/loadavg"))
    content.trim
      .split("\\s+")
      .headOption
      .flatMap(_.toDoubleOption)
      .getOrElse(0.0)
