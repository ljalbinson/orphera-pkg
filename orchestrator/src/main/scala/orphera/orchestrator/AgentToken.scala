// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.Auth

/** The shared token the orchestrator sends to every agent (`ORPHERA_TOKEN` on the
  * agent's side). Where it comes from, first match wins:
  *
  *   1. the environment variable `ORPHERA_TOKEN`
  *   2. the secret store, under `orphera/agent-token`
  *      (`orphera secrets generate orphera/agent-token`)
  *   3. the built-in development token, which is in the source code and therefore
  *      protects nothing; a warning says so, once, on stderr.
  *
  * The token is registered for masking, so it does not appear in the output.
  */
object AgentToken:
  val StoreName = "orphera/agent-token"

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
    else "the built-in development token (no agent token is configured here)"

  lazy val current: String =
    resolve(sys.env.get("ORPHERA_TOKEN"), fromStore()) match
      case Some(token) =>
        Secrets.register(token)
        token
      case None =>
        System.err.println(
          s"warning: no agent token is configured, so the built-in development token is used, " +
            s"which anyone who has the source can use. Run `orphera secrets generate $StoreName`, " +
            "then `utils/set-agent-token.sh <node>...`."
        )
        Auth.SharedToken
