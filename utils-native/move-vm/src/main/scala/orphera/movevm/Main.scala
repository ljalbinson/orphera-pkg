// SPDX-License-Identifier: Apache-2.0

package orphera.movevm

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Base64
import java.util.regex.{Matcher, Pattern}

import scala.annotation.tailrec
import scala.scalanative.posix.unistd
import scala.util.{Random, Try}

import orphera.nativeutils.{ErrMode, Out, Proc, Sources}

/** utils/move-vm.sh in Scala, for Scala Native: moves a VM provisioned on ZFS
  * from one hypervisor to another (a cold move: shut down, snapshot, zfs send
  * | nc | zfs receive, define on the destination, undefine on the source).
  * The same options, checks, remote scripts (Scripts), rollback, output and
  * exit codes; the usage text is the comment at the top of
  * utils/move-vm.sh. Relative paths (config/, inventory.yaml) count from the
  * working directory, as in the shell version: run it in orphera-pkg.
  *
  * Exit codes: 0 the VM is on the destination and answered (or was left shut
  * off as asked), 1 it is not, 2 a usage error.
  */
object Main:

  final case class Opts(
      dry: Boolean = false,
      yes: Boolean = false,
      deleteSrc: Boolean = false,
      noStart: Boolean = false,
      forceStop: Boolean = false,
      stopWait: String = "180",
      bootWait: String = "300",
      xferTimeout: String = "7200",
      port: String = ""
  )

  def main(args: Array[String]): Unit =
    sys.exit(run(args.toList, Sources.load(rootIsCheckout = false)))

  private[movevm] def die(code: Int, msg: String): Nothing =
    Out.err(msg)
    sys.exit(code)

  def usage(src: Sources): Unit =
    src.usage(
      "move-vm.sh",
      "Usage: move-vm [--dry-run] [--yes] [--delete-source] [--no-start] [--force-stop] " +
        "[--stop-wait SEC] [--boot-wait SEC] [--transfer-timeout SEC] [--port N] <vm> <hypervisor>"
    )

  /** The options, up to the first argument that is not one (the shell's `break`). */
  @tailrec
  private def parse(args: List[String], o: Opts, src: Sources): (Opts, List[String]) =
    def num(opt: String, rest: List[String]): (String, List[String]) =
      rest match
        case v :: t if v.nonEmpty && Sources.allDigits(v) => (v, t)
        case _                                           => die(2, s"$opt needs a number")
    args match
      case "--dry-run" :: t       => parse(t, o.copy(dry = true), src)
      case "--yes" :: t           => parse(t, o.copy(yes = true), src)
      case "--delete-source" :: t => parse(t, o.copy(deleteSrc = true), src)
      case "--no-start" :: t      => parse(t, o.copy(noStart = true), src)
      case "--force-stop" :: t    => parse(t, o.copy(forceStop = true), src)
      case "--stop-wait" :: rest =>
        val (v, t) = num("--stop-wait", rest)
        parse(t, o.copy(stopWait = v), src)
      case "--boot-wait" :: rest =>
        val (v, t) = num("--boot-wait", rest)
        parse(t, o.copy(bootWait = v), src)
      case "--transfer-timeout" :: rest =>
        val (v, t) = num("--transfer-timeout", rest)
        parse(t, o.copy(xferTimeout = v), src)
      case "--port" :: rest =>
        val (v, t) = num("--port", rest)
        parse(t, o.copy(port = v), src)
      case ("-h" | "--help") :: _ =>
        usage(src)
        sys.exit(0)
      case opt :: _ if opt.startsWith("-") => die(2, s"unknown option $opt")
      case _                               => (o, args)

  def run(args: List[String], src: Sources): Int =
    val (o, rest) = parse(args, Opts(), src)
    rest match
      case List(vmArg, dst) =>
        val vm = src.vmLookup(vmArg).getOrElse(sys.exit(1))
        if dst.isEmpty || !dst.forall(Sources.plainChar) then
          die(2, s"$dst: not a plain hypervisor name")
        if dst == vm.hv then die(1, s"${vm.name} is already on ${vm.hv}")
        val port = if o.port.isEmpty then (29000 + Random.nextInt(1000)).toString else o.port
        new Move(src, o, vm, dst, port).run()
      case _ =>
        usage(src)
        2

/** One move, step by step as in move-vm.sh, with its rollback state. */
final class Move(
    src: Sources,
    o: Main.Opts,
    vmFound: Sources.Vm,
    dst: String,
    port: String
):
  import Main.die

  private val vm = vmFound.name
  private val cfg = vmFound.cfg
  private val srcHv = vmFound.hv

  private val env = src.env
  private val fsSrc = s"tank/kvm/$srcHv/$vm"
  private val fsDst = s"tank/kvm/$dst/$vm"
  private val dirSrc = s"/exports/kvm/$srcHv/$vm"
  private val dirDst = s"/exports/kvm/$dst/$vm"
  private val tmpDir = env.get("TMPDIR").filter(_.nonEmpty).getOrElse("/tmp")
  // The time comes from date(1), as in the shell version (Scala Native has no java.time).
  private val snap = s"$fsSrc@move-${date("+%Y%m%d-%H%M%S")}"
  private val xmlBackup = s"$tmpDir/move-vm-$vm-$srcHv.xml"

  // What the last vmRun gave: everything that came back, and what follows "RESULT|".
  private var out = ""
  private var result = ""
  // Read from the source, then put into every remote script by pre.
  private var bridges = ""
  private var autostart = ""
  private var xmlB64 = ""
  // Rolling back is only done while the source domain still exists.
  private var stopped = false
  private var wasRunning = false
  private var snapMade = false
  private var dstTouched = false
  private var srcUndefined = false

  private def date(fmt: String): String = Proc.capture(List("date", fmt), env, src.root)._2

  private def log(msg: String): Unit = Out.out(s"[${date("+%H:%M:%S")}] $msg")

  private def err(msg: String): Unit = Out.err(msg)

  private def lines(text: String): Vector[String] = text.split("\n", -1).toVector

  private val Tagged = Pattern.compile("^\\[[^\\]]*\\] *")

  /** The rest of the first line that starts with `key`, alone or after an
    * `[node] ` tag (`sed -n 's/^\[[^]]*\] *KEY//p; s/^KEY//p' | head -1`).
    */
  private def after(text: String, key: String): String =
    lines(text).iterator
      .flatMap { l =>
        val m = Tagged.matcher(l)
        if m.find() && l.startsWith(key, m.end()) then Some(l.substring(m.end() + key.length))
        else if l.startsWith(key) then Some(l.substring(key.length))
        else None
      }
      .nextOption()
      .getOrElse("")

  /** `field <key>`: a DATA|<key>|<value> line in the output of the last vmRun. */
  private def field(key: String): String = after(out, s"DATA|$key|")

  /** `vm_run`: runs the script on the hypervisor as the agent (root). */
  private def vmRun(hv: String, script: String, timeout: String): Unit =
    val (_, text) = Proc.capture(
      List("orphera", "run", "--nodes", hv, "--quiet", "--timeout", timeout, "--", "sh", "-c", script),
      env,
      src.root,
      ErrMode.Merge
    )
    out = text
    result = after(text, "RESULT|")

  /** `step`: vmRun, and on anything but RESULT|ok print the failure (unless
    * `quiet`, the shell's `>/dev/null 2>&1`) and give false.
    */
  private def step(hv: String, script: String, timeout: String = "120", quiet: Boolean = false): Boolean =
    vmRun(hv, script, timeout)
    if result.startsWith("ok|") then true
    else
      if !quiet then
        if result.startsWith("error|") then err(s"  FAILED on $hv: ${result.stripPrefix("error|")}")
        else
          err(s"  FAILED: no answer from $hv")
          lines(out).takeRight(3).foreach(err)
      false

  /** The variables every remote script reads. All values are plain names, numbers or base64. */
  private def pre: String =
    s"VM='$vm'; SRC='$srcHv'; DST='$dst'; PORT='$port'; SNAP='$snap'; BRIDGES='$bridges'; " +
      s"AUTOSTART='$autostart'; XML_B64='$xmlB64'"

  private def withPre(body: String): String = s"$pre\n$body"

  private def abort(msg: String): Nothing =
    err(s"ABORT: $msg")
    if !srcUndefined then
      err("rolling back")
      if dstTouched && !step(dst, withPre(Scripts.cleanupDst), "120", quiet = true) then
        err(s"  could not clean tank/kvm/$dst/$vm on $dst")
      if snapMade && !step(srcHv, withPre(Scripts.cleanupSrc), "60", quiet = true) then
        err(s"  could not remove snapshot $snap on $srcHv")
      if stopped && wasRunning then
        if step(srcHv, withPre(Scripts.start), "60", quiet = true) then
          err(s"  $vm started again on $srcHv")
        else err(s"  could not start $vm on $srcHv: use utils/start-vm.sh $vm")
    else
      err(
        s"the source domain is already undefined: $vm exists on $dst; the original XML is in $xmlBackup; " +
          s"the source dataset $fsSrc on $srcHv is still there"
      )
    sys.exit(1)

  /** A number as bash arithmetic reads it here: empty (or not a number) is 0. */
  private def n(s: String): Long = s.toLongOption.getOrElse(0L)

  private def mib(s: String): Long = n(s) / 1048576

  private def plainAddr(s: String): Boolean = s.nonEmpty && s.forall(Sources.plainChar)

  /** The destination's address for the transfer: its `host:` in the inventory, else its plain name. */
  private def destinationAddress: String =
    val fromInventory =
      src.inventoryFile.toOption
        .flatMap(f => Try(new String(Files.readAllBytes(src.path(f)), StandardCharsets.UTF_8)).toOption)
        .flatMap { text =>
          // awk: `$1 == "-" && $2 == "name:" { hit = ($3 == n) } hit && $1 == "host:" { print $2; exit }`
          var hit = false
          lines(text).iterator
            .flatMap { line =>
              val f = line.trim.split("[ \t]+").toVector
              def at(i: Int) = f.lift(i).getOrElse("")
              if at(0) == "-" && at(1) == "name:" then hit = at(2) == dst
              if hit && at(0) == "host:" then Some(at(1).replace("\"", "")) else None
            }
            .nextOption()
        }
        .getOrElse("")
    if fromInventory.nonEmpty then fromInventory else dst

  private def readReply(): String =
    val sb = new java.lang.StringBuilder
    var c = System.in.read()
    while c >= 0 && c != '\n' do
      sb.append(c.toChar)
      c = System.in.read()
    sb.toString.replaceAll("^[ \t]+|[ \t]+$", "")

  private def fileLines(p: Path): Vector[String] =
    Try(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).toOption match
      case None                   => Vector.empty
      case Some(text) if text.isEmpty => Vector.empty
      case Some(text)             => lines(text.stripSuffix("\n"))

  def run(): Int =
    log(s"checking $vm on $srcHv")
    if !step(srcHv, withPre(Scripts.srcCheck), "120") then sys.exit(1)
    val state = field("state")
    val used = field("used")
    val mem = field("mem")
    autostart = field("autostart")
    bridges = field("bridges").replaceAll("\\s*$", "")
    val vendorSrc = field("vendor")
    if !s"$bridges$autostart".forall(c => Sources.plainChar(c) || c == ' ') then
      die(1, "unexpected characters in the domain's bridges or autostart; not continuing")

    log(s"checking $dst")
    if !step(dst, withPre(Scripts.dstCheck), "120") then sys.exit(1)
    val avail = field("avail")
    val memavail = field("memavail")
    val vendorDst = field("vendor")

    if vendorSrc.nonEmpty && vendorSrc != vendorDst then
      die(
        1,
        s"CPU vendor differs ($srcHv: $vendorSrc, $dst: $vendorDst); a host-passthrough guest may not start. Not moving."
      )
    if used.nonEmpty && avail.toLongOption.exists(a => n(used) + n(used) / 10 > a) then
      die(1, s"not enough space on $dst: dataset uses ${mib(used)} MiB, tank has ${mib(avail)} MiB free")
    if mem.toLongOption.exists(m => memavail.toLongOption.exists(m > _)) then
      err(s"WARNING: $vm has $mem MiB and $dst has $memavail MiB available; it may not start there")

    val dstAddr = destinationAddress
    if !plainAddr(dstAddr) then die(1, s"unusual destination address '$dstAddr'")

    // The source sends to that address, so it must resolve there (not only on scala0).
    log(s"checking that $srcHv can resolve $dstAddr")
    if !step(srcHv, s"DST_ADDR='$dstAddr'\n${Scripts.resolve}", "30") then sys.exit(1)

    val startPlan =
      if o.noStart then "no"
      else if state == "running" then "yes (it is running now)"
      else s"no (it is $state)"
    Vector(
      "",
      s"  VM            $vm  (state: $state, autostart: ${if autostart.isEmpty then "?" else autostart})",
      s"  from          $srcHv  $fsSrc  (${mib(used)} MiB used)",
      s"  to            $dst  $fsDst",
      s"  bridges       ${if bridges.isEmpty then "none" else bridges}",
      s"  transfer      zfs send | nc  ->  $dstAddr:$port  (not encrypted)",
      s"  after         source dataset ${if o.deleteSrc then "destroyed" else "kept"}; " +
        s"config file $cfg: hypervisor: $srcHv -> $dst",
      s"  start on $dst $startPlan",
      ""
    ).foreach(Out.out)
    if o.dry then
      Out.out("dry run: nothing was changed")
      sys.exit(0)

    if !o.yes then
      if unistd.isatty(unistd.STDIN_FILENO) == 0 then die(2, "not a terminal: add --yes to go ahead")
      Out.outPart(s"Move $vm from $srcHv to $dst? The VM will be shut down. [y/N] ")
      readReply() match
        case "y" | "Y" | "yes" | "YES" => ()
        case _ =>
          Out.out("not moved")
          sys.exit(1)

    // 1. shut down
    if state == "running" then wasRunning = true
    if state != "shut off" then
      log(s"shutting $vm down")
      val stopArgs = List("--wait", o.stopWait) ++ (if o.forceStop then List("--force") else Nil)
      val rc = Proc.inherit(src.utilsDir.resolve("stop-vm.sh").toString :: (stopArgs :+ vm), env, src.root)
      if rc != 0 then abort(s"$vm did not shut down (add --force-stop to power it off)")
      stopped = true

    // 2. save the XML, snapshot
    log("reading the domain XML")
    if !step(srcHv, withPre(Scripts.xml), "60") then abort("could not read the domain XML")
    xmlB64 = field("xml")
    val saved = Try(Base64.getDecoder.decode(field("xmlsave"))).getOrElse(Array.emptyByteArray)
    Try(Files.write(Paths.get(xmlBackup), saved))
    if xmlB64.isEmpty then abort("the domain XML came back empty")
    log(s"snapshot $snap")
    if !step(srcHv, withPre(Scripts.snapshot), "120") then abort("could not take the snapshot")
    snapMade = true
    if !step(srcHv, s"DIR='$dirSrc'\n${Scripts.manifest}", "300") then
      abort(s"could not list the files on $srcHv")
    val manSrc = field("manifest")
    val filesSrc = field("files")

    // 3. transfer
    log(s"transfer: $dst listens on $port, $srcHv sends (${mib(used)} MiB)")
    dstTouched = true
    val recvOut = Try(Files.createTempFile(Paths.get(tmpDir), "tmp.", "")).getOrElse(
      die(1, s"mktemp: cannot create a file in $tmpDir")
    )
    val receiver = Proc.background(
      List("orphera", "run", "--nodes", dst, "--quiet", "--timeout", o.xferTimeout, "--", "sh", "-c",
        withPre(Scripts.listen)),
      env,
      src.root,
      recvOut
    )
    def stopReceiver(): Int =
      receiver.foreach(_.destroy())
      receiver.fold(127)(_.waitFor())
    @tailrec
    def waitListening(tries: Int): Boolean =
      if tries == 0 || !receiver.exists(_.isAlive) then false
      else
        vmRun(dst, withPre(Scripts.listening), "30")
        if result.startsWith("ok|") then true
        else
          Thread.sleep(2000)
          waitListening(tries - 1)
    if !waitListening(30) then
      stopReceiver()
      fileLines(recvOut).foreach(l => err(s"    $l"))
      Files.deleteIfExists(recvOut)
      abort(s"the receiver on $dst did not start listening")
    if !step(srcHv, s"DST_ADDR='$dstAddr'\n${withPre(Scripts.send)}", o.xferTimeout) then
      stopReceiver()
      Files.deleteIfExists(recvOut)
      abort("the send failed")
    val recvRc = receiver.fold(127)(_.waitFor())
    val recvResult = after(fileLines(recvOut).mkString("\n"), "RESULT|")
    if recvResult.startsWith("ok|") then
      Files.deleteIfExists(recvOut)
      log(s"  ${recvResult.stripPrefix("ok|")}")
    else
      // Show what the receiver's `orphera run` printed, and keep it.
      val recvLog = Paths.get(s"$tmpDir/move-vm-$vm-receive.log")
      Try(Files.copy(recvOut, recvLog, StandardCopyOption.REPLACE_EXISTING))
      Files.deleteIfExists(recvOut)
      err(s"  receiver on $dst: orphera run exited $recvRc; its output (also in $recvLog):")
      if Try(Files.size(recvLog)).getOrElse(0L) > 0 then
        fileLines(recvLog).takeRight(15).foreach(l => err(s"    $l"))
      else err("    (nothing)")
      if recvResult.startsWith("error|") then
        abort(s"receive on $dst: ${recvResult.stripPrefix("error|")}")
      else abort(s"no answer from the receiver on $dst")

    // 4. verify
    if !step(dst, s"DIR='$dirDst'\n${Scripts.manifest}", "300") then
      abort(s"could not list the files on $dst")
    val manDst = field("manifest")
    val filesDst = field("files")
    if manSrc.isEmpty || manSrc != manDst then
      abort(
        s"the files on $dst differ from $srcHv (source: $filesSrc files, $manSrc; " +
          s"destination: $filesDst files, $manDst)"
      )
    log(s"verified: $filesDst files, names and sizes match")

    // 5. define on the destination, undefine on the source
    if !step(dst, withPre(Scripts.define), "60") then abort(s"could not define $vm on $dst")
    log(s"defined on $dst")
    val left = field("leftover")
    if left.nonEmpty then err(s"WARNING: the XML on $dst still contains the name $srcHv: $left")
    if !step(srcHv, withPre(Scripts.undefine), "60") then
      step(dst, withPre(Scripts.cleanupDst), "60", quiet = true)
      abort(s"could not undefine $vm on $srcHv")
    srcUndefined = true
    log(s"undefined on $srcHv")

    // 6. the config file
    val cfgPath = src.path(cfg)
    if cfgPath.toFile.canWrite then
      // sed -i "s/^\(  hypervisor:[[:space:]]*\).*/\1$dst/"
      Try {
        val text = new String(Files.readAllBytes(cfgPath), StandardCharsets.ISO_8859_1)
        val hyp = Pattern.compile("^(  hypervisor:\\s*).*$")
        val changed = lines(text)
          .map(l => hyp.matcher(l).replaceFirst("$1" + Matcher.quoteReplacement(dst)))
          .mkString("\n")
        Files.write(cfgPath, changed.getBytes(StandardCharsets.ISO_8859_1))
      }
      val now = src.configValue(cfg, "hypervisor")
      if now == dst then log(s"$cfg: hypervisor: $dst")
      else err(s"WARNING: could not set hypervisor: in $cfg (it says '$now'); edit it by hand")
    else err(s"WARNING: $cfg is not writable; set hypervisor: $dst in it by hand")

    // 7. start
    var rc = 0
    if wasRunning && !o.noStart then
      log(s"starting $vm on $dst")
      // libvirt can fail the first start when the tap name it picked (vnetN) is still held by a stale Open vSwitch
      // port on another bridge; the next attempt picks another name, so try twice.
      val started =
        step(dst, withPre(Scripts.start), "60") || {
          log(
            s"start failed on $dst; trying once more in 5 s (see 'journalctl -u libvirtd' there for the reason)"
          )
          Thread.sleep(5000)
          step(dst, withPre(Scripts.start), "60")
        }
      if started then
        val bootWait = n(o.bootWait)
        log(s"waiting up to ${o.bootWait}s for the agent in $vm")
        val answers = Pattern.compile(vm, Pattern.CASE_INSENSITIVE)
        @tailrec
        def waitAgent(t: Long): Option[Long] =
          if t >= bootWait then None
          else
            val (_, hn) = Proc.capture(
              List("orphera", "run", "--nodes", vm, "--quiet", "--timeout", "10", "--", "hostname"),
              env,
              src.root,
              ErrMode.Discard
            )
            if lines(hn).exists(l => answers.matcher(l).find()) then Some(t)
            else
              Thread.sleep(5000)
              waitAgent(t + 5)
        waitAgent(0) match
          case Some(t) => log(s"$vm answers on $dst after about ${t}s")
          case None =>
            err(s"$vm was started on $dst but its agent did not answer in ${o.bootWait}s")
            rc = 1
      else
        err(s"$vm is defined on $dst but did not start: check with 'virsh start $vm' there")
        rc = 1
    else log(s"$vm is shut off on $dst (utils/start-vm.sh $vm starts it)")

    // 8. the source copy
    if o.deleteSrc && rc == 0 then
      if step(srcHv, withPre(Scripts.destroySrc), "300") then log(s"source dataset $fsSrc destroyed")
      else
        err(s"could not destroy $fsSrc on $srcHv")
        rc = 1
    else
      Out.out(s"The source copy is still on $srcHv. Once $vm is confirmed on $dst, remove it there with:")
      Out.out(s"    zfs destroy -r $fsSrc")
    Out.out(s"""Commit the changed config:  git add $cfg && git commit -m "$vm moved to $dst"""")
    rc
