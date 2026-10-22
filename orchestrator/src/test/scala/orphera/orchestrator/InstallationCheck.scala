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

    // Files named by a playbook
    import java.nio.file.Paths
    val rootEnv = Map(Installation.RootEnv -> "/opt/orphera").get
    val none: java.nio.file.Path => Boolean = _ => false
    check(
      "a file in the current directory is used as given",
      Installation.sourceOf(rootEnv, p => p.toString == "files/motd", "files/motd").toString ==
        "files/motd"
    )
    check(
      "a file missing here is taken from ORPHERA_ROOT",
      Installation.sourceOf(
        rootEnv,
        p => p == Paths.get("/opt/orphera/files/motd"),
        "files/motd"
      ).toString == "/opt/orphera/files/motd"
    )
    check(
      "a file in both places is the one in the current directory",
      Installation.sourceOf(rootEnv, _ => true, "files/motd").toString == "files/motd"
    )
    check(
      "a file in neither place keeps the name the playbook used",
      Installation.sourceOf(rootEnv, none, "files/motd").toString == "files/motd"
    )
    check(
      "without ORPHERA_ROOT nothing changes",
      Installation.sourceOf(_ => None, none, "files/motd").toString == "files/motd"
    )
    check(
      "an empty ORPHERA_ROOT is ignored",
      Installation.sourceOf(Map(Installation.RootEnv -> " ").get, _ => true, "files/motd")
        .toString == "files/motd"
    )
    check(
      "an absolute path is never moved",
      Installation.sourceOf(rootEnv, p => p.toString.startsWith("/opt"), "/etc/motd").toString ==
        "/etc/motd"
    )

    // The CA certificate
    check(
      "a missing CA certificate is said to be missing, with how to fix it",
      Installation.caCertProblemOf("/etc/orphera/certs/ca.crt", false, false).exists(m =>
        m.contains("/etc/orphera/certs/ca.crt does not exist") && m.contains("ORPHERA_CERTS_DIR")
      )
    )
    check(
      "an unreadable CA certificate is said to be unreadable",
      Installation.caCertProblemOf("certs/ca.crt", true, false).exists(_.contains("permission denied"))
    )
    check(
      "a CA certificate that is there is no problem",
      Installation.caCertProblemOf("certs/ca.crt", true, true).isEmpty
    )

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"All $passes checks passed")
