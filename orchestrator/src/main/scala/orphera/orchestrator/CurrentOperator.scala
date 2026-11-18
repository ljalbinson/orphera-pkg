// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Path, Paths}
import java.security.cert.{CertificateFactory, X509Certificate}

import scala.util.{Try, Using}

import orphera.common.Operator

/** The operator this orchestrator acts as: the person in `ORPHERA_OPERATOR`,
  * whose certificate (`operators/<name>.crt`, see `orphera issue-client-cert
  * --operator`) it presents to the agents and whose name and role the audit log
  * records. With the variable unset there is no operator, and everything is as
  * before.
  */
object CurrentOperator:

  /** The operator a certificate file names, or why it names none. */
  def fromFile(path: Path): Either[String, Operator] =
    Try(
      Using.resource(Files.newInputStream(path))(in =>
        CertificateFactory.getInstance("X.509").generateCertificate(in)
      )
    ).toEither.left
      .map(e => s"cannot read $path: ${e.getMessage}")
      .flatMap {
        case c: X509Certificate =>
          Operator
            .fromCertificate(c)
            .toRight(
              s"$path does not name an operator and role (it needs CN=<name>, OU=<role>)"
            )
        case _ => Left(s"$path is not an X.509 certificate")
      }

  /** Why `ORPHERA_OPERATOR` cannot be acted on, or None when it is unset or
    * its certificate is there and names that operator. Checked before any
    * command runs: an operator who is named but cannot be proved must not fall
    * back to the shared client certificate.
    */
  def problem: Option[String] =
    Installation.operatorName.flatMap { name =>
      if !Operator.validName(name) then
        Some(
          s"${Operator.Env} is '$name', which is not an operator name " +
            "(lower-case letters, digits, '.', '_' and '-')"
        )
      else if !ClientCert.present then
        Some(
          s"${Operator.Env}=$name, but ${Installation.clientCert} and ${Installation.clientKey} " +
            s"are not both there. Make them with: orphera issue-client-cert --operator $name --role <role>"
        )
      else
        fromFile(Paths.get(Installation.clientCert)) match
          case Left(why)                      => Some(why)
          case Right(op) if op.name != name =>
            Some(
              s"${Installation.clientCert} is for operator ${op.name}, not $name"
            )
          case Right(_) => None
    }

  /** Who is acting, for the audit log; None without `ORPHERA_OPERATOR`. */
  def current: Option[Operator] =
    Installation.operatorName.flatMap(_ =>
      fromFile(Paths.get(Installation.clientCert)).toOption
    )
