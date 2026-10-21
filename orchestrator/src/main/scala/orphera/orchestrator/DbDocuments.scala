// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*
import io.circe.yaml.parser

/** Turns the rows of the MariaDB tables (schemas/mariadb/001-tables.sql) into
  * the documents `inventory.yaml` and `config/<vm>.yaml` hold, as JSON, which
  * the YAML readers parse unchanged. It is the Scala twin of `build_inventory`,
  * `build_vm` and `build_configs` in `utils/sql-to-yaml.py`; a change to one is
  * a change to the other.
  *
  * Pure: the rows come in as JSON objects (one list per table, as
  * `JSON_ARRAYAGG(JSON_OBJECT(...))` returns them), so none of this needs a
  * database to be checked. What the database does not hold is not invented: the
  * nodes, groups and members come out sorted by name, and `pdisks` is empty.
  */
object DbDocuments:

  /** The rows of each table, by table name. */
  type Rows = Map[String, List[Json]]

  val InventoryTables: List[(String, List[String])] = List(
    "node" -> List("name", "host", "port", "never_reboot"),
    "node_var" -> List("node_name", "name", "value"),
    "node_group" -> List("name", "never_reboot"),
    "group_member" -> List("group_name", "node_name"),
    "group_var" -> List("group_name", "name", "value")
  )

  val ConfigTables: List[(String, List[String])] = List(
    "vm_config" -> List(
      "hostname",
      "hypervisor",
      "domainname",
      "os",
      "disk_gb",
      "memory_mib",
      "cpu",
      "host_type",
      "dns1",
      "dns2",
      "nic0_nic",
      "nic0_ipaddress",
      "nic0_netmask",
      "nic0_cidr",
      "nic0_gateway",
      "nic0_bridge",
      "nic0_mtu",
      "nic0_route_table",
      "nic0_priority",
      "extensions"
    ),
    "vm_disk" -> List("hostname", "ordinal", "size_gb"),
    "vm_nic" -> List(
      "hostname",
      "ordinal",
      "nic",
      "bridge",
      "mtu",
      "ipaddress",
      "netmask",
      "cidr",
      "gateway",
      "route_table",
      "priority"
    ),
    "vm_package" -> List("hostname", "phase", "ordinal", "package_name"),
    "config_document" -> List("kind", "name", "document")
  )

  // ---- reading a row ------------------------------------------------------

  private def field(row: Json, key: String): Option[Json] =
    row.hcursor.downField(key).focus.filterNot(_.isNull)

  private def text(row: Json, key: String): Option[String] =
    field(row, key).flatMap { j =>
      j.asString
        .orElse(j.asNumber.map(_.toString))
        .orElse(j.asBoolean.map(_.toString))
    }

  private def int(row: Json, key: String, default: Int): Int =
    field(row, key)
      .flatMap { j =>
        j.asNumber.flatMap(_.toInt).orElse(j.asString.flatMap(_.toIntOption))
      }
      .getOrElse(default)

  /** TINYINT(1) comes back as 0/1, a BOOLEAN as true/false. */
  private def flag(row: Json, key: String): Boolean =
    field(row, key)
      .flatMap { j =>
        j.asBoolean
          .orElse(j.asNumber.flatMap(_.toInt).map(_ != 0))
          .orElse(j.asString.map(s => s == "1" || s.equalsIgnoreCase("true")))
      }
      .getOrElse(false)

  private def str(row: Json, key: String): Json =
    text(row, key).fold(Json.Null)(Json.fromString)

  private def rowsOf(rows: Rows, table: String): List[Json] =
    rows.getOrElse(table, Nil)

  /** A column that holds JSON. A JSON column can arrive as text or already as
    * an object, depending on how the server renders it.
    */
  private def embedded(row: Json, key: String): Either[String, Json] =
    field(row, key) match
      case None                  => Right(Json.Null)
      case Some(j) if j.isString =>
        parser
          .parse(j.asString.getOrElse(""))
          .left
          .map(e => s"$key holds invalid JSON: ${e.getMessage}")
      case Some(j) => Right(j)

  // ---- the inventory ------------------------------------------------------

  private def varsBy(
      rows: Rows,
      table: String,
      ownerKey: String
  ): Map[String, Map[String, String]] =
    rowsOf(rows, table)
      .flatMap { r =>
        for
          owner <- text(r, ownerKey)
          name <- text(r, "name")
          value <- text(r, "value")
        yield (owner, (name, value))
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toMap)
      .toMap

  private def varsField(vars: Map[String, String]): List[(String, Json)] =
    if vars.isEmpty then Nil
    else
      List(
        "vars" -> Json.obj(
          vars.toList.sortBy(_._1).map((k, v) => k -> Json.fromString(v))*
        )
      )

  def inventory(rows: Rows): Json =
    val nodeVars = varsBy(rows, "node_var", "node_name")
    val groupVars = varsBy(rows, "group_var", "group_name")
    val members: Map[String, List[String]] =
      rowsOf(rows, "group_member")
        .flatMap(r =>
          for
            g <- text(r, "group_name")
            n <- text(r, "node_name")
          yield (g, n)
        )
        .groupMap(_._1)(_._2)

    val nodes = rowsOf(rows, "node")
      .flatMap(r => text(r, "name").map(name => (name, r)))
      .sortBy(_._1)
      .map { (name, r) =>
        val port = int(r, "port", 50051)
        Json.obj(
          (List(
            "name" -> Json.fromString(name),
            "host" -> Json.fromString(text(r, "host").getOrElse(""))
          ) ++
            (if port != 50051 then List("port" -> Json.fromInt(port))
             else Nil) ++
            (if flag(r, "never_reboot") then List("never-reboot" -> Json.True)
             else Nil) ++
            varsField(nodeVars.getOrElse(name, Map.empty)))*
        )
      }

    val groups = rowsOf(rows, "node_group")
      .flatMap(r => text(r, "name").map(name => (name, r)))
      .sortBy(_._1)
      .map { (name, r) =>
        Json.obj(
          (List(
            "name" -> Json.fromString(name),
            "members" -> Json.arr(
              members
                .getOrElse(name, Nil)
                .sorted
                .map(Json.fromString)*
            )
          ) ++
            (if flag(r, "never_reboot") then List("never-reboot" -> Json.True)
             else Nil) ++
            varsField(groupVars.getOrElse(name, Map.empty)))*
        )
      }

    Json.obj(
      (List("nodes" -> Json.arr(nodes*)) ++
        (if groups.isEmpty then Nil else List("groups" -> Json.arr(groups*))))*
    )

  // ---- the per-VM configs -------------------------------------------------

  private def forHost(rows: Rows, table: String, host: String): List[Json] =
    rowsOf(rows, table)
      .filter(r => text(r, "hostname").contains(host))
      .sortBy(r => int(r, "ordinal", 0))

  private def optional(
      row: Json,
      columns: List[(String, String)]
  ): List[(String, Json)] =
    columns.flatMap((key, column) =>
      text(row, column).map(v => key -> Json.fromString(v))
    )

  private def nonDefault(
      row: Json,
      columns: List[(String, String, Int)]
  ): List[(String, Json)] =
    columns.flatMap { (key, column, default) =>
      val v = int(row, column, default)
      if v != default then List(key -> Json.fromInt(v)) else Nil
    }

  private def vm(rows: Rows, r: Json): Either[String, Json] =
    val host = text(r, "hostname").getOrElse("")
    val nic0 = Json.obj(
      (List(
        "nic" -> Json.fromString(text(r, "nic0_nic").getOrElse("")),
        "ipaddress" -> Json.fromString(text(r, "nic0_ipaddress").getOrElse("")),
        "netmask" -> Json.fromString(text(r, "nic0_netmask").getOrElse("")),
        "gateway" -> Json.fromString(text(r, "nic0_gateway").getOrElse("")),
        "bridge" -> Json.fromString(text(r, "nic0_bridge").getOrElse(""))
      ) ++
        optional(r, List("cidr" -> "nic0_cidr")) ++
        List(
          "mtu" -> Json.fromInt(int(r, "nic0_mtu", 1500)),
          "table" -> Json.fromInt(int(r, "nic0_route_table", 0)),
          "priority" -> Json.fromInt(int(r, "nic0_priority", 0))
        ))*
    )
    val nics = forHost(rows, "vm_nic", host).map { n =>
      Json.obj(
        (optional(
          n,
          List(
            "nic" -> "nic",
            "bridge" -> "bridge",
            "ipaddress" -> "ipaddress",
            "netmask" -> "netmask",
            "cidr" -> "cidr",
            "gateway" -> "gateway"
          )
        ) ++ nonDefault(
          n,
          List(
            ("mtu", "mtu", 1500),
            ("table", "route_table", 0),
            ("priority", "priority", 0)
          )
        ))*
      )
    }
    val disks = forHost(rows, "vm_disk", host).map(d =>
      Json.obj("disk" -> Json.fromInt(int(d, "size_gb", 0)))
    )
    val packages = forHost(rows, "vm_package", host)
    def inPhase(phase: String): Json =
      Json.arr(
        packages
          .filter(p => text(p, "phase").contains(phase))
          .flatMap(p => text(p, "package_name"))
          .map(Json.fromString)*
      )
    val base = JsonObject.fromIterable(
      List(
        "hypervisor" -> str(r, "hypervisor"),
        "hostname" -> Json.fromString(host),
        "domainname" -> str(r, "domainname"),
        "os" -> str(r, "os"),
        "disk" -> str(r, "disk_gb"),
        "memory" -> str(r, "memory_mib"),
        "cpu" -> str(r, "cpu"),
        "host_type" -> str(r, "host_type"),
        "dns1" -> str(r, "dns1"),
        "dns2" -> str(r, "dns2"),
        "nic0" -> nic0,
        "nics" -> Json.arr(nics*),
        "disks" -> Json.arr(disks*),
        "pdisks" -> Json.arr(),
        "pre_basic_packages" -> inPhase("pre_basic"),
        "basic_packages" -> inPhase("basic")
      )
    )
    embedded(r, "extensions").map { ext =>
      val withExtensions = ext.asObject.fold(base) { o =>
        o.toList.sortBy(_._1).foldLeft(base)((acc, kv) => acc.add(kv._1, kv._2))
      }
      Json.obj("params" -> Json.fromJsonObject(withExtensions))
    }

  /** The names of the configs the database holds. */
  def configNames(rows: Rows): List[String] =
    (rowsOf(rows, "vm_config").flatMap(r => text(r, "hostname")) ++
      rowsOf(rows, "config_document").flatMap(r => text(r, "name"))).sorted

  /** `config/<name>.yaml` as a document: a VM from its tables, or a free-form
    * document (such as `dns`) from `config_document`.
    */
  def config(rows: Rows, name: String): Either[String, Json] =
    rowsOf(rows, "vm_config").find(r =>
      text(r, "hostname").contains(name)
    ) match
      case Some(r) => vm(rows, r)
      case None    =>
        rowsOf(rows, "config_document").find(r =>
          text(r, "name").contains(name)
        ) match
          case Some(r) =>
            embedded(r, "document").map(doc => Json.obj("params" -> doc))
          case None =>
            Left(
              s"the database has no config called $name " +
                s"(it has: ${configNames(rows).mkString(", ")})"
            )
