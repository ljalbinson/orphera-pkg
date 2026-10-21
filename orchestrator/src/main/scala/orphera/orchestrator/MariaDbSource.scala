// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.net.URI
import java.nio.file.{Files, Paths}
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

import io.circe.*
import io.circe.yaml.parser

/** Reads the inventory and the per-VM configs from the MariaDB tables, behind
  * the same `-I` and `-c` options (and environment variables) as a file:
  *
  * {{{
  * orphera -I mariadb://orphera_ro@dbhost.example.com/orphera/inventory \
  *         -c mariadb://orphera_ro@dbhost.example.com/orphera/config  version
  * }}}
  *
  * `--config tst3` then means `<config-dir>/tst3.yaml`, as for a directory,
  * and the part after the last `/` names the config. The port defaults to
  * 3306.
  *
  * Read-only: the account should only be able to `SELECT` (the role
  * `orphera_reader` in schemas/mariadb/002-roles.sql). The connection always
  * verifies the server certificate and its host name against the Orphera CA
  * (`certs/ca.crt`, or the file named by `ORPHERA_DB_CA`); there is no way to
  * turn that off. The password is the secret `orphera/inventory-db-reader`,
  * which must already exist: unlike other secrets it is never made on first
  * use.
  *
  * The rows become the same documents the files hold (see `DbDocuments`) and
  * are handed to the YAML readers as JSON, which is YAML, so nothing else
  * knows where the data came from. The first read of a database in a run
  * fetches the tables; later reads in the same run use that copy.
  */
object MariaDbSource:

  val Scheme = "mariadb"
  val PasswordSecret = "orphera/inventory-db-reader"
  val DefaultPort = 3306

  sealed trait What
  case object InventoryDoc extends What
  final case class ConfigDoc(name: String) extends What

  final case class Target(
      user: String,
      host: String,
      port: Int,
      database: String,
      what: What
  )

  private val Usage =
    "expected mariadb://<user>@<host>[:<port>]/<database>/inventory " +
      "or mariadb://<user>@<host>[:<port>]/<database>/config/<name>.yaml"

  private val Plain = "^[A-Za-z0-9_.-]+$".r

  /** Splits a location into the server, the database and what is wanted. */
  def parse(location: String): Either[String, Target] =
    scala.util
      .Try(new URI(location))
      .toEither
      .left
      .map(e => s"not a valid URI: $location (${e.getMessage})")
      .flatMap(uri => fromUri(location, uri))

  private def fromUri(location: String, uri: URI): Either[String, Target] =
    val user = Option(uri.getUserInfo).getOrElse("")
    val host = Option(uri.getHost).getOrElse("")
    val port = if uri.getPort > 0 then uri.getPort else DefaultPort
    val segments =
      Option(uri.getPath).getOrElse("").split("/").toList.filter(_.nonEmpty)
    if user.isEmpty || user.contains(":") then
      Left(
        s"$location: give the user as mariadb://<user>@<host>/... " +
          s"(the password comes from the secret $PasswordSecret, not the URI)"
      )
    else if host.isEmpty then Left(s"$location: no host. $Usage")
    else
      segments match
        case db :: ("inventory" | "inventory.yaml") :: Nil =>
          checked(location, user, host, port, db, InventoryDoc)
        case db :: "config" :: file :: Nil =>
          checked(location, user, host, port, db, ConfigDoc(stripExtension(file)))
        case _ => Left(s"$location: $Usage")

  private def stripExtension(file: String): String =
    if file.endsWith(".yaml") then file.stripSuffix(".yaml")
    else if file.endsWith(".yml") then file.stripSuffix(".yml")
    else file

  private def checked(
      location: String,
      user: String,
      host: String,
      port: Int,
      db: String,
      what: What
  ): Either[String, Target] =
    val named: List[String] = what match
      case ConfigDoc(name) => List(name)
      case InventoryDoc    => Nil
    if (List(user, host, db) ++ named).forall(p => Plain.matches(p)) then
      Right(Target(user, host, port, db, what))
    else
      Left(
        s"$location: the user, host, database and name may only contain " +
          "letters, digits, '.', '_' and '-'"
      )

  // ---- the registered reader ----------------------------------------------

  private val cache = new ConcurrentHashMap[String, DbDocuments.Rows]()

  /** What `Sources.read` calls for a `mariadb:` location. */
  def read(location: String): Either[String, String] =
    for
      target <- parse(location)
      rows <- rowsFor(target)
      json <- document(rows, target.what)
    yield json.noSpaces

  /** The document a location asks for, from the rows. Pure. */
  def document(
      rows: DbDocuments.Rows,
      what: What
  ): Either[String, Json] =
    what match
      case InventoryDoc => Right(DbDocuments.inventory(rows))
      case ConfigDoc(name) => DbDocuments.config(rows, name)

  private def rowsFor(t: Target): Either[String, DbDocuments.Rows] =
    val tables = t.what match
      case InventoryDoc => DbDocuments.InventoryTables
      case ConfigDoc(_) => DbDocuments.ConfigTables
    val key = s"${t.user}@${t.host}:${t.port}/${t.database}/${tables.map(_._1).mkString(",")}"
    Option(cache.get(key)) match
      case Some(rows) => Right(rows)
      case None       =>
        fetch(t, tables).map { rows =>
          cache.put(key, rows)
          rows
        }

  // ---- the connection ------------------------------------------------------

  private def caFile: String =
    sys.env.get("ORPHERA_DB_CA").map(_.trim).filter(_.nonEmpty).getOrElse("certs/ca.crt")

  private def password: Either[String, String] =
    SecretStore.default().flatMap(_.get(PasswordSecret)).flatMap {
      case Some(value) =>
        Secrets.register(value)
        Right(value)
      case None =>
        Left(
          s"there is no secret called $PasswordSecret: store the reader's password with " +
            s"'orphera secrets set $PasswordSecret' (it is not made on first use)"
        )
    }

  private def fetch(
      t: Target,
      tables: List[(String, List[String])]
  ): Either[String, DbDocuments.Rows] =
    for
      pw <- password
      ca <- caPath
      rows <- query(t, pw, ca, tables)
    yield rows

  private def caPath: Either[String, String] =
    if Files.isRegularFile(Paths.get(caFile)) then Right(caFile)
    else
      Left(
        s"the CA certificate $caFile is missing; the database connection is always " +
          "verified against it (set ORPHERA_DB_CA to use another file)"
      )

  private def query(
      t: Target,
      pw: String,
      ca: String,
      tables: List[(String, List[String])]
  ): Either[String, DbDocuments.Rows] =
    val props = new Properties()
    props.setProperty("user", t.user)
    props.setProperty("password", pw)
    props.setProperty("sslMode", "verify-full")
    props.setProperty("serverSslCert", ca)
    props.setProperty("connectTimeout", "10000")
    props.setProperty("socketTimeout", "30000")
    val url = s"jdbc:mariadb://${t.host}:${t.port}/${t.database}"
    scala.util
      .Try {
        val connection: Connection =
          Option(new org.mariadb.jdbc.Driver().connect(url, props))
            .getOrElse(throw new IllegalStateException(s"the driver does not accept $url"))
        try
          connection.setReadOnly(true)
          val statement = connection.createStatement()
          try
            statement.execute("SET SESSION group_concat_max_len = 1073741824")
            tables.map { (table, columns) =>
              val pairs = columns.map(c => s"'$c', $c").mkString(", ")
              val result = statement.executeQuery(
                s"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT($pairs)), '[]') FROM $table"
              )
              try
                if result.next() then table -> parseRows(table, result.getString(1))
                else table -> (Right(List.empty[Json]): Either[String, List[Json]])
              finally result.close()
            }
          finally statement.close()
        finally connection.close()
      }
      .toEither
      .left
      .map(e => s"cannot read the database at ${t.host}: ${e.getMessage}")
      .flatMap { results =>
        results.collectFirst { case (table, Left(err)) => s"$table: $err" } match
          case Some(err) => Left(err)
          case None      =>
            Right(results.collect { case (table, Right(rows)) => table -> rows }.toMap)
      }

  private def parseRows(table: String, text: String): Either[String, List[Json]] =
    parser
      .parse(text)
      .left
      .map(e => s"the rows are not JSON: ${e.getMessage}")
      .flatMap(_.asArray.map(_.toList).toRight(s"the rows of $table are not a list"))
