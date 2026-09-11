// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import java.nio.file.Paths

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    Cli.parse(args) match

      case Left(error) =>
        IO.println(s"Error: $error") >> IO.println(Cli.usage) >> IO.pure(
          ExitCode.Error
        )

      case Right(Command.Help) =>
        IO.println(Cli.usage) >> IO.pure(ExitCode.Success)

      case Right(Command.Install(packages, nodeNames, updateCache)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.installPackages(targets, packages, updateCache)
        }

      case Right(Command.Remove(packages, nodeNames, purge)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.removePackages(targets, packages, purge)
        }

      case Right(Command.AutoRemove(nodeNames, purge)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.autoRemove(targets, purge)
        }

      case Right(Command.Copy(local, dest, nodeNames, owner, group, mode)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.copyFile(
            targets,
            Paths.get(local),
            dest,
            owner,
            group,
            mode
          )
        }

      case Right(Command.NetworkApply(nodeNames, timeoutSeconds)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.applyNetworkConfig(targets, timeoutSeconds)
        }

      case Right(Command.DeployAgent(local, remotePath, nodeNames)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.deployDeb(targets, java.nio.file.Paths.get(local), remotePath)
        }

  private def withTargets(nodeNames: Option[List[String]])(
      action: List[Node] => IO[Unit]
  ): IO[ExitCode] =
    val targets = nodeNames match
      case Some(names) => Inventory.all.filter(n => names.contains(n.name))
      case None        => Inventory.all

    if targets.isEmpty then
      IO.println("No matching nodes found in inventory.") >> IO.pure(
        ExitCode.Error
      )
    else action(targets) >> IO.pure(ExitCode.Success)
