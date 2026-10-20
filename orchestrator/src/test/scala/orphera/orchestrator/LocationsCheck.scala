// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.Files

/** Checks for the inventory and config-directory locations (`-I`, `-c`, and
  * their environment variables): the options, the names a `--config` can take,
  * and reading through a URI. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.LocationsCheck"`.
  */
object LocationsCheck:
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

  private def extracted(args: String*) = Locations.extract(args.toList)

  def main(args: Array[String]): Unit =
    // The options
    check(
      "no options leaves the arguments alone",
      extracted("version", "--nodes", "tst0") ==
        Right((Locations.Overrides(None, None), List("version", "--nodes", "tst0")))
    )
    check(
      "-I and -c before the command",
      extracted("-I", "file:///a/inv.yaml", "-c", "file:///a/cfg", "version") ==
        Right(
          (
            Locations.Overrides(Some("file:///a/inv.yaml"), Some("file:///a/cfg")),
            List("version")
          )
        )
    )
    check(
      "the long forms, in either order",
      extracted("--config-dir", "cfg", "--inventory", "inv.yaml", "facts") ==
        Right((Locations.Overrides(Some("inv.yaml"), Some("cfg")), List("facts")))
    )
    check(
      "an option without a value is refused",
      extracted("-I").swap.exists(_.contains("-I")) &&
        extracted("-c", "--nodes").swap.exists(_.contains("-c"))
    )
    check(
      "-c after the command belongs to the command",
      extracted("run", "sh", "-c", "uptime") ==
        Right((Locations.Overrides(None, None), List("run", "sh", "-c", "uptime")))
    )

    // Where things are looked for
    val before = (Locations.inventory, Locations.configDir)
    Locations.install(
      Locations.Overrides(Some("file:///x/inv.yaml"), Some("file:///x/cfg/"))
    )
    check("an option sets the inventory", Locations.inventory == "file:///x/inv.yaml")
    check("an option sets the config directory", Locations.configDir == "file:///x/cfg/")
    check(
      "a name is a file of the config directory",
      Locations.resolveConfig("tst0") == "file:///x/cfg/tst0.yaml"
    )
    check(
      "a path, a file name with .yaml, or a URI is taken as it is",
      List("config/tst0.yaml", "tst0.yaml", "tst0.yml", "/x/y.yaml", "file:///y/z.yaml", "db://h/z")
        .forall(v => Locations.resolveConfig(v) == v)
    )
    check(
      "the child process is told the same places",
      Locations.childEnv == Map(
        "ORPHERA_INVENTORY" -> "file:///x/inv.yaml",
        "ORPHERA_CONFIG_DIR" -> "file:///x/cfg/"
      )
    )
    Locations.install(Locations.Overrides(None, None))
    check("no options, no child environment", Locations.childEnv.isEmpty)
    check(
      "the defaults when nothing is set",
      sys.env.contains("ORPHERA_INVENTORY") || Locations.inventory == "inventory.yaml"
    )
    check(
      "the default config directory when nothing is set",
      sys.env.contains("ORPHERA_CONFIG_DIR") || Locations.configDir == "config"
    )
    check(
      "a config's label is its file name without the extension",
      Locations.label("config/tst3.yaml") == "tst3" &&
        Locations.label("file:///x/tst3.yaml") == "tst3" &&
        Locations.label("db://h/vms/tst3?rev=2") == "tst3"
    )

    // Reading
    val dir = Files.createTempDirectory("locations-check")
    val inv = dir.resolve("inv.yaml")
    Files.writeString(inv, "nodes:\n  - name: a\n    host: a.example\n")
    check("a plain path is read", Sources.read(inv.toString).isRight)
    check("a file: URI is read", Sources.read(inv.toUri.toString).exists(_.contains("a.example")))
    check(
      "the inventory loads through a file: URI",
      InventoryYaml.load(inv.toUri.toString).exists(_._1.map(_.name) == List("a"))
    )
    check(
      "a missing file says which one",
      Sources.read(dir.resolve("none.yaml").toString).swap.exists(_.contains("none.yaml"))
    )
    check(
      "an unsupported scheme names it and what is supported",
      Sources.read("postgres://h/db").swap.exists(e =>
        e.contains("'postgres'") && e.contains("file")
      )
    )
    check(
      "a file: URI with a host is refused",
      Sources.read("file://somehost/etc/x.yaml").isLeft
    )
    check(
      "a scheme can be added for another source",
      { Sources.register("mem", loc => Right(s"nodes: []   # from $loc"))
        Sources.read("mem://inventory").exists(_.contains("mem://inventory")) }
    )
    check(
      "the added scheme can hold an inventory",
      InventoryYaml.load("mem://inventory").exists(_._1.isEmpty)
    )
    check("a plain path has no scheme", Sources.schemeOf("config/tst0.yaml").isEmpty)
    check("a URI has one", Sources.schemeOf("File:///x").contains("file"))

    Files.delete(inv)
    Files.delete(dir)
    check("the locations are as they were", (Locations.inventory, Locations.configDir) == before)

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"LocationsCheck: all $passes checks passed")
