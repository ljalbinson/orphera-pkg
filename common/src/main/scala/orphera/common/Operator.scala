// SPDX-License-Identifier: Apache-2.0

package orphera.common

import java.security.cert.X509Certificate
import javax.naming.ldap.LdapName

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Who is calling: a person (`name`) acting in a `role`, taken from the client
  * certificate they present, whose subject is `CN=<name>, OU=<role>` (made by
  * `orphera issue-client-cert --operator <name> --role <role>`). The
  * orchestrator records it in its audit log and an agent in its own log.
  * Nothing is refused on it yet.
  */
final case class Operator(name: String, role: String)

object Operator:

  /** The variable naming the operator the orchestrator acts as. */
  val Env = "ORPHERA_OPERATOR"

  /** A name or role is lower-case letters, digits, '.', '_' and '-', starting
    * with a letter or digit, at most 64 characters: safe as a file name and in
    * a certificate subject.
    */
  def validName(s: String): Boolean =
    s.matches("[a-z0-9][a-z0-9._-]{0,63}")

  /** The `-subj` argument for openssl. */
  def subject(o: Operator): String = s"/CN=${o.name}/OU=${o.role}"

  /** The operator in a distinguished name such as `CN=alice,OU=deployer`, if it
    * has a valid CN and OU.
    */
  def fromDn(dn: String): Option[Operator] =
    Try(new LdapName(dn).getRdns.asScala.toList).toOption.flatMap { rdns =>
      def value(kind: String): Option[String] =
        rdns.find(_.getType.equalsIgnoreCase(kind)).map(_.getValue.toString)
      for
        n <- value("CN")
        r <- value("OU")
        if validName(n) && validName(r)
      yield Operator(n, r)
    }

  def fromCertificate(c: X509Certificate): Option[Operator] =
    fromDn(c.getSubjectX500Principal.getName)

  /** How a caller is named in the agent's log. */
  def describe(c: X509Certificate): String =
    fromCertificate(c) match
      case Some(o) => s"operator=${o.name} role=${o.role}"
      case None    => s"client=${c.getSubjectX500Principal.getName}"
