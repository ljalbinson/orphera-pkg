// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.File
import java.nio.file.{Files, Path, Paths}

/** Where the files `orphera` itself needs are, when it is not run from a
  * checkout of the repository: the jar that compiles and runs `.scala`
  * playbooks, the certificate authority, and the directories it writes run
  * state to.
  *
  * Certificate authority: `ORPHERA_CERTS_DIR` (default `certs`) holds `ca.crt`
  * and `ca.key`.
  *
  * Run state (`.orphera-state`, `.orphera-logs`, `.orphera-audit`,
  * `.orphera-build-logs`): under `ORPHERA_STATE_DIR` when it is set, else in
  * the current directory, as before. Playbook scripts started by `orphera`
  * inherit both variables.
  *
  * The scripting jar is taken from, in order:
  *   1. `ORPHERA_SCRIPTING_JAR`, which must name a file
  *   1. `/usr/local/lib/orphera-scripting.jar`, where `make orpheracli`
  *      installs it beside the orchestrator jar
  *   1. the newest `scripting-assembly*.jar` under `scripting/target/`, as
  *      before, for a build tree
  */
object Installation:

  val ScriptingEnv = "ORPHERA_SCRIPTING_JAR"
  val OrchestratorEnv = "ORPHERA_ORCHESTRATOR_JAR"
  val InstalledOrchestratorJar = "/usr/local/lib/orphera-orchestrator.jar"
  val CertsEnv = "ORPHERA_CERTS_DIR"
  val StateEnv = "ORPHERA_STATE_DIR"
  val DefaultCertsDir = "certs"
  val InstalledScriptingJar = "/usr/local/lib/orphera-scripting.jar"

  private def set(env: String => Option[String], name: String): Option[String] =
    env(name).map(_.trim).filter(_.nonEmpty)

  /** The directory of the CA certificate and key. */
  def certsDirOf(env: String => Option[String]): String =
    set(env, CertsEnv).getOrElse(DefaultCertsDir).stripSuffix("/")

  def caCert: String = s"${certsDirOf(name => sys.env.get(name))}/ca.crt"
  def caKey: String = s"${certsDirOf(name => sys.env.get(name))}/ca.key"

  /** A run-state directory such as `.orphera-logs`: inside `ORPHERA_STATE_DIR`
    * when that is set, else relative to the current directory.
    */
  def statePathOf(env: String => Option[String], name: String): Path =
    set(env, StateEnv) match
      case Some(dir) => Paths.get(dir, name)
      case None      => Paths.get(name)

  def statePath(name: String): Path =
    statePathOf(key => sys.env.get(key), name)

  /** The scripting jar for this run. */
  def scriptingJar: Either[String, String] =
    resolveScriptingJar(
      name => sys.env.get(name),
      path => Files.isRegularFile(Paths.get(path)),
      findLatestJar("scripting/target", "scripting-assembly", ".jar")
    )

  /** The orchestrator jar a `.scala` playbook is compiled and run against. */
  def orchestratorJar: Either[String, String] =
    resolveOrchestratorJar(
      name => sys.env.get(name),
      path => Files.isRegularFile(Paths.get(path)),
      findLatestJar("orchestrator/target", "orchestrator-assembly", ".jar")
    )

  /** Pure: the choice, given the environment, a file test and the jar found in
    * a build tree.
    */
  def resolveScriptingJar(
      env: String => Option[String],
      isFile: String => Boolean,
      built: => Option[String]
  ): Either[String, String] =
    resolveJar(
      ScriptingEnv,
      InstalledScriptingJar,
      "scripting jar for .scala playbooks",
      "sbt scripting/assembly",
      env,
      isFile,
      built
    )

  def resolveOrchestratorJar(
      env: String => Option[String],
      isFile: String => Boolean,
      built: => Option[String]
  ): Either[String, String] =
    resolveJar(
      OrchestratorEnv,
      InstalledOrchestratorJar,
      "orchestrator jar for .scala playbooks",
      "sbt orchestrator/assembly",
      env,
      isFile,
      built
    )

  private def resolveJar(
      envName: String,
      installed: String,
      what: String,
      buildCommand: String,
      env: String => Option[String],
      isFile: String => Boolean,
      built: => Option[String]
  ): Either[String, String] =
    set(env, envName) match
      case Some(path) =>
        if isFile(path) then Right(path)
        else Left(s"$envName is $path, which is not a file")
      case None =>
        if isFile(installed) then Right(installed)
        else
          built.toRight(
            s"no $what: run 'make orpheracli' (it installs $installed), " +
              s"or set $envName, or run '$buildCommand' in the repository"
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
