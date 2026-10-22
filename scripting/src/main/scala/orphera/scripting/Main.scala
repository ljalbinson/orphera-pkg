// SPDX-License-Identifier: Apache-2.0

package orphera.scripting

import java.io.File
import java.nio.file.{Files, Paths}

import orphera.orchestrator.Installation

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

    val orchestratorJar = Installation.orchestratorJar match
      case Right(jar) => jar
      case Left(err)  =>
        System.err.println(s"Error: $err")
        sys.exit(1)

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

  private def findOwnJarPath(): Option[String] =
    Option(getClass.getProtectionDomain.getCodeSource)
      .map(f => Paths.get(f.getLocation.toURI).toString)
