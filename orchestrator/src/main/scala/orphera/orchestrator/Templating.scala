// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import com.github.mustachejava.DefaultMustacheFactory
import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

object Templating:

  private val factory = new DefaultMustacheFactory()

  def render(templateContent: String, vars: Map[String, String]): String =
    val mustache = factory.compile(new StringReader(templateContent), "template")
    val writer = new StringWriter()
    mustache.execute(writer, vars.asJava)
    writer.flush()
    writer.toString
