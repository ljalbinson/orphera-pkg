// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

trait OrpheraPlaybook extends IOApp.Simple:

  def playbook: Playbook

  def run: IO[Unit] =
    IO.println(s"Running playbook '${playbook.name}'") >> PlaybookRunner.run(
      playbook
    ).flatMap { ok =>
      // PlaybookRunner.run now reports success/failure as a Boolean
      // rather than always completing as IO[Unit] (see PlaybookRunner
      // for why). IOApp.Simple's `run` must stay IO[Unit], and it only
      // exits the process with a nonzero code when the IO itself
      // raises — so a `false` here is turned into a raised error,
      // which is what makes `orphera playbook somefile.scala` (run via
      // runScalaPlaybookScript's separate `java` process + waitFor())
      // actually exit nonzero on a real failure instead of always 0.
      if ok then IO.unit
      else IO.raiseError(new RuntimeException(s"Playbook '${playbook.name}' failed (see output above)"))
    }
