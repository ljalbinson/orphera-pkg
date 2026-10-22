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
  * Files named by a playbook (`src = "files/motd"`): a relative path is read
  * from the current directory, as before; when it is not there and
  * `ORPHERA_ROOT` is set (the packaged launcher sets it to `/opt/orphera`), it
  * is looked for under that directory. An absolute path is never moved.
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
  val RootEnv = "ORPHERA_ROOT"
  val DefaultCertsDir = "certs"
  val InstalledScriptingJar = "/usr/local/lib/orphera-scripting.jar"

  private def set(env: String => Option[String], name: String): Option[String] =
    env(name).map(_.trim).filter(_.nonEmpty)

  /** The directory of the CA certificate and key. */
  def certsDirOf(env: String => Option[String]): String =
    set(env, CertsEnv).getOrElse(DefaultCertsDir).stripSuffix("/")

  def caCert: String = s"${certsDirOf(name => sys.env.get(name))}/ca.crt"
  def caKey: String = s"${certsDirOf(name => sys.env.get(name))}/ca.key"

  /** Why the CA certificate cannot be used, or `None` when the file is there
    * and readable. The TLS library only says "File does not contain valid
    * certificates" for a missing file as much as for a bad one.
    */
  def caCertProblemOf(
      path: String,
      exists: Boolean,
      readable: Boolean
  ): Option[String] =
    if !exists then
      Some(
        s"no CA certificate: $path does not exist (put ca.crt there, or set $CertsEnv to the directory that has it)"
      )
    else if !readable then
      Some(s"cannot read the CA certificate $path: permission denied")
    else None

  def caCertProblem: Option[String] =
    val file = new File(caCert)
    caCertProblemOf(caCert, file.isFile, file.canRead)

  /** The file a playbook means by `src`: as given when it is absolute or exists
    * relative to the current directory, else under `ORPHERA_ROOT` when it is
    * there, else as given, so that a missing file is reported under the name
    * the playbook used.
    */
  def sourceOf(
      env: String => Option[String],
      exists: Path => Boolean,
      src: String
  ): Path =
    val asGiven = Paths.get(src)
    if asGiven.isAbsolute || exists(asGiven) then asGiven
    else
      set(env, RootEnv)
        .map(root => Paths.get(root).resolve(src))
        .filter(exists)
        .getOrElse(asGiven)

  def source(src: String): Path =
    sourceOf(key => sys.env.get(key), p => Files.exists(p), src)

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
