// SPDX-License-Identifier: Apache-2.0

package orphera.rebootvm

import scala.annotation.tailrec

import orphera.nativeutils.{Bash, Out, Sources, VmCommon}

/** utils/reboot-vm.sh in Scala, for Scala Native: reboots VMs from the
  * hypervisor (--soft: virsh reboot; --hard: shut down, wait, start). Same
  * options, output and exit codes; the usage text is the comment at the top
  * of utils/reboot-vm.sh.
  */
object Main:

  final case class Opts(hard: Boolean = false, waitS: String = "120", force: Boolean = false)

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private def die(code: Int, msg: String): Nothing =
    Out.err(msg)
    sys.exit(code)

  private def usage(src: Sources): Unit =
    src.usage("reboot-vm.sh", "Usage: reboot-vm [--soft | --hard] [--wait SECONDS] [--force] <vm>...")

  @tailrec
  private def parse(args: List[String], o: Opts, src: Sources): (Opts, List[String]) =
    args match
      case "--soft" :: t  => parse(t, o.copy(hard = false), src)
      case "--hard" :: t  => parse(t, o.copy(hard = true), src)
      case "--force" :: t => parse(t, o.copy(force = true), src)
      case "--wait" :: rest =>
        rest match
          case v :: t => parse(t, o.copy(waitS = v), src)
          case Nil    => die(2, "--wait needs a number of seconds")
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") => die(2, s"unknown option $opt")
      case _                               => (o, args)

  def run(args: List[String], src: Sources): Int =
    val (o, vms) = parse(args, Opts(), src)
    if o.waitS.isEmpty || !Sources.allDigits(o.waitS) then die(2, "--wait needs a number of seconds")
    if vms.isEmpty then
      usage(src)
      sys.exit(2)
    // $((wait_s + 60)): a leading 0 is octal there, as in bash
    val hardTimeout = (Bash.arith(o.waitS).getOrElse(o.waitS.toLong) + 60).toString
    val failed = vms.foldLeft(Vector.empty[String]) { (acc, arg) =>
      src.vmLookup(arg) match
        case None => acc :+ arg
        case Some(vm) =>
          val result =
            if o.hard then
              val script = Scripts.hard
                .replace("@VM@", vm.name)
                .replace("@WAIT@", o.waitS)
                .replace("@FORCE@", if o.force then "1" else "0")
              VmCommon.vmRun(src, vm.hv, script, hardTimeout)
            else VmCommon.vmRun(src, vm.hv, Scripts.soft.replace("@VM@", vm.name))
          if VmCommon.report(vm.name, vm.hv, result) then acc else acc :+ vm.name
    }
    if failed.isEmpty then 0
    else
      Out.err(s"Not rebooted:${failed.map(f => s" $f").mkString}")
      1
