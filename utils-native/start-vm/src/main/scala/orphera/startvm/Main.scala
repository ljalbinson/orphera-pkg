// SPDX-License-Identifier: Apache-2.0

package orphera.startvm

import scala.annotation.tailrec

import orphera.nativeutils.{Out, Selection, Sources, VmCommon}

/** utils/start-vm.sh in Scala, for Scala Native: starts one or more VMs on
  * their hypervisors (a shut-off VM is started, a paused one resumed, a
  * running one left alone), named or by inventory group (--node-groups,
  * --exclude-nodes). Same arguments, output and exit codes; the usage
  * text is the comment at the top of utils/start-vm.sh. Relative paths count
  * from the working directory, as in the shell version.
  */
object Main:

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private def usage(src: Sources): Unit =
    src.usage("start-vm.sh", "Usage: start-vm [--node-groups G] [--exclude-nodes V] <vm | number | config.yaml>...")

  @tailrec
  private def parse(args: List[String], sel: Selection, src: Sources): Selection =
    args match
      case Nil => sel
      case (opt @ ("--node-groups" | "--exclude-nodes")) :: rest =>
        sel.withOption(opt, rest.headOption) match
          case Some(s) => parse(rest.drop(1), s, src)
          case None    => sys.exit(2)
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") =>
        Out.err(s"unknown option $opt")
        sys.exit(2)
      case n :: t => parse(t, sel.withName(n), src)

  def run(args: List[String], src: Sources): Int =
    if args.isEmpty then
      usage(src)
      return 2
    val sel = parse(args, Selection(), src)
    val vms = if sel.wanted then sel.expand(src) else sel.names
    if vms.isEmpty then
      usage(src)
      2
    else
      val failed = vms.foldLeft(Vector.empty[String]) { (acc, arg) =>
        src.vmLookup(arg) match
          case None => acc :+ arg
          case Some(vm) =>
            val result = VmCommon.vmRun(src, vm.hv, Scripts.start.replace("@VM@", vm.name))
            if VmCommon.report(vm.name, vm.hv, result) then acc else acc :+ vm.name
      }
      if failed.isEmpty then 0
      else
        Out.err(s"Not started:${failed.map(f => s" $f").mkString}")
        1
