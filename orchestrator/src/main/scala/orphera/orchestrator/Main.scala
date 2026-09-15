// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
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
          Orchestrator.deployDeb(
            targets,
            java.nio.file.Paths.get(local),
            remotePath
          )
        }

      case Right(
            Command.Bootstrap(local, nodeNames, sshUser, sshKeyPath, remotePath)
          ) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.bootstrapAgent(
            targets,
            local,
            sshUser,
            sshKeyPath,
            remotePath
          )
        }

      case Right(
            Command.Teardown(nodeNames, sshUser, sshKeyPath, purge, confirmed)
          ) =>
        if !confirmed then
          IO.println(
            "Refusing to run teardown without --yes (this uninstalls the agent and disables remote management of the host until re-bootstrapped)."
          ) >>
            IO.pure(ExitCode.Error)
        else
          val targets = Inventory.all.filter(n => nodeNames.contains(n.name))
          if targets.isEmpty then
            IO.println("No matching nodes found in inventory.") >> IO.pure(
              ExitCode.Error
            )
          else
            Orchestrator.teardownAgent(
              targets,
              sshUser,
              sshKeyPath,
              purge
            ) >> IO.pure(ExitCode.Success)

      case Right(Command.Fetch(remotePath, localDir, nodeNames)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.fetchFile(
            targets,
            remotePath,
            java.nio.file.Paths.get(localDir)
          )
        }

      case Right(Command.Facts(nodeNames)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.gatherFacts(targets).flatMap { factsByNode =>
            targets.traverse_ { node =>
              factsByNode.get(node.name) match
                case Some(f) =>
                  IO.println(
                    s"[${node.name}] ${f.osId} ${f.osVersion}, kernel ${f.kernelVersion}, " +
                      s"${f.architecture}, ${f.cpuCount} CPUs, ${f.memoryTotalBytes / (1024 * 1024)} MiB RAM"
                  )
                case None =>
                  IO.println(s"[${node.name}] no facts returned")
            }
          }
        }

      case Right(Command.Version(nodeNames)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.getVersions(targets).flatMap { versions =>
            targets.traverse_ { node =>
              IO.println(
                s"[${node.name}] ${versions.getOrElse(node.name, "unknown")}"
              )
            }
          }
        }

      case Right(Command.RunPlaybook(source)) =>
        val playbookResult: Either[String, Playbook] =
          if source.endsWith(".yaml") || source.endsWith(".yml") then
            PlaybookYaml.load(source)
          else
            PlaybookRegistry.all
              .get(source)
              .toRight(
                s"No compiled playbook named '$source' (and it doesn't end in .yaml/.yml)"
              )

        playbookResult match
          case Left(err) =>
            IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(pb) => PlaybookRunner.run(pb) >> IO.pure(ExitCode.Success)

      case Right(
            Command.Reboot(nodeNames, delaySeconds, wait, waitTimeoutSeconds)
          ) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.reboot(targets, delaySeconds, wait, waitTimeoutSeconds)
        }

      case Right(Command.Uptime(nodeNames)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.getUptimes(targets).flatMap { uptimes =>
            targets.traverse_ { node =>
              uptimes.get(node.name) match
                case Some(info) =>
                  IO.println(
                    s"[${node.name}] up ${formatUptime(info.uptimeSeconds)}, load avg (1m) ${info.loadAverage1M}"
                  )
                case None =>
                  IO.println(s"[${node.name}] unreachable")
            }
          }
        }

      case Right(Command.RunClusterPlaybook(path)) =>
        ClusterPlaybookYaml.load(path) match
          case Left(err) =>
            IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(pb) =>
            ClusterPlaybookRunner.run(pb) >> IO.pure(ExitCode.Success)

  private def formatUptime(seconds: Long): String =
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    if days > 0 then s"${days}d ${hours}h ${minutes}m"
    else if hours > 0 then s"${hours}h ${minutes}m"
    else s"${minutes}m"

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
