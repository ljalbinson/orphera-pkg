// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

/** Checks for `orphera init-ca`: the options, the openssl command, the refusal
  * to replace a CA, and (where `openssl` is installed) making one for real.
  * Run with `sbt "orchestrator/Test/runMain orphera.orchestrator.CaInitCheck"`.
  */
object CaInitCheck:
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

  private def opensslInstalled: Boolean =
    Try {
      val p = new ProcessBuilder("openssl", "version").redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor() == 0
    }.getOrElse(false)

  private def out(command: String*): String =
    val p = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val text = new String(p.getInputStream.readAllBytes())
    p.waitFor()
    text

  def main(args: Array[String]): Unit =
    // Options
    check(
      "the defaults",
      CaInit.parse(Nil) == Right(CaInit.Options(3650, "Orphera CA"))
    )
    check(
      "--days and --name",
      CaInit.parse(List("--days", "365", "--name", "Lab CA")) ==
        Right(CaInit.Options(365, "Lab CA"))
    )
    check(
      "--days must be a positive number",
      CaInit.parse(List("--days", "0")).isLeft &&
        CaInit.parse(List("--days", "soon")).isLeft &&
        CaInit.parse(List("--days", "-5")).isLeft
    )
    check(
      "--name may not hold a '/', which would split the subject",
      CaInit.parse(List("--name", "a/b")).isLeft &&
        CaInit.parse(List("--name", " ")).isLeft
    )
    check(
      "an option without its value is said so",
      CaInit.parse(List("--days")).swap.exists(_.contains("needs a value"))
    )
    check(
      "an unknown argument is named",
      CaInit.parse(List("--force")).swap.exists(_.contains("--force"))
    )

    // The command
    val cmd = CaInit.opensslArgs(
      CaInit.Options(30, "Lab CA"),
      Paths.get("/x/ca.key"),
      Paths.get("/x/ca.crt")
    )
    check(
      "the openssl command makes a self-signed certificate and a key",
      cmd.take(3) == List("openssl", "req", "-x509") &&
        cmd.containsSlice(List("-days", "30")) &&
        cmd.containsSlice(List("-subj", "/CN=Lab CA")) &&
        cmd.containsSlice(List("-keyout", "/x/ca.key")) &&
        cmd.containsSlice(List("-out", "/x/ca.crt"))
    )

    // The refusal
    val dir0 = Paths.get("/etc/orphera/certs")
    check(
      "no CA there: no refusal",
      CaInit.refusal(dir0, _ => false).isEmpty
    )
    check(
      "a CA certificate there: refused, naming the file",
      CaInit.refusal(dir0, _ == dir0.resolve("ca.crt")).exists(m =>
        m.contains("/etc/orphera/certs/ca.crt") && m.contains("nothing was changed")
      )
    )
    check(
      "a CA key there alone is refused too",
      CaInit.refusal(dir0, _ == dir0.resolve("ca.key")).isDefined
    )

    // For real
    if opensslInstalled then
      val dir: Path = Files.createTempDirectory("ca-init-check").resolve("certs")
      val made = CaInit.create(dir, CaInit.Options(30, "Check CA"))
      check("a CA is made", made.isRight, made.toString)
      check(
        "both files exist",
        Files.isRegularFile(dir.resolve("ca.crt")) && Files.isRegularFile(dir.resolve("ca.key"))
      )
      check(
        "the key is private to its owner",
        Files.getPosixFilePermissions(dir.resolve("ca.key")) ==
          PosixFilePermissions.fromString("rw-------")
      )
      val text = out("openssl", "x509", "-noout", "-text", "-in", dir.resolve("ca.crt").toString)
      check("it is a CA", text.contains("CA:TRUE"), text)
      check("it has the name given", text.contains("Check CA"), text)
      check(
        "the fingerprint is the certificate's",
        made.exists(fp => fp.nonEmpty && fp.contains(":") && fp.length == 95),
        made.toString
      )
      val again = CaInit.create(dir, CaInit.Options(30, "Other"))
      check("a second run is refused", again.isLeft)
      check(
        "and changes nothing",
        out("openssl", "x509", "-noout", "-text", "-in", dir.resolve("ca.crt").toString) == text
      )
      List("ca.crt", "ca.key").foreach(f => Files.deleteIfExists(dir.resolve(f)))
      Files.deleteIfExists(dir)
      Files.deleteIfExists(dir.getParent)
    else println("openssl is not installed: skipped making a real CA")

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"CaInitCheck: all $passes checks passed")
