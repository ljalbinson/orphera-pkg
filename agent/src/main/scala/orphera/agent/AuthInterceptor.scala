// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.security.cert.X509Certificate
import java.time.Instant

import scala.util.Try

import io.grpc.*

import orphera.common.{Auth, Operator}

/** Lets a call through only when it carries `token` (see `Auth.requireToken`).
  * Each call let through is logged with who made it: the operator and role in
  * the client certificate when the agent requires one (`tls/ca.crt`), see
  * `callerOf`. The log is for the record only; no call is refused on it.
  */
class AuthInterceptor(token: String) extends ServerInterceptor:
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    val provided = Option(headers.get(Auth.TokenKey))
    if Auth.accepts(token, provided) then
      println(
        s"${Instant.now} ${call.getMethodDescriptor.getFullMethodName} ${AuthInterceptor.callerOf(call.getAttributes)}"
      )
      next.startCall(call, headers)
    else
      call.close(
        Status.UNAUTHENTICATED.withDescription("missing or invalid token"),
        new Metadata()
      )
      new ServerCall.Listener[ReqT] {}

object AuthInterceptor:

  /** The caller named by the certificate it presented over TLS. The connection
    * has verified it against the CA; it cannot be set by the caller as a header
    * could.
    */
  def callerOf(attributes: Attributes): String =
    Option(attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
      .flatMap(session => Try(session.getPeerCertificates.toList).toOption)
      .flatMap(_.headOption)
      .collect { case c: X509Certificate => Operator.describe(c) }
      .getOrElse("no client certificate")
