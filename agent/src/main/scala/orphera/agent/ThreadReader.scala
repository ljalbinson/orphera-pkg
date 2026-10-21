// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

import cats.effect.*

import orphera.common.*

/** What the agent process is doing with threads and memory, for
  * `orphera thread-info`. The Java figures come from the JVM, the process
  * figures from `/proc/self`; anything `/proc` cannot give is left at zero or
  * empty rather than failing the call.
  */
object ThreadReader:

  def read(resetPeak: Boolean): IO[ThreadInfo] =
    IO.blocking {
      val threads = ManagementFactory.getThreadMXBean
      val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
      // The peak is read before any reset, so the caller sees the value up to now.
      val peak = threads.getPeakThreadCount
      if resetPeak then threads.resetPeakThreadCount()
      val status = ThreadStats.parseStatus(
        Try(Files.readAllLines(Paths.get("/proc/self/status")).asScala.toList)
          .getOrElse(Nil)
      )
      ThreadInfo(
        osThreads = status.get("Threads").flatMap(_.toIntOption).getOrElse(0),
        jvmThreads = threads.getThreadCount,
        jvmPeak = peak,
        jvmDaemon = threads.getDaemonThreadCount,
        jvmStarted = threads.getTotalStartedThreadCount,
        cpus = Runtime.getRuntime.availableProcessors,
        rssBytes = ThreadStats.kilobytes(status.get("VmRSS")),
        heapUsedBytes = heap.getUsed,
        heapMaxBytes = heap.getMax,
        states = named(
          ThreadStats.counts(
            Thread.getAllStackTraces.keySet.asScala.toList
              .map(_.getState.toString)
          )
        ),
        groups =
          named(ThreadStats.counts(osThreadNames().map(ThreadStats.groupName)))
      )
    }

  private def named(counts: List[(String, Int)]): List[NameCount] =
    counts.map { case (name, count) => NameCount(name, count) }

  /** The name of every OS thread of this process
    * (`/proc/self/task/<tid>/comm`), which includes the JVM's own threads that
    * Java code cannot list.
    */
  private def osThreadNames(): List[String] =
    Using(Files.newDirectoryStream(Paths.get("/proc/self/task"))) { tasks =>
      tasks.asScala.toList.flatMap(task =>
        Try(Files.readString(task.resolve("comm"))).toOption
      )
    }.getOrElse(Nil)
