// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** `orphera nodes`: prints the node names a selection stands for, one per line,
  * from the inventory (-I / ORPHERA_INVENTORY). It takes the same `--nodes`,
  * `--node-groups` and `--exclude-nodes` as every other command and expands
  * them with the same code (Cli.expandNodeGroups), so an unknown group or a bad
  * exclusion gives the same error here as anywhere else. With no selection it
  * prints every node. `nodes --groups` prints each group instead, as
  * `<group>: <member> <member> ...`.
  *
  * It only reads the inventory and runs on this machine. Its use is scripts
  * that need a group's members, such as utils/vmctl.sh:
  *
  * {{{
  * orphera nodes --node-groups k8s-workers --exclude-nodes tst39
  * }}}
  */
object ListNodes:

  val Usage: String =
    "Usage: orphera nodes [--nodes a,b] [--node-groups g1,g2] " +
      "[--exclude-nodes x,y]\n       orphera nodes --groups"

  /** The node names `args` (everything after `nodes`) select. Names given with
    * `--nodes` must be inventory nodes: a typo is an error, not a line of
    * output a script would then act on.
    */
  def select(
      args: List[String],
      groups: List[Group],
      allNodes: List[String]
  ): Either[String, List[String]] =
    Cli
      .expandNodeGroups("nodes" :: args, groups, allNodes)
      .flatMap {
        case "nodes" :: Nil                       => Right(allNodes)
        case "nodes" :: "--nodes" :: names :: Nil =>
          val selected = names.split(",").toList.map(_.trim).filter(_.nonEmpty)
          val unknown = selected.filterNot(allNodes.contains)
          if unknown.nonEmpty then
            Left(s"Unknown node(s): ${unknown.mkString(", ")}")
          else Right(selected.distinct)
        case _ => Left(Usage)
      }

  def run(args: List[String]): Int =
    val groups = Inventory.groups
    val allNodes = Inventory.all.map(_.name)
    args match
      case List("-h") | List("--help") =>
        println(Usage)
        0
      case List("--groups") =>
        groups.foreach(g => println(s"${g.name}: ${g.members.mkString(" ")}"))
        0
      case _ if allNodes.isEmpty =>
        System.err.println(
          s"Error: no nodes in the inventory (${Locations.inventory})"
        )
        1
      case _ =>
        select(args, groups, allNodes) match
          case Right(names) =>
            names.foreach(println)
            0
          case Left(err) =>
            System.err.println(if err == Usage then err else s"Error: $err")
            2
