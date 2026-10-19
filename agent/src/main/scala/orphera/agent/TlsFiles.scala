// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.io.File

/** The agent's TLS certificate and key, relative to /etc/orphera-agent (the
  * service's working directory). Each node has its own in `tls/`, issued over
  * SSH by `orphera issue-certs` or `orphera bootstrap`; the package no longer
  * ships one. `certs/` is still read if present, for an install that predates
  * per-node certificates and has not been upgraded or issued one yet.
  */
object TlsFiles:

  private val perNode = (new File("tls/server.crt"), new File("tls/server.key"))
  private val legacy = (new File("certs/server.crt"), new File("certs/server.key"))

  private def present(pair: (File, File)): Boolean =
    pair._1.isFile && pair._2.isFile

  def perNodePresent: Boolean = present(perNode)

  def choose: Option[(File, File)] =
    if present(perNode) then Some(perNode)
    else if present(legacy) then Some(legacy)
    else None

  val Missing: String =
    "No TLS certificate in /etc/orphera-agent/tls: this node has not been issued one. " +
      "From the orchestrator run: orphera issue-certs --nodes <this node> --ssh-user <user>"
