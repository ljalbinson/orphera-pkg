// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.IOException
import java.net.{ConnectException, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

import io.circe.Json
import io.circe.yaml.parser  // YAML is a superset of JSON, and this is the parser the project has

/** What went wrong with a request to the iLO. */
final case class IloError(message: String, status: Option[Int] = None)

/** One HPE iLO (iLO 4 or later), reached over its REST interface: the Redfish
  * API (/redfish/v1) and, for iLO 4 firmware that predates it, the HP REST
  * API (/rest/v1). `target` is an address or DNS name, or a full http(s) URL.
  * Under `dryRun`, a reset is described through `log` and not sent.
  */
final class Ilo(
    target: String,
    val user: String,
    password: String,
    insecure: Boolean = false,
    cacert: Option[String] = None,
    timeoutSeconds: Int = 30,
    val dryRun: Boolean = false,
    val pollMillis: Long = 3000L,
    val log: String => Unit = println(_)
):
  private val trimmed = target.trim.stripSuffix("/")
  val base: String =
    if trimmed.startsWith("http://") || trimmed.startsWith("https://") then trimmed
    else "https://" + trimmed

  /** What the iLO is called in messages. */
  val name: String = base.split("://", 2).lastOption.getOrElse(base)

  private val auth: String =
    "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(UTF_8))

  private lazy val client: Either[IloError, HttpClient] =
    DeviceAccess
      .httpClient(insecure, cacert, timeoutSeconds)
      .left
      .map(m => IloError(m))

  private var systemPath: Option[String] = None  // the computer system resource
  private var legacy: Boolean = false            // the HP REST API (/rest/v1), not Redfish

  // ---- HTTP ----
  def call(method: String, path: String, body: Option[Json] = None): Either[IloError, Json] =
    client.flatMap { c =>
      val b = HttpRequest
        .newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", auth)
        .header("Accept", "application/json")
      val publisher = body match
        case Some(j) =>
          b.header("Content-Type", "application/json")
          HttpRequest.BodyPublishers.ofString(j.noSpaces)
        case None => HttpRequest.BodyPublishers.noBody()
      try
        val resp = c.send(
          b.method(method, publisher).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        val code = resp.statusCode
        val doc = Ilo.parseBody(resp.body)
        if code == 401 then
          Left(
            IloError(
              s"$name refused the login for user $user (not retried: repeated failures can lock the account)",
              Some(401)
            )
          )
        else if code >= 400 then
          Left(IloError(s"$name: $method $path returned HTTP $code${Ilo.detail(doc)}", Some(code)))
        else Right(doc)
      catch
        case e: SSLHandshakeException =>
          Left(
            IloError(
              s"$name: the certificate is not trusted (${e.getMessage}); use --insecure for a self-signed iLO " +
                "certificate or --cacert FILE"
            )
          )
        case _: HttpTimeoutException =>
          Left(IloError(s"$name: no answer within $timeoutSeconds s"))
        case e: ConnectException =>
          Left(IloError(s"$name: cannot reach the iLO: ${Option(e.getMessage).getOrElse("connection failed")}"))
        case e: IOException =>
          Left(IloError(s"$name: ${Option(e.getMessage).getOrElse(e.getClass.getName)}"))
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(IloError(s"$name: interrupted"))
    }

  // ---- the computer system ----
  private def discover(): Either[IloError, String] =
    systemPath match
      case Some(p) => Right(p)
      case None =>
        val redfish: Either[IloError, Option[String]] =
          call("GET", "/redfish/v1/Systems/") match
            case Right(coll) =>
              Right(coll.hcursor.downField("Members").downArray.get[String]("@odata.id").toOption)
            case Left(e) if e.status.contains(404) => Right(None)
            case Left(e)                           => Left(e)
        redfish.flatMap {
          case Some(p) =>
            systemPath = Some(p)
            Right(p)
          case None =>
            call("GET", "/rest/v1/Systems").flatMap { coll => // iLO 4 firmware before Redfish
              val c = coll.hcursor
              val href = c
                .downField("links").downField("Member").downArray.get[String]("href").toOption
                .orElse(c.downField("Items").downArray.downField("links").downField("self").get[String]("href").toOption)
                .filter(_.nonEmpty)
              href match
                case Some(p) =>
                  systemPath = Some(p)
                  legacy = true
                  Right(p)
                case None =>
                  Left(IloError(s"$name: no computer system found under /redfish/v1 or /rest/v1"))
            }
        }

  def read(): Either[IloError, Json] =
    discover().flatMap(p => call("GET", p))

  def powerState(): Either[IloError, String] =
    read().flatMap { doc =>
      Ilo.stateOf(doc) match
        case "" => Left(IloError(s"$name: the system reports no power state"))
        case s  => Right(s)
    }

  /** The path and body of a reset request: legacy POSTs to the system itself, Redfish to the Reset action. */
  private def resetRequest(doc: Json, system: String, resetType: String): (String, Json) =
    if legacy then
      (
        system,
        Json.obj("Action" -> Json.fromString("Reset"), "ResetType" -> Json.fromString(resetType))
      )
    else
      val action = doc.hcursor.downField("Actions").downField("#ComputerSystem.Reset")
      val path = action
        .get[String]("target")
        .toOption
        .filter(_.nonEmpty)
        .getOrElse(system.stripSuffix("/") + "/Actions/ComputerSystem.Reset/")
      (path, Json.obj("ResetType" -> Json.fromString(resetType)))

  /** Sends a ResetType; Right(false) when --dry-run kept it from being sent. */
  def sendReset(resetType: String): Either[IloError, Boolean] =
    for
      doc <- read()
      system <- discover()
      (path, body) = resetRequest(doc, system, resetType)
      sent <-
        if dryRun then
          log(s"$name: would send POST $path ${body.noSpaces}")
          Right(false)
        else call("POST", path, Some(body)).map(_ => true)
    yield sent

  /** Polls until the power state is `want`, or fails after `timeout` seconds. */
  def waitFor(want: String, timeout: Int): Either[IloError, Unit] =
    val deadline = System.nanoTime() + timeout.toLong * 1000000000L
    @scala.annotation.tailrec
    def loop(): Either[IloError, Unit] =
      val seen = powerState() match
        case Right(s) if s == want => None
        case Right(s)              => Some(s)
        case Left(e)               => Some(s"unreadable (${e.message})")
      seen match
        case None => Right(())
        case Some(last) =>
          if System.nanoTime() >= deadline then
            Left(IloError(s"$name: still $last after $timeout s, wanted $want"))
          else
            Thread.sleep(pollMillis)
            loop()
    loop()

object Ilo:
  /** The reply body as JSON; an empty or non-JSON body is an empty object. */
  def parseBody(text: String): Json =
    val t = text.trim
    if t.startsWith("{") || t.startsWith("[") then parser.parse(t).getOrElse(Json.obj())
    else Json.obj()

  /** ": <message>" from a Redfish or HP REST error reply, or "". */
  def detail(doc: Json): String =
    val err = doc.hcursor.downField("error")
    val first = err.downField("@Message.ExtendedInfo").downArray
    val ext = if first.succeeded then first else err.downField("MessageExtendedInfo").downArray
    val msg =
      ext.get[String]("Message").toOption.filter(_.nonEmpty)
        .orElse(ext.get[String]("MessageId").toOption.filter(_.nonEmpty))
        .orElse(err.get[String]("message").toOption.filter(_.nonEmpty))
    msg.map(": " + _).getOrElse("")

  /** The power state as the iLO reports it, "On" or "Off" ("" when it says nothing). */
  def stateOf(doc: Json): String =
    val c = doc.hcursor
    c.get[String]("PowerState").toOption.filter(_.nonEmpty)
      .orElse(c.get[String]("Power").toOption.filter(_.nonEmpty))
      .map(s => s.trim.toLowerCase.capitalize)
      .getOrElse("")

  /** The ResetTypes the system says it accepts. */
  def allowed(doc: Json): List[String] =
    doc.hcursor
      .downField("Actions").downField("#ComputerSystem.Reset")
      .get[List[String]]("ResetType@Redfish.AllowableValues")
      .getOrElse(Nil)
