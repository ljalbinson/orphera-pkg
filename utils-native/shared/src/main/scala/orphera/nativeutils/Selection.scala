// SPDX-License-Identifier: Apache-2.0

package orphera.nativeutils

/** --node-groups and --exclude-nodes for start-vm, stop-vm and reboot-vm:
  * `vm_select` of utils/vm-common.sh (the same as vmctl's).
  *
  * @param order
  *   the groups (Left, a value of --node-groups) and VM arguments (Right) in
  *   command-line order
  * @param exclude
  *   the values of --exclude-nodes
  */
final case class Selection(
    order: Vector[Either[String, String]] = Vector.empty,
    exclude: Vector[String] = Vector.empty
):
  def groups: Vector[String] = order.collect { case Left(g) => g }

  def names: Vector[String] = order.collect { case Right(n) => n }

  /** True if --node-groups or --exclude-nodes was given. */
  def wanted: Boolean = groups.nonEmpty || exclude.nonEmpty

  def withName(n: String): Selection = copy(order = order :+ Right(n))

  /** `vm_select_option <option> <value>`: None (after saying why) for a missing value. */
  def withOption(option: String, value: Option[String]): Option[Selection] =
    (option, value) match
      case ("--node-groups", Some(v)) if v.nonEmpty   => Some(copy(order = order :+ Left(v)))
      case ("--exclude-nodes", Some(v)) if v.nonEmpty => Some(copy(exclude = exclude :+ v))
      case ("--node-groups", _) =>
        Out.err("--node-groups needs a group name")
        None
      case _ =>
        Out.err("--exclude-nodes needs a VM name")
        None

  /** `vm_select`: the VMs, printed after "VMs: "; exits 2 on a usage error or
    * if `orphera nodes` fails.
    */
  def expand(src: Sources): Vector[String] =
    def die(msg: String): Nothing =
      Out.err(msg)
      sys.exit(2)
    if groups.isEmpty && names.isEmpty then
      die("--exclude-nodes takes VMs out of a selection: give --node-groups or VM names as well")
    val members: Map[String, String] =
      if groups.isEmpty then Map.empty
      else
        val (rc, listing) = Proc.capture(List("orphera", "nodes", "--groups"), src.env, src.root)
        if rc != 0 then sys.exit(2)
        listing
          .split("\n")
          .iterator
          .filter(_.nonEmpty)
          .map { line =>
            val i = line.indexOf(": ")
            line.takeWhile(_ != ':') -> (if i < 0 then line else line.substring(i + 2))
          }
          .toMap
    val wantedNames: Vector[String] = order.flatMap {
      case Left(gs) =>
        gs.split(",").toVector.filter(_.nonEmpty).flatMap { g =>
          members.get(g) match
            case Some(m) => m.split("[ \t\n]+").toVector.filter(_.nonEmpty)
            case None =>
              die(
                s"Error: Unknown node group(s): $g. Defined in inventory: " +
                  members.keys.toVector.sorted.mkString(", ")
              )
        }
      case Right(f) if f.endsWith(".yaml") =>
        if !src.isFile(f) then die(s"$f: no such config file")
        val n = src.configValue(f, "hostname")
        if n.isEmpty then die(s"$f has no hostname:")
        Vector(n)
      case Right(n) if !Sources.allDigits(n) => Vector(n)
      case Right(n)                          => Vector(s"tst$n")
    }
    if wantedNames.isEmpty then die("the selection has no VMs in it")
    val sel = List("--nodes", wantedNames.mkString(",")) ++
      (if exclude.isEmpty then Nil else List("--exclude-nodes", exclude.mkString(",")))
    val (rc, expanded) = Proc.capture("orphera" :: "nodes" :: sel, src.env, src.root)
    if rc != 0 then sys.exit(2)
    val vms = expanded.split("[ \t\n]+").toVector.filter(_.nonEmpty)
    if vms.isEmpty then die("the selection has no VMs in it")
    Out.out(s"VMs: ${vms.mkString(" ")}")
    vms
