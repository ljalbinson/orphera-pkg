// SPDX-License-Identifier: Apache-2.0

package orphera.vmctl

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.regex.Pattern

import scala.annotation.tailrec
import scala.util.Try

import orphera.nativeutils.{Bash, Out, Proc, Sources, VmCommon}

/** utils/vmctl.sh in Scala, for Scala Native: the same actions, options,
  * output, log files and exit codes. The VM playbooks in manifests/ do the
  * work, run with `orphera cluster-playbook`; this is the front end that picks
  * the VMs, sets the playbooks' environment variables and prints one line per
  * step. The usage text is the comment at the top of utils/vmctl.sh, so the
  * two cannot drift apart.
  *
  * Exit codes: 0 done, 1 a VM or playbook failed (or a playbook is missing),
  * 2 a usage error.
  */
object Main:

  private val Actions =
    Set("start", "stop", "reboot", "shutdown-all", "start-all", "autostart")

  /** A VM name (or number, or config file) or a `--node-groups` value, in
    * command-line order.
    */
  enum Item:
    case Name(value: String)
    case Groups(value: String)

  final case class Opts(
      retries: String = "",
      waitS: String = "",
      force: Boolean = false,
      mode: String = "",
      yes: Boolean = false,
      except: String = "",
      all: Boolean = false,
      dry: Boolean = false,
      par: String = "",
      ngroups: Vector[String] = Vector.empty,
      nexclude: Vector[String] = Vector.empty,
      hvs: Vector[String] = Vector.empty,
      pos: Vector[String] = Vector.empty,
      order: Vector[Item] = Vector.empty
  )

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = true)))

  private def die(code: Int, msg: String): Nothing =
    Out.err(msg)
    sys.exit(code)

  /** The comment block at the top of utils/vmctl.sh. */
  def usage(src: Sources): Unit =
    src.usage(
      "vmctl.sh",
      "Usage: vmctl start|stop|reboot|shutdown-all|start-all|autostart add|remove|list ... " +
        "(utils/vmctl.sh, not found, describes the options)"
    )

  @tailrec
  private def parse(args: List[String], o: Opts, src: Sources): Opts =
    def value(rest: List[String], msg: String): (String, List[String]) =
      rest match
        case v :: t => (v, t)
        case Nil    => die(2, msg)
    args match
      case Nil => o
      case "--retries" :: rest =>
        val (v, t) = value(rest, "--retries needs a number")
        parse(t, o.copy(retries = v), src)
      case "--wait" :: rest =>
        val (v, t) = value(rest, "--wait needs a number of seconds")
        parse(t, o.copy(waitS = v), src)
      case "--force" :: t   => parse(t, o.copy(force = true), src)
      case "--parallel" :: rest =>
        VmCommon.parallelOption(rest.headOption) match
          case Some(v) => parse(rest.drop(1), o.copy(par = v), src)
          case None    => sys.exit(2)
      case "--hard" :: t    => parse(t, o.copy(mode = "hard"), src)
      case "--soft" :: t    => parse(t, o.copy(mode = "soft"), src)
      case "--yes" :: t     => parse(t, o.copy(yes = true), src)
      case "--all" :: t     => parse(t, o.copy(all = true), src)
      case "--dry-run" :: t => parse(t, o.copy(dry = true), src)
      case "--hypervisor" :: rest =>
        val (v, t) = value(rest, "--hypervisor needs a name")
        parse(t, o.copy(hvs = o.hvs :+ v), src)
      case "--except" :: rest =>
        val (v, t) = value(rest, "--except needs a VM name")
        parse(t, o.copy(except = v), src)
      case "--node-groups" :: rest =>
        rest match
          case v :: t if v.nonEmpty =>
            parse(t, o.copy(ngroups = o.ngroups :+ v, order = o.order :+ Item.Groups(v)), src)
          case _ => die(2, "--node-groups needs a group name")
      case "--exclude-nodes" :: rest =>
        rest match
          case v :: t if v.nonEmpty => parse(t, o.copy(nexclude = o.nexclude :+ v), src)
          case _                    => die(2, "--exclude-nodes needs a VM name")
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") => die(2, s"unknown option $opt")
      case p :: t =>
        parse(t, o.copy(pos = o.pos :+ p, order = o.order :+ Item.Name(p)), src)

  /** The option combinations vmctl.sh refuses, with its messages, in its order. */
  private def check(action: String, o: Opts): Unit =
    if !Sources.allDigits(o.retries) || !Sources.allDigits(o.waitS) then
      die(2, "--retries and --wait need a number")
    if o.retries.nonEmpty && action == "stop" then
      die(2, "--retries applies to start and reboot --hard only")
    if o.yes && action != "shutdown-all" then die(2, "--yes applies to shutdown-all only")
    if o.except.nonEmpty && action != "shutdown-all" && action != "start-all" then
      die(2, "--except applies to shutdown-all and start-all only")
    if o.dry && action != "start-all" then die(2, "--dry-run applies to start-all only")
    if o.all && action != "start-all" && action != "autostart" then
      die(2, "--all applies to start-all and autostart only")
    if o.hvs.nonEmpty && action != "autostart" then
      die(2, "--hypervisor applies to autostart only")
    if action == "start" && (o.waitS.nonEmpty || o.force) then
      die(2, "--wait and --force apply to stop and reboot only")
    if o.mode.nonEmpty && action != "reboot" then
      die(2, "--hard and --soft apply to reboot only")
    if o.par.nonEmpty && !Set("start", "stop", "reboot").contains(action) then
      die(2, "--parallel applies to start, stop and reboot only")

  /** --node-groups and --exclude-nodes: the groups and VM names in command-line
    * order, each group as its members (`orphera nodes --groups`), a bare
    * number N as tstN and a config file as its hostname:, then `orphera nodes`
    * to check the names, drop repeats and apply the exclusions.
    */
  private def expand(action: String, o: Opts, src: Sources): Vector[String] =
    if !Set("start", "stop", "reboot", "autostart").contains(action) then
      die(2, "--node-groups and --exclude-nodes apply to start, stop, reboot and autostart only")
    if o.all || o.hvs.nonEmpty then
      die(2, "--node-groups and --exclude-nodes do not go with --all or --hypervisor")
    if o.ngroups.isEmpty && o.pos.isEmpty then
      die(
        2,
        "--exclude-nodes takes VMs out of a selection: give --node-groups or VM names as well"
      )
    val members: Map[String, String] =
      if o.ngroups.isEmpty then Map.empty
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
    val wanted: Vector[String] = o.order.flatMap {
      case Item.Groups(gs) =>
        gs.split(",").toVector.filter(_.nonEmpty).flatMap { g =>
          members.get(g) match
            case Some(m) => m.split("[ \t\n]+").toVector.filter(_.nonEmpty)
            case None =>
              die(
                2,
                s"Error: Unknown node group(s): $g. Defined in inventory: " +
                  members.keys.toVector.sorted.mkString(", ")
              )
        }
      case Item.Name(f) if f.endsWith(".yaml") =>
        if !src.isFile(f) then die(2, s"$f: no such config file")
        val n = src.configValue(f, "hostname")
        if n.isEmpty then die(2, s"$f has no hostname:")
        Vector(n)
      case Item.Name(n) if !Sources.allDigits(n) => Vector(n)
      case Item.Name(n)                          => Vector(s"tst$n")
    }
    if wanted.isEmpty then die(2, "the selection has no VMs in it")
    val sel = List("--nodes", wanted.mkString(",")) ++
      (if o.nexclude.isEmpty then Nil else List("--exclude-nodes", o.nexclude.mkString(",")))
    val (rc, expanded) = Proc.capture("orphera" :: "nodes" :: sel, src.env, src.root)
    if rc != 0 then sys.exit(2)
    val vms = expanded.split("[ \t\n]+").toVector.filter(_.nonEmpty)
    if vms.isEmpty then die(2, "the selection has no VMs in it")
    Out.out(s"VMs: ${vms.mkString(" ")}")
    vms

  def run(args: List[String], src: Sources): Int =
    args match
      case Nil =>
        usage(src)
        2
      case ("-h" | "--help") :: _ =>
        usage(src)
        0
      // list: utils/list-vms.sh, for the named hypervisors or, with none, for all of them
      case "list" :: rest =>
        Proc.inherit(src.utilsDir.resolve("list-vms.sh").toString :: rest, src.env, src.root)
      case action :: rest0 =>
        val (sub, rest) =
          if action != "autostart" then ("", rest0)
          else
            rest0 match
              case (s @ ("add" | "remove")) :: t => (s, t)
              case _                             => die(2, "autostart needs add or remove")
        if !Actions.contains(action) then
          die(
            2,
            s"unknown action '$action' (start, stop, reboot, shutdown-all, start-all, autostart or list)"
          )
        val o = parse(rest, Opts(), src)
        check(action, o)
        val vms =
          if o.ngroups.nonEmpty || o.nexclude.nonEmpty then expand(action, o, src) else o.pos
        if vms.isEmpty && action != "autostart" then
          usage(src)
          2
        else
          action match
            case "autostart"                  => autostart(sub, o, vms, src)
            case "shutdown-all" | "start-all" => wholeHypervisors(action, o, vms, src)
            case _                            => eachVm(action, o, vms, src)

  // --- the playbook runs ------------------------------------------------------------------------

  private val StepLine = Pattern.compile("^\\[[A-Za-z0-9_-]+\\] ")
  private val FailLine =
    Pattern.compile("aborting remaining stages|Cluster playbook .* failed|FAILED")

  /** The lines of a log, as bytes, so they are printed exactly as written. */
  private def logLines(log: String): Vector[Array[Byte]] =
    Try(Files.readAllBytes(Paths.get(log))).toOption match
      case None => Vector.empty
      case Some(bytes) =>
        val lines = Vector.newBuilder[Array[Byte]]
        var start = 0
        var i = 0
        while i < bytes.length do
          if bytes(i) == '\n'.toByte then
            lines += java.util.Arrays.copyOfRange(bytes, start, i)
            start = i + 1
          i += 1
        if start < bytes.length then lines += java.util.Arrays.copyOfRange(bytes, start, bytes.length)
        lines.result()

  private def text(line: Array[Byte]): String = new String(line, StandardCharsets.ISO_8859_1)

  /** One line per step: the `[name] ` lines, less the echoed "Running: ..."
    * lines and the playbook's own "Starting" line, indented by two spaces.
    */
  private def showSteps(log: String, starting: String): Unit =
    val startingLine = Pattern.compile(starting)
    logLines(log).foreach { b =>
      val t = text(b)
      if StepLine.matcher(t).find() && !t.contains(": Running: ") &&
        !startingLine.matcher(t).find()
      then
        val out = new ByteArrayOutputStream(b.length + 3)
        out.write(' '.toInt)
        out.write(' '.toInt)
        out.write(b, 0, b.length)
        out.write('\n'.toInt)
        Out.outBytes(out.toByteArray)
    }

  private def failedIn(log: String): Boolean =
    logLines(log).exists(b => FailLine.matcher(text(b)).find())

  private def requirePlaybook(src: Sources, playbook: String): Unit =
    if !src.isFile(playbook) then die(1, s"$playbook not found")

  private def opt(cond: Boolean, kv: (String, String)): Map[String, String] =
    if cond then Map(kv) else Map.empty

  /** autostart add|remove: manifests/kvm_autostart.scala, once per hypervisor. */
  private def autostart(sub: String, o: Opts, vms: Vector[String], src: Sources): Int =
    if o.retries.nonEmpty || o.waitS.nonEmpty || o.force || o.mode.nonEmpty || o.yes ||
      o.except.nonEmpty || o.dry
    then die(2, "autostart takes only VM names, --hypervisor and --all")
    val playbook = "manifests/kvm_autostart.scala"
    requirePlaybook(src, playbook)
    val setting = if sub == "remove" then "off" else "on"

    // <hypervisors, space separated> <VM names, space separated, or empty for all>
    def runAutostart(hvs: String, names: String): Boolean =
      val safe = hvs.map(c => if Sources.alnum(c) || c == '\n' then c else '_')
      val log = s"/tmp/vmctl.autostart.$safe.log"
      val rc = Proc.toLog(
        List("orphera", "cluster-playbook", playbook),
        src.env ++ Map(
          "ORPHERA_AUTOSTART" -> setting,
          "ORPHERA_HYPERVISORS" -> hvs,
          "ORPHERA_VM_NAMES" -> names
        ),
        src.root,
        Paths.get(log)
      )
      showSteps(log, "^\\[kvm-autostart\\] Starting")
      if rc != 0 || failedIn(log) then
        Out.err(s"autostart $sub FAILED on $hvs (full output: $log)")
        false
      else true

    val ok =
      if o.all then
        if vms.nonEmpty || o.hvs.nonEmpty then
          die(2, "--all takes no VM names and no --hypervisor")
        runAutostart(src.hypervisorList, "")
      else if o.hvs.nonEmpty then
        if vms.nonEmpty then die(2, "give VM names or --hypervisor, not both")
        runAutostart(o.hvs.mkString(" "), "")
      else
        if vms.isEmpty then
          usage(src)
          sys.exit(2)
        val looked = vms.map(src.vmLookup)
        // hypervisor -> " vm1 vm2", one run per hypervisor in name order (as vmctl.sh, LC_ALL=C sort)
        val byHv = looked.flatten.foldLeft(Vector.empty[(String, String)]) { (acc, vm) =>
          acc.indexWhere(_._1 == vm.hv) match
            case -1 => acc :+ (vm.hv -> s" ${vm.name}")
            case i  => acc.updated(i, (vm.hv, s"${acc(i)._2} ${vm.name}"))
        }
        val runs = byHv.sortBy(_._1).map((hv, names) => runAutostart(hv, names))
        looked.forall(_.isDefined) && runs.forall(identity)
    if !ok then 1
    else
      Out.out(s"autostart $sub done")
      0

  /** shutdown-all and start-all: manifests/kvm_shutdown_all.scala or
    * kvm_start_all.scala, once, for the hypervisors named.
    */
  private def wholeHypervisors(action: String, o: Opts, hvs: Vector[String], src: Sources): Int =
    val playbook =
      if action == "shutdown-all" then
        if o.retries.nonEmpty || o.mode.nonEmpty then
          die(2, "--retries, --hard and --soft do not apply to shutdown-all")
        "manifests/kvm_shutdown_all.scala"
      else
        if o.waitS.nonEmpty || o.force || o.mode.nonEmpty then
          die(2, "--wait, --force, --hard and --soft do not apply to start-all")
        "manifests/kvm_start_all.scala"
    requirePlaybook(src, playbook)
    val log = s"/tmp/vmctl.$action.log"
    val extra = Map("ORPHERA_HYPERVISORS" -> hvs.mkString(" ")) ++
      opt(o.yes, "ORPHERA_VM_CONFIRM" -> "1") ++
      opt(o.waitS.nonEmpty, "ORPHERA_VM_WAIT" -> o.waitS) ++
      opt(o.force, "ORPHERA_VM_FORCE" -> "1") ++
      opt(o.except.nonEmpty, "ORPHERA_VM_EXCLUDE" -> o.except) ++
      opt(o.retries.nonEmpty, "ORPHERA_VM_RETRIES" -> o.retries) ++
      opt(o.all, "ORPHERA_VM_ALL" -> "1") ++
      opt(o.dry, "ORPHERA_VM_DRY_RUN" -> "1")
    val rc = Proc.toLog(
      List("orphera", "cluster-playbook", playbook),
      src.env ++ extra,
      src.root,
      Paths.get(log)
    )
    showSteps(log, "^\\[kvm-[a-z-]*\\] Starting")
    if rc != 0 || failedIn(log) then
      Out.err(s"$action FAILED (full output: $log)")
      1
    else
      if action == "shutdown-all" && !o.yes then
        Out.out("shutdown-all: nothing was shut down; add --yes to do it")
      else Out.out(s"$action done")
      0

  /** start, stop and reboot: manifests/kvm_vm_<action>.scala, once per VM,
    * one after another; a failed VM does not stop the others.
    */
  private def eachVm(action: String, o: Opts, vms: Vector[String], src: Sources): Int =
    val playbook = s"manifests/kvm_vm_$action.scala"
    requirePlaybook(src, playbook)
    val extra = opt(o.retries.nonEmpty, "ORPHERA_VM_RETRIES" -> o.retries) ++
      opt(o.waitS.nonEmpty, "ORPHERA_VM_WAIT" -> o.waitS) ++
      opt(o.force, "ORPHERA_VM_FORCE" -> "1") ++
      opt(o.mode.nonEmpty, "ORPHERA_VM_MODE" -> o.mode)
    def command(vm: Sources.Vm) = List("orphera", "cluster-playbook", playbook, "--config", vm.cfg)
    def logOf(vm: Sources.Vm) = s"/tmp/vmctl.${vm.name}.log"

    // show_vm: one line per step (without the echoed script text and the "Running: ..." lines) and the result
    def show(vm: Sources.Vm, rc: Int): Boolean =
      val log = logOf(vm)
      showSteps(log, "^\\[kvm-vm-[a-z]*\\] Starting")
      if rc != 0 || failedIn(log) then
        Out.err(s"${vm.name}: $action FAILED (full output: $log)")
        false
      else
        Out.out(s"${vm.name}: $action done")
        true

    val failed =
      if Bash.testInt(o.par).forall(_ <= 1) then
        vms.foldLeft(Vector.empty[String]) { (acc, arg) =>
          src.vmLookup(arg) match
            case None => acc :+ arg
            case Some(vm) =>
              val rc = Proc.toLog(command(vm), src.env ++ extra, src.root, Paths.get(logOf(vm)))
              if show(vm, rc) then acc else acc :+ vm.name
        }
      else
        // Look every VM up first, then keep up to --parallel playbooks running, reporting in command-line order.
        val looked = vms.map(a => (a, src.vmLookup(a)))
        val window = Bash.arith(o.par).orElse(Bash.testInt(o.par)).getOrElse(1L)
        var running = Map.empty[Int, Option[Process]]
        var launched = 0
        looked.indices.foldLeft(Vector.empty[String]) { (acc, k) =>
          while launched < looked.size && launched < k + window do
            looked(launched)._2.foreach { vm =>
              running += launched -> Proc.background(command(vm), src.env ++ extra, src.root, Paths.get(logOf(vm)))
            }
            launched += 1
          looked(k) match
            case (_, Some(vm)) =>
              val rc = running.get(k).flatten.fold(127)(_.waitFor())
              if show(vm, rc) then acc else acc :+ vm.name
            case (arg, None) => acc :+ arg
        }
    if failed.isEmpty then 0
    else
      Out.err(s"Failed:${failed.map(f => s" $f").mkString}")
      1
