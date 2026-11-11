// SPDX-License-Identifier: Apache-2.0

package orphera.listvms

import java.util.regex.Pattern

import orphera.nativeutils.{ErrMode, Fmt, Out, Proc, Sources}

/** utils/list-vms.sh in Scala, for Scala Native: every VM on each hypervisor
  * with its state, vCPUs, memory and autostart, and a total of the running
  * ones per hypervisor. The same arguments (hypervisor names; none means the
  * inventory group `hypervisors`, else HYPERVISORS, else gs1 gs2 gs3 st0),
  * output and exit code (1 if a hypervisor did not answer).
  */
object Main:

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private val NodeLine = Pattern.compile("^\\[([^\\]]+)\\] ")
  private val AwkNumber =
    Pattern.compile("^[ \t\n\r\f\\x0B]*[-+]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][-+]?[0-9]+)?")

  /** A string as awk reads it as a number: its numeric prefix, else 0. */
  private def awkNum(s: String): Double =
    val m = AwkNumber.matcher(s)
    if m.find() then m.group().trim.toDoubleOption.getOrElse(0.0) else 0.0

  /** awk's trim here: leading blanks, trailing blanks and carriage returns. */
  private def trim(s: String): String = s.replaceFirst("^[ \t]+", "").replaceFirst("[ \t\r]+$", "")

  /** awk `split(s, f, "|")`, 1-based: f(i) is "" past the end. */
  private final class Fields(s: String):
    private val parts = s.split("\\|", -1).toVector
    def apply(i: Int): String = parts.lift(i - 1).getOrElse("")

  private final case class Hv(
      rows: Vector[String] = Vector.empty,
      running: Int = 0,
      cpu: Double = 0,
      mem: Double = 0
  )

  def run(args: List[String], src: Sources): Int =
    val hvs = if args.nonEmpty then args.mkString(" ") else src.hypervisorList
    val nodes = hvs.replaceAll(" +", ",")
    val (_, raw) = Proc.capture(
      List("orphera", "run", "--nodes", nodes, "--quiet", "--", "sh", "-c", Scripts.remote),
      src.env,
      src.root,
      ErrMode.Merge
    )

    // What awk collects from the output: which hypervisors answered, and their VMs in virsh's order.
    val (up, byHv) = raw
      .split("\n", -1)
      .foldLeft((Set.empty[String], Map.empty[String, Hv])) { case ((seen, acc), line) =>
        val m = NodeLine.matcher(line)
        if !m.find() then (seen, acc)
        else
          val node = m.group(1)
          val rest = line.substring(m.end())
          if rest.startsWith("HV|up") then (seen + node, acc)
          else if rest.startsWith("VM|") then
            val f = new Fields(rest)
            val state = trim(f(3))
            val auto = trim(f(6)) match
              case "enable"  => "yes"
              case "disable" => "no"
              case _         => "?"
            val row = s"  ${Fmt.left(f(2), 22)} ${Fmt.left(state, 12)} ${Fmt.right(f(4), 5)} " +
              s"${Fmt.right(s"${f(5)} MiB", 9)}  $auto"
            val h = acc.getOrElse(node, Hv())
            val h2 =
              if state == "running" then
                h.copy(rows = h.rows :+ row, running = h.running + 1, cpu = h.cpu + awkNum(f(4)),
                  mem = h.mem + awkNum(f(5)))
              else h.copy(rows = h.rows :+ row)
            (seen, acc + (node -> h2))
          else (seen, acc)
      }

    val order = hvs.trim.split("[ \t\n]+").toVector.filter(_.nonEmpty)
    order.foldLeft(0) { (bad, h) =>
      if !up.contains(h) then
        Out.out(s"$h: no answer (agent down or host unreachable)")
        Out.out("")
        1
      else
        val x = byHv.getOrElse(h, Hv())
        Out.out(
          s"$h  (${x.rows.size} VMs, ${x.running} running: ${x.cpu.toLong} vCPU, ${x.mem.toLong} MiB)"
        )
        Out.out(s"  ${Fmt.left("VM", 22)} ${Fmt.left("STATE", 12)} ${Fmt.right("VCPU", 5)} " +
          s"${Fmt.right("MEMORY", 13)}  AUTOSTART")
        // names are sorted by virsh already; keep its order
        x.rows.foreach(Out.out)
        Out.out("")
        bad
    }
