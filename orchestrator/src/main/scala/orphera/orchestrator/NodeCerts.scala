// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.SecureRandom

import cats.effect.*

/** A TLS certificate of its own for each agent. The agent package carries
  * none (it used to carry one wildcard key shared by every node).
  *
  * The node's private key is generated on the node and never leaves it: the
  * node sends back only a certificate signing request, which is signed here
  * with the CA in `certs/` (the same CA the orchestrator already trusts, so no
  * orchestrator change is needed), and the signed certificate is sent back.
  * The certificate names exactly the node's inventory `host` (a DNS name, or an
  * IP address, which the wildcard could never cover).
  *
  * Files on the node: `/etc/orphera-agent/tls/server.{key,crt}`. No package
  * owns `tls/`, so installing or upgrading the agent never removes them.
  *
  * The new key and certificate are checked against each other on the node and
  * only then moved into place, so a failure at any step leaves the node's
  * current pair untouched and the agent still running on it.
  */
object NodeCerts:

  val RemoteDir = "/etc/orphera-agent/tls"
  private val CaCert: Path = Paths.get("certs/ca.crt")
  private val CaKey: Path = Paths.get("certs/ca.key")
  private val ValidDays = 825

  /** Whether this orchestrator can sign node certificates at all. */
  def caAvailable: Boolean = Files.isRegularFile(CaCert) && Files.isRegularFile(CaKey)

  def issue(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      _ <- IO.raiseUnless(caAvailable)(
        new RuntimeException(
          s"$CaCert and $CaKey are needed to sign node certificates (make certs DOMAIN=...)"
        )
      )
      csrLines <- SshDeployer.sshCollect(
        node,
        sshUser,
        sshKeyPath,
        newKeyAndCsr(node.host)
      )
      csr <- IO.fromEither(
        pemBlock(csrLines, "CERTIFICATE REQUEST").toRight(
          new RuntimeException(
            s"${node.name}: no certificate request came back from the node: " +
              csrLines.takeRight(5).mkString("; ")
          )
        )
      )
      cert <- sign(node.host, csr)
      _ <- SshDeployer.sshRunWithInput(
        node,
        sshUser,
        sshKeyPath,
        installCert,
        cert,
        onLine
      )
      _ <- onLine(s"issued a certificate for ${node.host} (valid $ValidDays days); agent restarted")
    yield ()

  /** Makes `server.key.new` (mode 0600) and prints a CSR for it. Nothing the
    * agent uses is touched yet.
    */
  private def newKeyAndCsr(host: String): String =
    s"sudo sh -c 'set -e; umask 077; mkdir -p $RemoteDir; " +
      s"openssl req -new -newkey rsa:4096 -nodes -keyout $RemoteDir/server.key.new " +
      s"""-subj "/CN=$host" 2>/dev/null'"""

  /** Reads the certificate from stdin, checks it carries the public half of
    * `server.key.new`, then replaces the live pair and restarts the agent.
    */
  private val installCert: String =
    s"sudo sh -c 'set -e; d=$RemoteDir; cat > $$d/server.crt.new; " +
      "if [ \"$(openssl x509 -noout -pubkey -in $d/server.crt.new)\" != " +
      "\"$(openssl pkey -pubout -in $d/server.key.new)\" ]; then " +
      "rm -f $d/server.crt.new $d/server.key.new; echo \"signed certificate does not match the new key; nothing changed\"; exit 1; fi; " +
      "chmod 644 $d/server.crt.new; mv $d/server.key.new $d/server.key; mv $d/server.crt.new $d/server.crt; " +
      "systemctl restart orphera-agent'"

  private def isIpAddress(host: String): Boolean =
    host.matches("""\d{1,3}(\.\d{1,3}){3}""") || host.contains(':')

  /** Signs the CSR with the CA. The certificate's names come from here, not
    * from the request: exactly the node's inventory host. A random 128-bit
    * serial needs no shared serial file, so nodes can be signed in parallel.
    */
  private def sign(host: String, csr: String): IO[String] =
    IO.blocking {
      val dir = Files.createTempDirectory("orphera-sign")
      try
        val csrFile = dir.resolve("node.csr")
        val extFile = dir.resolve("node.ext")
        val certFile = dir.resolve("node.crt")
        Files.writeString(csrFile, csr)
        val san = if isIpAddress(host) then s"IP:$host" else s"DNS:$host"
        Files.writeString(
          extFile,
          s"""subjectAltName=$san
             |keyUsage=critical,digitalSignature,keyEncipherment
             |extendedKeyUsage=serverAuth
             |""".stripMargin
        )
        val serialBytes = new Array[Byte](16)
        new SecureRandom().nextBytes(serialBytes)
        serialBytes(0) = (serialBytes(0) & 0x7f).toByte
        val serial = "0x" + serialBytes.map(b => f"$b%02x").mkString
        val (code, output) = runLocal(
          List(
            "openssl", "x509", "-req", "-in", csrFile.toString,
            "-CA", CaCert.toString, "-CAkey", CaKey.toString,
            "-set_serial", serial, "-days", ValidDays.toString,
            "-extfile", extFile.toString, "-out", certFile.toString
          )
        )
        if code != 0 then Left(s"signing the certificate for $host failed: ${output.trim}")
        else Right(Files.readString(certFile, UTF_8))
      finally
        Option(dir.toFile.listFiles()).toList.flatten.foreach(_.delete())
        Files.deleteIfExists(dir)
    }.flatMap(r => IO.fromEither(r.left.map(new RuntimeException(_))))

  private def runLocal(command: List[String]): (Int, String) =
    val p = new ProcessBuilder(command*).redirectErrorStream(true).start()
    p.getOutputStream.close()
    val out = new String(p.getInputStream.readAllBytes(), UTF_8)
    (p.waitFor(), out)

  /** The first PEM block of the given type, with its BEGIN/END lines. Other
    * output (ssh notices, warnings) is ignored.
    */
  private[orchestrator] def pemBlock(lines: List[String], kind: String): Option[String] =
    val begin = s"-----BEGIN $kind-----"
    val end = s"-----END $kind-----"
    val fromBegin = lines.map(_.trim).dropWhile(_ != begin)
    val body = fromBegin.takeWhile(_ != end)
    if fromBegin.isEmpty || body.length == fromBegin.length then None
    else Some((body :+ end).mkString("", "\n", "\n"))
