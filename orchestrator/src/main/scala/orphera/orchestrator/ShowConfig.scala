// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*

/** `orphera show-config <name>...`: prints each VM config as `orphera` reads it,
  * one line per config, from wherever `-c` / `ORPHERA_CONFIG_DIR` points (a
  * directory of files, or a `mariadb://` location). It only reads, and runs on
  * this machine. Its use is comparing sources:
  *
  * {{{
  * names=$(ls config | sed 's/\.yaml$//')
  * orphera -c config show-config $names > /tmp/files.txt
  * orphera -c mariadb://u@host/orphera/config show-config $names > /tmp/db.txt
  * diff /tmp/files.txt /tmp/db.txt
  * }}}
  *
  * A config that cannot be read or decoded prints `<name>: ERROR <why>` and
  * makes the exit status 1; `dns`, which is not a VM, is one such.
  */
object ShowConfig:

  val Usage: String =
    "Usage: orphera [-c <config-dir>] show-config <name>...   " +
      "(a name is a file of the config directory, such as tst3)"

  /** The extra NICs are kept as the JSON they were written as, whose keys come
    * in the order of the source (a file keeps its own, the database has a fixed
    * one). Sorting the keys makes two sources with the same data print the same.
    */
  private def sortedKeys(json: Json): Json =
    json.fold(
      json,
      _ => json,
      _ => json,
      _ => json,
      array => Json.arr(array.map(sortedKeys)*),
      obj =>
        Json.obj(obj.toList.sortBy(_._1).map((k, v) => k -> sortedKeys(v))*)
    )

  def run(args: List[String]): Int =
    if args.isEmpty || args.exists(_.startsWith("-")) then
      System.err.println(Usage)
      2
    else
      val allRead = args.map { name =>
        ConfigYaml.load(Locations.resolveConfig(name)) match
          case Right(params) =>
            println(s"$name: ${params.copy(nics = params.nics.map(sortedKeys))}")
            true
          case Left(err) =>
            println(s"$name: ERROR $err")
            false
      }
      if allRead.forall(identity) then 0 else 1
