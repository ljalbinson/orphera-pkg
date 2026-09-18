// SPDX-License-Identifier: Apache-2.0

package orphera.scripting

import java.io.File
import java.nio.file.{Files, Paths}

object Main:

  def main(args: Array[String]): Unit =
    args.toList match
      case scriptPath :: Nil => run(scriptPath)
      case _                 =>
        System.err.println("Usage: orphera.scripting.Main <script.scala>")
        sys.exit(2)

  private def run(scriptPath: String): Unit =
    val script = Paths.get(scriptPath)

    if !Files.exists(script) then
      System.err.println(s"Error: script not found: $scriptPath")
      sys.exit(1)

    val objectName = script.getFileName.toString.stripSuffix(".scala")

    val orchestratorJar =
      findLatestJar("orchestrator/target", "orchestrator-assembly", ".jar")
        .getOrElse {
          System.err.println(
            "Error: orchestrator-assembly jar not found under orchestrator/target/. " +
              "Run 'sbt orchestrator/assembly' first."
          )
          sys.exit(1)
        }

    val selfJar = findOwnJarPath()
      .getOrElse {
        System.err.println(
          "Error: could not determine this module's own jar path."
        )
        sys.exit(1)
      }

    val outDir = Files.createTempDirectory("orphera-script-classes")

    val compileExit = runProcess(
      List(
        "java",
        "-cp",
        selfJar,
        "dotty.tools.dotc.Main",
        "-classpath",
        orchestratorJar,
        "-d",
        outDir.toString,
        scriptPath
      )
    )

    if compileExit != 0 then
      System.err.println(s"Error: compilation of $scriptPath failed.")
      sys.exit(compileExit)

    val runClasspath = s"$outDir${File.pathSeparator}$orchestratorJar"

    val runExit = runProcess(List("java", "-cp", runClasspath, objectName))
    sys.exit(runExit)

  private def runProcess(command: List[String]): Int =
    new ProcessBuilder(command*).inheritIO().start().waitFor()

  private def findLatestJar(
      dir: String,
      prefix: String,
      suffix: String
  ): Option[String] =
    val base = new File(dir)
    if !base.isDirectory then None
    else
      val candidates =
        Option(base.listFiles()).toList.flatten
          .filter(_.isDirectory)
          .flatMap(scalaDir => Option(scalaDir.listFiles()).toList.flatten)
          .filter(f =>
            f.isFile && f.getName.startsWith(prefix) && f.getName
              .endsWith(suffix)
          )
          .sortBy(_.getName)
      candidates.lastOption.map(_.getAbsolutePath)

  private def findOwnJarPath(): Option[String] =
    Option(getClass.getProtectionDomain.getCodeSource)
      .map(f => Paths.get(f.getLocation.toURI).toString)
