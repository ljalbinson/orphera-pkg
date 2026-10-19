// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.grpc.{Status, StatusRuntimeException}

import orphera.common.{NameCount, ThreadInfo, ThreadStats}

/** Checks for `orphera thread-info`: the naming and counting of threads, the
  * reading of `/proc/self/status`, the report, and the command line. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.ThreadInfoCheck"`.
  */
object ThreadInfoCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val Mb = 1024L * 1024L

  private val sample = ThreadInfo(
    osThreads = 41,
    jvmThreads = 29,
    jvmPeak = 36,
    jvmDaemon = 24,
    jvmStarted = 120L,
    cpus = 4,
    rssBytes = 212L * Mb,
    heapUsedBytes = 48L * Mb,
    heapMaxBytes = 981L * Mb,
    states = List(NameCount("WAITING", 15), NameCount("RUNNABLE", 5)),
    groups = List(NameCount("ioapp-compute", 4), NameCount("GC Thread", 3))
  )

  def main(args: Array[String]): Unit =
    // Naming and counting.
    check(
      "the numbers at the end of a thread name are dropped",
      ThreadStats.groupName("ioapp-compute-3") == "ioapp-compute" &&
        ThreadStats.groupName("GC Thread#2") == "GC Thread" &&
        ThreadStats.groupName("pool-1-thread-12") == "pool-1-thread"
    )
    check(
      "a name with no number at the end, or only digits, is kept",
      ThreadStats.groupName("VM Thread") == "VM Thread" &&
        ThreadStats.groupName("123") == "123" && ThreadStats.groupName("") == ""
    )
    check(
      "counts are largest first, alphabetical among equals",
      ThreadStats.counts(List("b", "a", "a", "c", "b", "a", "d")) ==
        List(("a", 3), ("b", 2), ("c", 1), ("d", 1))
    )
    check(
      "/proc/self/status is read into a map, lines without a colon ignored",
      ThreadStats.parseStatus(
        List("Name:\tjava", "Threads:\t34", "VmRSS:\t  212340 kB", "junk")
      ) == Map("Name" -> "java", "Threads" -> "34", "VmRSS" -> "212340 kB")
    )
    check(
      "a /proc size in kB becomes bytes; nothing usable becomes 0",
      ThreadStats.kilobytes(Some("212340 kB")) == 212340L * 1024L &&
        ThreadStats.kilobytes(None) == 0L &&
        ThreadStats.kilobytes(Some("many")) == 0L
    )

    // The report.
    val one = ThreadReport.lines(sample, detail = false, resetPeak = false)
    check(
      "the summary line says threads, peak, cpus, memory",
      one == List(
        "41 threads (java 29, peak 36), 4 cpus, rss 212 MB, heap 48 of 981 MB"
      ),
      one.toString
    )
    check(
      "without --detail there is one line",
      one.size == 1
    )
    check(
      "an undefined heap limit is left out",
      ThreadReport
        .lines(sample.copy(heapMaxBytes = -1L), detail = false, resetPeak = false)
        .headOption
        .exists(_.endsWith("heap 48 MB"))
    )
    check(
      "--reset-peak is noted on the summary line",
      ThreadReport
        .lines(sample, detail = false, resetPeak = true)
        .headOption
        .exists(_.endsWith(", peak reset"))
    )
    val full = ThreadReport.lines(sample, detail = true, resetPeak = false)
    check(
      "--detail adds the java thread facts, states and threads by name",
      full.size == 4 &&
        full.contains("  java threads: 24 daemon, 120 started since the JVM began") &&
        full.contains("  java thread states: WAITING 15, RUNNABLE 5") &&
        full.contains("  threads by name: ioapp-compute 4, GC Thread 3"),
      full.toString
    )
    check(
      "an empty list is reported as none",
      ThreadReport
        .lines(
          sample.copy(states = Nil, groups = Nil),
          detail = true,
          resetPeak = false
        )
        .exists(_ == "  threads by name: none")
    )

    // A node too old to know the call.
    check(
      "an agent that does not know the call is reported as unsupported",
      NodeError
        .line(new StatusRuntimeException(Status.UNIMPLEMENTED))
        .startsWith("unsupported")
    )

    // The command line.
    check(
      "thread-info with no arguments",
      Cli.parse(List("thread-info")) == Right(Command.Threads(None, false, false))
    )
    check(
      "thread-info with all its options",
      Cli.parse(
        List("thread-info", "--nodes", "tst0,tst1", "--detail", "--reset-peak")
      ) == Right(Command.Threads(Some(List("tst0", "tst1")), true, true))
    )
    check(
      "thread-info refuses an unknown option",
      Cli.parse(List("thread-info", "--verbose")).swap.exists(_.contains("thread-info"))
    )
    check(
      "thread-info can be a pipeline step",
      Cli.pipelineVerbs.contains("thread-info")
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"ThreadInfoCheck: all $passes checks passed")
