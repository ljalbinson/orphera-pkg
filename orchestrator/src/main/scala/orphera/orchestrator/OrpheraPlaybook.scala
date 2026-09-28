// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

trait OrpheraPlaybook extends IOApp.Simple:

  def playbook: Playbook

  def run: IO[Unit] =
    // Resume is read from ORPHERA_RESUME rather than a CLI arg, because
    // IOApp.Simple's `run: IO[Unit]` has no access to process args at
    // all. Main.scala's runScalaPlaybookScript sets this env var on the
    // subprocess when `orphera playbook somefile.scala --resume` (or
    // cluster-playbook) was invoked with --resume — see there and
    // Checkpoint.scala for the full mechanism.
    val resume = sys.env.get("ORPHERA_RESUME").contains("true")
    IO.println(s"Running playbook '${playbook.name}'${if resume then " (resuming from checkpoint)" else ""}") >>
      PlaybookRunner.run(playbook, resume).flatMap { ok =>
        // PlaybookRunner.run reports success/failure as a Boolean rather
        // than always completing as IO[Unit] (see PlaybookRunner).
        // IOApp.Simple's `run` must stay IO[Unit], and only exits the
        // process with a nonzero code when the IO itself raises — so
        // `false` is turned into a raised error, which is what makes
        // `orphera playbook somefile.scala` (run via
        // runScalaPlaybookScript's separate `java` process + waitFor())
        // actually exit nonzero on a real failure instead of always 0.
        if ok then IO.unit
        else IO.raiseError(new RuntimeException(s"Playbook '${playbook.name}' failed (see output above)"))
      }
