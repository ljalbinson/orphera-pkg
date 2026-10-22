// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** Checks for where the scripting jar is looked for. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.InstallationCheck"`.
  */
object InstallationCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val Installed = Installation.InstalledScriptingJar
  private val Built = Some("/repo/scripting/target/scala-3.8.4/scripting-assembly-1.jar")

  private def resolve(
      env: Map[String, String],
      files: Set[String],
      built: Option[String]
  ) = Installation.resolveScriptingJar(env.get, files.contains, built)

  def main(args: Array[String]): Unit =
    check(
      "the environment variable wins when it names a file",
      resolve(
        Map(Installation.ScriptingEnv -> "/x/s.jar"),
        Set("/x/s.jar", Installed),
        Built
      ) == Right("/x/s.jar")
    )
    check(
      "an environment variable naming nothing is an error, not a fallback",
      resolve(Map(Installation.ScriptingEnv -> "/x/s.jar"), Set(Installed), Built).swap
        .exists(e => e.contains("/x/s.jar") && e.contains("not a file"))
    )
    check(
      "an empty environment variable is ignored",
      resolve(Map(Installation.ScriptingEnv -> "  "), Set(Installed), None) ==
        Right(Installed)
    )
    check(
      "the installed jar is used when there is no variable",
      resolve(Map.empty, Set(Installed), Built) == Right(Installed)
    )
    check(
      "a build tree is the last resort",
      resolve(Map.empty, Set.empty, Built) == Right(Built.getOrElse(""))
    )
    check(
      "nothing found says how to get it",
      resolve(Map.empty, Set.empty, None).swap.exists(e =>
        e.contains("make orpheracli") && e.contains(Installation.ScriptingEnv) &&
          e.contains(Installed)
      )
    )
    check(
      "the build tree is not searched when something else is found",
      {
        var searched = false
        val r = Installation.resolveScriptingJar(
          _ => None,
          _ == Installed,
          { searched = true; Built }
        )
        r == Right(Installed) && !searched
      }
    )

    // The orchestrator jar a playbook is compiled against
    val InstalledOrch = Installation.InstalledOrchestratorJar
    check(
      "the orchestrator jar follows ORPHERA_ORCHESTRATOR_JAR, then the installed jar, then a build tree",
      Installation.resolveOrchestratorJar(
        Map(Installation.OrchestratorEnv -> "/x/o.jar").get,
        Set("/x/o.jar", InstalledOrch).contains,
        None
      ) == Right("/x/o.jar") &&
        Installation.resolveOrchestratorJar(_ => None, Set(InstalledOrch).contains, Built) ==
          Right(InstalledOrch) &&
        Installation.resolveOrchestratorJar(_ => None, _ => false, Built) ==
          Right(Built.getOrElse(""))
    )
    check(
      "no orchestrator jar says how to get one",
      Installation.resolveOrchestratorJar(_ => None, _ => false, None).swap.exists(e =>
        e.contains("make orpheracli") && e.contains(Installation.OrchestratorEnv)
      )
    )

    // The certificate authority and the run state
    check(
      "the CA is in certs by default",
      Installation.certsDirOf(_ => None) == "certs"
    )
    check(
      "ORPHERA_CERTS_DIR moves it, and a trailing slash does not matter",
      Installation.certsDirOf(Map(Installation.CertsEnv -> "/etc/orphera/ca/").get) ==
        "/etc/orphera/ca" &&
        Installation.certsDirOf(Map(Installation.CertsEnv -> " ").get) == "certs"
    )
    check(
      "run state is relative to the current directory by default",
      Installation.statePathOf(_ => None, ".orphera-logs").toString == ".orphera-logs"
    )
    check(
      "ORPHERA_STATE_DIR puts run state inside it",
      Installation.statePathOf(
        Map(Installation.StateEnv -> "/var/lib/orphera").get,
        ".orphera-logs"
      ).toString == "/var/lib/orphera/.orphera-logs"
    )
    check(
      "an empty ORPHERA_STATE_DIR is ignored",
      Installation.statePathOf(Map(Installation.StateEnv -> "").get, ".orphera-state").toString ==
        ".orphera-state"
    )

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"All $passes checks passed")
