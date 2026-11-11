// SPDX-License-Identifier: Apache-2.0

package orphera.stopvm

import scala.annotation.tailrec

import orphera.nativeutils.{Bash, Out, Selection, Sources, VmCommon}

/** utils/stop-vm.sh in Scala, for Scala Native: asks each VM's guest to shut
  * down and waits until libvirt reports it shut off (--force: then power it
  * off). Same options, output and exit codes; the usage text is the comment
  * at the top of utils/stop-vm.sh.
  */
object Main:

  final case class Opts(
      sel: Selection = Selection(),
      waitS: String = "120",
      force: Boolean = false
  )

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private def die(code: Int, msg: String): Nothing =
    Out.err(msg)
    sys.exit(code)

  private def usage(src: Sources): Unit =
    src.usage("stop-vm.sh", "Usage: stop-vm [--wait SECONDS] [--force] [--node-groups G] [--exclude-nodes V] <vm>...")

  @tailrec
  private def parse(args: List[String], o: Opts, src: Sources): Opts =
    args match
      case Nil => o
      case (opt @ ("--node-groups" | "--exclude-nodes")) :: rest =>
        o.sel.withOption(opt, rest.headOption) match
          case Some(s) => parse(rest.drop(1), o.copy(sel = s), src)
          case None    => sys.exit(2)
      case "--wait" :: rest =>
        rest match
          case v :: t => parse(t, o.copy(waitS = v), src)
          case Nil    => die(2, "--wait needs a number of seconds")
      case "--force" :: t => parse(t, o.copy(force = true), src)
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") => die(2, s"unknown option $opt")
      case n :: t                          => parse(t, o.copy(sel = o.sel.withName(n)), src)

  def run(args: List[String], src: Sources): Int =
    val o = parse(args, Opts(), src)
    if o.waitS.isEmpty || !Sources.allDigits(o.waitS) then die(2, "--wait needs a number of seconds")
    val vms = if o.sel.wanted then o.sel.expand(src) else o.sel.names
    if vms.isEmpty then
      usage(src)
      sys.exit(2)
    // $((wait_s + 30)): a leading 0 is octal there, as in bash
    val timeout = (Bash.arith(o.waitS).getOrElse(o.waitS.toLong) + 30).toString
    val failed = vms.foldLeft(Vector.empty[String]) { (acc, arg) =>
      src.vmLookup(arg) match
        case None => acc :+ arg
        case Some(vm) =>
          val script = Scripts.stop
            .replace("@VM@", vm.name)
            .replace("@WAIT@", o.waitS)
            .replace("@FORCE@", if o.force then "1" else "0")
          val result = VmCommon.vmRun(src, vm.hv, script, timeout)
          if VmCommon.report(vm.name, vm.hv, result) then acc else acc :+ vm.name
    }
    if failed.isEmpty then 0
    else
      Out.err(s"Not stopped:${failed.map(f => s" $f").mkString}")
      1
