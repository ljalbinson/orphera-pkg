// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** Checks for how `orphera audit-log` shortens its Nodes and Detail columns.
  * Run with `sbt "orchestrator/Test/runMain orphera.orchestrator.AuditLogCheck"`.
  */
object AuditLogCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private def same(name: String, got: String, want: String): Unit =
    check(name, got == want, s"got [$got], want [$want]")

  def main(args: Array[String]): Unit =
    val fleet =
      ((0 to 7) ++ (9 to 20)).map(n => s"tst$n").mkString(",") +
        ",gs1,gs2,gs3,st0"
    same(
      "the whole fleet becomes runs",
      LogSummary.compressNodes(fleet),
      "tst0-7,tst9-20,gs1-3,st0"
    )
    same(
      "two in a row stay as two names",
      LogSummary.compressNodes("tst19,tst20"),
      "tst19,tst20"
    )
    same(
      "only neighbours in the list are joined; order is kept",
      LogSummary.compressNodes("tst2,tst0,tst1"),
      "tst2,tst0,tst1"
    )
    same(
      "different prefixes are not joined",
      LogSummary.compressNodes("gs3,st4,st5,st6"),
      "gs3,st4-6"
    )
    same(
      "leading zeros and names without a number are left alone",
      LogSummary.compressNodes("node01,node02,web,(all)"),
      "node01,node02,web,(all)"
    )
    same("one node", LogSummary.compressNodes("tst15"), "tst15")
    same("the no-nodes marker", LogSummary.compressNodes("-"), "-")
    same("an empty list", LogSummary.compressNodes(""), "")

    same(
      "a multi-line script becomes one line",
      LogSummary.oneLine("sh -c V=x\nstate=$(y)\n  case a in\tb", 0),
      "sh -c V=x state=$(y) case a in b"
    )
    same(
      "a long text is cut to the limit, ending in an ellipsis",
      LogSummary.oneLine("a" * 50, 10),
      "a" * 9 + "…"
    )
    same("a text at the limit is not cut", LogSummary.oneLine("abcde", 5), "abcde")
    same("0 means no limit", LogSummary.oneLine("a" * 500, 0), "a" * 500)

    check(
      "audit-log takes --full and --limit in either order",
      Cli.parse(List("audit-log", "--full", "--limit", "5")) ==
        Right(Command.ShowAuditLog(5, true)) &&
        Cli.parse(List("audit-log", "--limit", "5", "--full")) ==
        Right(Command.ShowAuditLog(5, true)) &&
        Cli.parse(List("audit-log")) == Right(Command.ShowAuditLog(20, false))
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"AuditLogCheck: all $passes checks passed")
