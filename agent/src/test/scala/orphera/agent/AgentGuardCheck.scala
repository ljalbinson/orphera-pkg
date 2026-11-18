// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.file.Files

import cats.effect.unsafe.implicits.global

import io.grpc.{Status, StatusRuntimeException}

import orphera.common.*

/** Checks that an agent on a host with the never-reboot marker refuses to
  * reboot or power it off. Run with
  * `sbt "agent/Test/runMain orphera.agent.AgentGuardCheck"`.
  *
  * Only the PROTECTED path is called. The unprotected path would really
  * schedule a reboot or shutdown of the machine running this check, so it is
  * never reached here; that the refusal comes before anything is started is
  * what the checks below rely on.
  */
object AgentGuardCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name${if detail.nonEmpty then s" — $detail" else ""}")

  private def failedPrecondition(e: Throwable): Boolean =
    e match
      case s: StatusRuntimeException =>
        s.getStatus.getCode == Status.Code.FAILED_PRECONDITION
      case _ => false

  private val testPolicy =
    """# a policy
      |default_role admin
      |role viewer facts,version,uptime,thread-info,check-file,fetch
      |role deployer facts,copy,packages   # no run
      |role admin all
      |""".stripMargin

  private def certFor(
      dir: java.nio.file.Path,
      subject: String
  ): Option[java.security.cert.X509Certificate] =
    scala.util.Try {
      val key = dir.resolve("k.pem")
      val crt = dir.resolve("c.pem")
      val p = new ProcessBuilder(
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout",
        key.toString, "-out", crt.toString, "-subj", subject, "-days", "1"
      ).redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      require(p.waitFor() == 0)
      val in = Files.newInputStream(crt)
      try
        java.security.cert.CertificateFactory
          .getInstance("X.509")
          .generateCertificate(in)
          .asInstanceOf[java.security.cert.X509Certificate]
      finally in.close()
    }.toOption

  private def verdict(
      policy: Option[(String, Option[String])],
      cert: Option[java.security.cert.X509Certificate],
      method: String
  ): Option[String] =
    AuthInterceptor.decide(policy, cert, method) match
      case AuthInterceptor.Decision.Allow(_)       => None
      case AuthInterceptor.Decision.Refuse(_, why) => Some(why)

  private def policyChecks(): Unit =
    val pol = Some((testPolicy, Some("")))
    check(
      "the policy parses",
      Policy.parse(testPolicy).exists(p =>
        p.defaultRole == "admin" && p.roles.size == 3
      )
    )
    check(
      "a policy with no default_role, an unknown operation, no operations or a stray line is refused",
      List(
        "role viewer facts\n",
        "default_role admin\nrole v nonsense\n",
        "default_role admin\nrole v\n",
        "default_role admin\nwhat is this\n",
        "default_role Admin\nrole admin all\n",
        ""
      ).forall(t => Policy.parse(t).isLeft)
    )
    check(
      "a node with no policy allows everything, certificate or not",
      verdict(None, None, "ExecuteCommand").isEmpty
    )
    check(
      "an unparseable policy refuses every call",
      verdict(Some(("rubbish\n", Some(""))), None, "GatherFacts")
        .exists(_.contains("cannot be read"))
    )
    check(
      "a call with no certificate has no role and is refused",
      verdict(pol, None, "GatherFacts").exists(_.contains("no client certificate"))
    )
    if !opensslInstalled then
      println("openssl is not installed: skipped the certificate cases")
    else
      val dir = Files.createTempDirectory("agent-policy-check")
      val viewer = certFor(dir, "/CN=alice/OU=viewer")
      val deployer = certFor(dir, "/CN=bob/OU=deployer")
      val admin = certFor(dir, "/CN=carol/OU=admin")
      val orch = certFor(dir, "/CN=orphera-orchestrator@scala0")
      val stranger = certFor(dir, "/CN=dave/OU=auditor")
      val badOu = certFor(dir, "/CN=mallory/OU=Not A Role")
      check(
        "the certificates for the cases were made",
        List(viewer, deployer, admin, orch, stranger, badOu).forall(_.isDefined)
      )
      check(
        "a viewer may read",
        verdict(pol, viewer, "GatherFacts").isEmpty
      )
      check(
        "a viewer may not run, and is told the operation",
        verdict(pol, viewer, "ExecuteCommand").exists(w =>
          w.contains("ExecuteCommand") && w.contains("operation run")
        )
      )
      check(
        "a viewer may not copy",
        verdict(pol, viewer, "CopyFile").isDefined
      )
      check(
        "a deployer may copy, check a file and install, but not reboot",
        verdict(pol, deployer, "CopyFile").isEmpty &&
          verdict(pol, deployer, "CheckFile").isEmpty &&
          verdict(pol, deployer, "Install").isEmpty &&
          verdict(pol, deployer, "TriggerReboot").isDefined
      )
      check(
        "an admin may make any call, one no operation lists too",
        verdict(pol, admin, "ExecuteCommand").isEmpty &&
          verdict(pol, admin, "SomethingNew").isEmpty
      )
      check(
        "another role may not make a call no operation lists",
        verdict(pol, deployer, "SomethingNew").exists(_.contains("operation (none)"))
      )
      check(
        "the orchestrator's own certificate has the default role",
        verdict(pol, orch, "ExecuteCommand").isEmpty
      )
      check(
        "a role the policy does not list is refused",
        verdict(pol, stranger, "GatherFacts")
          .exists(_.contains("not in this node's access policy"))
      )
      check(
        "an OU that is no valid role is refused, not given the default",
        verdict(pol, badOu, "GatherFacts")
          .exists(_.contains("no valid operator and role"))
      )
      val serial = admin.map(c => Policy.serialOf(c)).getOrElse("")
      check(
        "a revoked serial is refused",
        verdict(
          Some((testPolicy, Some(s"# gone\n$serial\n"))),
          admin,
          "GatherFacts"
        ).exists(_.contains("revoked"))
      )
      check(
        "a revoked serial in upper case with a 0x prefix is refused too",
        verdict(
          Some((testPolicy, Some(s"0x${serial.toUpperCase}\n"))),
          admin,
          "GatherFacts"
        ).exists(_.contains("revoked"))
      )
      check(
        "another serial is not revoked",
        verdict(Some((testPolicy, Some("1\n"))), admin, "GatherFacts").isEmpty
      )
      // The files, as the interceptor reads them
      val policyFile = dir.resolve("policy")
      val revokedFile = dir.resolve("revoked")
      check(
        "no policy file: no policy",
        AuthInterceptor.readPolicy(policyFile, revokedFile).isEmpty
      )
      Files.writeString(policyFile, testPolicy)
      check(
        "a policy file without a revoked file reads with an empty list",
        AuthInterceptor
          .readPolicy(policyFile, revokedFile)
          .contains((testPolicy, Some("")))
      )
      Files.writeString(revokedFile, "5\n")
      check(
        "and with one",
        AuthInterceptor
          .readPolicy(policyFile, revokedFile)
          .contains((testPolicy, Some("5\n")))
      )
      List("k.pem", "c.pem", "policy", "revoked").foreach(f =>
        Files.deleteIfExists(dir.resolve(f))
      )
      Files.deleteIfExists(dir)

  private def opensslInstalled: Boolean =
    scala.util.Try {
      val p = new ProcessBuilder("openssl", "version")
        .redirectErrorStream(true)
        .start()
      p.getInputStream.readAllBytes()
      p.waitFor() == 0
    }.getOrElse(false)

  def main(args: Array[String]): Unit =
    check(
      "a call with no TLS session is logged as having no client certificate",
      AuthInterceptor.callerOf(io.grpc.Attributes.EMPTY) ==
        "no client certificate"
    )

    policyChecks()

    val dir = Files.createTempDirectory("agent-guard-check")
    val marker = dir.resolve("never-reboot")
    val env = Map(NeverReboot.EnvVar -> marker.toString)

    check(
      "the marker defaults to /etc/orphera-agent/never-reboot",
      NeverReboot
        .markerPath(Map.empty)
        .toString == "/etc/orphera-agent/never-reboot"
    )
    check(
      "ORPHERA_NEVER_REBOOT_FILE overrides the marker path",
      NeverReboot.markerPath(env) == marker
    )
    check(
      "no marker file: the host is not protected",
      !NeverReboot.isProtected(marker)
    )

    Files.createFile(marker)
    check("a marker file protects the host", NeverReboot.isProtected(marker))

    val reboot = Rebooter.trigger(RebootRequest(0), env).attempt.unsafeRunSync()
    check(
      "the agent refuses a reboot on a protected host (FAILED_PRECONDITION)",
      reboot.left.exists(failedPrecondition),
      reboot.toString
    )
    check(
      "the refusal names the marker and says how to lift it",
      reboot.left.exists(e =>
        e.getMessage.contains(marker.toString) && e.getMessage.contains(
          "never-reboot"
        )
      ),
      reboot.toString
    )

    val shutdown =
      Shutdowner.trigger(ShutdownRequest(0), env).attempt.unsafeRunSync()
    check(
      "the agent refuses a power off on a protected host (FAILED_PRECONDITION)",
      shutdown.left.exists(failedPrecondition),
      shutdown.toString
    )
    check(
      "the power-off refusal says so",
      shutdown.left.exists(_.getMessage.contains("power off")),
      shutdown.toString
    )

    Files.delete(marker)
    check(
      "removing the marker lifts the protection",
      !NeverReboot.isProtected(marker)
    )
    Files.delete(dir)

    protectedPathChecks()

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"AgentGuardCheck: all $passes checks passed")

  /** copy and fetch refuse the agent's own directory (ProtectedPaths). */
  private def protectedPathChecks(): Unit =
    val base = Files.createTempDirectory("orphera-protected")
    val prot = base.resolve("orphera-agent")
    Files.createDirectories(prot.resolve("tls"))
    Files.writeString(prot.resolve("agent.env"), "ORPHERA_TOKEN=x\n")
    val link = base.resolve("link")
    Files.createSymbolicLink(link, prot)
    def p(path: java.nio.file.Path): Boolean =
      ProtectedPaths.isProtected(path.toString, prot)

    check("the token file is protected", p(prot.resolve("agent.env")))
    check("the directory itself is protected", p(prot))
    check(
      "a file that does not exist yet in it is protected",
      p(prot.resolve("tls/server.key"))
    )
    check(
      "a path with .. into it is protected",
      p(base.resolve("other/../orphera-agent/agent.env"))
    )
    check(
      "a path through a symbolic link to it is protected",
      p(link.resolve("agent.env"))
    )
    check(
      "a new file through a symbolic link is protected",
      p(link.resolve("tls/new.crt"))
    )
    check(
      "a directory whose name only starts the same is not",
      !p(base.resolve("orphera-agent-other/x"))
    )
    check("another file is not protected", !p(base.resolve("elsewhere.txt")))
    check(
      "the real agent directory is refused, with the reason",
      ProtectedPaths
        .refusal("/etc/orphera-agent/agent.env")
        .exists(_.contains("token")) &&
        ProtectedPaths.refusal("/etc/motd").isEmpty
    )

    val fetched = FileTransfer
      .send(FetchFile("/etc/orphera-agent/agent.env"))
      .compile
      .toList
      .unsafeRunSync()
    check(
      "fetch of the token file sends only a refusal",
      fetched
        .map(_.payload.error)
        .flatten
        .exists(_.startsWith("Refused")) && fetched.length == 1,
      fetched.toString
    )
    val checked = FileTransfer
      .check(FileCheck("/etc/orphera-agent/agent.env", "x"))
      .unsafeRunSync()
    check(
      "the check before a copy says nothing about a protected file",
      checked.needsCopy && checked.reason == "protected path",
      checked.toString
    )
