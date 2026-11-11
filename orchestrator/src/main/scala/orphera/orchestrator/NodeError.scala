// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.grpc.Status

/** How a failed call to an agent is reported. `unauthenticated` means the agent
  * answered and refused this orchestrator's token, which is fixed by setting
  * the token on that node; `unsupported` means it answered but is too old to
  * know the call; `unreachable` means nothing usable came back (no route, no
  * listener, a certificate the orchestrator does not trust), which is not.
  */
object NodeError:

  /** True when the agent answered and rejected the token. */
  def isUnauthenticated(e: Throwable): Boolean =
    Status.fromThrowable(e).getCode == Status.Code.UNAUTHENTICATED

  // The TLS alerts a node sends when it requires a client certificate and got
  // none (Java and BoringSSL spell them differently), and when it got one it
  // does not trust. Alerts come from the node, so neither is the orchestrator
  // distrusting the node's certificate, which fails locally with a different
  // message.
  private val ClientCertRequiredAlerts =
    List("CERTIFICATE_REQUIRED", "certificate_required", "certificate required")
  private val ClientCertRejectedAlerts =
    List(
      "ALERT_UNKNOWN_CA",
      "ALERT_BAD_CERTIFICATE",
      "ALERT_CERTIFICATE_UNKNOWN",
      "Received fatal alert: unknown_ca",
      "Received fatal alert: bad_certificate",
      "Received fatal alert: certificate_unknown"
    )

  private def messages(e: Throwable): List[String] =
    Iterator
      .iterate(Option(e))(_.flatMap(c => Option(c.getCause)))
      .take(10)
      .takeWhile(_.isDefined)
      .flatten
      .flatMap(t => Option(t.getMessage))
      .toList

  private def tlsAlert(e: Throwable, alerts: List[String]): Boolean =
    messages(e).exists(m => alerts.exists(m.contains))

  /** True when the call was not made because this orchestrator has no agent
    * token (it is not the node's fault).
    */
  private def notConfigured(e: Throwable): Boolean =
    Iterator
      .iterate(Option(e))(_.flatMap(c => Option(c.getCause)))
      .take(10)
      .takeWhile(_.isDefined)
      .flatten
      .exists {
        case _: AgentToken.NotConfiguredError => true
        case _                                => false
      }

  /** True when the agent answered but does not know the call, i.e. it is older
    * than the orchestrator.
    */
  def isUnimplemented(e: Throwable): Boolean =
    Status.fromThrowable(e).getCode == Status.Code.UNIMPLEMENTED

  /** One line for a node's output, starting with `unauthenticated`,
    * `unsupported` or `unreachable`.
    */
  def line(e: Throwable): String =
    if notConfigured(e) then AgentToken.NotConfigured
    else if tlsAlert(e, ClientCertRequiredAlerts) then
      if ClientCert.present then
        "client certificate required: the node requires mutual TLS, but this orchestrator's " +
          s"certificate (${Installation.clientCert}) was not sent; check that it and its key are readable"
      else
        "client certificate required: the node requires mutual TLS, and this orchestrator has no " +
          "client certificate (orphera issue-client-cert, or copy certs/client.crt and client.key " +
          "from an orchestrator that has them)"
    else if tlsAlert(e, ClientCertRejectedAlerts) then
      "client certificate rejected: the node requires mutual TLS and did not accept this " +
        s"orchestrator's certificate (${Installation.clientCert}); it must be signed by the CA the node " +
        "has in /etc/orphera-agent/tls/ca.crt (orphera issue-client-cert --force with that CA)"
    else if isUnauthenticated(e) then
      "unauthenticated: the node rejected this orchestrator's token " +
        "(utils/set-agent-token.sh <node> sets it)"
    else if isUnimplemented(e) then
      "unsupported: this agent is older than the orchestrator and does not know the " +
        "command (upgrade it with deploy-agent)"
    else s"unreachable: ${describe(e)}"

  /** The gRPC status (e.g. `UNAVAILABLE: io exception`) plus the underlying
    * cause when there is one.
    */
  def describe(e: Throwable): String =
    val top = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    Option(e.getCause)
      .flatMap(c => Option(c.getMessage).map(causeText(c, _)))
      .filterNot(top.contains)
      .fold(top)(c => s"$top ($c)")

  /** The cause's message, with a name for what went wrong where the network
    * library left it out: Netty can report a failed connection as
    * `null: host/address:port`, the `null` being the underlying exception's
    * missing message.
    */
  private def causeText(cause: Throwable, msg: String): String =
    if msg == "null" then networkReason(cause)
    else if msg.startsWith("null: ") then
      s"${networkReason(cause)}: ${msg.stripPrefix("null: ")}"
    else msg

  private def networkReason(cause: Throwable): String =
    cause match
      case _: java.net.NoRouteToHostException => "no route to host"
      // with no message, a refused connection and a host that is down look
      // the same here
      case _: java.net.ConnectException       => "could not connect"
      case _: java.net.UnknownHostException   => "unknown host"
      case _: java.net.SocketTimeoutException => "timed out"
      case other                              => other.getClass.getSimpleName
