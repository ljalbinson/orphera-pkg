package orphera.agent

import io.grpc.*
import orphera.common.Auth

class AuthInterceptor extends ServerInterceptor:
  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    val provided = Option(headers.get(Auth.TokenKey))
    if provided.contains(Auth.SharedToken) then
      next.startCall(call, headers)
    else
      call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid token"), new Metadata())
      new ServerCall.Listener[ReqT] {}
