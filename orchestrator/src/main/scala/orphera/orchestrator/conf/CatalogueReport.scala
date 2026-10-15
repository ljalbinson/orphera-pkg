// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import java.nio.file.{Files, Paths}

/** Reads an oslo JSON dump and prints what it found: counts, the type words oslo
  * used and how each was mapped, and the options deprecated within their section.
  * For checking the loader against a real image.
  *
  *   sbt "orchestrator/Test/runMain orphera.orchestrator.conf.CatalogueReport /path/placement-api.json"
  */
object CatalogueReport:
  def main(args: Array[String]): Unit =
    val path = args.headOption.getOrElse(sys.error("usage: CatalogueReport <oslo json file>"))
    OsloJson.load("service", Files.readString(Paths.get(path))) match
      case Left(e) => println(s"error: $e"); sys.exit(1)
      case Right(c) =>
        println(s"sections: ${c.sections.size}, options: ${c.options.size}")
        println("type mapping:")
        c.options.groupBy(o => kind(o.tpe)).toList.sortBy(_._1).foreach { (k, os) =>
          println(f"  $k%-28s ${os.size}%5d  e.g. ${os.take(2).map(o => s"${o.section}.${o.name}").mkString(", ")}")
        }
        println(s"secret options: ${c.options.count(_.secret)}")
        println(s"options with an old name in the same section: ${c.options.count(_.deprecated.nonEmpty)}")
        println("placement.conf sections used by the playbook:")
        List("DEFAULT", "api", "placement_database", "keystone_authtoken").foreach { s =>
          println(s"  [$s] ${c.optionsIn(s).size} options" + (if c.sections.contains(s) then "" else "  MISSING"))
        }

  private def kind(t: OptType): String = t match
    case OptType.Other(n)   => s"Other($n)"
    case OptType.Choice(_)  => "Choice"
    case other              => other.toString
