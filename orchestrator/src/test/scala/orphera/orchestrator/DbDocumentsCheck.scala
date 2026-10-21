// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*
import io.circe.yaml.parser

/** Checks for the database reader that need no database: the `mariadb://`
  * locations, and the rows-to-documents conversion that `utils/sql-to-yaml.py`
  * does too. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.DbDocumentsCheck"`.
  *
  * The rows below are shaped as `JSON_ARRAYAGG(JSON_OBJECT(...))` returns them:
  * numbers for integers (`never_reboot` as 0/1), strings for text, and a JSON
  * column as text.
  */
object DbDocumentsCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private def rows(table: String, json: String): (String, List[Json]) =
    table -> parser
      .parse(json)
      .toOption
      .flatMap(_.asArray)
      .map(_.toList)
      .getOrElse(Nil)

  private val data: DbDocuments.Rows = Map(
    rows(
      "node",
      """[{"name":"tst1","host":"tst1.example","port":50051,"never_reboot":0},
        | {"name":"gs1","host":"gs1.example","port":50052,"never_reboot":1}]""".stripMargin
    ),
    rows(
      "node_var",
      """[{"node_name":"tst1","name":"role","value":"mon"},
        | {"node_name":"tst1","name":"cluster_ip","value":"10.0.0.2"}]""".stripMargin
    ),
    rows(
      "node_group",
      """[{"name":"ceph","never_reboot":0},{"name":"hypervisors","never_reboot":1}]"""
    ),
    rows(
      "group_member",
      """[{"group_name":"ceph","node_name":"tst1"},
        | {"group_name":"hypervisors","node_name":"gs1"}]""".stripMargin
    ),
    rows("group_var", """[{"group_name":"ceph","name":"pool","value":"rbd"}]"""),
    rows(
      "vm_config",
      """[{"hostname":"tst1","hypervisor":"gs1","domainname":"example","os":"ubuntu",
        |  "disk_gb":32,"memory_mib":16384,"cpu":4,"host_type":"virtual",
        |  "dns1":"10.0.0.1","dns2":"10.0.0.2","nic0_nic":"enp1s0",
        |  "nic0_ipaddress":"10.0.0.13","nic0_netmask":"255.255.255.0","nic0_cidr":"24",
        |  "nic0_gateway":"10.0.0.1","nic0_bridge":"br0","nic0_mtu":1500,
        |  "nic0_route_table":0,"nic0_priority":0,
        |  "extensions":"{\"custom_port\": 8080, \"flag\": true}"}]""".stripMargin
    ),
    rows(
      "vm_disk",
      """[{"hostname":"tst1","ordinal":1,"size_gb":128},{"hostname":"tst1","ordinal":0,"size_gb":256}]"""
    ),
    rows(
      "vm_nic",
      """[{"hostname":"tst1","ordinal":0,"nic":"enp2s0","bridge":"br1","mtu":9000,
        |  "ipaddress":null,"netmask":null,"cidr":null,"gateway":null,
        |  "route_table":0,"priority":0}]""".stripMargin
    ),
    rows(
      "vm_package",
      """[{"hostname":"tst1","phase":"basic","ordinal":1,"package_name":"vim"},
        | {"hostname":"tst1","phase":"basic","ordinal":0,"package_name":"curl"},
        | {"hostname":"tst1","phase":"pre_basic","ordinal":0,"package_name":"gnupg"}]""".stripMargin
    ),
    rows(
      "config_document",
      """[{"kind":"dns","name":"dns","document":"{\"zone\": \"example\"}"}]"""
    )
  )

  private def at(json: Json, path: String*): Option[Json] =
    path.foldLeft(Option(json))((acc, key) =>
      acc.flatMap(j => j.hcursor.downField(key).focus)
    )

  private def strings(json: Option[Json], key: String): List[String] =
    json
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.hcursor.get[String](key).toOption))
      .getOrElse(Nil)

  def main(args: Array[String]): Unit =
    // The locations
    val ok = MariaDbSource.parse("mariadb://orphera_ro@dbhost.example.com/orphera/inventory")
    check(
      "an inventory location is understood",
      ok == Right(
        MariaDbSource.Target(
          "orphera_ro",
          "dbhost.example.com",
          3306,
          "orphera",
          MariaDbSource.InventoryDoc
        )
      ),
      ok.toString
    )
    check(
      "a config location names the config and keeps the port",
      MariaDbSource
        .parse("mariadb://u@h:3307/orphera/config/tst3.yaml")
        .map(t => (t.port, t.what)) ==
        Right((3307, MariaDbSource.ConfigDoc("tst3")))
    )
    check(
      "a password in the URI is refused",
      MariaDbSource.parse("mariadb://u:secret@h/orphera/inventory").isLeft
    )
    check(
      "no user is refused",
      MariaDbSource.parse("mariadb://h/orphera/inventory").isLeft
    )
    check(
      "an unknown path is refused",
      MariaDbSource.parse("mariadb://u@h/orphera/other").isLeft
    )
    check(
      "a directory without a config name is refused",
      MariaDbSource.parse("mariadb://u@h/orphera/config").isLeft
    )
    check(
      "odd characters in the database name are refused",
      MariaDbSource.parse("mariadb://u@h/or%20phera/inventory").isLeft
    )
    check(
      "the scheme is registered with Sources",
      Sources.read("mariadb://u@h/orphera/other").swap.exists(!_.contains("unsupported"))
    )

    // The inventory
    val inventory = DbDocuments.inventory(data)
    val nodes = at(inventory, "nodes")
    check("nodes are sorted by name", strings(nodes, "name") == List("gs1", "tst1"))
    check(
      "a non-default port is kept, the default left out",
      at(inventory, "nodes").flatMap(_.asArray).exists { ns =>
        ns.toList.map(_.hcursor.get[Int]("port").toOption) == List(Some(50052), None)
      }
    )
    check(
      "never-reboot comes through for nodes and groups",
      at(inventory, "nodes").flatMap(_.asArray).exists(
        _.toList.map(_.hcursor.get[Boolean]("never-reboot").toOption) ==
          List(Some(true), None)
      ) && at(inventory, "groups").flatMap(_.asArray).exists(
        _.toList.map(_.hcursor.get[Boolean]("never-reboot").toOption) ==
          List(None, Some(true))
      )
    )
    check(
      "node vars are kept",
      at(inventory, "nodes").flatMap(_.asArray).exists(
        _.toList.exists(_.hcursor.downField("vars").get[String]("cluster_ip") == Right("10.0.0.2"))
      )
    )
    check(
      "group members and vars are kept",
      at(inventory, "groups").flatMap(_.asArray).exists(
        _.toList.exists(g =>
          g.hcursor.get[List[String]]("members") == Right(List("tst1")) &&
            g.hcursor.downField("vars").get[String]("pool") == Right("rbd")
        )
      )
    )
    // The real readers, fed the documents through a stand-in scheme (there is no server here)
    Sources.register(
      "dbcheck",
      loc =>
        if loc.endsWith("inventory") then
          MariaDbSource.document(data, MariaDbSource.InventoryDoc).map(_.noSpaces)
        else
          MariaDbSource
            .document(data, MariaDbSource.ConfigDoc(loc.stripPrefix("dbcheck://")))
            .map(_.noSpaces)
    )
    val loaded = InventoryYaml.load("dbcheck://inventory")
    check(
      "the inventory reader accepts the document",
      loaded.exists((ns, gs) =>
        ns.map(_.name) == List("gs1", "tst1") && ns.exists(n =>
          n.name == "gs1" && n.port == 50052 && n.neverReboot
        ) && gs.map(_.name) == List("ceph", "hypervisors")
      ),
      loaded.toString
    )
    check(
      "an empty database is an inventory with no nodes",
      DbDocuments.inventory(Map.empty) == Json.obj("nodes" -> Json.arr())
    )

    // A VM config
    val vm = DbDocuments.config(data, "tst1")
    val params = vm.toOption.flatMap(at(_, "params"))
    check("a VM config is found", vm.isRight, vm.toString)
    check(
      "disk, memory and cpu are strings, as in the files",
      at(params.getOrElse(Json.Null), "disk").flatMap(_.asString).contains("32") &&
        at(params.getOrElse(Json.Null), "memory").flatMap(_.asString).contains("16384") &&
        at(params.getOrElse(Json.Null), "cpu").flatMap(_.asString).contains("4")
    )
    check(
      "nic0 keeps cidr and the numbers",
      at(params.getOrElse(Json.Null), "nic0", "cidr").flatMap(_.asString).contains("24") &&
        at(params.getOrElse(Json.Null), "nic0", "mtu").flatMap(_.asNumber).flatMap(_.toInt).contains(1500)
    )
    check(
      "disks follow their ordinal",
      at(params.getOrElse(Json.Null), "disks").flatMap(_.asArray).map(
        _.toList.flatMap(_.hcursor.get[Int]("disk").toOption)
      ).contains(List(256, 128))
    )
    check(
      "an extra NIC keeps a non-default mtu and leaves out the rest",
      at(params.getOrElse(Json.Null), "nics").flatMap(_.asArray).exists { ns =>
        ns.toList.map(_.asObject.map(_.keys.toList.sorted)) ==
          List(Some(List("bridge", "mtu", "nic")))
      }
    )
    check(
      "packages are split by phase and ordered",
      at(params.getOrElse(Json.Null), "basic_packages").flatMap(_.as[List[String]].toOption) ==
        Some(List("curl", "vim")) &&
        at(params.getOrElse(Json.Null), "pre_basic_packages").flatMap(_.as[List[String]].toOption) ==
        Some(List("gnupg"))
    )
    check(
      "extension keys come through with their types",
      at(params.getOrElse(Json.Null), "custom_port").flatMap(_.asNumber).flatMap(_.toInt).contains(8080) &&
        at(params.getOrElse(Json.Null), "flag").flatMap(_.asBoolean).contains(true)
    )
    check(
      "pdisks is empty",
      at(params.getOrElse(Json.Null), "pdisks").flatMap(_.asArray).exists(_.isEmpty)
    )
    val loadedVm = ConfigYaml.load("dbcheck://tst1")
    check(
      "the config reader accepts the VM document",
      loadedVm.exists(v =>
        v.hostname == "tst1" && v.disk == "32" && v.nic0.cidr == "24" &&
          v.disks == List("256", "128") && v.basicPackages == List("curl", "vim")
      ),
      loadedVm.toString
    )

    // A free-form document and a miss
    check(
      "a config_document is wrapped in params",
      DbDocuments.config(data, "dns").exists(d => at(d, "params", "zone").flatMap(_.asString).contains("example"))
    )
    check(
      "an unknown config names what exists",
      DbDocuments.config(data, "nope").swap.exists(e => e.contains("nope") && e.contains("tst1") && e.contains("dns"))
    )
    check(
      "a document is picked by what the location asks for",
      MariaDbSource.document(data, MariaDbSource.ConfigDoc("dns")).isRight &&
        MariaDbSource.document(data, MariaDbSource.InventoryDoc).isRight
    )
    check(
      "bad JSON in a JSON column is reported",
      DbDocuments
        .config(
          data.updated(
            "vm_config",
            data.getOrElse("vm_config", Nil).map(r =>
              r.mapObject(_.add("extensions", Json.fromString("{not json")))
            )
          ),
          "tst1"
        )
        .isLeft
    )

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"All $passes checks passed")
