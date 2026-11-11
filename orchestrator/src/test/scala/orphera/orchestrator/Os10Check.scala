// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import scala.collection.mutable

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.yaml.parser  // YAML is a superset of JSON, and this is the parser the project has

/** Checks for the OS10 client and `orphera os10`, against a mock switch (the
  * JDK's HTTP server on localhost) whose data is shaped like a real
  * S5148F-ON's. Needs no switch. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.Os10Check"`.
  */
object Os10Check:
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

  private val Trunk = "dell-interface:mode" -> Json.fromString("MODE_L2HYBRID")

  private type Iface = mutable.LinkedHashMap[String, Json]

  private def iface(fields: (String, Json)*): Iface =
    mutable.LinkedHashMap.from(fields)

  private def eth(name: String, configured: Boolean = true): Iface =
    val base: Seq[(String, Json)] = Seq(
      "name" -> Json.fromString(name),
      "type" -> Json.fromString("iana-if-type:ethernetCsmacd")
    )
    val settings: Seq[(String, Json)] =
      if configured then
        Seq(
          "dell-interface:mtu" -> Json.fromInt(9216),
          Trunk,
          "dell-interface:speed" -> Json.fromString("25GIGE")
        )
      else Nil
    iface((base ++ settings)*)

  private def strs(xs: String*): Json = Json.arr(xs.map(Json.fromString)*)

  private def vlan(vid: Int, tagged: Seq[String] = Nil, untagged: Seq[String] = Nil): Iface =
    val fields: Seq[(String, Json)] =
      Seq(
        "name" -> Json.fromString(s"vlan$vid"),
        "type" -> Json.fromString("iana-if-type:l2vlan")
      ) ++
        (if tagged.nonEmpty then Seq(Os10.Tagged -> strs(tagged*)) else Nil) ++
        (if untagged.nonEmpty then Seq(Os10.Untagged -> strs(untagged*)) else Nil)
    iface(fields*)

  /** A mock switch. `untaggedAdd`: "move" (the real switch moves the port out of its old VLAN), "refuse" or "keep". */
  private final class Mock(var untaggedAdd: String = "move"):
    val trunk = List("ethernet1/1/1", "ethernet1/1/49:1")
    val ifaces: mutable.ListBuffer[Iface] = mutable.ListBuffer(
      eth("ethernet1/1/1"), eth("ethernet1/1/2"), eth("ethernet1/1/5", configured = false),
      eth("ethernet1/1/10", configured = false), eth("ethernet1/1/49:1"),
      iface("name" -> Json.fromString("mgmt1/1/1")),
      vlan(1, untagged = List("ethernet1/1/5", "ethernet1/1/10")),
      vlan(228, tagged = trunk), vlan(230, tagged = trunk),
      vlan(105, tagged = List("ethernet1/1/1")), vlan(106, tagged = List("ethernet1/1/1")),
      vlan(107, tagged = List("ethernet1/1/1"))
    )
    val requests = mutable.ListBuffer.empty[(String, String)]
    val bodies = mutable.ListBuffer.empty[Json]
    var put405 = false
    var ignoreWrites = false
    var saved = false
    var schemaReply: Option[String] = None
    var failPatchOn: Option[String] = None

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (ex: HttpExchange) => handle(ex))
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    def stop(): Unit = server.stop(0)

    def get(name: String): Option[Iface] = ifaces.find(_("name").asString.contains(name))
    def list(name: String, key: String): List[String] =
      get(name).flatMap(_.get(key)).flatMap(_.as[List[String]].toOption).getOrElse(Nil)
    def writes: List[(String, String)] = requests.toList.filter(r => r._1 != "GET")

    private def reply(ex: HttpExchange, code: Int, body: String = ""): Unit =
      val bytes = body.getBytes(UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/yang-data+json")
      if bytes.isEmpty then ex.sendResponseHeaders(code, -1)
      else
        ex.sendResponseHeaders(code, bytes.length)
        ex.getResponseBody.write(bytes)
      ex.close()

    private def error(ex: HttpExchange, code: Int, msg: String): Unit =
      reply(ex, code, s"""{"ietf-restconf:errors":{"error":[{"error-message":"$msg"}]}}""")

    private def toJson(i: Iface): Json = Json.fromFields(i)

    private def handle(ex: HttpExchange): Unit =
      val method = ex.getRequestMethod
      val path = ex.getRequestURI.getRawPath
      val raw = String(ex.getRequestBody.readAllBytes(), UTF_8)
      requests += (method -> path)
      val body = if raw.isEmpty then None else parser.parse(raw).toOption
      body.foreach(bodies += _)
      val expect = "Basic " + Base64.getEncoder.encodeToString("admin:secret".getBytes(UTF_8))
      if ex.getRequestHeaders.getFirst("Authorization") != expect then reply(ex, 401)
      else dispatch(ex, method, path, body)

    private val base = "/restconf/data/ietf-interfaces:interfaces/interface"

    private def dispatch(ex: HttpExchange, method: String, path: String, body: Option[Json]): Unit =
      if method == "GET" then
        if path == base then
          reply(ex, 200, Json.obj("ietf-interfaces:interface" -> Json.arr(ifaces.toList.map(toJson)*)).noSpaces)
        else if path.startsWith(base + "=") then
          val name = URLDecoder.decode(path.drop(base.length + 1), UTF_8)
          get(name) match
            case Some(i) => reply(ex, 200, Json.obj("ietf-interfaces:interface" -> Json.arr(toJson(i))).noSpaces)
            case None    => error(ex, 400, "unknown resource instance")
        else if path == "/restconf/operations" then
          reply(ex, 200, """{"ietf-restconf:operations":{"yumaworks-system:backup":[null],"dell-alarm:clear-alarm":[null]}}""")
        else if path == "/restconf/data" then
          reply(ex, 200, Json.obj("ietf-interfaces:interfaces" -> Json.obj("interface" -> Json.arr(ifaces.toList.map(toJson)*))).noSpaces)
        else reply(ex, 404)
      else if method == "POST" && path == "/restconf/operations/ietf-netconf-monitoring:get-schema" then
        reply(ex, 200, schemaReply.getOrElse("""{"ietf-netconf-monitoring:output":{"data":"module yuma-netconf { }"}}"""))
      else if method == "POST" && path == "/restconf/operations/yuma-netconf:copy-config" then
        saved = true
        reply(ex, 204)
      else if ignoreWrites then reply(ex, 204)
      else write(ex, method, path, body)

    private def entries(body: Option[Json]): List[Json] =
      body.flatMap(_.hcursor.get[List[Json]]("ietf-interfaces:interface").toOption).getOrElse(Nil)

    private def write(ex: HttpExchange, method: String, path: String, body: Option[Json]): Unit =
      if method == "POST" && path == "/restconf/data/ietf-interfaces:interfaces" then
        entries(body).foreach(e => ifaces += mutable.LinkedHashMap.from(e.asObject.get.toList))
        reply(ex, 201)
      else if !path.startsWith(base + "=") then error(ex, 404, "no such resource")
      else
        val rest = path.drop(base.length + 1)
        val (rawName, leaf) = rest.indexOf('/') match
          case -1 => (rest, "")
          case n  => (rest.take(n), rest.drop(n + 1))
        val name = URLDecoder.decode(rawName, UTF_8)
        method match
          case "PUT" =>
            if put405 then error(ex, 405, "PUT not allowed")
            else
              get(name).foreach(ifaces -= _)
              entries(body).headOption.foreach(e => ifaces += mutable.LinkedHashMap.from(e.asObject.get.toList))
              reply(ex, 201)
          case "DELETE" =>
            get(name) match
              case None => error(ex, 400, "unknown resource instance")
              case Some(i) =>
                if leaf.isEmpty then ifaces -= i
                else if leaf.contains("=") then
                  val key = leaf.takeWhile(_ != '=')
                  val value = URLDecoder.decode(leaf.dropWhile(_ != '=').drop(1), UTF_8)
                  i(key) = strs(list(name, key).filter(_ != value)*)
                else i.remove(leaf)
                reply(ex, 204)
          case "PATCH" =>
            if failPatchOn.exists(path.contains) then error(ex, 500, "internal error")
            else
              get(name) match
                case None => error(ex, 400, "unknown resource instance")
                case Some(i) =>
                  val fields = entries(body).headOption.flatMap(_.asObject).map(_.toList).getOrElse(Nil)
                  val problem = fields.collectFirst(Function.unlift { (k, v) =>
                    if k == Os10.Tagged || k == Os10.Untagged then
                      v.as[List[String]].toOption.getOrElse(Nil).collectFirst(Function.unlift { port =>
                        if get(port).isEmpty then Some("no such port " + port)
                        else if k == Os10.Tagged && !get(port).exists(_.get("dell-interface:mode").contains(Trunk._2)) then
                          Some("Interface being added (to VLAN) is not in switchport mode trunk!")
                        else if k == Os10.Untagged && untaggedAdd == "refuse" &&
                          ifaces.exists(o => !o("name").asString.contains(name) && list(o("name").asString.get, Os10.Untagged).contains(port))
                        then Some("port " + port + " is already untagged in another VLAN")
                        else None
                      })
                    else None
                  })
                  problem match
                    case Some(msg) => error(ex, 400, msg)
                    case None =>
                      for (k, v) <- fields if k != "name" do
                        if k == Os10.Tagged || k == Os10.Untagged then
                          val ports = v.as[List[String]].toOption.getOrElse(Nil)
                          if k == Os10.Untagged && untaggedAdd == "move" then
                            for o <- ifaces if !o("name").asString.contains(name) do
                              val n = o("name").asString.get
                              if list(n, k).exists(ports.contains) then o(k) = strs(list(n, k).filterNot(ports.contains)*)
                          i(k) = strs((list(name, k) ++ ports).distinct.sorted*)
                        else i(k) = v
                      reply(ex, 204)
          case _ => reply(ex, 405)

  /** Runs `orphera os10 ...` and returns (exit code, standard output, standard error). */
  private def run(args: String*): (Int, String, String) =
    runWith(Map("OS10_PASSWORD" -> "secret"), args*)

  /** `orphera os10 <args>` with this environment: its exit code, standard
    * output and standard error (captured, so the checks print only their
    * own lines).
    */
  private def runWith(env: Map[String, String], args: String*): (Int, String, String) =
    val out, err = ByteArrayOutputStream()
    val code = Console.withOut(out) {
      Console.withErr(err) {
        Os10Tool.run(args.toList, env)
      }
    }
    (code, out.toString(UTF_8), err.toString(UTF_8))

  def main(args: Array[String]): Unit =
    // ---- helpers ----
    check("ranges joins consecutive numbers", Os10.ranges(List(105, 106, 107, 228, 230, 231)) == "105-107,228,230-231")
    check(
      "ports sort in port order, not as text",
      List("ethernet1/1/10", "ethernet1/1/2", "ethernet1/1/49:1", "ethernet1/1/5").sorted(using Os10.natural) ==
        List("ethernet1/1/2", "ethernet1/1/5", "ethernet1/1/10", "ethernet1/1/49:1")
    )
    check("a name is percent-encoded with / and :", Os10.q("ethernet1/1/49:1") == "ethernet1%2F1%2F49%3A1")
    check("the secret is named after the switch as typed", Os10Tool.secretKey("swt4") == "swt4")
    check("the secret of a URL is its host", Os10Tool.secretKey("https://swt4.example.com:443/") == "swt4.example.com")
    check("1/1/5 means ethernet1/1/5", Os10.portName("1/1/5") == Right("ethernet1/1/5"))
    check("a VLAN or management interface is not a port that can be changed", Os10.portName("vlan228").isLeft)

    // ---- reading ----
    var m = Mock()
    val (c1, o1, e1) = run("interfaces", m.url)
    val lines = o1.linesIterator.toList
    check("interfaces: header and rows in port order", c1 == 0 &&
      lines.head.split("\\s+").toList == List("PORT", "MODE", "SPEED", "MTU", "UNTAGGED", "TAGGED") &&
      lines.tail.map(_.split("\\s+").head) == List("ethernet1/1/1", "ethernet1/1/2", "ethernet1/1/5", "ethernet1/1/10", "ethernet1/1/49:1"), o1 + e1)
    check("interfaces: a trunk port shows mode, speed, MTU and VLANs",
      lines(1).split("\\s+").toList == List("ethernet1/1/1", "L2HYBRID", "25G", "9216", "-", "105-107,228,230"), lines(1))
    check("interfaces: an unconfigured port shows dashes and its default VLAN",
      lines(3).split("\\s+").toList == List("ethernet1/1/5", "-", "-", "-", "1", "-"), lines(3))
    val (_, oj, _) = run("interfaces", m.url, "--json")
    check("interfaces --json is JSON", parser.parse(oj).toOption.exists(_.asArray.exists(_.nonEmpty)), oj.take(200))
    val (c2, o2, e2) = run("vlans", m.url)
    val vrows = o2.linesIterator.drop(1).map(_.split("\\s+").toList).toList
    check("vlans: sorted by number with port counts", c2 == 0 && vrows.head == List("1", "2", "0") && vrows(1) == List("105", "0", "1") && vrows.last == List("230", "0", "2"), o2 + e2)
    val (c3, o3, _) = run("vlan", m.url, "228")
    check("vlan 228 lists its ports", c3 == 0 && o3.contains("tagged   (2): ethernet1/1/1 ethernet1/1/49:1") && o3.contains("untagged (0): -"), o3)
    val (c4, _, e4) = run("vlan", m.url, "999")
    check("an unknown VLAN is an error naming it (the switch says HTTP 400 unknown resource instance)", c4 == 1 && e4.contains("no VLAN 999"), e4)
    val (c5, _, e5) = run("vlan", m.url, "abc")
    check("a VLAN must be a number", c5 == 1 && e5.contains("number"), e5)
    m.requests.clear()
    val (c6, o6, e6) = run("port", m.url, "1/1/49:1")
    check("port 1/1/49:1 asks for the encoded name and shows its VLANs",
      c6 == 0 && m.requests.exists(_._2.endsWith("interface=ethernet1%2F1%2F49%3A1")) && o6.contains("tagged VLANs:   228,230") && o6.contains("untagged VLANs: -"), o6 + e6)
    check("JSON is printed as \"name\": \"x\", with no space before the colon", o6.contains("\"name\": \"ethernet1/1/49:1\"") && !o6.contains("\" :"), o6)
    val (c7, _, e7) = run("port", m.url, "1/1/99")
    check("an unknown port is reported as no port", c7 == 1 && e7.contains("no port ethernet1/1/99"), e7)
    val (c8, o8, _) = run("operations", m.url)
    check("operations lists the names", c8 == 0 && o8.linesIterator.toList == List("dell-alarm:clear-alarm", "yumaworks-system:backup"), o8)
    val (c9, o9, _) = run("get", m.url, "ietf-interfaces:interfaces/interface=vlan228")
    check("get prints the document", c9 == 0 && o9.contains("vlan228"), o9)
    m.requests.clear()
    run("interfaces", m.url); run("vlans", m.url); run("port", m.url, "1/1/1"); run("operations", m.url); run("get", m.url, "x")
    check("reading sends only GET", m.requests.forall(_._1 == "GET"), m.requests.toString)
    m.stop()

    m = Mock()
    for (reply, what) <- List(
        (None, "the switch's wrapped JSON"),
        (Some("module yuma-netconf { rpc copy-config; }"), "plain text"),
        (Some("<data xmlns=\"x\">module a { leaf b; }</data>"), "XML"),
        (Some("{\"ietf-netconf-monitoring:output\":{\"data\":\nmodule a { type string; // x &lt;running/&gt;\n}\n}}"), "invalid JSON with escaped text")
      )
    do
      m.schemaReply = reply
      val (c, o, e) = run("schema", m.url, "yuma-netconf")
      check(s"schema takes $what", c == 0 && o.contains("module") && !o.contains("output") && !o.contains("&lt;"), o + e)
    m.schemaReply = Some("{\"ietf-netconf-monitoring:output\":{\"data\":\nmodule a { type string; // x &lt;running/&gt;\n}\n}}")
    check("schema keeps the module's own closing brace and unescapes", run("schema", m.url, "a")._2.trim.endsWith("}") && run("schema", m.url, "a")._2.contains("<running/>"))
    m.stop()

    // ---- errors ----
    m = Mock()
    val (cb, _, eb) = runWith(Map("OS10_PASSWORD" -> "wrong"), "vlans", m.url)
    check(
      "a refused login is one request, not retried",
      cb == 1 && m.requests.size == 1 && eb.contains("refused the login") && eb.contains("not retried"),
      m.requests.toString + eb
    )
    m.stop()
    val (cu, _, eu) = run("vlans", "http://127.0.0.1:9")
    check("an unreachable switch names the likely cause", cu == 1 && eu.contains("rest api restconf"), eu)
    val (cs, _, es) = runWith(Map.empty, "vlans")
    check("a command needs a switch", cs == 2 && es.contains("vlans needs a switch"), es)
    val (cv, _, ev) = runWith(Map.empty, "vlan", "http://127.0.0.1:9")
    check("vlan needs a number", cv == 2 && ev.contains("vlan needs a VLAN number"), ev)
    val (cx, _, ex) = runWith(Map.empty, "frobnicate", "x")
    check("an unknown command is a usage error", cx == 2 && ex.contains("unknown command 'frobnicate'"), ex)

    // ---- changing ----
    m = Mock()
    val (d1, od1, ed1) = run("set-description", m.url, "1/1/5", "gs1 eth0", "--yes")
    check("set-description PATCHes the port and reads it back",
      d1 == 0 && m.get("ethernet1/1/5").exists(_.get("description").contains(Json.fromString("gs1 eth0"))) &&
        m.writes == List("PATCH" -> "/restconf/data/ietf-interfaces:interfaces/interface=ethernet1%2F1%2F5") &&
        od1.contains("(none) -> gs1 eth0") && od1.contains("Not saved"), m.writes.toString + od1 + ed1)
    check("the PATCH body names the port and carries the setting",
      m.bodies.head == parser.parse("""{"ietf-interfaces:interface":[{"name":"ethernet1/1/5","description":"gs1 eth0"}]}""").toOption.get, m.bodies.head.noSpaces)
    val (d2, _, _) = run("set-description", m.url, "1/1/5", "", "--yes")
    check("an empty description removes it (DELETE of the leaf)", d2 == 0 && !m.get("ethernet1/1/5").exists(_.contains("description")) && m.writes.last._1 == "DELETE" && m.writes.last._2.endsWith("/description"), m.writes.toString)
    val (d3, od3, _) = run("set-mtu", m.url, "1/1/1", "1500", "--yes")
    check("set-mtu shows old -> new and sets a number", d3 == 0 && od3.contains("9216 -> 1500") && m.get("ethernet1/1/1").exists(_("dell-interface:mtu") == Json.fromInt(1500)), od3)
    val n0 = m.requests.size
    val (d4, _, ed4) = run("set-mtu", m.url, "1/1/1", "jumbo", "--yes")
    check("a non-numeric MTU is refused before anything is sent", d4 == 1 && ed4.contains("jumbo") && m.requests.size == n0, ed4)
    val (d5, od5, _) = run("set-speed", m.url, "1/1/1", "10g", "--yes")
    check("set-speed 10g sends 10GIGE", d5 == 0 && od5.contains("25GIGE -> 10GIGE") && m.get("ethernet1/1/1").exists(_("dell-interface:speed") == Json.fromString("10GIGE")), od5)
    val (d6, _, ed6) = run("set-speed", m.url, "1/1/1", "7G", "--yes")
    check("an unknown speed lists the choices", d6 == 1 && ed6.contains("25G"), ed6)
    val w0 = m.writes.size
    val (d7, od7, _) = run("set-mtu", m.url, "1/1/2", "9216", "--yes")
    check("a value already set sends no change", d7 == 0 && od7.contains("already") && m.writes.size == w0, od7)
    val (d8, od8, _) = run("set-mtu", m.url, "1/1/2", "1500", "--dry-run")
    check("--dry-run shows the request and changes nothing", d8 == 0 && od8.contains("would send: PATCH") && m.writes.size == w0 && m.get("ethernet1/1/2").exists(_("dell-interface:mtu") == Json.fromInt(9216)), od8)
    val (d9, _, ed9) = run("set-mtu", m.url, "vlan228", "1500", "--yes")
    check("a VLAN cannot be changed as a port", d9 == 1 && ed9.contains("only ethernet"), ed9)
    val (d10, _, ed10) = run("set-mtu", m.url, "1/1/99", "1500", "--yes")
    check("an unknown port is refused", d10 == 1 && ed10.contains("no port ethernet1/1/99"), ed10)
    val (d11, od11, _) = run("set-mode", m.url, "1/1/5", "trunk", "--yes")
    check("set-mode trunk sets MODE_L2HYBRID", d11 == 0 && od11.contains("trunk (MODE_L2HYBRID)") && m.get("ethernet1/1/5").exists(_("dell-interface:mode") == Json.fromString("MODE_L2HYBRID")), od11)
    val (d12, _, _) = run("set-mode", m.url, "1/1/5", "access", "--yes")
    check("set-mode access removes the mode leaf", d12 == 0 && !m.get("ethernet1/1/5").exists(_.contains("dell-interface:mode")) && m.writes.last._1 == "DELETE", m.writes.toString)
    m.ignoreWrites = true
    val (d13, _, ed13) = run("set-mtu", m.url, "1/1/1", "1400", "--yes")
    check("a change the switch accepts but does not apply is reported", d13 == 1 && ed13.contains("now reads"), ed13)
    m.stop()

    // ---- save ----
    m = Mock()
    val (s1, os1, _) = run("save", m.url, "--yes")
    check("save sends copy-config running -> startup", s1 == 0 && m.saved && os1.contains("saved") &&
      m.bodies.last == parser.parse("""{"yuma-netconf:input":{"source":{"running":[null]},"target":{"startup":[null]}}}""").toOption.get, m.bodies.lastOption.toString)
    m.saved = false
    val (s2, os2, _) = run("save", m.url, "--dry-run")
    check("save --dry-run sends nothing", s2 == 0 && !m.saved && os2.contains("would send: POST"), os2)
    m.stop()

    // ---- VLANs ----
    m = Mock()
    val (v1, ov1, ev1) = run("vlan-create", m.url, "600", "--yes")
    check("vlan-create PUTs the VLAN interface and reads it back", v1 == 0 && m.get("vlan600").isDefined && m.writes.map(_._1) == List("PUT") && ov1.contains("VLAN 600 created"), m.writes.toString + ov1 + ev1)
    val (v2, ov2, _) = run("vlan-create", m.url, "228", "--yes")
    check("an existing VLAN is not created again", v2 == 0 && ov2.contains("already exists"), ov2)
    for bad <- List("1", "4094", "abc") do
      val n = m.requests.size
      val (c, _, e) = run("vlan-create", m.url, bad, "--yes")
      check(s"vlan-create $bad is refused before anything is sent", c == 1 && e.contains("2 to 4093") && m.requests.size == n, e)
    val (v3, ov3, _) = run("vlan-create", m.url, "601", "--dry-run")
    check("vlan-create --dry-run sends nothing", v3 == 0 && m.get("vlan601").isEmpty && ov3.contains("would send: PUT"), ov3)
    val (v4, ov4, _) = run("vlan-delete", m.url, "600", "--yes")
    check("vlan-delete removes an empty VLAN", v4 == 0 && m.get("vlan600").isEmpty && ov4.contains("deleted"), ov4)
    val (v5, _, ev5) = run("vlan-delete", m.url, "228", "--yes")
    check("vlan-delete refuses a VLAN with ports and names the way out", v5 == 1 && ev5.contains("still has 2 port") && ev5.contains("vlan-remove") && m.get("vlan228").isDefined, ev5)
    val (v6, _, ev6) = run("vlan-delete", m.url, "777", "--yes")
    check("vlan-delete of an unknown VLAN is an error", v6 == 1 && ev6.contains("no VLAN 777"), ev6)
    val (v7, _, ev7) = run("vlan-delete", m.url, "1", "--yes")
    check("VLAN 1 cannot be deleted", v7 == 1 && ev7.contains("2 to 4093"), ev7)
    m.stop()

    m = Mock()
    run("vlan-create", m.url, "600", "--yes")
    val (t1, ot1, et1) = run("vlan-add", m.url, "600", "1/1/2", "--yes")
    check("vlan-add puts a trunk port in a VLAN, tagged by default", t1 == 0 && m.list("vlan600", Os10.Tagged) == List("ethernet1/1/2") && ot1.contains("now tagged"), ot1 + et1)
    val (t2, ot2, _) = run("vlan-add", m.url, "600", "1/1/2", "--yes")
    check("adding it again changes nothing", t2 == 0 && ot2.contains("already tagged"), ot2)
    val (t3, _, _) = run("vlan-remove", m.url, "600", "1/1/2", "--yes")
    check("vlan-remove takes it out (DELETE of the leaf-list entry)", t3 == 0 && m.list("vlan600", Os10.Tagged).isEmpty && m.writes.last._2.endsWith("interface=vlan600/dell-interface:tagged-ports=ethernet1%2F1%2F2"), m.writes.last.toString)
    val (t4, ot4, _) = run("vlan-remove", m.url, "600", "1/1/2", "--yes")
    check("removing a port that is not there changes nothing", t4 == 0 && ot4.contains("not in VLAN 600"), ot4)
    val (t5, _, et5) = run("vlan-add", m.url, "600", "1/1/5", "--yes")
    check("tagging a port that is not a trunk shows the switch's message", t5 == 1 && et5.contains("not in switchport mode trunk"), et5)
    val w1 = m.writes.size
    val (t6, ot6, et6) = run("vlan-add", m.url, "600", "1/1/5", "--untagged", "--yes")
    check("vlan-add --untagged is asked of the switch directly (one request when it moves the port itself)",
      t6 == 0 && m.writes.size == w1 + 1 && m.list("vlan600", Os10.Untagged) == List("ethernet1/1/5") && !m.list("vlan1", Os10.Untagged).contains("ethernet1/1/5"), ot6 + et6 + m.writes.toString)
    val (t7, ot7, _) = run("vlan-add", m.url, "1", "1/1/5", "--untagged", "--yes")
    check("a port can be put back into VLAN 1", t7 == 0 && m.list("vlan1", Os10.Untagged).contains("ethernet1/1/5") && !m.list("vlan600", Os10.Untagged).contains("ethernet1/1/5"), ot7)
    val (t8, _, et8) = run("vlan-add", m.url, "999", "1/1/5", "--yes")
    check("vlan-add to a VLAN that does not exist says to create it", t8 == 1 && et8.contains("vlan-create"), et8)
    val (t9, _, et9) = run("vlan-add", m.url, "228", "vlan105", "--yes")
    check("vlan-add only takes ethernet ports", t9 == 1 && et9.contains("only ethernet"), et9)
    val (t10, _, et10) = run("vlan-add", m.url, "228")
    check("vlan-add needs a port", t10 == 1 && et10.contains("needs a VLAN and a port"), et10)
    val (t11, ot11, _) = run("vlan-add", m.url, "600", "1/1/10", "--untagged", "--dry-run")
    check("--dry-run of an untagged add says what the retry would be", t11 == 0 && ot11.contains("retried after taking the port out of that VLAN") && m.list("vlan600", Os10.Untagged).isEmpty, ot11)
    m.stop()

    m = Mock(untaggedAdd = "refuse")
    run("vlan-create", m.url, "600", "--yes")
    val w2 = m.writes.size
    val (r1, or1, er1) = run("vlan-add", m.url, "600", "1/1/5", "--untagged", "--yes")
    check("when the switch refuses a direct untagged add, the port is taken out of its old VLAN first and added",
      r1 == 0 && m.writes.drop(w2).map(_._1) == List("PATCH", "DELETE", "PATCH") && m.list("vlan600", Os10.Untagged) == List("ethernet1/1/5") &&
        !m.list("vlan1", Os10.Untagged).contains("ethernet1/1/5") && or1.contains("the switch refused") && or1.contains("moving it out of VLAN 1"), or1 + er1 + m.writes.toString)
    m.stop()

    m = Mock(untaggedAdd = "keep")
    run("vlan-create", m.url, "600", "--yes")
    val (k1, ok1, ek1) = run("vlan-add", m.url, "600", "1/1/5", "--untagged", "--yes")
    check("when the switch leaves the port untagged in the old VLAN too, it is taken out of it",
      k1 == 0 && ok1.contains("still untagged in VLAN 1") && !m.list("vlan1", Os10.Untagged).contains("ethernet1/1/5") && m.list("vlan600", Os10.Untagged) == List("ethernet1/1/5"), ok1 + ek1)
    m.stop()

    m = Mock(untaggedAdd = "refuse")
    run("vlan-create", m.url, "600", "--yes")
    m.failPatchOn = Some("vlan600")
    val (f1, of1, ef1) = run("vlan-add", m.url, "600", "1/1/5", "--untagged", "--yes")
    check("when the second step of the retry fails, the first is taken back and it says so",
      f1 == 1 && ef1.contains("internal error") && of1.contains("took back") && m.list("vlan1", Os10.Untagged).contains("ethernet1/1/5"), of1 + ef1)
    m.stop()

    m = Mock()
    m.put405 = true
    val (p1, op1, ep1) = run("vlan-create", m.url, "600", "--yes")
    check("vlan-create falls back to POST when PUT is not allowed", p1 == 0 && m.writes.map(_._1) == List("PUT", "POST") && m.get("vlan600").isDefined, op1 + ep1 + m.writes.toString)
    m.stop()

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"Os10Check: all $passes checks passed")
