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

  /** True when the agent answered but does not know the call, i.e. it is older
    * than the orchestrator.
    */
  def isUnimplemented(e: Throwable): Boolean =
    Status.fromThrowable(e).getCode == Status.Code.UNIMPLEMENTED

  /** One line for a node's output, starting with `unauthenticated`,
    * `unsupported` or `unreachable`.
    */
  def line(e: Throwable): String =
    if isUnauthenticated(e) then
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
      .flatMap(c => Option(c.getMessage))
      .filterNot(top.contains)
      .fold(top)(c => s"$top ($c)")
