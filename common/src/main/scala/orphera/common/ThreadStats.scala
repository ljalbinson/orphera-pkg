// SPDX-License-Identifier: Apache-2.0

package orphera.common

/** The pure parts of the agent's thread report, kept here so the check programs
  * can run them: naming and counting threads, and reading `/proc/self/status`.
  */
object ThreadStats:

  /** A thread's name without its number, so the threads of one pool count
    * together: `ioapp-compute-3` and `ioapp-compute-12` both become
    * `ioapp-compute`, `GC Thread#2` becomes `GC Thread`. A name that is only
    * digits, or empty, is kept as it is.
    */
  def groupName(comm: String): String =
    val trimmed = comm.trim
    val stripped = trimmed.replaceAll("[-_# ]?[0-9]+$", "")
    if stripped.isEmpty then trimmed else stripped

  /** Counts of each name, largest first and alphabetical among equals. */
  def counts(names: List[String]): List[(String, Int)] =
    names
      .groupMapReduce(identity)(_ => 1)(_ + _)
      .toList
      .sortBy { case (name, count) => (-count, name) }

  /** `/proc/self/status` lines (`Key:<tab>value`) as a map. */
  def parseStatus(lines: List[String]): Map[String, String] =
    lines.flatMap { line =>
      line.split(":", 2).toList match
        case key :: value :: Nil => Some(key.trim -> value.trim)
        case _                   => None
    }.toMap

  /** A `/proc` size such as `212340 kB`, in bytes. */
  def kilobytes(value: Option[String]): Long =
    value
      .flatMap(_.split("\\s+").headOption)
      .flatMap(_.toLongOption)
      .map(_ * 1024L)
      .getOrElse(0L)
