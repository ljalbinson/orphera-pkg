// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

/** Mirror of OrpheraPlaybook for staged/cluster playbooks — extend this to make
  * a ClusterPlaybookDsl-built playbook directly runnable as its own program,
  * e.g. via `orphera cluster-playbook somefile.scala`.
  */
trait OrpheraClusterPlaybook extends IOApp.Simple:

  def playbook: ClusterPlaybook

  def run: IO[Unit] =
    IO.println(
      s"Running cluster playbook '${playbook.name}'"
    ) >> ClusterPlaybookRunner.run(playbook).flatMap { ok =>
      // Same fix as OrpheraPlaybook: ClusterPlaybookRunner.run now
      // reports success/failure as a Boolean instead of always
      // completing as IO[Unit]. IOApp.Simple only exits the process
      // nonzero when the IO raises, so `false` is turned into a raised
      // error here — this is what makes `orphera cluster-playbook
      // somefile.scala` actually exit nonzero when a stage fails.
      if ok then IO.unit
      else IO.raiseError(new RuntimeException(s"Cluster playbook '${playbook.name}' failed (see output above)"))
    }
