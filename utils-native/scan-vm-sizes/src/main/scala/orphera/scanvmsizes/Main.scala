// SPDX-License-Identifier: Apache-2.0

package orphera.scanvmsizes

import java.util.regex.Pattern

import scala.util.Try

import orphera.nativeutils.{Bash, ErrMode, Fmt, Out, Proc, Sources}

/** utils/scan-vm-sizes.sh in Scala, for Scala Native: compares the CPU and
  * memory of every VM as its config file, libvirt and the running guest see
  * them. The same arguments (VM names or numbers; none means every VM with a
  * config file), GUEST_MEM_MIN_PCT, remote commands, table and exit code (1 if
  * a size differs or a hypervisor did not answer). Read-only.
  */
object Main:

  def main(args: Array[String]): Unit =
    sys.exit(new Scan(Sources.load(rootIsCheckout = false), args.toList).run())

/** `sort -V` (GNU filevercmp): names with numbers in them in numeric order. */
object VersionOrder extends Ordering[String]:

  private val Suffix = Pattern.compile("(\\.[A-Za-z~][A-Za-z0-9~]*)*$")

  private def order(c: Char): Int =
    if c.isDigit then 0
    else if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') then c.toInt
    else if c == '~' then -1
    else c.toInt + 256

  private def isDigit(s: String, i: Int): Boolean = i < s.length && s(i) >= '0' && s(i) <= '9'

  /** Debian's verrevcmp. */
  private def verrevcmp(a: String, b: String): Int =
    var i = 0
    var j = 0
    var result = 0
    while result == 0 && (i < a.length || j < b.length) do
      var firstDiff = 0
      while result == 0 && ((i < a.length && !isDigit(a, i)) || (j < b.length && !isDigit(b, j))) do
        val ac = if i < a.length then order(a(i)) else 0
        val bc = if j < b.length then order(b(j)) else 0
        if ac != bc then result = ac - bc
        i += 1
        j += 1
      if result == 0 then
        while i < a.length && a(i) == '0' do i += 1
        while j < b.length && b(j) == '0' do j += 1
        while isDigit(a, i) && isDigit(b, j) do
          if firstDiff == 0 then firstDiff = a(i) - b(j)
          i += 1
          j += 1
        if isDigit(a, i) then result = 1
        else if isDigit(b, j) then result = -1
        else if firstDiff != 0 then result = firstDiff
    result

  private def prefix(s: String): String =
    val m = Suffix.matcher(s)
    if m.find() then s.substring(0, m.start()) else s

  def compare(a: String, b: String): Int =
    if a == b then 0
    else if a.isEmpty then -1
    else if b.isEmpty then 1
    else if a.startsWith(".") && !b.startsWith(".") then -1
    else if !a.startsWith(".") && b.startsWith(".") then 1
    else
      val byPrefix = verrevcmp(prefix(a), prefix(b))
      if byPrefix != 0 then byPrefix
      else
        val whole = verrevcmp(a, b)
        if whole != 0 then whole else a.compareTo(b)

final class Scan(src: Sources, args: List[String]):

  private def die(msg: String): Nothing =
    Out.err(msg)
    sys.exit(1)

  /** `$(( ))` on a value from a config file or a guest: bash stops at a name
    * (with `set -u`, an unset variable) and skips the assignment at a bad number.
    */
  private def arith(s: String): Option[Long] =
    Bash.arith(s) match
      case some @ Some(_) => some
      case None if s.trim.matches("[A-Za-z_][A-Za-z0-9_]*") =>
        die(s"scan-vm-sizes: ${s.trim}: unbound variable")
      case None =>
        Out.err(s"scan-vm-sizes: $s: not a number")
        None

  private def want(v: String): Boolean =
    args.isEmpty || args.exists(a => if Sources.allDigits(a) then s"tst$a" == v else a == v)

  private def lines(text: String): Vector[String] = text.split("\n", -1).toVector

  /** printf '%-8s %-5s | %5s %7s | %5s %7s %-9s | %5s %7s |' and, with a verdict, ' %s'. */
  private def printRow(cols: Vector[String], verdict: Option[String]): Unit =
    val widths = Vector(-8, -5, 5, 7, 5, 7, -9, 5, 7)
    val cells = cols.zip(widths).map((c, w) => if w < 0 then Fmt.left(c, -w) else Fmt.right(c, w))
    val text =
      s"${cells(0)} ${cells(1)} | ${cells(2)} ${cells(3)} | ${cells(4)} ${cells(5)} ${cells(6)} | " +
        s"${cells(7)} ${cells(8)} |"
    Out.out(verdict.fold(text)(v => s"$text $v"))

  def run(): Int =
    val minPctS = src.env.get("GUEST_MEM_MIN_PCT").filter(_.nonEmpty).getOrElse("88")
    if !Sources.allDigits(minPctS) then die("GUEST_MEM_MIN_PCT must be a whole number")
    val dir = src.configDir match
      case Left(msg) => die(msg)
      case Right(d)  => d

    // ---- static: the config files
    val configs = Try(Option(src.path(dir).toFile.list()).fold(Vector.empty[String])(_.toVector))
      .getOrElse(Vector.empty)
      .filter(n => n.endsWith(".yaml") && !n.startsWith("."))
      .sorted
      .map(n => s"$dir/$n")
      .filter(src.isFile)
    final case class Cfg(hv: String, cpu: String, mem: String)
    val found: Vector[(String, Cfg)] = configs.flatMap { cfg =>
      val hv = src.configValue(cfg, "hypervisor")
      val vm = src.configValue(cfg, "hostname")
      if hv.isEmpty || vm.isEmpty then None
      else if !s"$hv$vm".forall(Sources.plainChar) then
        Out.err(s"$cfg: unusual characters in hypervisor or hostname; skipped")
        None
      else if !want(vm) then None
      else Some(vm -> Cfg(hv, src.configValue(cfg, "cpu"), src.configValue(cfg, "memory")))
    }
    if found.isEmpty then die(s"no VM config with hypervisor: and hostname: found in $dir")
    val conf: Map[String, Cfg] = found.toMap // a later file with the same hostname wins, as in bash
    val vms = found.map(_._1).sorted(using VersionOrder)
    def cfgOf(v: String): Cfg = conf.getOrElse(v, Cfg("", "", ""))
    val hvs = vms.map(cfgOf(_).hv).distinct.sorted

    // ---- dynamic: the hypervisors (libvirt)
    val (_, raw) = Proc.capture(
      List("orphera", "run", "--nodes", hvs.mkString(","), "--quiet", "--timeout", "60", "--", "sh", "-c",
        Scripts.hypervisor),
      src.env,
      src.root,
      ErrMode.Merge
    )
    final case class Dom(state: String, cpu: String, mem: String)
    val nodeLine = Pattern.compile("^\\[([^\\]]+)\\] (.*)$")
    val (hvUp, doms, seen) =
      lines(raw).foldLeft((Set.empty[String], Map.empty[String, Dom], Vector.empty[String])) {
        case ((upAcc, domAcc, seenAcc), line) =>
          val m = nodeLine.matcher(line)
          if !m.matches() then (upAcc, domAcc, seenAcc)
          else
            val node = m.group(1)
            val rest = m.group(2)
            if rest == "HV|up" then (upAcc + node, domAcc, seenAcc)
            else if rest.startsWith("VM|") then
              // IFS='|' read -r _ name state cpu mem
              val f = rest.split("\\|", -1).toVector
              def at(i: Int) = f.lift(i).getOrElse("")
              val mem = if f.size > 4 then f.drop(4).mkString("|") else ""
              val key = s"$node/${at(1)}"
              val dom = Dom(at(2).replaceFirst("\\s+$", ""), at(3), mem)
              (upAcc, domAcc + (key -> dom), if seenAcc.contains(key) then seenAcc else seenAcc :+ key)
            else (upAcc, domAcc, seenAcc)
      }

    // ---- dynamic: the running guests
    val running = vms.filter(v => doms.get(s"${cfgOf(v).hv}/$v").exists(_.state == "running"))
    val guests: Map[String, (String, String)] =
      if running.isEmpty then Map.empty
      else
        val (_, graw) = Proc.capture(
          List("orphera", "run", "--nodes", running.mkString(","), "--quiet", "--timeout", "60", "--", "sh",
            "-c", Scripts.guest),
          src.env,
          src.root,
          ErrMode.Merge
        )
        lines(graw).foldLeft(Map.empty[String, (String, String)]) { (acc, line) =>
          val at = line.indexOf("] G|")
          if !line.startsWith("[") || at < 1 then acc
          else
            val gnode = line.takeWhile(_ != ']').stripPrefix("[")
            val grest = line.substring(at + 4)
            val gcpu = grest.takeWhile(_ != '|')
            val gkb = (if grest.contains('|') then grest.dropWhile(_ != '|').drop(1) else grest)
              .replaceAll("\\s", "")
            val digits = s"$gcpu$gkb"
            if digits.isEmpty || !Sources.allDigits(digits) then acc
            else Bash.arith(gkb).fold(acc)(kb => acc + (gnode -> (gcpu, (kb / 1024).toString)))
        }

    // ---- compare and print
    Out.out(
      s"${Fmt.left("VM", 8)} ${Fmt.left("HV", 5)} | ${Fmt.left("CONFIG", 13)} | ${Fmt.left("HYPERVISOR", 24)} | " +
        s"${Fmt.left("GUEST", 13)} | CHECK"
    )
    printRow(Vector("", "", "cpu", "MiB", "cpu", "MiB", "state", "cpu", "MiB"), Some(""))

    val minPct = arith(minPctS).getOrElse(88L)
    var diffs = 0
    var badHv = 0
    var tCC, tCM, tHC, tHM, tGC, tGM = 0L
    def add(total: Long, v: String): Long = arith(if v.isEmpty then "0" else v).fold(total)(total + _)

    vms.foreach { v =>
      val c = cfgOf(v)
      val hv = c.hv
      val (cc, cm) = (c.cpu, c.mem)
      val dom = doms.get(s"$hv/$v")
      val (hc, hm, hs) = dom.fold(("", "", ""))(d => (d.cpu, d.mem, d.state))
      val (gc, gm) = guests.getOrElse(v, ("", ""))
      var notes = Vector.empty[String]
      val d0 = diffs

      if !hvUp.contains(hv) then
        notes :+= s"hypervisor $hv did not answer"
        badHv = 1
      else if hs.isEmpty then
        notes :+= s"not defined on $hv"
        diffs += 1
      else
        if cc.nonEmpty && cc != hc then
          notes :+= s"cpu: config $cc, libvirt $hc"
          diffs += 1
        if cm.nonEmpty && cm != hm then
          notes :+= s"memory: config $cm, libvirt $hm"
          diffs += 1

      if hs == "running" then
        if gc.isEmpty then notes :+= "guest did not answer"
        else
          if cc.nonEmpty && cc != gc then
            notes :+= s"cpu: config $cc, guest $gc"
            diffs += 1
          if cm.nonEmpty && Bash.testInt(cm).exists(_ > 0) then
            // [ $((gm * 100)) -lt $((cm * MIN_PCT)) ] || [ "$gm" -gt "$cm" ]
            val low = (Bash.arith(gm), Bash.arith(cm)) match
              case (Some(g), Some(m)) => g * 100 < m * minPct
              case _                  => false
            val high = (Bash.testInt(gm), Bash.testInt(cm)) match
              case (Some(g), Some(m)) => g > m
              case _                  => false
            if low || high then
              notes :+= s"memory: config $cm, guest sees $gm"
              diffs += 1
        tGC = add(tGC, gc)
        tGM = add(tGM, gm)

      tCC = add(tCC, cc)
      tCM = add(tCM, cm)
      if hs == "running" then
        tHC = add(tHC, hc)
        tHM = add(tHM, hm)

      val verdict =
        if notes.isEmpty then if hs != "running" && hs.nonEmpty then s"ok ($hs)" else "ok"
        else s"${if diffs > d0 then "DIFF" else "NOTE"} ${notes.mkString("; ")}"
      def dash(s: String) = if s.isEmpty then "-" else s
      printRow(Vector(v, hv, dash(cc), dash(cm), dash(hc), dash(hm), dash(hs), dash(gc), dash(gm)), Some(verdict))
    }

    printRow(
      Vector("total", "", tCC.toString, tCM.toString, tHC.toString, tHM.toString, "(running)", tGC.toString,
        tGM.toString),
      None
    )

    // VMs libvirt knows that have no config file (only when the whole fleet was scanned).
    if args.isEmpty then
      val extra = seen.filterNot(key => conf.contains(key.dropWhile(_ != '/').drop(1)))
      if extra.nonEmpty then
        Out.out("")
        Out.out("on a hypervisor but with no config file here:")
        extra.sorted(using VersionOrder).foreach { e =>
          val d = doms.getOrElse(e, Dom("", "", ""))
          Out.out(s"  ${Fmt.left(e, 14)} ${Fmt.left(d.state, 9)} ${Fmt.right(d.cpu, 3)} cpu ${Fmt.right(d.mem, 7)} MiB")
        }

    Out.out("")
    if diffs > 0 || badHv > 0 then
      Out.out(s"differences: $diffs; hypervisors not answering: $badHv")
      1
    else
      Out.out("every size agrees")
      0
