// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.common.Operator

/** Checks for operator identity: which names are usable and how a certificate
  * subject becomes an operator and role. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.OperatorCheck"`.
  */
object OperatorCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name${if detail.nonEmpty then s" — $detail" else ""}")

  def main(args: Array[String]): Unit =
    check(
      "usable names",
      List("alice", "bob.smith", "ops_2", "a-b", "9lives", "x" * 64)
        .forall(Operator.validName)
    )
    check(
      "unusable names",
      List("", "Alice", "a b", "a/b", "../x", "-x", ".x", "x" * 65, "a=b")
        .forall(n => !Operator.validName(n))
    )
    check(
      "the openssl subject",
      Operator.subject(Operator("alice", "deployer")) == "/CN=alice/OU=deployer"
    )
    check(
      "a subject as the JDK prints it",
      Operator.fromDn("OU=deployer,CN=alice") ==
        Some(Operator("alice", "deployer")) &&
        Operator.fromDn("CN=alice,OU=deployer") ==
          Some(Operator("alice", "deployer"))
    )
    check(
      "the orchestrator's own certificate is not an operator",
      Operator.fromDn("CN=orphera-orchestrator@scala0").isEmpty
    )
    check(
      "a subject with no role is not an operator",
      Operator.fromDn("CN=alice").isEmpty
    )
    check(
      "an invalid name in the subject is not an operator",
      Operator.fromDn("CN=Alice B,OU=deployer").isEmpty &&
        Operator.fromDn("CN=alice,OU=Dep Loyer").isEmpty
    )
    check(
      "something that is not a subject is not an operator",
      Operator.fromDn("not a dn").isEmpty
    )

    if failures > 0 then
      println(s"$failures check(s) failed, $passes passed")
      sys.exit(1)
    else println(s"OperatorCheck: all $passes checks passed")
