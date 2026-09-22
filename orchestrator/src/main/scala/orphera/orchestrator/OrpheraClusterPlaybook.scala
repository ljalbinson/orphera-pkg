// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

/** Mirror of OrpheraPlaybook for staged/cluster playbooks — extend
  * this to make a ClusterPlaybookDsl-built playbook directly runnable
  * as its own program, e.g. via
  * `orphera cluster-playbook somefile.scala`.
  */
trait OrpheraClusterPlaybook extends IOApp.Simple:

  def playbook: ClusterPlaybook

  def run: IO[Unit] =
    IO.println(s"Running cluster playbook '${playbook.name}'") >> ClusterPlaybookRunner.run(playbook)
