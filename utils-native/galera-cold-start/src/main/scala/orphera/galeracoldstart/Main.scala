// SPDX-License-Identifier: Apache-2.0

package orphera.galeracoldstart

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

import scala.annotation.tailrec
import scala.util.Try

import orphera.nativeutils.{Bash, Clock, ErrMode, Out, Proc, Sources}

/** utils/galera-cold-start.sh in Scala, for Scala Native: brings the Galera
  * cluster on tst3, tst4 and tst5 back after every node was stopped,
  * bootstrapping from the node with the highest committed position, and
  * refuses to guess when no node reports one or their cluster UUIDs differ.
  * The same options (--bootstrap <node>, --check), decisions, messages, remote
  * scripts and exit codes as the shell version; its usage text is lines 2 to
  * 24 of utils/galera-cold-start.sh, as there.
  */
object Main:

  /** What a node reported: none | up size=N | down seqno=N safe=0|1 uuid=U */
  final case class State(line: String):
    val kind: String = line.takeWhile(_ != ' ')
    val size: String = State.field(line, ".*size=([0-9]*).*")
    val seq: String = State.field(line, ".*seqno=(-?[0-9]*).*")
    val safe: String = State.field(line, ".*safe=([01]).*")
    val uuid: String = State.field(line, ".*uuid=([0-9a-f-]*).*")

  object State:
    /** `sed -n 's/<re>/\1/p'`: the group if the line matches, else "". */
    def field(line: String, re: String): String =
      val m = Pattern.compile(re).matcher(line)
      if m.matches() then m.group(1) else ""

  def main(args: Array[String]): Unit =
    sys.exit(new Run(Sources.plain()).run(args.toList))

/** One run, with the clock, the remote commands and the decisions. */
final class Run(src: Sources):
  import Main.State

  private val Nodes = Vector("tst3", "tst4", "tst5")
  private val self = src.utilsDir.resolve("galera-cold-start").toString

  private def log(msg: String): Unit = Out.out(s"[${Clock.hms(src)}] galera: $msg")

  /** `run_on <node> <script> [timeout]`: everything `orphera run` printed (stdout and stderr). */
  private def runOn(n: String, script: String, timeout: String = "120"): String =
    Proc.capture(
      List("orphera", "run", "sh", "-c", script, "--nodes", n, "--timeout", timeout),
      src.env,
      src.root,
      ErrMode.Merge
    )._2

  private def lines(text: String): Vector[String] = text.split("\n", -1).toVector

  /** `... <<<"$out"`: the lines of a captured output (one empty line if it was empty). */
  private def indented(out: String): Vector[String] = lines(out).map(l => s"    $l")

  private def usage(): Unit =
    Try(new String(Files.readAllBytes(src.utilsDir.resolve("galera-cold-start.sh")), StandardCharsets.UTF_8))
      .toOption match
      case Some(text) => lines(text).slice(1, 24).foreach(Out.out)
      case None =>
        Out.out("Usage:  utils/galera-cold-start [--bootstrap <node>] [--check]")

  private def parse(args: List[String]): (String, Boolean) =
    @tailrec
    def go(args: List[String], force: String, check: Boolean): (String, Boolean) =
      args match
        case Nil => (force, check)
        case "--bootstrap" :: rest =>
          rest match
            case v :: t if v.nonEmpty => go(t, v, check)
            case _ =>
              // bash's ${2:?...}
              Out.err("galera-cold-start: 2: --bootstrap needs a node name")
              sys.exit(1)
        case "--check" :: t => go(t, force, true)
        case ("-h" | "--help") :: _ =>
          usage()
          sys.exit(0)
        case other :: _ =>
          Out.err(s"unknown option: $other")
          sys.exit(2)
    go(args, "", false)

  private def healthy(n: String): Boolean =
    lines(runOn(n, Scripts.health)).exists(_.startsWith(s"[$n] GALERA-HEALTH ok"))

  private val wsrepDump =
    "echo \"unit: $(systemctl is-active mariadb)\"; mariadb -N -e \"SHOW STATUS WHERE Variable_name IN " +
      "('wsrep_cluster_size','wsrep_cluster_status','wsrep_ready','wsrep_local_state_comment')\" 2>&1 | tr '\\t' '='"

  /** Up to 36 rounds 5 s apart: size 3, Primary and ready on every node. */
  private def waitHealthy(): Boolean =
    log("waiting for size 3, Primary, ready on every node (up to 3 minutes)")
    @tailrec
    def round(i: Int): Boolean =
      if i > 36 then false
      else
        val bad = Nodes.filterNot(healthy)
        if bad.isEmpty then true
        else
          if i % 6 == 0 then
            log(s"  still waiting (${i * 5}s): not yet healthy:${bad.map(n => s" $n").mkString}")
          Thread.sleep(5000)
          round(i + 1)
    if round(1) then true
    else
      log("not healthy after 3 minutes; wsrep state per node:")
      Nodes.foreach { n =>
        val out = runOn(n, wsrepDump)
        if out.nonEmpty then indented(out).foreach(Out.out)
      }
      false

  /** `systemctl start mariadb` on the node; exits 1 with its output if that failed. */
  private def startOn(n: String): Unit =
    val out = runOn(n, "systemctl start mariadb", "600")
    if !out.contains("success=true") then
      log(s"starting MariaDB on $n failed:")
      indented(out).takeRight(15).foreach(Out.out)
      sys.exit(1)

  private def gt(a: String, b: String): Boolean =
    (Bash.testInt(a), Bash.testInt(b)) match
      case (Some(x), Some(y)) => x > y
      case _                  => false

  private def sameNumber(a: String, b: String): Boolean =
    (Bash.testInt(a), Bash.testInt(b)) match
      case (Some(x), Some(y)) => x == y
      case _                  => false

  def run(args: List[String]): Int =
    val (force, check) = parse(args)
    val stopLoops = if check then "0" else "1"

    val states: Map[String, State] = Nodes.map { n =>
      val out = runOn(n, s"STOP_LOOPS=$stopLoops\n${Scripts.state}")
      val prefix = s"[$n] GALERA-STATE "
      val line = lines(out).filter(_.startsWith(prefix)).lastOption.map(_.drop(prefix.length)).getOrElse("")
      if line.isEmpty then
        log(s"no state from $n (agent not answering, or MariaDB could not be stopped). Last output:")
        val running = Pattern.compile("^\\[.*\\] Running:")
        indented(out).zip(lines(out)).filterNot((_, l) => running.matcher(l).find()).map(_._1).takeRight(6)
          .foreach(Out.out)
        sys.exit(1)
      log(s"$n: $line")
      n -> State(line)
    }.toMap
    def st(n: String): State = states.getOrElse(n, State(""))

    val none = Nodes.count(st(_).kind == "none")
    val standalone = Nodes.count(n => st(n).kind == "up" && (st(n).size.isEmpty || st(n).size == "0"))
    val up = Nodes.count(n => st(n).kind == "up" && st(n).size.nonEmpty && st(n).size != "0")

    if none > 0 || standalone > 0 then
      log("Galera is not (fully) built on these nodes yet; leaving it to the galera step")
      return 0
    if up == 3 && Nodes.forall(st(_).size == "3") then
      log("all three nodes are in the cluster (size 3); nothing to do")
      return 0

    val upNodes = Nodes.filter(st(_).kind == "up")
    if up >= 2 && upNodes.map(st(_).size).distinct.size > 1 then
      log("SPLIT BRAIN: the running nodes disagree about the cluster size, so they are in separate components:")
      upNodes.foreach(n => log(s"  $n sees ${st(n).size} member(s)"))
      log(
        "Not touching it. Compare 'SHOW STATUS LIKE wsrep_last_committed' on each side, keep the side with the newer data,"
      )
      log(
        "then on each node of the other side: systemctl stop mariadb; rm /var/lib/mysql/grastate.dat; systemctl start mariadb"
      )
      return 1
    if up == 3 then
      log("all three nodes are running but report different sizes; check 'SHOW STATUS LIKE wsrep_%'")
      return 1

    if up > 0 then
      log(s"$up node(s) are in a running cluster; starting MariaDB on the others so they rejoin")
      if check then return 0
      Nodes.filter(st(_).kind == "down").foreach(startOn)
      if waitHealthy() then
        log("cluster healthy: size 3 on every node")
        return 0
      log("the cluster did not reach size 3 within 3 minutes")
      return 1

    // All three are down: choose where to bootstrap.
    val byPosition = Nodes.drop(1).foldLeft(Nodes(0)) { (best, n) =>
      val s = st(n)
      val b = st(best)
      if gt(s.seq, b.seq) || (sameNumber(s.seq, b.seq) && s.safe == "1" && b.safe != "1") then n else best
    }
    val uuids = Nodes.filter(n => Bash.testInt(st(n).seq).exists(_ >= 0)).map(st(_).uuid).distinct.size

    val best =
      if force.nonEmpty then
        if !Nodes.contains(force) then
          log(s"--bootstrap must be one of: ${Nodes.mkString(" ")}")
          return 2
        log(s"bootstrapping from $force as instructed (positions above are not used to choose)")
        force
      else
        if Bash.testInt(st(byPosition).seq).exists(_ < 0) then
          log("no node could report a committed position (seqno -1 everywhere). Not guessing which has the newest data.")
          log(s"If you know (or the data is disposable), run again with:  $self --bootstrap <node>")
          return 1
        if uuids > 1 then
          log("the nodes report different cluster UUIDs; their histories have diverged. Not choosing for you.")
          log(s"Decide which node to keep, then:  $self --bootstrap <node>")
          return 1
        log(s"highest committed position: $byPosition (seqno ${st(byPosition).seq})")
        byPosition
    if check then
      log(s"would bootstrap from $best, then start the other two")
      return 0

    val out = runOn(
      best,
      "sed -i 's/safe_to_bootstrap: 0/safe_to_bootstrap: 1/' /var/lib/mysql/grastate.dat 2>/dev/null; " +
        "galera_new_cluster || { echo 'galera_new_cluster failed' >&2; journalctl -xeu mariadb --no-pager -n 30 >&2; exit 1; }",
      "300"
    )
    if !out.contains("success=true") then
      log(s"bootstrap on $best failed:")
      indented(out).takeRight(25).foreach(Out.out)
      return 1
    log(s"bootstrapped a new cluster on $best")
    Nodes.filterNot(_ == best).foreach { n =>
      startOn(n)
      log(s"$n started and joined")
    }
    if waitHealthy() then
      log("cluster healthy: size 3, Primary, ready on every node")
      0
    else
      log("the cluster did not reach size 3 within 3 minutes")
      1
