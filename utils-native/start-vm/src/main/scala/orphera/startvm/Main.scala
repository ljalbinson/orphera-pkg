// SPDX-License-Identifier: Apache-2.0

package orphera.startvm

import scala.annotation.tailrec

import orphera.nativeutils.{Out, Selection, Sources, VmCommon}

/** utils/start-vm.sh in Scala, for Scala Native: starts one or more VMs on
  * their hypervisors (a shut-off VM is started, a paused one resumed, a
  * running one left alone; a failed start retried, with stale Open vSwitch
  * ports removed first), named or by inventory group (--node-groups,
  * --exclude-nodes). Same arguments, output and exit codes; the usage
  * text is the comment at the top of utils/start-vm.sh. Relative paths count
  * from the working directory, as in the shell version.
  */
object Main:

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private def usage(src: Sources): Unit =
    src.usage("start-vm.sh", "Usage: start-vm [--parallel N] [--retries N] [--node-groups G] [--exclude-nodes V] <vm | number | config.yaml>...")

  final case class Opts(sel: Selection = Selection(), par: String = "1", retries: String = "3")

  @tailrec
  private def parse(args: List[String], o: Opts, src: Sources): Opts =
    args match
      case Nil => o
      case (opt @ ("--node-groups" | "--exclude-nodes")) :: rest =>
        o.sel.withOption(opt, rest.headOption) match
          case Some(s) => parse(rest.drop(1), o.copy(sel = s), src)
          case None    => sys.exit(2)
      case "--retries" :: rest =>
        rest.headOption match
          case Some(v) if v.nonEmpty && Sources.allDigits(v) => parse(rest.drop(1), o.copy(retries = BigInt(v).toString), src)
          case _ =>
            Out.err("--retries needs a number (0 or more)")
            sys.exit(2)
      case "--parallel" :: rest =>
        VmCommon.parallelOption(rest.headOption) match
          case Some(v) => parse(rest.drop(1), o.copy(par = v), src)
          case None    => sys.exit(2)
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") =>
        Out.err(s"unknown option $opt")
        sys.exit(2)
      case n :: t => parse(t, o.copy(sel = o.sel.withName(n)), src)

  def run(args: List[String], src: Sources): Int =
    if args.isEmpty then
      usage(src)
      return 2
    val o = parse(args, Opts(), src)
    val vms = if o.sel.wanted then o.sel.expand(src) else o.sel.names
    if vms.isEmpty then
      usage(src)
      2
    else
      // Each attempt may take up to a minute, with 5 s between them.
      val r = BigInt(o.retries)
      val timeout = (BigInt(60) * (r + 1) + BigInt(5) * r).toString
      val failed = VmCommon.runAll(src, o.par, timeout, vm => Scripts.start.replace("@VM@", vm).replace("@RETRIES@", o.retries), vms)
      if failed.isEmpty then 0
      else
        Out.err(s"Not started:${failed.map(f => s" $f").mkString}")
        1
