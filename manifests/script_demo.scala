// SPDX-License-Identifier: Apache-2.0
//
// A Scala playbook written as a script (OrpheraScript, docs/SCRIPT-DSL-DESIGN.md),
// on tst0 and tst1, touching only /tmp and the `tree` package:
//
//   orphera playbook manifests/script_demo.scala
//
// What it shows, and what to expect:
//   - a value read on one node used on another: tst1 writes tst0's kernel
//     version into /tmp/orphera-script-demo.txt;
//   - a decision made from a command's output: tree is installed only where
//     `command -v tree` finds none (after `orphera remove tree --nodes tst0`,
//     only tst0 installs it);
//   - a handler as plain code: "note the change" runs only if the file changed
//     (on the first run; a second run says Skipped: unchanged and does not);
//   - each nodes.each is a barrier, as a stage is.

import cats.effect.IO

import orphera.orchestrator.*

object script_demo extends OrpheraScript:

  def script(using orphera: Orphera): IO[Unit] =
    val both = orphera.nodes("tst0", "tst1")
    val tst0 = orphera.node("tst0")
    val tst1 = orphera.node("tst1")
    for
      // A decision from a command's output, on each node.
      _ <- both.each { node =>
        node.query("is tree installed", "command -v tree || true").flatMap { found =>
          if found.stdout.trim.nonEmpty then IO.unit
          else node.install("install tree", List("tree")).void
        }
      }
      // A value read on one node, used on another.
      kernel <- tst0.query("read tst0's kernel", List("uname", "-r"))
      written <- tst1.writeFile(
        "record tst0's kernel",
        s"tst0 runs kernel ${kernel.stdout.trim}\n",
        "/tmp/orphera-script-demo.txt",
        mode = "0644"
      )
      // A handler as plain code: only when the file changed.
      _ <- IO.whenA(written.changed)(
        tst1.sh("note the change", "date >> /tmp/orphera-script-demo.log; echo noted").void
      )
    yield ()
