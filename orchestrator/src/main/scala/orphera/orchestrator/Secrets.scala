// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.Event
import java.util.concurrent.ConcurrentHashMap

/** Values that must not appear in what the orchestrator prints or logs:
  * passwords, tokens, connection strings with credentials. A playbook (or a
  * `Resource` that holds secrets) registers them; every event received from a
  * node passes through `mask` first, so the console and the run log show
  * `<redacted>` where a value was echoed, in a "Running: ..." line or in
  * command output.
  *
  * This hides a value from the orchestrator's own output. It does not hide it
  * from the node: a command that contains a password still has it in its
  * arguments there (see `Resource.checkTask`, which avoids that for file
  * checks).
  */
object Secrets:
  /** Values shorter than this are not registered: masking "yes" or "1" would
    * mangle every line that contains it.
    */
  val MinLength = 6

  val Placeholder = "<redacted>"

  private val registered = ConcurrentHashMap.newKeySet[String]()

  def register(value: String): Unit =
    if value.length >= MinLength then registered.add(value)

  def registerAll(values: Iterable[String]): Unit = values.foreach(register)

  /** Forgets everything registered. For tests. */
  def clear(): Unit = registered.clear()

  def mask(text: String): String =
    if registered.isEmpty then text
    else
      // Longest first, so a value that contains another is masked whole.
      import scala.jdk.CollectionConverters.*
      registered.asScala.toList
        .sortBy(-_.length)
        .foldLeft(text)((t, v) => t.replace(v, Placeholder))

  def mask(event: Event): Event =
    val m = mask(event.message)
    if m == event.message then event else event.copy(message = m)
