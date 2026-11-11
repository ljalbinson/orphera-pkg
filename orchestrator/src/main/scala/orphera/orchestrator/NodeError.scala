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
