// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.file.{Files, Path, Paths}
import java.security.cert.X509Certificate
import java.time.Instant

import scala.util.Try

import io.grpc.*

import orphera.common.{Auth, Operator, Policy}

/** Lets a call through only when it carries `token` (see `Auth.requireToken`)
  * and, on a node that has a policy file, only when the caller's role may make
  * it (see `Policy`, `AuthInterceptor.decide`). The caller is the operator and
  * role in the client certificate the connection presented, so a node with a
  * policy must also require client certificates (`tls/ca.crt`). Each call is
  * logged with who made it and whether it was allowed.
  */
class AuthInterceptor(
    token: String,
    policyPath: Path = AuthInterceptor.PolicyPath,
    revokedPath: Path = AuthInterceptor.RevokedPath
) extends ServerInterceptor:
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    // The role reaches the handlers in this header; a caller cannot supply it.
    headers.discardAll(PathLimits.RoleKey)
    val provided = Option(headers.get(Auth.TokenKey))
    if !Auth.accepts(token, provided) then
      call.close(
        Status.UNAUTHENTICATED.withDescription("missing or invalid token"),
        new Metadata()
      )
      new ServerCall.Listener[ReqT] {}
    else
      val method = call.getMethodDescriptor.getBareMethodName
      val decision = AuthInterceptor.decide(
        AuthInterceptor.readPolicy(policyPath, revokedPath),
        AuthInterceptor.peerCertificate(call.getAttributes),
        method
      )
      decision match
        case AuthInterceptor.Decision.Allow(who, role) =>
          println(s"${Instant.now} $method $who allowed")
          role.foreach(headers.put(PathLimits.RoleKey, _))
          next.startCall(call, headers)
        case AuthInterceptor.Decision.Refuse(who, why) =>
          println(s"${Instant.now} $method $who REFUSED: $why")
          call.close(
            Status.PERMISSION_DENIED.withDescription(s"refused: $why"),
            new Metadata()
          )
          new ServerCall.Listener[ReqT] {}

object AuthInterceptor:

  /** Relative to /etc/orphera-agent, the service's working directory. */
  val PolicyPath: Path = Paths.get("policy")
  val RevokedPath: Path = Paths.get("revoked")

  enum Decision:
    case Allow(who: String, role: Option[String] = None)
    case Refuse(who: String, why: String)

  /** The policy file and the revoked list, as text; None when the node has no
    * policy (every call is then allowed, as before). A policy that is there but
    * cannot be read is returned as an empty text, which does not parse, so
    * every call is refused rather than allowed.
    */
  def readPolicy(
      policyPath: Path,
      revokedPath: Path
  ): Option[(String, Option[String])] =
    if !Files.exists(policyPath) then None
    else
      val policy = Try(Files.readString(policyPath)).getOrElse("")
      val revoked =
        if Files.exists(revokedPath) then
          Try(Files.readString(revokedPath)).toOption
        else Some("")
      revoked match
        case Some(r) => Some((policy, Some(r)))
        case None    => Some(("", None))

  /** The certificate the caller presented over TLS. The connection has verified
    * it against the CA; it cannot be set by the caller as a header could.
    */
  def peerCertificate(attributes: Attributes): Option[X509Certificate] =
    Option(attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
      .flatMap(session => Try(session.getPeerCertificates.toList).toOption)
      .flatMap(_.headOption)
      .collect { case c: X509Certificate => c }

  def callerOf(attributes: Attributes): String =
    peerCertificate(attributes).fold("no client certificate")(Operator.describe)

  /** Whether the call `method` (its bare name) may go ahead. */
  def decide(
      policy: Option[(String, Option[String])],
      cert: Option[X509Certificate],
      method: String
  ): Decision =
    val who = cert.fold("no client certificate")(Operator.describe)
    policy match
      case None                      => Decision.Allow(who)
      case Some((text, revokedText)) =>
        Policy.parse(text) match
          case Left(why) =>
            Decision.Refuse(
              who,
              s"this node's access policy cannot be read ($why); every call is refused until it is fixed"
            )
          case Right(p) =>
            cert match
              case None =>
                Decision.Refuse(who, "no client certificate, so no role")
              case Some(c) =>
                val revoked =
                  revokedText.map(Policy.parseRevoked).getOrElse(Set.empty)
                if revoked.contains(Policy.serialOf(c)) then
                  Decision.Refuse(
                    who,
                    "the client certificate has been revoked"
                  )
                else
                  Policy.roleOf(c, p.defaultRole) match
                    case Left(why)   => Decision.Refuse(who, why)
                    case Right(role) =>
                      p.refusal(role, method) match
                        case None =>
                          Decision.Allow(s"$who as $role", Some(role))
                        case Some(why) => Decision.Refuse(s"$who as $role", why)
