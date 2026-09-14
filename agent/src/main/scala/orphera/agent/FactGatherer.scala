// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import java.nio.file.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import orphera.common.*

object FactGatherer:

  def gather(): IO[Facts] =
    for
      hostname <- readCommand(List("hostname"))
      osRelease <- readOsRelease()
      kernel <- readCommand(List("uname", "-r"))
      arch <- readCommand(List("uname", "-m"))
      cpuCount <- IO(Runtime.getRuntime.availableProcessors())
      memTotal <- readMemTotalBytes()
      disks <- readDisks()
      interfaces <- readInterfaces()
      now <- IO(java.time.Instant.now.toEpochMilli)
    yield Facts(
      hostname = hostname.trim,
      osId = osRelease.getOrElse("ID", ""),
      osVersion = osRelease.getOrElse("VERSION_ID", ""),
      kernelVersion = kernel.trim,
      architecture = arch.trim,
      cpuCount = cpuCount,
      memoryTotalBytes = memTotal,
      disks = disks,
      interfaces = interfaces,
      collectedAtEpochMillis = now
    )

  private def readCommand(command: List[String]): IO[String] =
    IO.blocking {
      val process =
        new ProcessBuilder(command*).redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes())
      process.waitFor()
      output
    }.handleError(_ => "")

  private def readOsRelease(): IO[Map[String, String]] =
    IO.blocking {
      val path = Paths.get("/etc/os-release")
      if !Files.exists(path) then Map.empty
      else
        Files
          .readAllLines(path)
          .asScala
          .filter(_.contains("="))
          .map { line =>
            val Array(key, rawValue) = line.split("=", 2)
            key -> rawValue.stripPrefix("\"").stripSuffix("\"")
          }
          .toMap
    }.handleError(_ => Map.empty)

  private def readMemTotalBytes(): IO[Long] =
    IO.blocking {
      val path = Paths.get("/proc/meminfo")
      if !Files.exists(path) then 0L
      else
        Files
          .readAllLines(path)
          .asScala
          .find(_.startsWith("MemTotal:"))
          .flatMap(line => line.replaceAll("[^0-9]", "").toLongOption)
          .map(_ * 1024L) // /proc/meminfo reports kB
          .getOrElse(0L)
    }.handleError(_ => 0L)

  private def readDisks(): IO[Seq[DiskInfo]] =
    IO.blocking {
      FileSystems.getDefault.getFileStores.asScala.toSeq.flatMap { store =>
        try
          Some(
            DiskInfo(
              mountPoint = store.name(),
              filesystem = store.`type`(),
              totalBytes = store.getTotalSpace,
              availableBytes = store.getUsableSpace
            )
          )
        catch case _: Throwable => None
      }
    }.handleError(_ => Seq.empty)

  private def readInterfaces(): IO[Seq[NetworkInterface]] =
    IO.blocking {
      java.net.NetworkInterface.getNetworkInterfaces.asScala.toSeq
        .filter(iface => !iface.isLoopback && iface.isUp)
        .map { iface =>
          val addresses = iface.getInetAddresses.asScala
            .map(_.getHostAddress)
            .toSeq

          val mac = Option(iface.getHardwareAddress)
            .map(_.map(b => f"$b%02x").mkString(":"))
            .getOrElse("")

          NetworkInterface(iface.getName, addresses, mac)
        }
    }.handleError(_ => Seq.empty)
