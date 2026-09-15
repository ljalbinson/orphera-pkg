// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import com.github.mustachejava.DefaultMustacheFactory
import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

object Templating:

  private val factory = new DefaultMustacheFactory()

  /** vars values are typically String, but may also be nested
    * java.util.Map[String, Any] (see PlaybookRunner.buildNestedNodeFacts)
    * to support Mustache's dotted-name traversal, e.g. {{nodes.tst0.hostname}}
    * walking nodes -> tst0 -> hostname as real nested maps rather than a
    * literal flat string key. Mustache does NOT treat a flat key
    * containing dots as a single lookup — it always tries to traverse.
    */
  def render(templateContent: String, vars: Map[String, Any]): String =
    val mustache = factory.compile(new StringReader(templateContent), "template")
    val writer = new StringWriter()
    mustache.execute(writer, vars.asJava)
    writer.flush()
    writer.toString
