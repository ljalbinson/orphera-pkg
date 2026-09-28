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

  /** A single node's own vars (not merged with its groups' vars — use
    * groupVarsFor for that side separately if a caller ever needs both).
    * Empty map for an unknown node name rather than throwing, matching
    * this object's existing fail-soft convention (see `loaded` above).
    */
  def varsFor(nodeName: String): Map[String, String] =
    all.find(_.name == nodeName).map(_.vars).getOrElse(Map.empty)

  /** Reads a comma-separated list-valued var off a single node — added
    * for per-host disk paths (`osd_disks`, `zap_disks`), which don't fit
    * the plain String-valued `vars: Map[String, String]` shape on their
    * own. Kept generic (not disk-specific) since any inventory var could
    * reasonably want to be a list some day. Whitespace around each
    * element is trimmed, and empty elements (a trailing comma, or the
    * var simply not being set on that node) are dropped rather than
    * producing a list with a blank entry.
    */
  def csvVar(nodeName: String, key: String): List[String] =
    varsFor(nodeName)
      .get(key)
      .map(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
      .getOrElse(Nil)
