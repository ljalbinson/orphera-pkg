// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.FileInputStream
import java.net.{InetSocketAddress, Socket}
import java.security.MessageDigest
import java.security.cert.*
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SSLContext, SSLSocket, X509TrustManager}

import scala.annotation.unused
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

import cats.effect.*
import cats.syntax.all.*

/** `orphera cert-info`: which certificate each agent actually presents.
  *
  * It connects to the agent's port and reads the certificate from the TLS
  * handshake, rather than reading files on the node, so it reports what the
  * orchestrator really sees: a per-node or the wildcard certificate, whether
  * it names the node's host, whether it chains to `certs/ca.crt`, and when it
  * expires. It needs neither SSH nor the token. The certificate is recorded
  * before it is judged, so an untrusted or mismatched one is still described
  * rather than only refused.
  */
object CertInfo:

  private val CaCert = "certs/ca.crt"
  private val TimeoutMillis = 5000
  val WarnDays = 30

  enum Kind:
    case PerNode, Wildcard, Other

  final case class Report(
      kind: Kind,
      subject: String,
      names: List[String],
      issuer: String,
      notAfter: Instant,
      serialHex: String,
      sha256: String,
      namesHost: Boolean,
      trusted: Either[String, Unit]
  ):
    def daysLeft(now: Instant): Long = ChronoUnit.DAYS.between(now, notAfter)
    def ok(now: Instant): Boolean =
      namesHost && trusted.isRight && daysLeft(now) > 0

  def inspect(node: Node): IO[Report] =
    IO.blocking(presentedChain(node.host, node.port)).flatMap {
      case Nil  => IO.raiseError(new RuntimeException("the agent presented no certificate"))
      case leaf :: rest => IO.pure(describe(node.host, leaf, leaf :: rest))
    }

  /** Runs the TLS handshake and returns the chain the server sent. */
  private def presentedChain(host: String, port: Int): List[X509Certificate] =
    val seen = new AtomicReference[List[X509Certificate]](Nil)
    val recordAll = new X509TrustManager:
      def checkClientTrusted(
          @unused chain: Array[X509Certificate],
          @unused authType: String
      ): Unit = ()
      def checkServerTrusted(
          chain: Array[X509Certificate],
          @unused authType: String
      ): Unit =
        seen.set(chain.toList)
      def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array(recordAll), null)
    Using.resource(new Socket()) { raw =>
      raw.connect(new InetSocketAddress(host, port), TimeoutMillis)
      raw.setSoTimeout(TimeoutMillis)
      val tls = ctx.getSocketFactory.createSocket(raw, host, port, true) match
        case s: SSLSocket => s
        case other =>
          throw new IllegalStateException(s"not a TLS socket: ${other.getClass}")
      val params = tls.getSSLParameters
      params.setApplicationProtocols(Array("h2"))
      tls.setSSLParameters(params)
      tls.startHandshake()
      tls.close()
    }
    seen.get

  private def describe(
      host: String,
      leaf: X509Certificate,
      chain: List[X509Certificate]
  ): Report =
    val names = subjectAltNames(leaf)
    val kind =
      if names.exists(_.startsWith("*.")) then Kind.Wildcard
      else if names.exists(_.equalsIgnoreCase(host)) then Kind.PerNode
      else Kind.Other
    Report(
      kind = kind,
      subject = leaf.getSubjectX500Principal.getName,
      names = names,
      issuer = leaf.getIssuerX500Principal.getName,
      notAfter = leaf.getNotAfter.toInstant,
      serialHex = leaf.getSerialNumber.toString(16),
      sha256 = MessageDigest
        .getInstance("SHA-256")
        .digest(leaf.getEncoded)
        .map(b => f"$b%02X")
        .mkString(":"),
      namesHost = names.exists(n => nameMatches(n, host)),
      trusted = chainsToCa(chain)
    )

  /** DNS and IP entries of the certificate's subjectAltName. */
  private def subjectAltNames(cert: X509Certificate): List[String] =
    Option(cert.getSubjectAlternativeNames).toList
      .flatMap(_.asScala.toList)
      .flatMap { entry =>
        entry.asScala.toList match
          case (kind: Integer) :: (value: String) :: Nil
              if kind.intValue == 2 || kind.intValue == 7 =>
            List(value)
          case _ => Nil
      }

  /** Hostname match as the gRPC client does it: exact, or a wildcard covering
    * exactly one leading label.
    */
  private def nameMatches(name: String, host: String): Boolean =
    if name.startsWith("*.") then
      val suffix = name.drop(1).toLowerCase
      val h = host.toLowerCase
      h.endsWith(suffix) && !h.dropRight(suffix.length).contains('.') &&
      h.length > suffix.length
    else name.equalsIgnoreCase(host)

  private def chainsToCa(chain: List[X509Certificate]): Either[String, Unit] =
    Try {
      val factory = CertificateFactory.getInstance("X.509")
      val ca = Using.resource(new FileInputStream(CaCert)) { in =>
        factory.generateCertificate(in) match
          case x: X509Certificate => x
          case _ => throw new IllegalStateException(s"$CaCert is not an X.509 certificate")
      }
      val params = new PKIXParameters(java.util.Set.of(new TrustAnchor(ca, null)))
      params.setRevocationEnabled(false)
      CertPathValidator
        .getInstance("PKIX")
        .validate(factory.generateCertPath(chain.asJava), params)
    }.toEither.void.leftMap(e => Option(e.getMessage).getOrElse(e.toString))

  def render(name: String, r: Report, now: Instant): String =
    val kind = r.kind match
      case Kind.PerNode  => "per-node"
      case Kind.Wildcard => "wildcard"
      case Kind.Other    => "other   "
    val days = r.daysLeft(now)
    val expiry =
      s"expires ${r.notAfter.atZone(ZoneOffset.UTC).toLocalDate} (${days}d)"
    val problems = List(
      Option.when(!r.namesHost)("does not name this host"),
      r.trusted.left.toOption.map(e => s"not signed by $CaCert: $e"),
      Option.when(days <= 0)("EXPIRED"),
      Option.when(days > 0 && days <= WarnDays)("expires soon")
    ).flatten
    s"[$name] $kind ${r.names.mkString(",")}  $expiry  serial ${r.serialHex.take(12)}  " +
      s"sha256 ${r.sha256.take(23)}" +
      (if problems.isEmpty then "" else s"  !! ${problems.mkString("; ")}")
