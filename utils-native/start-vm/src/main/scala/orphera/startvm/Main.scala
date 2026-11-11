// SPDX-License-Identifier: Apache-2.0

package orphera.startvm

import orphera.nativeutils.{Out, Sources, VmCommon}

/** utils/start-vm.sh in Scala, for Scala Native: starts one or more VMs on
  * their hypervisors (a shut-off VM is started, a paused one resumed, a
  * running one left alone). Same arguments, output and exit codes; the usage
  * text is the comment at the top of utils/start-vm.sh. Relative paths count
  * from the working directory, as in the shell version.
  */
object Main:

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  def run(args: List[String], src: Sources): Int =
    if args.isEmpty then
      src.usage("start-vm.sh", "Usage: start-vm <vm | number | config.yaml>...")
      2
    else
      val failed = args.foldLeft(Vector.empty[String]) { (acc, arg) =>
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
