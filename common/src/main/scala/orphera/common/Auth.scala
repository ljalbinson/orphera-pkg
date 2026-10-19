// SPDX-License-Identifier: Apache-2.0

package orphera.common

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import io.grpc.Metadata

object Auth:
  val TokenKey: Metadata.Key[String] =
    Metadata.Key.of("orphera-token", Metadata.ASCII_STRING_MARSHALLER)

  /** The token used when none is configured. It is in the source code, so it
    * protects nothing: an orchestrator may still send it (with a warning), but
    * an agent refuses to run with it.
    */
  val DevToken: String = "dev-only-insecure-token"

  private val HowToSet =
    "From the orchestrator run: orphera secrets generate orphera/agent-token (once), " +
      "then utils/set-agent-token.sh <this node>."

  /** The token an agent may run with, from its `ORPHERA_TOKEN`. An agent with
    * no token, or with the built-in one, would accept calls from anyone who has
    * read the source, so it is not started; the message says what to do.
    */
  def requireToken(env: Option[String]): Either[String, String] =
    env.filter(_.nonEmpty) match
      case None =>
        Left(
          "Refusing to start: ORPHERA_TOKEN is not set in /etc/orphera-agent/agent.env. " +
            "Without it the agent would accept the built-in development token, which anyone " +
            s"who has the source code knows. $HowToSet"
        )
      case Some(token) if token == DevToken =>
        Left(
          "Refusing to start: ORPHERA_TOKEN in /etc/orphera-agent/agent.env is the built-in " +
            s"development token, which anyone who has the source code knows. $HowToSet"
        )
      case Some(token) => Right(token)

  /** Whether the token a caller sent is the one expected. Compared without
    * stopping at the first difference.
    */
  def accepts(expected: String, provided: Option[String]): Boolean =
    provided.exists(p =>
      MessageDigest.isEqual(p.getBytes(UTF_8), expected.getBytes(UTF_8))
    )
