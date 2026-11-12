// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Every playbook compiled into the orchestrator (an `object` extending
  * OrpheraPlaybook in its sources, not a .scala script in manifests/) is in
  * PlaybookRegistry.all, under its own name, so `orphera playbook <name>`
  * finds it. Scala cannot list the objects of a trait at compile time (and
  * OrpheraPlaybook cannot be sealed, since the scripts extend it too), so this
  * finds them among the compiled classes instead. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.RegistryCheck"`.
  */
object RegistryCheck:
  private var failures = 0
  private var passes = 0

  private def check(name: String, ok: Boolean, detail: => String): Unit =
    if ok then passes += 1
    else
      failures += 1
      println(s"FAIL: $name $detail")

  /** The names of the compiled orchestrator classes that may be objects
    * (`...$.class`), from class directories and jars on the class path alike
    * (sbt may hand a forked run either), leaving out the test classes.
    */
  private def objectClassNames: List[String] =
    getClass.getClassLoader
      .getResources("orphera/orchestrator")
      .asScala
      .toList
      .filterNot(u => u.toString.contains("test-classes") || u.toString.contains("-tests.jar"))
      .flatMap { url =>
        url.getProtocol match
          case "file" =>
            val dir = Paths.get(url.toURI)
            val root = dir.getParent.getParent // the directory that holds orphera/
            Files
              .walk(dir)
              .iterator
              .asScala
              .map(p => root.relativize(p).toString.replace('\\', '/'))
              .toList
          case "jar" =>
            val jar = url.openConnection.asInstanceOf[java.net.JarURLConnection].getJarFile
            jar.entries.asScala.map(_.getName).toList
          case _ => Nil
      }
      .filter(n => n.startsWith("orphera/orchestrator/") && n.endsWith("$.class"))
      .map(_.stripSuffix(".class").replace('/', '.'))
      .distinct

  /** The objects among them that extend OrpheraPlaybook. */
  private def playbookObjects: List[OrpheraPlaybook] =
    objectClassNames.flatMap { name =>
      Try(Class.forName(name, false, getClass.getClassLoader)).toOption
        .filter(c => classOf[OrpheraPlaybook].isAssignableFrom(c))
        .flatMap(c => Try(c.getField("MODULE$").get(null)).toOption)
        .collect { case pb: OrpheraPlaybook => pb }
    }

  def main(args: Array[String]): Unit =
    val names = objectClassNames
    check(
      "the compiled orchestrator classes are found",
      names.contains("orphera.orchestrator.PlaybookRegistry$"),
      getClass.getClassLoader.getResources("orphera/orchestrator").asScala.toList.toString
    )
    val found = playbookObjects.distinct
    check("at least one compiled playbook is found (WebBaseline)", found.exists(_ eq WebBaseline), found.toString)

    val registered = PlaybookRegistry.all
    found.foreach { pb =>
      val entry = registered.find(_._2 eq pb.playbook)
      check(
        s"${pb.getClass.getName.stripSuffix("$")} is in PlaybookRegistry.all",
        entry.isDefined,
        s"(add \"${pb.playbook.name}\" -> ${pb.getClass.getSimpleName.stripSuffix("$")}.playbook)"
      )
      entry.foreach { case (key, playbook) =>
        check(
          s"PlaybookRegistry key '$key' is the playbook's own name",
          key == playbook.name,
          s"(the playbook is named '${playbook.name}')"
        )
      }
    }

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"RegistryCheck: all $passes checks passed (${found.length} compiled playbook(s))")
