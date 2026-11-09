// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.{FileInputStream, IOException}
import java.net.{ConnectException, Socket, URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyStore, SecureRandom}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.Duration
import java.util.Base64
import javax.net.ssl.{
  SSLContext,
  SSLEngine,
  SSLHandshakeException,
  TrustManager,
  TrustManagerFactory,
  X509ExtendedTrustManager
}

import io.circe.Json
import io.circe.yaml.parser  // YAML is a superset of JSON, and this is the parser the project has

/** What went wrong with a request to the switch. */
final case class Os10Error(message: String, status: Option[Int] = None):
  /** The switch has no such interface. OS10 answers HTTP 400 "unknown resource
    * instance" for that; other RESTCONF servers answer 404.
    */
  def missing: Boolean =
    status.contains(404) ||
      (status.contains(400) && message.contains("unknown resource"))

/** Accepts any certificate (the switch's own, self-signed). An extended trust
  * manager, so the JDK does not add its host name check on top.
  */
private object Os10TrustAll extends X509ExtendedTrustManager:
  def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
  def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
  def checkClientTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit = ()
  def checkServerTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit = ()
  def checkClientTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit = ()
  def checkServerTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit = ()
  def getAcceptedIssuers: Array[X509Certificate] = Array.empty

/** One Dell OS10 switch, reached over RESTCONF (RFC 8040): HTTPS port 443,
  * basic authentication with a user of the switch. `target` is an address or
  * DNS name, or a full http(s):// URL.
  */
final class Os10Switch(
    target: String,
    val user: String,
    password: String,
    insecure: Boolean = false,
    cacert: Option[String] = None,
    timeoutSeconds: Int = 60
):
  val base: String =
    val t = target.trim.stripSuffix("/")
    if t.startsWith("http://") || t.startsWith("https://") then t
    else "https://" + t

  /** What the switch is called in messages. */
  val name: String = base.split("://", 2).last

  private val auth: String =
    "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(UTF_8))

  private def sslContext(): Either[Os10Error, SSLContext] =
    if insecure then
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, Array[TrustManager](Os10TrustAll), SecureRandom())
      Right(ctx)
    else
      cacert match
        case None       => Right(SSLContext.getDefault)
        case Some(path) =>
          try
            val in = FileInputStream(path)
            val cert =
              try CertificateFactory.getInstance("X.509").generateCertificate(in)
              finally in.close()
            val ks = KeyStore.getInstance(KeyStore.getDefaultType)
            ks.load(null, null)
            ks.setCertificateEntry("switch", cert)
            val tmf = TrustManagerFactory.getInstance(
              TrustManagerFactory.getDefaultAlgorithm
            )
            tmf.init(ks)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tmf.getTrustManagers, SecureRandom())
            Right(ctx)
          catch
            case e: Exception =>
              Left(Os10Error(s"cannot use the certificate $path: ${e.getMessage}"))

  private lazy val client: Either[Os10Error, HttpClient] =
    sslContext().map(ctx =>
      HttpClient
        .newBuilder()
        .sslContext(ctx)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    )

  /** Sends a request to a path under /restconf (it starts with /data or
    * /operations) and returns the reply body as text.
    */
  def request(
      method: String,
      path: String,
      body: Option[Json] = None
  ): Either[Os10Error, String] =
    client.flatMap { c =>
      val b = HttpRequest
        .newBuilder(URI.create(base + "/restconf" + path))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", auth)
        .header("Accept", Os10Switch.Accept)
      val publisher = body match
        case Some(j) =>
          b.header("Content-Type", "application/yang-data+json")
          HttpRequest.BodyPublishers.ofString(j.noSpaces)
        case None => HttpRequest.BodyPublishers.noBody()
      try
        val resp = c.send(
          b.method(method, publisher).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        val code = resp.statusCode
        if code == 401 then
          Left(
            Os10Error(
              s"$name refused the login for user $user (not retried: repeated failures can lock the account)",
              Some(401)
            )
          )
        else if code >= 400 then
          Left(
            Os10Error(
              s"$name: $method $path returned HTTP $code${Os10Switch.detail(resp.body)}",
              Some(code)
            )
          )
        else Right(resp.body)
      catch
        case e: SSLHandshakeException =>
          Left(
            Os10Error(
              s"$name: the certificate is not trusted (${e.getMessage}); use --insecure for the switch's " +
                "self-signed certificate or --cacert FILE"
            )
          )
        case e: HttpTimeoutException =>
          Left(Os10Error(s"$name: no answer within $timeoutSeconds s"))
        case e: ConnectException =>
          Left(
            Os10Error(
              s"$name: cannot reach the switch: ${Option(e.getMessage).getOrElse("connection failed")} (is `rest api restconf` on?)"
            )
          )
        case e: IOException =>
          Left(Os10Error(s"$name: ${Option(e.getMessage).getOrElse(e.getClass.getName)}"))
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(Os10Error(s"$name: interrupted"))
    }

  def json(path: String): Either[Os10Error, Json] =
    request("GET", path).flatMap { text =>
      if text.isBlank then Right(Json.obj())
      else
        parser
          .parse(text)
          .left
          .map(_ => Os10Error(s"$name: the reply to GET $path is not JSON"))
    }

  /** GET a path under /restconf/data. */
  def data(path: String): Either[Os10Error, Json] =
    json("/data/" + path.stripPrefix("/"))

object Os10Switch:
  val Accept = "application/yang-data+json, application/json, */*;q=0.8"

  /** ": <message>" from a RESTCONF error reply, or "". */
  def detail(body: String): String =
    parser
      .parse(body)
      .toOption
      .map(
        _.hcursor.downField("ietf-restconf:errors").downField("error").downArray
      )
      .flatMap(c =>
        c.get[String]("error-message")
          .orElse(c.get[String]("error-tag"))
          .toOption
      )
      .filter(_.nonEmpty)
      .fold("")(": " + _)

/** One request of a change. `undo` takes it back if a later step fails;
  * `fallback` is tried instead when the switch answers 405 or 501.
  */
final case class Os10Step(
    method: String,
    url: String,
    body: Option[Json] = None,
    undo: Option[Os10Step] = None,
    fallback: Option[Os10Step] = None
):
  def show: String =
    s"$method /restconf$url${body.fold("")(" " + _.noSpaces)}"

/** A change that is worked out and not yet sent. `headline` says what it does
  * (without sending anything). `verify` reads the switch back after the steps
  * and returns the closing message. `retry` is another way to the same end,
  * used when the steps are refused.
  */
final case class Os10Change(
    headline: String,
    steps: List[Os10Step],
    verify: (String => Unit) => Either[Os10Error, String],
    retry: Option[Os10Change] = None,
    retryNote: Option[String] = None
)

sealed trait Os10Plan
final case class Os10NoChange(message: String) extends Os10Plan
final case class Os10Apply(change: Os10Change) extends Os10Plan

final case class Os10PortRow(
    name: String,
    mode: String,
    speed: String,
    mtu: String,
    untagged: List[Int],
    tagged: List[Int]
)

/** Reading and changing a switch. Nothing here prints: reads return data, and
  * a change is planned (`plan*`) and then sent with `execute`. A change is to
  * the running configuration; `save` is what keeps it over a reload.
  */
object Os10:
  val Ifaces = "/data/ietf-interfaces:interfaces/interface"
  val Tagged = "dell-interface:tagged-ports"
  val Untagged = "dell-interface:untagged-ports"
  val NotSaved = "not saved to the startup configuration: `orphera os10 save` or `write memory` on the switch keeps it"

  /** A name or a value as a path segment: percent-encoded, '/' and ':' too. */
  def q(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  // ---- small helpers ----
  private type Key = (Int, Long, String)
  private val chunkRe = "\\d+|\\D+".r

  private def naturalKey(s: String): List[Key] =
    chunkRe
      .findAllIn(s)
      .toList
      .map(c => if c.head.isDigit then (0, c.toLong, "") else (1, 0L, c))

  private def compareKeys(x: List[Key], y: List[Key]): Int =
    (x, y) match
      case (Nil, Nil)         => 0
      case (Nil, _)           => -1
      case (_, Nil)           => 1
      case (a :: as, b :: bs) =>
        val c = Ordering[Key].compare(a, b)
        if c != 0 then c else compareKeys(as, bs)

  /** Port names in port order: ethernet1/1/2 before ethernet1/1/10. */
  val natural: Ordering[String] = new Ordering[String]:
    def compare(a: String, b: String): Int =
      compareKeys(naturalKey(a), naturalKey(b))

  /** [105, 106, 107, 228] as "105-107,228". */
  def ranges(ids: Iterable[Int]): String =
    val s = ids.toList.distinct.sorted
    val out = scala.collection.mutable.ListBuffer.empty[String]
    var i = 0
    while i < s.length do
      var j = i
      while j + 1 < s.length && s(j + 1) == s(j) + 1 do j += 1
      out += (if i == j then s(i).toString else s"${s(i)}-${s(j)}")
      i = j + 1
    out.mkString(",")

  def table(header: List[String], rows: List[List[String]]): String =
    val all = header :: rows
    val widths = header.indices.map(i => all.map(_(i).length).max)
    all
      .map(r =>
        r.zip(widths).map((c, w) => c.padTo(w, ' ')).mkString("  ").stripTrailing()
      )
      .mkString("\n")

  def nameOf(j: Json): String = j.hcursor.get[String]("name").getOrElse("")
  def strings(j: Json, key: String): List[String] =
    j.hcursor.get[List[String]](key).getOrElse(Nil)
  def leaf(j: Json, key: String): Option[Json] = j.hcursor.downField(key).focus
  private def show(j: Option[Json]): String =
    j.fold("(none)")(v => v.asString.getOrElse(v.noSpaces))
  private def rc(s: String): String = s"$s   (running configuration only)"

  def vlanId(name: String): Option[Int] =
    Option
      .when(name.startsWith("vlan") && name.length > 4 && name.drop(4).forall(_.isDigit))(
        name.drop(4).toInt
      )

  /** "1/1/5" is ethernet1/1/5. Only ethernet ports; not vlan..., mgmt... */
  def portName(arg: String): Either[Os10Error, String] =
    if arg.headOption.exists(_.isLetter) && !arg.startsWith("ethernet") then
      Left(Os10Error(s"only ethernet ports can be changed, not $arg"))
    else Right(if arg.startsWith("ethernet") then arg else "ethernet" + arg)

  // ---- reading ----
  private def interfaceList(doc: Json): List[Json] =
    doc.hcursor
      .get[List[Json]]("ietf-interfaces:interface")
      .orElse(doc.hcursor.get[List[Json]]("interface"))
      .getOrElse(Nil)

  def allInterfaces(sw: Os10Switch): Either[Os10Error, List[Json]] =
    sw.data("ietf-interfaces:interfaces/interface").map(interfaceList)

  /** The interface, or None if the switch has none of that name. */
  def getInterface(sw: Os10Switch, name: String): Either[Os10Error, Option[Json]] =
    sw.data("ietf-interfaces:interfaces/interface=" + q(name)) match
      case Left(e) if e.missing => Right(None)
      case Left(e)              => Left(e)
      case Right(doc)           => Right(interfaceList(doc).headOption)

  def vlansOf(ifaces: List[Json]): Map[Int, Json] =
    ifaces.flatMap(i => vlanId(nameOf(i)).map(_ -> i)).toMap

  def portsOf(ifaces: List[Json]): List[Json] =
    ifaces.filter(i => nameOf(i).startsWith("ethernet"))

  /** port name -> (VLANs it is untagged in, VLANs it is tagged in) */
  def membership(vlans: Map[Int, Json]): Map[String, (List[Int], List[Int])] =
    val un = scala.collection.mutable.Map.empty[String, List[Int]]
    val tg = scala.collection.mutable.Map.empty[String, List[Int]]
    for (vid, v) <- vlans do
      for p <- strings(v, Untagged) do un(p) = vid :: un.getOrElse(p, Nil)
      for p <- strings(v, Tagged) do tg(p) = vid :: tg.getOrElse(p, Nil)
    (un.keySet ++ tg.keySet)
      .map(p => p -> (un.getOrElse(p, Nil).sorted, tg.getOrElse(p, Nil).sorted))
      .toMap

  def portRows(sw: Os10Switch): Either[Os10Error, List[Os10PortRow]] =
    allInterfaces(sw).map { ifaces =>
      val mem = membership(vlansOf(ifaces))
      portsOf(ifaces)
        .sortBy(nameOf)(using natural)
        .map { p =>
          val (un, tg) = mem.getOrElse(nameOf(p), (Nil, Nil))
          Os10PortRow(
            nameOf(p),
            leaf(p, "dell-interface:mode")
              .flatMap(_.asString)
              .fold("-")(_.replace("MODE_", "")),
            leaf(p, "dell-interface:speed")
              .flatMap(_.asString)
              .fold("-")(_.replace("GIGE", "G")),
            leaf(p, "dell-interface:mtu").fold("-")(j =>
              j.asString.getOrElse(j.noSpaces)
            ),
            un,
            tg
          )
        }
    }

  def operations(sw: Os10Switch): Either[Os10Error, List[String]] =
    sw.json("/operations").map(
      _.hcursor
        .downField("ietf-restconf:operations")
        .keys
        .fold(List.empty[String])(_.toList.sorted)
    )

  /** The text of a YANG module, from the switch's get-schema. The reply may be
    * JSON, JSON that is not valid (the text unquoted inside a wrapper, as
    * OS10 sends it), XML, or the plain text.
    */
  def schema(sw: Os10Switch, module: String): Either[Os10Error, String] =
    val body = Json.obj(
      "ietf-netconf-monitoring:input" -> Json.obj(
        "identifier" -> Json.fromString(module),
        "format" -> Json.fromString("yang")
      )
    )
    sw.request("POST", "/operations/ietf-netconf-monitoring:get-schema", Some(body))
      .flatMap { raw =>
        val t = raw.trim
        val wrapper = "{\"ietf-netconf-monitoring:output\":{\"data\":"
        val text =
          // OS10 sends the module text unquoted inside the wrapper, which is not JSON; and plain YANG text would
          // parse as YAML, so only text that starts like JSON is parsed
          if t.startsWith(wrapper) && !t.drop(wrapper.length).trim.startsWith("\"") then
            Some(unwrapSchema(raw))
          else if t.startsWith("{") || t.startsWith("[") then
            parser.parse(raw) match
              case Right(j) => firstText(j)
              case Left(_)  => Some(unwrapSchema(raw))
          else Some(unwrapSchema(raw))
        text.filter(_.nonEmpty).toRight(Os10Error(s"${sw.name}: no schema text for $module in the reply"))
      }

  private def firstText(j: Json): Option[String] =
    j.asString.orElse(
      j.asObject
        .flatMap(_.values.iterator.map(firstText).collectFirst { case Some(t) => t })
        .orElse(
          j.asArray.flatMap(_.iterator.map(firstText).collectFirst { case Some(t) => t })
        )
    )

  private def unwrapSchema(raw: String): String =
    val wrapper = "{\"ietf-netconf-monitoring:output\":{\"data\":"
    val unwrapped =
      if raw.startsWith(wrapper) then
        raw.drop(wrapper.length).trim.stripSuffix("}").stripSuffix("}").trim
      else raw
    if unwrapped.trim.startsWith("<") && unwrapped.trim.endsWith(">") then
      try
        val doc = javax.xml.parsers.DocumentBuilderFactory
          .newInstance()
          .newDocumentBuilder()
          .parse(org.xml.sax.InputSource(java.io.StringReader(unwrapped)))
        doc.getDocumentElement.getTextContent
      catch case _: Exception => unescape(unwrapped)
    else unescape(unwrapped)

  private def unescape(s: String): String =
    s.replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&amp;", "&")

  // ---- changing: planning ----
  private def noPort(sw: Os10Switch, name: String) =
    Os10Error(s"${sw.name}: no port $name")

  private def patchBody(name: String, key: String, value: Json): Json =
    Json.obj(
      "ietf-interfaces:interface" -> Json.arr(
        Json.obj("name" -> Json.fromString(name), key -> value)
      )
    )

  /** Set one setting of a port (None removes it). */
  def planPortLeaf(
      sw: Os10Switch,
      arg: String,
      label: String,
      key: String,
      newValue: Option[Json],
      shownNew: String
  ): Either[Os10Error, Os10Plan] =
    for
      name <- portName(arg)
      port <- getInterface(sw, name).flatMap(_.toRight(noPort(sw, name)))
    yield
      val old = leaf(port, key)
      if old == newValue then
        Os10NoChange(s"${sw.name}: $name $label is already $shownNew, nothing to do")
      else
        val path = Ifaces + "=" + q(name)
        val step = newValue match
          case None    => Os10Step("DELETE", path + "/" + key)
          case Some(v) => Os10Step("PATCH", path, Some(patchBody(name, key, v)))
        Os10Apply(
          Os10Change(
            rc(s"${sw.name}: $name $label: ${show(old)} -> $shownNew"),
            List(step),
            _ =>
              getInterface(sw, name).flatMap { p =>
                val got = p.flatMap(leaf(_, key))
                if got == newValue then
                  Right(s"${sw.name}: $name $label is now $shownNew. ${NotSaved.capitalize}")
                else
                  Left(
                    Os10Error(
                      s"${sw.name}: sent, but $label now reads ${show(got)}, not $shownNew"
                    )
                  )
              }
          )
        )

  def planDescription(sw: Os10Switch, port: String, text: String): Either[Os10Error, Os10Plan] =
    planPortLeaf(
      sw, port, "description", "description",
      Option.when(text.nonEmpty)(Json.fromString(text)),
      if text.isEmpty then "(none)" else text
    )

  def planMtu(sw: Os10Switch, port: String, value: String): Either[Os10Error, Os10Plan] =
    if value.nonEmpty && value.forall(_.isDigit) && value.length <= 5 &&
      value.toInt >= 64 && value.toInt <= 65535
    then
      planPortLeaf(
        sw, port, "MTU", "dell-interface:mtu",
        Some(Json.fromInt(value.toInt)), value.toInt.toString
      )
    else Left(Os10Error(s"an MTU is a number of bytes, e.g. 9216, not '$value'"))

  val Speeds: Map[String, String] = Map(
    "10G" -> "10GIGE", "25G" -> "25GIGE", "40G" -> "40GIGE",
    "50G" -> "50GIGE", "100G" -> "100GIGE", "AUTO" -> "auto"
  )

  def planSpeed(sw: Os10Switch, port: String, value: String): Either[Os10Error, Os10Plan] =
    Speeds.get(value.toUpperCase) match
      case Some(raw) =>
        planPortLeaf(sw, port, "speed", "dell-interface:speed", Some(Json.fromString(raw)), raw)
      case None =>
        Left(Os10Error(s"speed '$value': use one of 10G 25G 40G 50G 100G auto"))

  /** trunk is MODE_L2HYBRID (what the trunk ports on the switch show); access is no mode at all. */
  def planMode(sw: Os10Switch, port: String, value: String): Either[Os10Error, Os10Plan] =
    value.toLowerCase match
      case "trunk" =>
        planPortLeaf(
          sw, port, "mode", "dell-interface:mode",
          Some(Json.fromString("MODE_L2HYBRID")), "trunk (MODE_L2HYBRID)"
        )
      case "access" =>
        planPortLeaf(sw, port, "mode", "dell-interface:mode", None, "access (no mode set)")
      case _ =>
        Left(
          Os10Error(
            s"a mode is trunk (several VLANs, tagged) or access (one untagged VLAN), not '$value'"
          )
        )

  /** VLAN number: 2-4093, or 1-4093 when it is a VLAN that ports go in and out of. */
  def vlanNumber(arg: String, member: Boolean = false): Either[Os10Error, Int] =
    val lowest = if member then 1 else 2
    Option
      .when(arg.nonEmpty && arg.length <= 4 && arg.forall(_.isDigit))(arg.toInt)
      .filter(n => n >= lowest && n <= 4093)
      .toRight(
        Os10Error(
          "a VLAN is a number from 2 to 4093 (VLAN 1, the default VLAN, can only have ports added or removed)"
        )
      )

  def getVlan(sw: Os10Switch, vid: Int): Either[Os10Error, Option[Json]] =
    getInterface(sw, s"vlan$vid")

  def planVlanCreate(sw: Os10Switch, arg: String): Either[Os10Error, Os10Plan] =
    for
      vid <- vlanNumber(arg)
      existing <- getVlan(sw, vid)
    yield
      if existing.isDefined then
        Os10NoChange(s"${sw.name}: VLAN $vid already exists, nothing to do")
      else
        val name = s"vlan$vid"
        val entry = Json.obj(
          "ietf-interfaces:interface" -> Json.arr(
            Json.obj(
              "name" -> Json.fromString(name),
              "type" -> Json.fromString("iana-if-type:l2vlan")
            )
          )
        )
        val put = Os10Step(
          "PUT",
          Ifaces + "=" + q(name),
          Some(entry),
          fallback = Some(Os10Step("POST", "/data/ietf-interfaces:interfaces", Some(entry)))
        )
        Os10Apply(
          Os10Change(
            rc(s"${sw.name}: create VLAN $vid"),
            List(put),
            _ =>
              getVlan(sw, vid).flatMap {
                case Some(_) => Right(s"${sw.name}: VLAN $vid created\n${sw.name}: $NotSaved")
                case None    => Left(Os10Error(s"${sw.name}: sent, but VLAN $vid is not there afterwards"))
              }
          )
        )

  private def mustBeEmpty(sw: Os10Switch, vid: Int, v: Json): Either[Os10Error, Unit] =
    val members = strings(v, Untagged) ++ strings(v, Tagged)
    if members.isEmpty then Right(())
    else
      val some = members.sorted(using natural).take(6).mkString(" ")
      val more = if members.size > 6 then " ..." else ""
      Left(
        Os10Error(
          s"${sw.name}: VLAN $vid still has ${members.size} port(s) ($some$more): take them out first with vlan-remove"
        )
      )

  def planVlanDelete(sw: Os10Switch, arg: String): Either[Os10Error, Os10Plan] =
    for
      vid <- vlanNumber(arg)
      v <- getVlan(sw, vid).flatMap(_.toRight(Os10Error(s"${sw.name}: no VLAN $vid")))
      _ <- mustBeEmpty(sw, vid, v)
    yield Os10Apply(
      Os10Change(
        rc(s"${sw.name}: delete VLAN $vid (it has no ports)"),
        List(Os10Step("DELETE", Ifaces + "=" + q(s"vlan$vid"))),
        _ =>
          getVlan(sw, vid).flatMap {
            case None    => Right(s"${sw.name}: VLAN $vid deleted\n${sw.name}: $NotSaved")
            case Some(_) => Left(Os10Error(s"${sw.name}: sent, but VLAN $vid is still there"))
          }
      )
    )

  /** The VLAN number, the port name and the VLAN, checked to exist. */
  private def vlanAndPort(
      sw: Os10Switch,
      command: String,
      vlanArg: String,
      portArg: Option[String]
  ): Either[Os10Error, (Int, String, Json)] =
    for
      vid <- vlanNumber(vlanArg, member = true)
      pa <- portArg.toRight(
        Os10Error(s"$command needs a VLAN and a port, e.g. $command swt4 600 1/1/44")
      )
      port <- portName(pa)
      _ <- getInterface(sw, port).flatMap(_.toRight(noPort(sw, port)))
      v <- getVlan(sw, vid).flatMap(
        _.toRight(Os10Error(s"${sw.name}: no VLAN $vid (create it with vlan-create)"))
      )
    yield (vid, port, v)

  private def membersNow(sw: Os10Switch, vid: Int): Either[Os10Error, (Set[String], Set[String])] =
    getVlan(sw, vid).map(v =>
      (
        v.fold(Set.empty[String])(strings(_, Untagged).toSet),
        v.fold(Set.empty[String])(strings(_, Tagged).toSet)
      )
    )

  private def vlanStep(vid: Int, key: String, port: String): Os10Step =
    Os10Step(
      "PATCH",
      Ifaces + "=" + q(s"vlan$vid"),
      Some(patchBody(s"vlan$vid", key, Json.arr(Json.fromString(port))))
    )

  private def leaveStep(vid: Int, key: String, port: String, withUndo: Boolean): Os10Step =
    Os10Step(
      "DELETE",
      Ifaces + "=" + q(s"vlan$vid") + "/" + key + "=" + q(port),
      undo = Option.when(withUndo)(vlanStep(vid, key, port))
    )

  def planVlanAdd(
      sw: Os10Switch,
      vlanArg: String,
      portArg: Option[String],
      untagged: Boolean
  ): Either[Os10Error, Os10Plan] =
    vlanAndPort(sw, "vlan-add", vlanArg, portArg).flatMap { (vid, port, v) =>
      addPlan(sw, vid, port, v, untagged)
    }

  private def addPlan(
      sw: Os10Switch,
      vid: Int,
      port: String,
      v: Json,
      untagged: Boolean
  ): Either[Os10Error, Os10Plan] =
    val key = if untagged then Untagged else Tagged
    val other = if untagged then Tagged else Untagged
    val word = if untagged then "untagged" else "tagged"
    if strings(v, key).contains(port) then
      Right(Os10NoChange(s"${sw.name}: $port is already $word in VLAN $vid, nothing to do"))
    else if strings(v, other).contains(port) then
      Left(
        Os10Error(
          s"${sw.name}: $port is ${if untagged then "tagged" else "untagged"} in VLAN $vid: take it out first with vlan-remove"
        )
      )
    else
      allInterfaces(sw).map { ifaces =>
        val olds =
          if !untagged then Nil
          else
            vlansOf(ifaces).toList
              .sortBy(_._1)
              .collect { case (ovid, o) if ovid != vid && strings(o, Untagged).contains(port) => ovid }
        val verify: (String => Unit) => Either[Os10Error, String] = say =>
          // the switch may leave the port untagged in its old VLAN too: take it out
          val cleaned = olds.foldLeft[Either[Os10Error, Unit]](Right(())) { (acc, o) =>
            acc.flatMap { _ =>
              membersNow(sw, o).flatMap { (un, _) =>
                if un.contains(port) then
                  say(s"${sw.name}: $port is still untagged in VLAN $o: taking it out")
                  val gone = leaveStep(o, Untagged, port, withUndo = false)
                  sw.request(gone.method, gone.url).map(_ => ())
                else Right(())
              }
            }
          }
          cleaned.flatMap(_ => membersNow(sw, vid)).flatMap { (un, tg) =>
            if (if untagged then un else tg).contains(port) then
              Right(s"${sw.name}: $port is now $word in VLAN $vid\n${sw.name}: $NotSaved")
            else Left(Os10Error(s"${sw.name}: sent, but $port is not $word in VLAN $vid afterwards"))
          }
        val retry =
          Option.when(olds.nonEmpty)(
            Os10Change(
              rc(
                s"${sw.name}: put $port in VLAN $vid untagged, moving it out of VLAN ${olds.mkString(", ")} first, where it is untagged"
              ),
              olds.map(o => leaveStep(o, Untagged, port, withUndo = true)) :+ vlanStep(vid, key, port),
              verify
            )
          )
        val note =
          Option.when(olds.nonEmpty)(
            s"${sw.name}: if the switch refuses because the port is untagged in VLAN ${olds.mkString(", ")}, it is retried after taking the port out of that VLAN"
          )
        Os10Apply(
          Os10Change(
            rc(s"${sw.name}: put $port in VLAN $vid $word"),
            List(vlanStep(vid, key, port)),
            verify,
            retry,
            note
          )
        )
      }

  def planVlanRemove(
      sw: Os10Switch,
      vlanArg: String,
      portArg: Option[String]
  ): Either[Os10Error, Os10Plan] =
    vlanAndPort(sw, "vlan-remove", vlanArg, portArg).map { (vid, port, v) =>
      val key =
        if strings(v, Untagged).contains(port) then Some(Untagged)
        else if strings(v, Tagged).contains(port) then Some(Tagged)
        else None
      key match
        case None =>
          Os10NoChange(s"${sw.name}: $port is not in VLAN $vid, nothing to do")
        case Some(k) =>
          val word = if k == Untagged then "untagged" else "tagged"
          Os10Apply(
            Os10Change(
              rc(s"${sw.name}: take $port out of VLAN $vid (it is $word there)"),
              List(leaveStep(vid, k, port, withUndo = false)),
              _ =>
                membersNow(sw, vid).flatMap { (un, tg) =>
                  if (un ++ tg).contains(port) then
                    Left(Os10Error(s"${sw.name}: sent, but $port is still in VLAN $vid"))
                  else Right(s"${sw.name}: $port is no longer in VLAN $vid\n${sw.name}: $NotSaved")
                }
            )
          )
    }

  /** copy running-configuration startup-configuration, as yuma-netconf:copy-config. */
  def planSave(sw: Os10Switch): Os10Plan =
    val body = Json.obj(
      "yuma-netconf:input" -> Json.obj(
        "source" -> Json.obj("running" -> Json.arr(Json.Null)),
        "target" -> Json.obj("startup" -> Json.arr(Json.Null))
      )
    )
    Os10Apply(
      Os10Change(
        s"${sw.name}: save the running configuration to the startup configuration (copy-config running -> startup)",
        List(Os10Step("POST", "/operations/yuma-netconf:copy-config", Some(body))),
        _ => Right(s"${sw.name}: saved. Check on the switch with `show startup-configuration`.")
      )
    )

  // ---- changing: sending ----
  private def send(sw: Os10Switch, step: Os10Step): Either[Os10Error, Os10Step] =
    sw.request(step.method, step.url, step.body) match
      case Right(_) => Right(step)
      case Left(e) =>
        (e.status, step.fallback) match
          case (Some(405 | 501), Some(f)) =>
            sw.request(f.method, f.url, f.body).map(_ => f)
          case _ => Left(e)

  /** Sends the steps in order; if one fails, takes back the earlier ones that can be. */
  private def runSteps(
      sw: Os10Switch,
      steps: List[Os10Step],
      say: String => Unit
  ): Either[Os10Error, Unit] =
    @scala.annotation.tailrec
    def go(rest: List[Os10Step], done: List[Os10Step]): Either[Os10Error, Unit] =
      rest match
        case Nil       => Right(())
        case s :: tail =>
          send(sw, s) match
            case Right(ran) => go(tail, ran :: done)
            case Left(e)    =>
              done.foreach { p =>
                p.undo.foreach { u =>
                  sw.request(u.method, u.url, u.body) match
                    case Right(_) =>
                      say(s"${sw.name}: took back the earlier step (${p.method} ${p.url})")
                    case Left(e2) =>
                      say(s"${sw.name}: could NOT take back ${p.method} ${p.url}: ${e2.message}")
                }
              }
              Left(e)
    go(steps, Nil)

  /** Sends a change, reads the switch back and returns the closing message.
    * If the steps are refused and the change has a `retry`, that is sent instead.
    */
  def execute(
      sw: Os10Switch,
      change: Os10Change,
      say: String => Unit
  ): Either[Os10Error, String] =
    runSteps(sw, change.steps, say) match
      case Right(_) => change.verify(say)
      case Left(first) =>
        change.retry match
          case None        => Left(first)
          case Some(retry) =>
            say(s"${sw.name}: the switch refused (${first.message})")
            say(retry.headline)
            runSteps(sw, retry.steps, say).flatMap(_ => retry.verify(say))
