// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.io.FileInputStream
import java.net.Socket
import java.net.http.HttpClient
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, SecureRandom}
import java.time.Duration
import javax.net.ssl.{
  SSLContext,
  SSLEngine,
  TrustManager,
  TrustManagerFactory,
  X509ExtendedTrustManager
}

/** Accepts any certificate (a switch's or an iLO's own, self-signed). An
  * extended trust manager, so the JDK does not add its host name check on top.
  */
private object DeviceTrustAll extends X509ExtendedTrustManager:
  def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
  def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
  def checkClientTrusted(
      c: Array[X509Certificate],
      a: String,
      s: Socket
  ): Unit = ()
  def checkServerTrusted(
      c: Array[X509Certificate],
      a: String,
      s: Socket
  ): Unit = ()
  def checkClientTrusted(
      c: Array[X509Certificate],
      a: String,
      e: SSLEngine
  ): Unit = ()
  def checkServerTrusted(
      c: Array[X509Certificate],
      a: String,
      e: SSLEngine
  ): Unit = ()
  def getAcceptedIssuers: Array[X509Certificate] = Array.empty

/** What reaching a device over HTTPS has in common, for the iLO and OS10 tools:
  * its certificate, how the HTTP client is made, and where its password comes
  * from.
  */
object DeviceAccess:

  /** --insecure: any certificate. --cacert FILE: only that one. Otherwise the
    * system's.
    */
  def tlsContext(
      insecure: Boolean,
      cacert: Option[String]
  ): Either[String, SSLContext] =
    if insecure then
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, Array[TrustManager](DeviceTrustAll), SecureRandom())
      Right(ctx)
    else
      cacert match
        case None       => Right(SSLContext.getDefault)
        case Some(path) =>
          try
            val in = FileInputStream(path)
            val cert =
              try
                CertificateFactory.getInstance("X.509").generateCertificate(in)
              finally in.close()
            val ks = KeyStore.getInstance(KeyStore.getDefaultType)
            ks.load(null, null)
            ks.setCertificateEntry("device", cert)
            val tmf = TrustManagerFactory.getInstance(
              TrustManagerFactory.getDefaultAlgorithm
            )
            tmf.init(ks)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tmf.getTrustManagers, SecureRandom())
            Right(ctx)
          catch
            case e: Exception =>
              Left(s"cannot use the certificate $path: ${e.getMessage}")

  def httpClient(
      insecure: Boolean,
      cacert: Option[String],
      timeoutSeconds: Int
  ): Either[String, HttpClient] =
    tlsContext(insecure, cacert).map(ctx =>
      HttpClient
        .newBuilder()
        .sslContext(ctx)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    )

  /** The secret that holds a device's password: its address or name as given,
    * or the host of a URL.
    */
  def secretKey(target: String): String =
    val t = target.trim.stripSuffix("/")
    if t.contains("://") then
      scala.util
        .Try(java.net.URI(t).getHost)
        .toOption
        .filter(h => h != null && h.nonEmpty)
        .getOrElse(t)
    else t

  /** The password for one device: the environment variable, --password-file,
    * the secret (--secret NAME, or the one named after the device as typed), or
    * a prompt on a terminal. Never from the command line.
    */
  def password(
      env: Map[String, String],
      envVar: String,
      passwordFile: Option[String],
      secret: Option[String],
      target: String,
      user: String,
      store: () => Either[String, SecretStore] = () => SecretStore.default()
  ): Either[String, String] =
    env.get(envVar).filter(_.nonEmpty) match
      case Some(p) => Right(p)
      case None    =>
        passwordFile match
          case Some(f) =>
            try
              Right(
                java.nio.file.Files
                  .readString(java.nio.file.Paths.get(f))
                  .stripSuffix("\n")
                  .stripSuffix("\r")
              )
            catch case e: Exception => Left(s"cannot read $f: ${e.getMessage}")
          case None =>
            val name = secret.getOrElse(secretKey(target))
            val (value, why) = store().flatMap(_.get(name)) match
              case Right(Some(v)) if v.nonEmpty => (Some(v), "")
              case Right(_)  => (None, s"no secret called $name")
              case Left(msg) => (None, msg)
            value match
              case Some(v)                          => Right(v)
              case None if System.console() != null =>
                val pw = System
                  .console()
                  .readPassword(
                    s"password for $user on $target (no secret $name: $why): "
                  )
                if pw == null then Left("no password given")
                else Right(String(pw))
              case None =>
                Left(
                  s"no password for $target: no secret `$name` ($why); store it with: echo 'the password' | orphera secrets set $name"
                )
