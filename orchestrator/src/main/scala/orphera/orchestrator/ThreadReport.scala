// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.ThreadInfo

/** How `orphera thread-info` prints one node's answer. */
object ThreadReport:

  private val Mb = 1024L * 1024L

  /** One summary line, and with `detail` the Java thread states and the
    * process's threads by name.
    */
  def lines(
      info: ThreadInfo,
      detail: Boolean,
      resetPeak: Boolean
  ): List[String] =
    val heap =
      if info.heapMaxBytes > 0 then
        s"${info.heapUsedBytes / Mb} of ${info.heapMaxBytes / Mb} MB"
      else s"${info.heapUsedBytes / Mb} MB"
    val summary =
      s"${info.osThreads} threads (java ${info.jvmThreads}, peak ${info.jvmPeak}), " +
        s"${info.cpus} cpus, rss ${info.rssBytes / Mb} MB, heap $heap" +
        (if resetPeak then ", peak reset" else "")
    if !detail then List(summary)
    else
      summary ::
        List(
          s"  java threads: ${info.jvmDaemon} daemon, ${info.jvmStarted} started since the JVM began",
          s"  java thread states: ${counts(info.states.toList.map(c => c.name -> c.count))}",
          s"  threads by name: ${counts(info.groups.toList.map(c => c.name -> c.count))}"
        )

  private def counts(entries: List[(String, Int)]): String =
    if entries.isEmpty then "none"
    else entries.map { case (name, count) => s"$name $count" }.mkString(", ")
