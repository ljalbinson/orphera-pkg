// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*

trait OrpheraPlaybook extends IOApp.Simple:

  def playbook: Playbook

  def run: IO[Unit] =
    IO.println(s"Running playbook '${playbook.name}'") >> PlaybookRunner.run(
      playbook
    )
