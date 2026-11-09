// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import scala.collection.mutable

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.yaml.parser  // YAML is a superset of JSON, and this is the parser the project has

/** Checks for the iLO client and `orphera ilo`, against a mock iLO (Redfish
  * or the older HP REST API; the JDK's HTTP server on localhost). Needs no
  * iLO. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.IloCheck"`.
  */
object IloCheck:
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

  private val AllowedIlo4 = List("On", "ForceOff", "ForceRestart", "PushPowerButton", "Nmi")

  /** One mock iLO: its state, what it accepts, and every POST it received. */
  private final class Mock(
      legacy: Boolean = false,
      var power: String = "On",
      allowed: List[String] = AllowedIlo4,
      shutdownWorks: Boolean = true
  ):
    val posts = mutable.ListBuffer.empty[(String, Json)]
    var requests = 0

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (ex: HttpExchange) => handle(ex))
    server.start()
    val port: Int = server.getAddress.getPort
    val url = s"http://127.0.0.1:$port"
    def stop(): Unit = server.stop(0)

    def resets: List[String] =
      posts.toList.flatMap(_._2.hcursor.get[String]("ResetType").toOption)

    private val login = "Basic " + Base64.getEncoder.encodeToString("Administrator:secret".getBytes(UTF_8))

    private def reply(ex: HttpExchange, code: Int, doc: Json): Unit =
      val bytes = doc.noSpaces.getBytes(UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(code, bytes.length.toLong)
      val os = ex.getResponseBody
      os.write(bytes)
      os.close()

    private def handle(ex: HttpExchange): Unit =
      this.synchronized {
        requests += 1
        val path = ex.getRequestURI.getPath
        val body = String(ex.getRequestBody.readAllBytes(), UTF_8)
        if !Option(ex.getRequestHeaders.getFirst("Authorization")).contains(login) then
          reply(ex, 401, Json.obj("error" -> Json.obj("message" -> Json.fromString("login failed"))))
        else if ex.getRequestMethod == "GET" then
          path match
            case "/redfish/v1/Systems/" =>
              if legacy then reply(ex, 404, Json.obj())
              else
                reply(ex, 200, Json.obj("Members" -> Json.arr(Json.obj("@odata.id" -> Json.fromString("/redfish/v1/Systems/1/")))))
            case "/rest/v1/Systems" =>
              reply(ex, 200, Json.obj("links" -> Json.obj("Member" -> Json.arr(Json.obj("href" -> Json.fromString("/rest/v1/Systems/1"))))))
            case "/redfish/v1/Systems/1/" =>
              reply(
                ex, 200,
                Json.obj(
                  "PowerState" -> Json.fromString(power),
                  "Actions" -> Json.obj(
                    "#ComputerSystem.Reset" -> Json.obj(
                      "target" -> Json.fromString("/redfish/v1/Systems/1/Actions/ComputerSystem.Reset/"),
                      "ResetType@Redfish.AllowableValues" -> Json.arr(allowed.map(Json.fromString)*)
                    )
                  )
                )
              )
            case "/rest/v1/Systems/1" => reply(ex, 200, Json.obj("Power" -> Json.fromString(power)))
            case _                    => reply(ex, 404, Json.obj())
        else
          val doc = parser.parse(body).getOrElse(Json.obj())
          posts += ((path, doc))
          val rt = doc.hcursor.get[String]("ResetType").getOrElse("")
          if rt == "On" && power == "On" then
            reply(
              ex, 400,
              Json.obj(
                "error" -> Json.obj(
                  "@Message.ExtendedInfo" -> Json.arr(Json.obj("Message" -> Json.fromString("The server is already on")))
                )
              )
            )
          else
            rt match
              case "On"       => power = "On"
              case "ForceOff" => power = "Off"
              case "PushPowerButton" =>
                if power == "On" && shutdownWorks then power = "Off"
                else if power == "Off" then power = "On"
              case "GracefulShutdown" => if shutdownWorks then power = "Off"
              case _                  => ()
            reply(ex, 200, Json.obj())
      }

  /** Runs `orphera ilo ...` and returns (exit code, standard output, standard error). */
  private def run(env: Map[String, String], args: String*): (Int, String, String) =
    val out, err = ByteArrayOutputStream()
    val code = Console.withOut(out) {
      Console.withErr(err) {
        IloTool.run(args.toList, env, pollMillis = 20L)
      }
    }
    (code, out.toString(UTF_8), err.toString(UTF_8))

  private val Env = Map("ILO_PASSWORD" -> "secret")
  private def go(args: String*) = run(Env, args*)
  private val Y = List("--yes", "--timeout", "2")

  def main(args: Array[String]): Unit =
    val noTerminal = System.console() == null

    // ---- Redfish ----
    var m = Mock()
    var set(go("status", m.url))
    check("status shows On", c == 0 && o.contains("On"), o + e)
    m.stop()

    m = Mock(); set(go("on", m.url))
    check("on when on: nothing sent", c == 0 && m.posts.isEmpty && o.contains("already on"), o + e)
    m.stop()

    m = Mock(power = "Off"); set(go("on", m.url))
    check("on when off: POST On, now On", c == 0 && m.resets == List("On") && m.power == "On", m.posts.toString + e)
    check("the POST goes to the action target", m.posts.headOption.map(_._1).contains("/redfish/v1/Systems/1/Actions/ComputerSystem.Reset/"))
    m.stop()

    m = Mock(); set(go(("off" :: m.url :: Y)*))
    check("off: ForceOff, now Off", c == 0 && m.resets == List("ForceOff") && m.power == "Off", m.posts.toString + e)
    m.stop()

    m = Mock(power = "Off"); set(go(("off" :: m.url :: Y)*))
    check("off when off: nothing sent", c == 0 && m.posts.isEmpty, m.posts.toString)
    m.stop()

    m = Mock(); set(go(("shutdown" :: m.url :: Y)*))
    check("shutdown on iLO 4 (no GracefulShutdown): PushPowerButton", c == 0 && m.resets == List("PushPowerButton") && m.power == "Off", m.posts.toString + e)
    m.stop()

    m = Mock(allowed = List("On", "GracefulShutdown", "ForceOff")); set(go(("shutdown" :: m.url :: Y)*))
    check("shutdown uses GracefulShutdown when offered", c == 0 && m.resets == List("GracefulShutdown"), m.posts.toString)
    m.stop()

    m = Mock(power = "Off"); set(go(("shutdown" :: m.url :: Y)*))
    check("shutdown when off never presses the button", c == 0 && m.posts.isEmpty, m.posts.toString)
    m.stop()

    m = Mock(shutdownWorks = false); set(go("shutdown", m.url, "--yes", "--timeout", "1"))
    check("shutdown that does not complete fails and does not force", c == 1 && m.resets == List("PushPowerButton") && e.contains("`off`"), m.posts.toString + e)
    m.stop()

    m = Mock(); set(go(("reset" :: m.url :: Y)*))
    check("reset: ForceRestart, still On", c == 0 && m.resets == List("ForceRestart") && m.power == "On", m.posts.toString + e)
    m.stop()

    m = Mock(power = "Off"); set(go(("reset" :: m.url :: Y)*))
    check("reset when off fails, nothing sent", c == 1 && m.posts.isEmpty && e.contains("not on"), m.posts.toString + e)
    m.stop()

    m = Mock(); set(go(("soft-reset" :: m.url :: Y)*))
    check("soft-reset on iLO 4: button, then On", c == 0 && m.resets == List("PushPowerButton", "On") && m.power == "On", m.posts.toString + e)
    m.stop()

    m = Mock(allowed = List("On", "GracefulRestart", "ForceOff")); set(go(("soft-reset" :: m.url :: Y)*))
    check("soft-reset uses GracefulRestart when offered", c == 0 && m.resets == List("GracefulRestart"), m.posts.toString + e)
    m.stop()

    m = Mock(shutdownWorks = false); set(go("soft-reset", m.url, "--yes", "--timeout", "1"))
    check("soft-reset whose shutdown stalls fails before powering on", c == 1 && m.resets == List("PushPowerButton"), m.posts.toString + e)
    m.stop()

    m = Mock(power = "Off"); set(go(("soft-reset" :: m.url :: Y)*))
    check("soft-reset when off fails, nothing sent", c == 1 && m.posts.isEmpty, m.posts.toString + e)
    m.stop()

    m = Mock(); set(go("reset", m.url, "--yes", "--dry-run"))
    check("--dry-run sends nothing and says what it would", c == 0 && m.posts.isEmpty && o.contains("would send") && o.contains("ForceRestart"), o + e)
    m.stop()

    m = Mock(); set(go("off", m.url, "--yes", "--no-wait"))
    check("--no-wait returns after the request", c == 0 && m.resets == List("ForceOff") && o.contains("requested"), o + e)
    m.stop()

    m = Mock(); set(go("soft-reset", m.url, "--yes", "--no-wait"))
    check("soft-reset --no-wait leaves the power on to the user", c == 0 && m.resets == List("PushPowerButton") && o.contains("--no-wait"), o + e)
    m.stop()

    // ---- HP REST API ----
    m = Mock(legacy = true); set(go("status", m.url))
    check("status through /rest/v1", c == 0 && o.contains("On"), o + e)
    m.stop()

    m = Mock(legacy = true); set(go(("off" :: m.url :: Y)*))
    check(
      "off: POST Action Reset ForceOff to the system",
      c == 0 && m.posts.toList == List(("/rest/v1/Systems/1", Json.obj("Action" -> Json.fromString("Reset"), "ResetType" -> Json.fromString("ForceOff")))),
      m.posts.toString + e
    )
    m.stop()

    m = Mock(legacy = true, power = "Off"); set(go("on", m.url))
    check("on through /rest/v1", c == 0 && m.power == "On", m.posts.toString + e)
    m.stop()

    // ---- errors and safety ----
    m = Mock(); set(run(Map("ILO_PASSWORD" -> "wrong"), "status", m.url))
    check("a refused login is one request, not retried", c == 1 && m.requests == 1 && e.contains("refused the login"), s"${m.requests} requests: $e")
    m.stop()

    m = Mock(); set(run(Env + ("ILO_USER" -> "operator"), "status", m.url))
    check("ILO_USER sets the user (the mock only accepts Administrator)", c == 1 && e.contains("user operator"), e)
    m.stop()

    m = Mock(); set(run(Env + ("ILO_USER" -> "operator"), "status", m.url, "--user", "Administrator"))
    check("--user overrides ILO_USER", c == 0 && o.contains("On"), o + e)
    m.stop()

    set(go("status", "http://127.0.0.1:9"))
    check("an unreachable iLO fails with a message", c == 1 && e.contains("cannot reach"), e)

    if noTerminal then
      m = Mock(); set(go("off", m.url))
      check("off without --yes and without a terminal is refused", c == 2 && m.posts.isEmpty, s"$c $e")
      m.stop()

    val a = Mock()
    val b = Mock(power = "Off")
    set(go(("reset" :: b.url :: a.url :: Y)*))
    check("several iLOs: one fails, the next still done, exit 1", c == 1 && a.resets == List("ForceRestart") && b.posts.isEmpty, s"$c $e")
    a.stop(); b.stop()

    m = Mock(); set(go("on", m.url + "/", "--no-wait"))
    check("a trailing slash in the URL is fine", c == 0, e)
    m.stop()

    set(go("status"))
    check("no iLO named is a usage error", c == 2 && e.contains("needs an iLO"), s"$c $e")
    set(go("frobnicate", "x"))
    check("an unknown action is a usage error", c == 2 && e.contains("unknown action"), s"$c $e")
    set(go("status", "x", "--timeout", "soon"))
    check("a bad --timeout is a usage error", c == 2 && e.contains("--timeout"), s"$c $e")

    // ---- passwords ----
    check("the secret is named after the iLO as typed", IloTool.secretKey("ilo-tst50") == "ilo-tst50")
    check("a trailing slash and a domain are kept as typed", IloTool.secretKey("ilo-tst50.example.com/") == "ilo-tst50.example.com")
    check("the secret of a URL is its host", IloTool.secretKey("https://ilo-tst50.example.com:8443/") == "ilo-tst50.example.com")

    // a real store in a temporary file, with a trivial cipher that counts how often it is read
    var reads = 0
    object CountingCipher extends Cipher:
      def encrypt(plain: String): Either[String, Array[Byte]] = Right(plain.getBytes(UTF_8).reverse)
      def decrypt(data: Array[Byte]): Either[String, String] =
        reads += 1
        Right(String(data.reverse, UTF_8))
    val dir = java.nio.file.Files.createTempDirectory("ilocheck")
    val ss = new SecretStore(dir.resolve("secrets"), CountingCipher)
    ss.set("ilo-tst50", "pw")
    ss.set("shared", "pw2")
    val store: () => Either[String, SecretStore] = () => Right(ss)
    reads = 0
    check(
      "with no password variable, the secret named after the iLO supplies it",
      DeviceAccess.password(Map.empty, "ILO_PASSWORD", None, None, "ilo-tst50", "Administrator", store) == Right("pw")
    )
    check(
      "--secret NAME names another secret",
      DeviceAccess.password(Map.empty, "ILO_PASSWORD", None, Some("shared"), "ilo-tst50", "Administrator", store) == Right("pw2")
    )
    reads = 0
    check(
      "ILO_PASSWORD overrides the store (the store is not read)",
      DeviceAccess.password(Env, "ILO_PASSWORD", None, None, "ilo-tst50", "Administrator", store) == Right("secret") && reads == 0
    )
    val pwFile = dir.resolve("pw")
    java.nio.file.Files.writeString(pwFile, "fromfile\n")
    check(
      "--password-file supplies it, without the newline",
      DeviceAccess.password(Map.empty, "ILO_PASSWORD", Some(pwFile.toString), None, "x", "Administrator", store) == Right("fromfile")
    )
    if noTerminal then
      val missing = DeviceAccess.password(Map.empty, "ILO_PASSWORD", None, Some("other/name"), "ilo-tst50", "Administrator", store)
      check(
        "a missing secret fails and says how to store it",
        missing.left.exists(m => m.contains("other/name") && m.contains("echo 'the password' | orphera secrets set other/name")),
        missing.toString
      )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"IloCheck: all $passes checks passed")
