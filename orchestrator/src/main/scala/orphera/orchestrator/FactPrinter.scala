// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import scala.jdk.CollectionConverters.*

/** Pretty-prints the same var tree a template/condition would see — this node's
  * own facts.*, every node's nodes.<name>.* (inventory vars, gathered facts,
  * and set-facts merged), for inspection via the `dump_facts` task. Purely
  * diagnostic — never used to build what's actually rendered, only to show what
  * would be.
  */
object FactPrinter:

  def render(vars: Map[String, Any]): String =
    val sb = new StringBuilder()
    val sortedKeys = vars.keys.toList.sorted

    for key <- sortedKeys do
      vars(key) match
        case nested: java.util.Map[?, ?] =>
          sb.append(s"$key:\n")
          appendNested(
            sb,
            nested.asInstanceOf[java.util.Map[String, Any]],
            indent = 2
          )
        case value =>
          sb.append(s"$key: $value\n")

    sb.toString.stripTrailing()

  private def appendNested(
      sb: StringBuilder,
      m: java.util.Map[String, Any],
      indent: Int
  ): Unit =
    val pad = " " * indent
    val sortedKeys = m.keySet().asScala.toList.sorted

    for key <- sortedKeys do
      m.get(key) match
        case nested: java.util.Map[?, ?] =>
          sb.append(s"$pad$key:\n")
          appendNested(
            sb,
            nested.asInstanceOf[java.util.Map[String, Any]],
            indent + 2
          )
        case value =>
          sb.append(s"$pad$key: $value\n")
