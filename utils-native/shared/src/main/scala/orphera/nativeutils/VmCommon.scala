// SPDX-License-Identifier: Apache-2.0

package orphera.nativeutils

import java.util.regex.Pattern

import scala.util.Try

/** What utils/vm-common.sh gives start-vm.sh, stop-vm.sh and reboot-vm.sh
  * besides `vm_lookup` (in Sources): `vm_run` and `vm_report`.
  */
object VmCommon:

  /** What one `vm_run` gave: everything that came back, and what follows
    * "RESULT|" (empty if the hypervisor never answered).
    */
  final case class Run(out: String, result: String)

  private val Tagged = Pattern.compile("^\\[[^\\]]*\\] *")

  def lines(text: String): Vector[String] = text.split("\n", -1).toVector

  /** The rest of the first line that starts with `key`, alone or after an
    * `[node] ` tag (`sed -n 's/^\[[^]]*\] *KEY//p; s/^KEY//p' | head -1`).
    */
  def after(text: String, key: String): String =
    lines(text).iterator
      .flatMap { l =>
        val m = Tagged.matcher(l)
        if m.find() && l.startsWith(key, m.end()) then Some(l.substring(m.end() + key.length))
        else if l.startsWith(key) then Some(l.substring(key.length))
        else None
      }
      .nextOption()
      .getOrElse("")

  /** `vm_run <hypervisor> <script> [timeout]`: runs the script there, as the agent (root). */
  def vmRun(src: Sources, hv: String, script: String, timeout: String = "60"): Run =
    val (_, out) = Proc.capture(
      List("orphera", "run", "--nodes", hv, "--quiet", "--timeout", timeout, "--", "sh", "-c", script),
      src.env,
      src.root,
      ErrMode.Merge
    )
    Run(out, after(out, "RESULT|"))

  /** `vm_report <vm> <hypervisor>`: prints the outcome of a vmRun; false when
    * it was not a success.
    */
  def report(vm: String, hv: String, run: Run): Boolean =
    val head = s"${Fmt.left(vm, 8)} on ${Fmt.left(hv, 4)}"
    if run.result.startsWith("ok|") then
      Out.out(s"$head ${run.result.stripPrefix("ok|")}")
      true
    else if run.result.startsWith("error|") then
      Out.err(s"$head FAILED: ${run.result.stripPrefix("error|")}")
      false
    else
      Out.err(s"$head FAILED: no answer from $hv")
      lines(run.out).takeRight(3).foreach(Out.err)
      false

/** printf field widths (`%-8s`, `%5s`), counted in characters, which is what
  * the shell versions get for the plain names and numbers they print.
  */
object Fmt:
  def left(s: String, width: Int): String =
    if s.length >= width then s else s + " " * (width - s.length)

  def right(s: String, width: Int): String =
    if s.length >= width then s else " " * (width - s.length) + s

/** Numbers as the shell versions read them. */
object Bash:

  /** A value in `$(( ))`: empty is 0, a leading 0 means octal, 0x hex; None
    * for anything else (where bash would stop with an error or read a
    * variable's name).
    */
  def arith(s: String): Option[Long] =
    val t = s.trim
    val (neg, body) =
      if t.startsWith("-") then (true, t.drop(1))
      else (false, t.stripPrefix("+"))
    val v =
      if t.isEmpty then Some(0L)
      else if body.matches("0[xX][0-9a-fA-F]+") then
        Try(java.lang.Long.parseLong(body.drop(2), 16)).toOption
      else if body.matches("0[0-7]+") then Try(java.lang.Long.parseLong(body, 8)).toOption
      else if body.matches("[1-9][0-9]*|0") then body.toLongOption
      else None
    v.map(n => if neg then -n else n)

  /** An operand of `[ a -gt b ]`: a decimal integer, spaces around allowed. */
  def testInt(s: String): Option[Long] =
    val t = s.trim
    if t.matches("[-+]?[0-9]+") then t.stripPrefix("+").toLongOption else None

/** Clock times from date(1), as in the shell versions (Scala Native has no java.time). */
object Clock:
  def date(src: Sources, fmt: String): String =
    Proc.capture(List("date", fmt), src.env, src.root)._2

  /** `[HH:MM:SS]` */
  def hms(src: Sources): String = date(src, "+%H:%M:%S")
