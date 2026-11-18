// SPDX-License-Identifier: Apache-2.0

package orphera.common

import java.math.BigInteger
import java.security.cert.X509Certificate

import scala.jdk.CollectionConverters.*

/** What each role may do on one node: the file `/etc/orphera-agent/policy`,
  * made for that node by `orphera issue-policy` from `config/access.yaml`. The
  * Go agent reads the same file and applies the same table (agent-go
  * `policy.go`); keep the two the same.
  *
  * The file, one line each:
  * {{{
  * default_role admin            # the role of a certificate with no role in it
  * role viewer facts,version     # operations, comma-separated
  * role deployer copy,fetch
  * paths deployer /etc/ceph/,/srv/   # limits the files that role's calls may name
  * role admin all
  * }}}
  * `paths` limits a role's copy, check-file, fetch, observe and apply-* calls to
  * the files under those prefixes (Go agent 0.1.212 or later, Scala agent 0.1.218 or
  * later; an older Scala agent does not parse the line and so refuses every call). A node with no policy
  * file allows every call, as before. With one, a call is allowed only to a
  * role that lists an operation containing it; a role not listed, or a
  * certificate that names no valid role, is refused.
  */
final case class Policy(
    defaultRole: String,
    roles: Map[String, Set[String]],
    paths: Map[String, List[String]] = Map.empty
):

  /** Why `role` may not use `path` with a call that names a file, or None: the
    * role has no `paths`, or `path` is one of them or under one, after making
    * it absolute, resolving `..` and following symbolic links, so another name
    * for the same place counts too.
    */
  def pathRefusal(role: String, path: String): Option[String] =
    paths.get(role).filter(_.nonEmpty).flatMap { prefixes =>
      val resolved = Policy.resolvePath(path)
      val allowed = prefixes.exists { pre =>
        val d = Policy.realPath(pre)
        Option(d.getParent).isEmpty || resolved.startsWith(d)
      }
      Option.unless(allowed)(
        s"role '$role' may reach files only under ${prefixes.mkString(", ")}; $path is outside"
      )
    }

  /** Why `role` may not make the call `method` (its bare name, `Install`), or
    * None when it may.
    */
  def refusal(role: String, method: String): Option[String] =
    roles.get(role) match
      case None =>
        Some(s"role '$role' is not in this node's access policy")
      case Some(ops) =>
        if ops.contains(Policy.All) || Policy.allows(ops, method) then None
        else
          val operation = Policy.operationOf(method).getOrElse("(none)")
          Some(
            s"role '$role' may not make the call $method (operation $operation); it may: " +
              ops.toList.sorted.mkString(", ")
          )

object Policy:

  val All = "all"

  /** The operations a policy may name, each a fixed set of agent calls (bare
    * method names). A call in no operation is allowed only by `all`, so a call
    * added to the agent later is refused to every role but `admin` until it is
    * listed here.
    */
  val Operations: Map[String, Set[String]] = Map(
    "facts" -> Set("GatherFacts"),
    "version" -> Set("GetVersion"),
    "uptime" -> Set("GetUptime"),
    "thread-info" -> Set("GetThreadInfo"),
    "check-file" -> Set("CheckFile"),
    "observe" -> Set("ObserveNode"),
    "apply-files" -> Set("ApplyFiles"),
    "apply-access" -> Set("ApplyAccess"),
    "apply-software" -> Set("ApplySoftware"),
    "fetch" -> Set("RemoteFetchFile"),
    "copy" -> Set("CopyFile", "CheckFile"),
    "packages" -> Set(
      "Install",
      "Remove",
      "RunAutoRemove",
      "RunDistUpgrade",
      "InstallDebPackage"
    ),
    "run" -> Set("ExecuteCommand"),
    "reboot" -> Set("TriggerReboot"),
    "shutdown" -> Set("TriggerShutdown"),
    "network" -> Set("ReloadNetwork", "ConfirmNetwork")
  )

  def allows(ops: Set[String], method: String): Boolean =
    ops.exists(op => Operations.get(op).exists(_.contains(method)))

  def operationOf(method: String): Option[String] =
    Operations.toList.sortBy(_._1).collectFirst {
      case (op, methods) if methods.contains(method) => op
    }

  /** Parses the file; Left says what is wrong, and an agent with a file it
    * cannot parse refuses every call rather than allowing them.
    */
  def parse(text: String): Either[String, Policy] =
    val lines = text.linesIterator
      .map(_.takeWhile(_ != '#').trim)
      .filter(_.nonEmpty)
      .toList
    lines
      .foldLeft[Either[String, Policy]](
        Right(Policy("", Map.empty))
      ) { (acc, line) =>
        acc.flatMap { p =>
          line.split("\\s+").toList match
            case "default_role" :: role :: Nil if Operator.validName(role) =>
              Right(p.copy(defaultRole = role))
            case "role" :: name :: ops :: Nil if Operator.validName(name) =>
              val names = ops.split(",").toList.map(_.trim).filter(_.nonEmpty)
              val unknown =
                names.filterNot(n => n == All || Operations.contains(n))
              if names.isEmpty then Left(s"role $name has no operations")
              else if unknown.nonEmpty then
                Left(s"role $name: unknown operation ${unknown.mkString(", ")}")
              else Right(p.copy(roles = p.roles + (name -> names.toSet)))
            case "paths" :: name :: list :: Nil if Operator.validName(name) =>
              val prefixes =
                list.split(",").toList.map(_.trim).filter(_.nonEmpty)
              if !p.roles.contains(name) then
                Left(s"paths for role $name, which is not defined above it")
              else if p.paths.contains(name) then
                Left(s"role $name has two paths lines")
              else if prefixes.isEmpty then Left(s"role $name has no paths")
              else
                prefixes.find(!_.startsWith("/")) match
                  case Some(bad) =>
                    Left(s"role $name: the path $bad is not absolute")
                  case None => Right(p.copy(paths = p.paths + (name -> prefixes)))
            case _ => Left(s"cannot read the policy line: $line")
        }
      }
      .flatMap(p =>
        if p.defaultRole.isEmpty then
          Left("the policy has no default_role line")
        else Right(p)
      )

  /** `path` made absolute, `..` resolved, and symbolic links followed (for a
    * file that does not exist yet, those in its directory).
    */
  def resolvePath(path: String): java.nio.file.Path =
    val abs = java.nio.file.Paths.get(path).toAbsolutePath.normalize
    if java.nio.file.Files.exists(abs) then realPath(abs)
    else
      Option(abs.getParent) match
        case Some(parent) => realPath(parent).resolve(abs.getFileName)
        case None         => abs

  def realPath(path: String): java.nio.file.Path =
    realPath(java.nio.file.Paths.get(path))

  private def realPath(path: java.nio.file.Path): java.nio.file.Path =
    scala.util
      .Try(path.toRealPath())
      .getOrElse(path.toAbsolutePath.normalize)

  /** The serial numbers in a `revoked` file: hexadecimal, one per line, `#`
    * starts a comment. Lower-case, no leading zeros.
    */
  def parseRevoked(text: String): Set[String] =
    text.linesIterator
      .map(_.takeWhile(_ != '#').trim.toLowerCase)
      .filter(_.nonEmpty)
      .map(normaliseSerial)
      .toSet

  def normaliseSerial(hex: String): String =
    scala.util
      .Try(new BigInteger(hex.stripPrefix("0x"), 16).toString(16))
      .getOrElse(hex)

  def serialOf(c: X509Certificate): String = c.getSerialNumber.toString(16)

  /** The role a caller's certificate gives: the OU of an operator certificate;
    * `defaultRole` for one with no OU at all (the orchestrator's own). One with
    * an OU that is not a valid role has none: refused.
    */
  def roleOf(c: X509Certificate, defaultRole: String): Either[String, String] =
    Operator.fromCertificate(c) match
      case Some(op)                                         => Right(op.role)
      case None if hasOu(c.getSubjectX500Principal.getName) =>
        Left(
          s"the client certificate ${c.getSubjectX500Principal.getName} names no valid operator and role"
        )
      case None => Right(defaultRole)

  private def hasOu(dn: String): Boolean =
    scala.util
      .Try(
        new javax.naming.ldap.LdapName(dn).getRdns.asScala
          .exists(_.getType.equalsIgnoreCase("OU"))
      )
      .getOrElse(true)
