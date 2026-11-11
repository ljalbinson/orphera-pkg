// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.IO

/** The shared token the orchestrator sends to every agent (`ORPHERA_TOKEN` on
  * the agent's side). Where it comes from, first match wins:
  *
  *   1. the environment variable `ORPHERA_TOKEN`
  *   2. the secret store, under `orphera/agent-token` (`orphera secrets
  *      generate orphera/agent-token`)
  *
  * With neither, a call to an agent fails with NotConfigured. (It used to fall
  * back to the built-in development token, with a warning; every agent refuses
  * that token, so the fallback only turned a clear error into a rejected one.)
  *
  * The token is registered for masking, so it does not appear in the output.
  */
object AgentToken:
  val StoreName = "orphera/agent-token"

  val NotConfigured: String =
    "no agent token is configured here: run `orphera secrets generate " +
      s"$StoreName` (or set ORPHERA_TOKEN), then `utils/set-agent-token.sh <node>...` " +
      "for nodes that do not have it yet"

  /** The error a call to an agent fails with when there is no token. */
  final class NotConfiguredError extends RuntimeException(NotConfigured)

  /** The first of the sources that has a non-empty value. Pure, for testing. */
  def resolve(env: Option[String], store: => Option[String]): Option[String] =
    env.filter(_.nonEmpty).orElse(store.filter(_.nonEmpty))

  private def fromStore(): Option[String] =
    SecretStore.default().toOption.flatMap(_.get(StoreName).toOption.flatten)

  /** Where `current` comes from, for a log line. */
  def describe: String =
    if sys.env.get("ORPHERA_TOKEN").exists(_.nonEmpty) then
      "this orchestrator's ORPHERA_TOKEN"
    else if fromStore().exists(_.nonEmpty) then s"the secret store ($StoreName)"
    else "nothing (no agent token is configured here)"

  /** The token, if one is configured. */
  lazy val configured: Option[String] =
    resolve(sys.env.get("ORPHERA_TOKEN"), fromStore()).map { token =>
      Secrets.register(token)
      token
    }

  /** Fails, with NotConfigured, when no token is configured: before a run that
    * would otherwise fail the same way on every node.
    */
  def requireConfigured: IO[Unit] =
    IO.raiseWhen(configured.isEmpty)(new NotConfiguredError)

  /** The token; throws NotConfigured when there is none. Read inside the IO
    * that calls an agent, so the error is that call's.
    */
  def current: String =
    configured.getOrElse(throw new NotConfiguredError)
