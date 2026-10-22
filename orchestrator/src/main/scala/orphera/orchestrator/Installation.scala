// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.File
import java.nio.file.{Files, Paths}

/** Where the files `orphera` itself needs are, when it is not run from a
  * checkout of the repository: today the jar that compiles and runs `.scala`
  * playbooks.
  *
  * The jar is taken from, in order:
  *   1. `ORPHERA_SCRIPTING_JAR`, which must name a file
  *   1. `/usr/local/lib/orphera-scripting.jar`, where `make orpheracli`
  *      installs it beside the orchestrator jar
  *   1. the newest `scripting-assembly*.jar` under `scripting/target/`, as
  *      before, for a build tree
  */
object Installation:

  val ScriptingEnv = "ORPHERA_SCRIPTING_JAR"
  val InstalledScriptingJar = "/usr/local/lib/orphera-scripting.jar"

  /** The scripting jar for this run. */
  def scriptingJar: Either[String, String] =
    resolveScriptingJar(
      name => sys.env.get(name),
      path => Files.isRegularFile(Paths.get(path)),
      findLatestJar("scripting/target", "scripting-assembly", ".jar")
    )

  /** Pure: the choice, given the environment, a file test and the jar found in
    * a build tree.
    */
  def resolveScriptingJar(
      env: String => Option[String],
      isFile: String => Boolean,
      built: => Option[String]
  ): Either[String, String] =
    env(ScriptingEnv).map(_.trim).filter(_.nonEmpty) match
      case Some(path) =>
        if isFile(path) then Right(path)
        else Left(s"$ScriptingEnv is $path, which is not a file")
      case None =>
        if isFile(InstalledScriptingJar) then Right(InstalledScriptingJar)
        else
          built.toRight(
            "no scripting jar for .scala playbooks: run 'make orpheracli' " +
              s"(it installs $InstalledScriptingJar), or set $ScriptingEnv, or " +
              "run 'sbt scripting/assembly' in the repository"
          )

  /** The newest jar called `<prefix>*<suffix>` in a subdirectory of `dir`. */
  def findLatestJar(
      dir: String,
      prefix: String,
      suffix: String
  ): Option[String] =
    val base = new File(dir)
    if !base.isDirectory then None
    else
      Option(base.listFiles()).toList.flatten
        .filter(_.isDirectory)
        .flatMap(scalaDir => Option(scalaDir.listFiles()).toList.flatten)
        .filter(f =>
          f.isFile && f.getName.startsWith(prefix) && f.getName.endsWith(suffix)
        )
        .sortBy(_.getName)
        .lastOption
        .map(_.getAbsolutePath)
