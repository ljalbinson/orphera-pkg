// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

case class Node(
    name: String,
    host: String,
    port: Int = 50051,
    vars: Map[String, String] = Map.empty
)

case class Group(
    name: String,
    members: List[String],
    vars: Map[String, String] = Map.empty
)

object Inventory:

  private val defaultPath =
    sys.env.getOrElse("ORPHERA_INVENTORY", "inventory.yaml")

  private val loaded: (List[Node], List[Group]) =
    InventoryYaml.load(defaultPath) match
      case Right(result) => result
      case Left(err)     =>
        Console.err.println(
          s"WARNING: could not load inventory from $defaultPath: $err"
        )
        Console.err.println(
          "Falling back to an empty inventory — every 'no matching nodes' error traces back to this."
        )
        (Nil, Nil)

  val all: List[Node] = loaded._1
  val groups: List[Group] = loaded._2

  def groupVarsFor(nodeName: String): Map[String, String] =
    groups
      .filter(_.members.contains(nodeName))
      .foldLeft(Map.empty[String, String]) { case (acc, group) =>
        acc ++ group.vars
      }
