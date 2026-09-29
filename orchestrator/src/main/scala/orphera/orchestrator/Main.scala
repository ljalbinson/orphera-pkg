// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.syntax.all.*
import java.nio.file.Paths

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    dispatch(args).handleErrorWith { err =>
      IO.println(s"Error: ${err.getMessage}") >> IO.pure(ExitCode.Error)
    }

  /** Entry point for every invocation. Auditable commands (see
    * AuditLog.isAuditable) get a start/end record in the audit trail
    * wrapped around dispatchCommand; everything else (read-only
    * commands, a parse failure) goes straight through. `args` gets
    * parsed twice on the audited path — once here just to classify the
    * command, once more inside dispatchCommand to actually run it —
    * a deliberate trade: parsing is cheap and pure, and this way
    * dispatchCommand's existing, already-verified match over every
    * Command case needed no restructuring at all for this feature.
    */
  private def dispatch(args: List[String]): IO[ExitCode] =
    Cli.parse(args).toOption.filter(AuditLog.isAuditable) match
      case Some(command) => auditedRun(command, args)
      case None           => dispatchCommand(args)

  private def auditedRun(command: Command, args: List[String]): IO[ExitCode] =
    for
      invocationId <- IO.delay(java.util.UUID.randomUUID().toString)
      startMs <- IO.delay(System.currentTimeMillis())
      _ <- AuditLog.recordStart(command, invocationId)
      exitCode <- dispatchCommand(args)
      endMs <- IO.delay(System.currentTimeMillis())
      _ <- AuditLog.recordEnd(command, invocationId, exitCode.code, endMs - startMs)
    yield exitCode

  private def dispatchCommand(args: List[String]): IO[ExitCode] =
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

      case Right(Command.DeployAgent(localOpt, remotePath, nodeNames)) =>
        resolveDebPath(localOpt) match
          case Left(err) => IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(local) =>
            withTargets(nodeNames) { targets =>
              Orchestrator.deployDeb(
                targets,
                java.nio.file.Paths.get(local),
                remotePath
              )
            }

      case Right(
            Command.Bootstrap(localOpt, nodeNames, sshUser, sshKeyPath, remotePath)
          ) =>
        resolveDebPath(localOpt) match
          case Left(err) => IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(local) =>
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

      case Right(Command.RunPlaybook(source, resume)) =>
        if source.endsWith(".scala") then runScalaPlaybookScript(source, resume)
        else
          val playbookResult: Either[String, Playbook] =
            if source.endsWith(".yaml") || source.endsWith(".yml") then
              PlaybookYaml.load(source)
            else
              PlaybookRegistry.all
                .get(source)
                .toRight(
                  s"No compiled playbook named '$source' (and it doesn't end in .yaml/.yml/.scala)"
                )

          playbookResult match
            case Left(err) =>
              IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
            case Right(pb) =>
              // PlaybookRunner.run reports whether every node's task
              // sequence actually succeeded, and --resume skips any
              // (node, task) already recorded complete from a previous
              // run's checkpoint file — see PlaybookRunner/Checkpoint.
              PlaybookRunner
                .run(pb, resume)
                .map(ok => if ok then ExitCode.Success else ExitCode.Error)

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

      case Right(Command.RunClusterPlaybook(path, resume)) =>
        if path.endsWith(".scala") then runScalaPlaybookScript(path, resume)
        else
          ClusterPlaybookYaml.load(path) match
            case Left(err) =>
              IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
            case Right(pb) =>
              // Same --resume support as RunPlaybook above, via
              // ClusterPlaybookRunner/Checkpoint.
              ClusterPlaybookRunner
                .run(pb, resume)
                .map(ok => if ok then ExitCode.Success else ExitCode.Error)

      case Right(Command.RunCommand(command, nodeNames, timeoutSeconds)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.executeCommand(targets, command, timeoutSeconds)
        }

      case Right(Command.LogSummary(target)) =>
        // Read-only reporting command: its own exit code reflects
        // whether the summary could be produced (file found/parsed),
        // not whether the run it's summarizing succeeded — see
        // LogSummary.run's doc comment.
        LogSummary
          .run(target)
          .map(ok => if ok then ExitCode.Success else ExitCode.Error)

      case Right(Command.ShowAuditLog(limit)) =>
        // Same "own exit code is about whether the report itself
        // rendered, not about what it reports" reasoning as
        // LogSummary above — a table full of FAILED rows is still a
        // successfully produced report.
        LogSummary
          .runAuditLog(limit)
          .map(ok => if ok then ExitCode.Success else ExitCode.Error)

  private def formatUptime(seconds: Long): String =
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    if days > 0 then s"${days}d ${hours}h ${minutes}m"
    else if hours > 0 then s"${hours}h ${minutes}m"
    else s"${minutes}m"

  /** Compiles and runs a standalone `.scala` playbook script in a separate
    * `java` process (see scripting/Main.scala). `resume` is passed via the
    * `ORPHERA_RESUME` environment variable rather than as a process argument:
    * OrpheraPlaybook/OrpheraClusterPlaybook extend `IOApp.Simple`, whose
    * `run: IO[Unit]` has no access to the process's command-line args at all,
    * so an env var is the only way to reach them here without changing that
    * trait's shape (and without needing to touch scripting/Main.scala's own
    * arg-forwarding, which this file has no visibility into).
    */
  private def runScalaPlaybookScript(
      scriptPath: String,
      resume: Boolean
  ): IO[ExitCode] =
    IO.blocking {
      findLatestJar("scripting/target", "scripting-assembly", ".jar") match
        case None =>
          Left(
            "scripting-assembly jar not found under scripting/target/. Run 'sbt scripting/assembly' first."
          )
        case Some(jar) =>
          val builder = new ProcessBuilder(
            "java",
            "-cp",
            jar,
            "orphera.scripting.Main",
            scriptPath
          )
          if resume then builder.environment().put("ORPHERA_RESUME", "true")
          val exit = builder
            .inheritIO()
            .start()
            .waitFor()
          Right(exit)
    }.flatMap {
      case Left(err) => IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
      case Right(0)  => IO.pure(ExitCode.Success)
      case Right(_)  => IO.pure(ExitCode.Error)
    }

  /** Resolves `bootstrap`/`deploy-agent`'s local `.deb` path: an
    * explicit `--file` wins, otherwise auto-discovers the freshly
    * built `orphera-agent_*.deb` in the current directory — the same
    * "find it by naming convention" approach as findLatestJar below,
    * for the same underlying reason: neither command should be taking
    * an arbitrary free-form path as its normal mode of use. Whatever
    * path this resolves to still gets checked against its own package
    * metadata in Orchestrator.requireOrpheraAgentPackage before
    * anything is pushed anywhere — this only decides which file, not
    * whether it's trusted.
    */
  private def resolveDebPath(explicit: Option[String]): Either[String, String] =
    explicit match
      case Some(path) => Right(path)
      case None =>
        findLatestDeb(".").toRight(
          "No orphera-agent_*.deb found in the current directory, and no --file given. " +
            "Run 'make deb' (or 'make release') first, or pass --file <path>."
        )

  private def findLatestDeb(dir: String): Option[String] =
    val base = new java.io.File(dir)
    if !base.isDirectory then None
    else
      Option(base.listFiles()).toList.flatten
        .filter(f =>
          f.isFile && f.getName.startsWith("orphera-agent_") && f.getName
            .endsWith("_amd64.deb")
        )
        // mtime, not name — unlike findLatestJar's build-directory
        // names, "0.1.9" sorts after "0.1.10" as a string, so a
        // lexicographic sort here would silently pick the wrong file
        // once the version climbs past a single digit.
        .sortBy(_.lastModified())
        .lastOption
        .map(_.getAbsolutePath)

  private def findLatestJar(
      dir: String,
      prefix: String,
      suffix: String
  ): Option[String] =
    val base = new java.io.File(dir)
    if !base.isDirectory then None
    else
      val candidates =
        Option(base.listFiles()).toList.flatten
          .filter(_.isDirectory)
          .flatMap(scalaDir => Option(scalaDir.listFiles()).toList.flatten)
          .filter(f =>
            f.isFile && f.getName.startsWith(prefix) && f.getName
              .endsWith(suffix)
          )
          .sortBy(_.getName)
      candidates.lastOption.map(_.getAbsolutePath)

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
