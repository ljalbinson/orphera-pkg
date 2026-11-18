// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.file.Path

import cats.effect.*

import io.grpc.{Metadata, Status}

import orphera.common.Policy

/** A role's `paths` (see `Policy`): the calls that name a file refuse a path
  * outside them, with `PERMISSION_DENIED`. The role is the one the interceptor
  * found in the caller's certificate and put in the request headers (a caller's
  * own header of that name is removed first); a node with no policy, or a
  * caller with no role, is not limited.
  */
object PathLimits:

  val RoleKey: Metadata.Key[String] =
    Metadata.Key.of("orphera-caller-role", Metadata.ASCII_STRING_MARSHALLER)

  /** Why the caller of `headers` may not use `path`, or None. Reads the policy
    * as it is now, as the interceptor does for each call.
    */
  def refusal(
      headers: Metadata,
      path: String,
      policyPath: Path = AuthInterceptor.PolicyPath,
      revokedPath: Path = AuthInterceptor.RevokedPath
  ): Option[String] =
    for
      role <- Option(headers.get(RoleKey))
      (text, _) <- AuthInterceptor.readPolicy(policyPath, revokedPath)
      policy <- Policy.parse(text).toOption
      why <- policy.pathRefusal(role, path)
    yield why

  /** The call's failure when any of `paths` is refused: nothing is changed or
    * read before it.
    */
  def check(headers: Metadata, paths: List[String]): IO[Unit] =
    paths.view
      .flatMap(p => refusal(headers, p).map(p -> _))
      .headOption match
      case None           => IO.unit
      case Some((_, why)) =>
        println(s"path as ${headers.get(RoleKey)} REFUSED: $why")
        IO.raiseError(
          Status.PERMISSION_DENIED
            .withDescription(s"refused: $why")
            .asRuntimeException()
        )
