// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import io.grpc.*

import orphera.common.Auth

/** Lets a call through only when it carries `token` (see `Auth.requireToken`).
  */
class AuthInterceptor(token: String) extends ServerInterceptor:
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    val provided = Option(headers.get(Auth.TokenKey))
    if Auth.accepts(token, provided) then next.startCall(call, headers)
    else
      call.close(
        Status.UNAUTHENTICATED.withDescription("missing or invalid token"),
        new Metadata()
      )
      new ServerCall.Listener[ReqT] {}
