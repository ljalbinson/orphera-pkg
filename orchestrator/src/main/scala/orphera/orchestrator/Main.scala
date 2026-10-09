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
    * AuditLog.isAuditable) get a start/end record in the audit trail wrapped
    * around dispatchCommand; everything else (read-only commands, a parse
    * failure) goes straight through. `args` gets parsed twice on the audited
    * path — once here just to classify the command, once more inside
    * dispatchCommand to actually run it — a deliberate trade: parsing is cheap
    * and pure, and this way dispatchCommand's existing, already-verified match
    * over every Command case needed no restructuring at all for this feature.
    */
  private def dispatch(args: List[String]): IO[ExitCode] =
    Cli.parse(args).toOption.filter(AuditLog.isAuditable) match
      case Some(command) => auditedRun(command, args)
      case None          => dispatchCommand(args)

  private def auditedRun(command: Command, args: List[String]): IO[ExitCode] =
    for
      invocationId <- IO.delay(java.util.UUID.randomUUID().toString)
      startMs <- IO.delay(System.currentTimeMillis())
      _ <- AuditLog.recordStart(command, invocationId)
      exitCode <- dispatchCommand(args)
      endMs <- IO.delay(System.currentTimeMillis())
      _ <- AuditLog.recordEnd(
        command,
        invocationId,
        exitCode.code,
        endMs - startMs
      )
    yield exitCode

  private def dispatchCommand(args: List[String]): IO[ExitCode] =
    Cli.parse(args) match

      case Left(error) =>
        // The whole usage text only for an unknown command; for a bad
        // argument it would bury the one-line error.
        val hint =
          if error.startsWith("Unknown command") then IO.println(Cli.usage)
          else IO.println("Run 'orphera help' for usage.")
        IO.println(s"Error: $error") >> hint >> IO.pure(ExitCode.Error)

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

      case Right(Command.DistUpgrade(nodeNames, updateCache)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.distUpgrade(targets, updateCache)
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

      case Right(
            Command.WriteFile(
              destPath,
              contentSource,
              nodeNames,
              owner,
              group,
              mode
            )
          ) =>
        resolveContentSource(contentSource).flatMap {
          case Left(err) =>
            IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(bytes) =>
            withTargets(nodeNames) { targets =>
              Orchestrator.writeFile(
                targets,
                bytes,
                destPath,
                owner,
                group,
                mode
              )
            }
        }

      case Right(Command.NetworkApply(nodeNames, timeoutSeconds)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.applyNetworkConfig(targets, timeoutSeconds)
        }

      case Right(Command.DeployAgent(localOpt, remotePath, nodeNames)) =>
        AgentPackages.resolve(localOpt) match
          case Left(err) =>
            IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(packages) =>
            val targets = nodeNames match
              case Some(names) =>
                Inventory.all.filter(n => names.contains(n.name))
              case None => Inventory.all
            if targets.isEmpty then
              IO.println("No matching nodes found in inventory.") >> IO.pure(
                ExitCode.Error
              )
            else
              // Unlike withTargets (which always reports Success once
              // its action runs, whatever that action actually did),
              // this reflects Orchestrator.deployDeb's per-node version
              // confirmation in both the summary and the exit code —
              // closing the gap where `deploy-agent` exiting 0 only
              // ever meant "every install was launched," not "every
              // node actually finished upgrading."
              Orchestrator
                .deployAgent(targets, packages, remotePath)
                .flatMap { confirmed =>
                  val (ok, failed) =
                    targets.partition(n => confirmed.getOrElse(n.name, false))
                  IO.println(
                    s"${ok.size}/${targets.size} node(s) confirmed running the new version" +
                      (if failed.isEmpty then ""
                       else
                         s" — not confirmed: ${failed.map(_.name).mkString(", ")}")
                  ) >> IO.pure(if failed.isEmpty then ExitCode.Success
                  else ExitCode.Error)
                }

      case Right(
            Command.Bootstrap(
              localOpt,
              nodeNames,
              sshUser,
              sshKeyPath,
              remotePath,
              forgetHostKey
            )
          ) =>
        AgentPackages.resolve(localOpt) match
          case Left(err) =>
            IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
          case Right(packages) =>
            withTargets(nodeNames) { targets =>
              Orchestrator.bootstrapAgent(
                targets,
                packages,
                sshUser,
                sshKeyPath,
                remotePath,
                forgetHostKey
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

      case Right(Command.RunPlaybook(source, resume, config)) =>
        if source.endsWith(".scala") then
          runScalaPlaybookScript(source, resume, config)
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

          // --config only reaches a running .scala script via
          // ORPHERA_CONFIG (see runScalaPlaybookScript) — a .yaml
          // file or a compiled, registered playbook has no code of its
          // own to read that env var, so there's nothing to wire it
          // into. Warn rather than silently ignore, so a typo'd
          // --config on the wrong kind of playbook doesn't look like
          // it did something.
          val configWarning =
            if config.isDefined then
              IO.println(
                "Note: --config has no effect on a .yaml file or a compiled playbook — only a .scala script can read it (via ConfigYaml.fromEnv())."
              )
            else IO.unit

          configWarning >> (playbookResult match
            case Left(err) =>
              IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
            case Right(pb) =>
              // PlaybookRunner.run reports whether every node's task
              // sequence actually succeeded, and --resume skips any
              // (node, task) already recorded complete from a previous
              // run's checkpoint file — see PlaybookRunner/Checkpoint.
              PlaybookRunner
                .run(pb, resume)
                .map(ok => if ok then ExitCode.Success else ExitCode.Error))

      case Right(
            Command.Reboot(nodeNames, delaySeconds, wait, waitTimeoutSeconds)
          ) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.reboot(targets, delaySeconds, wait, waitTimeoutSeconds)
        }

      case Right(Command.Shutdown(nodeNames, delaySeconds, confirmed)) =>
        if !confirmed then
          IO.println(
            "Refusing to run shutdown without --yes (this powers the host OFF; it cannot be powered back on remotely)."
          ) >> IO.pure(ExitCode.Error)
        else if nodeNames.isEmpty then
          IO.println(
            "Refusing to run shutdown without explicit targets: pass --nodes or --node-groups."
          ) >> IO.pure(ExitCode.Error)
        else
          withTargets(nodeNames) { targets =>
            Orchestrator.shutdown(targets, delaySeconds)
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

      case Right(
            Command.RunClusterPlaybook(
              paths,
              resume,
              configs,
              parallel,
              quiet,
              bootstrap
            )
          ) =>
        configs match
          case Nil =>
            runClusterPlaybookSequence(paths, resume, None, bootstrap)
          case single :: Nil =>
            runClusterPlaybookSequence(paths, resume, Some(single), bootstrap)
          case many =>
            runClusterPlaybookMatrix(
              paths,
              resume,
              many,
              parallel,
              quiet,
              bootstrap
            )

      case Right(Command.RunCommand(command, nodeNames, timeoutSeconds)) =>
        withTargets(nodeNames) { targets =>
          Orchestrator.executeCommand(targets, command, timeoutSeconds)
        }

      case Right(Command.Pipeline(steps, nodeNames, parallel, quiet)) =>
        runPipeline(steps, nodeNames.getOrElse(Nil), parallel, quiet)

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

  /** Runs one or more cluster playbooks in order, stopping at the first one
    * that fails — same "don't press on past a failure" convention as a single
    * playbook's own stages, applied one level up. `--resume` (if given) applies
    * to every playbook in the sequence, not just the first, since each has its
    * own independent checkpoint file. Prints a "[N/M]" header before each one
    * only when there's more than one, so a normal single-playbook invocation's
    * output is unchanged.
    */
  private def runClusterPlaybookSequence(
      paths: List[String],
      resume: Boolean,
      config: Option[String],
      bootstrap: BootstrapOpts
  ): IO[ExitCode] =
    val total = paths.size
    def go(remaining: List[(String, Int)]): IO[ExitCode] =
      remaining match
        case Nil                   => IO.pure(ExitCode.Success)
        case (path, index) :: rest =>
          val header =
            if total > 1 then IO.println(s"[$index/$total] $path") else IO.unit
          val step =
            if path == Cli.BootstrapStep then
              runBootstrapStep(config, bootstrap, line => IO.println(line))
            else runOneClusterPlaybook(path, resume, config)
          header >> step.flatMap {
            case ExitCode.Success => go(rest)
            case failed           => IO.pure(failed)
          }
    go(paths.zipWithIndex.map { case (p, i) => (p, i + 1) })

  /** One result row of a multi-config run. */
  private final case class MatrixResult(
      label: String,
      ok: Boolean,
      seconds: Long,
      failedAt: Option[String],
      log: java.nio.file.Path
  )

  /** The short name of a config file for output prefixes and log names: its
    * file name without the extension (`config/tst3.yaml` -> `tst3`).
    */
  private def configLabel(config: String): String =
    val name = Paths.get(config).getFileName.toString
    val dot = name.lastIndexOf('.')
    if dot > 0 then name.substring(0, dot) else name

  /** `cluster-playbook <chain> --config a b c ...`: runs the whole playbook
    * sequence once per config, at most `parallel` configs at a time (default
    * min(4, number of configs)). Within one config the sequence is exactly the
    * single-run behaviour — in order, stopping at the first failure — and a
    * failure stops only that config; the others carry on. Each playbook is the
    * same child process a single run starts, with `ORPHERA_CONFIG` set to that
    * config and `ORPHERA_RUN_TAG` set to its label (so the run-log and
    * checkpoint files of parallel runs of the same playbook don't collide; see
    * RunLog/Checkpoint). Only `.scala` playbooks: a YAML playbook can't read
    * the config, so running it once per config would just repeat it.
    *
    * Output of each child is read line by line, prefixed `[label]`, appended to
    * `.orphera-build-logs/<time>/<label>.log`, and printed (or, with `quiet`,
    * printed only if it is a stage header or a failure).
    */
  private def runClusterPlaybookMatrix(
      paths: List[String],
      resume: Boolean,
      configs: List[String],
      parallel: Option[Int],
      quiet: Boolean,
      bootstrap: BootstrapOpts
  ): IO[ExitCode] =
    val labels = configs.map(configLabel)
    if labels.distinct.size != labels.size then
      IO.println(
        "Error: the --config files must have distinct names (file name without extension), " +
          "since the name labels each run's output and logs"
      ) >> IO.pure(ExitCode.Error)
    else if paths.exists(p =>
        !p.endsWith(".scala") && !Cli.builtinSteps.contains(p)
      )
    then
      IO.println(
        "Error: several --config files need .scala playbooks (or the @bootstrap step) only — a .yaml playbook cannot read a config"
      ) >> IO.pure(ExitCode.Error)
    else
      val width = math.min(parallel.getOrElse(4), configs.size)
      for
        stamp <- IO.delay(
          java.time.LocalDateTime
            .now()
            .format(
              java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            )
        )
        logDir = Paths.get(".orphera-build-logs", stamp)
        _ <- IO.blocking(java.nio.file.Files.createDirectories(logDir))
        _ <- IO.println(
          s"Running ${paths.size} playbook(s) for ${configs.size} config(s), " +
            s"$width at a time — logs in $logDir"
        )
        sem <- cats.effect.std.Semaphore[IO](width.toLong)
        results <- configs.zip(labels).parTraverse { case (config, label) =>
          sem.permit.use { _ =>
            runChainForConfig(
              paths,
              resume,
              config,
              label,
              logDir,
              quiet,
              bootstrap
            )
          }
        }
        code <- summarizeMatrix(results)
      yield code

  private def runChainForConfig(
      paths: List[String],
      resume: Boolean,
      config: String,
      label: String,
      logDir: java.nio.file.Path,
      quiet: Boolean,
      bootstrap: BootstrapOpts
  ): IO[MatrixResult] =
    val logFile = logDir.resolve(s"$label.log")

    // Output of in-process steps (@bootstrap): same destination as a child
    // playbook's lines — the config's log file, and the terminal (all of it,
    // or only errors/failures when quiet), prefixed with the config's name.
    def emit(line: String): IO[Unit] =
      IO.blocking(
        java.nio.file.Files.write(
          logFile,
          java.util.Collections.singletonList(line),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND
        )
      ) >> (if !quiet || isProgressLine(line) then IO.println(s"[$label] $line")
            else IO.unit)

    // Returns the path of the playbook that failed, or None if all passed.
    def go(remaining: List[(String, Int)]): IO[Option[String]] =
      remaining match
        case Nil                   => IO.pure(None)
        case (path, index) :: rest =>
          val header = s"[$index/${paths.size}] $path"
          IO.blocking(
            java.nio.file.Files.write(
              logFile,
              java.util.Collections.singletonList(header),
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.APPEND
            )
          ) >> IO.println(s"[$label] $header") >> {
            val step =
              if path == Cli.BootstrapStep then
                runBootstrapStep(Some(config), bootstrap, emit)
              else
                runScalaPlaybookCaptured(
                  path,
                  resume,
                  config,
                  label,
                  logFile,
                  quiet
                )
            step.flatMap {
              case ExitCode.Success => go(rest)
              case _                => IO.pure(Some(path))
            }
          }

    for
      start <- IO.monotonic
      failedAt <- go(paths.zipWithIndex.map { case (p, i) => (p, i + 1) })
      end <- IO.monotonic
    yield MatrixResult(
      label,
      failedAt.isEmpty,
      (end - start).toSeconds,
      failedAt,
      logFile
    )

  /** Same child process as runScalaPlaybookScript, but with its output piped
    * through this process instead of inherited: each line is prefixed with
    * `[label]`, appended to `logFile`, and printed (all of it, or only progress
    * lines and failures when `quiet`). The child and everything it started are
    * killed if this fiber is cancelled (Ctrl-C).
    */
  private def runScalaPlaybookCaptured(
      scriptPath: String,
      resume: Boolean,
      config: String,
      label: String,
      logFile: java.nio.file.Path,
      quiet: Boolean
  ): IO[ExitCode] =
    IO.blocking(
      findLatestJar("scripting/target", "scripting-assembly", ".jar")
    ).flatMap {
      case None =>
        IO.println(
          s"[$label] Error: scripting-assembly jar not found under scripting/target/. Run 'sbt scripting/assembly' first."
        ) >> IO.pure(ExitCode.Error)
      case Some(jar) =>
        val builder = new ProcessBuilder(
          "java",
          "-cp",
          jar,
          "orphera.scripting.Main",
          scriptPath
        )
        builder.environment().put("ORPHERA_CONFIG", config)
        builder.environment().put("ORPHERA_RUN_TAG", label)
        if resume then builder.environment().put("ORPHERA_RESUME", "true")
        runProcessCaptured(builder, label, logFile, quiet)
    }

  /** Starts `builder` with its stderr merged into stdout and its stdin closed,
    * pumps the output through pumpLines (prefix, log file, quiet filter), and
    * waits for it. The process and everything it started are killed if this
    * fiber is cancelled (Ctrl-C).
    */
  private def runProcessCaptured(
      builder: ProcessBuilder,
      label: String,
      logFile: java.nio.file.Path,
      quiet: Boolean
  ): IO[ExitCode] =
    builder.redirectErrorStream(true)
    Resource
      .make(IO.blocking(builder.start()))(killProcessTree)
      .use { process =>
        for
          _ <- IO.blocking(process.getOutputStream.close())
          pump <- pumpLines(process, label, logFile, quiet).start
          exit <- IO.interruptible(process.waitFor())
          _ <- pump.joinWithNever
        yield if exit == 0 then ExitCode.Success else ExitCode.Error
      }

  private def killProcessTree(process: Process): IO[Unit] =
    IO.blocking {
      process.descendants().forEach { h =>
        h.destroyForcibly()
        ()
      }
      process.destroyForcibly()
      ()
    }

  private def pumpLines(
      process: Process,
      label: String,
      logFile: java.nio.file.Path,
      quiet: Boolean
  ): IO[Unit] =
    IO.blocking {
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream)
      )
      val writer = java.nio.file.Files.newBufferedWriter(
        logFile,
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
      try
        var line = reader.readLine()
        while line != null do
          writer.write(line)
          writer.newLine()
          writer.flush()
          // A verb run as a child already prefixes its lines with [node];
          // don't print the label twice.
          val shown =
            if line.startsWith(s"[$label]") then line else s"[$label] $line"
          if !quiet || isProgressLine(line) then println(shown)
          line = reader.readLine()
      finally writer.close()
    }

  /** Lines worth showing in a `--quiet` multi-config run. */
  private def isProgressLine(line: String): Boolean =
    line.startsWith("--- Stage") ||
      line.startsWith("Running cluster playbook") ||
      line.startsWith("Error") ||
      line.contains("FAILED") ||
      line.contains("success=false")

  private def summarizeMatrix(results: List[MatrixResult]): IO[ExitCode] =
    val rows = results.map { r =>
      val secs = "%02d".format(r.seconds % 60)
      val time = s"${r.seconds / 60}m${secs}s"
      val name = r.label.padTo(10, ' ')
      val stoppedAt = r.failedAt.getOrElse("?")
      if r.ok then s"  $name OK      $time"
      else s"  $name FAILED  $time  (stopped at $stoppedAt; log ${r.log})"
    }
    val failed = results.filterNot(_.ok)
    val tails = failed.flatTraverse { r =>
      IO.blocking {
        val all = java.nio.file.Files.readAllLines(r.log)
        val n = all.size
        s"--- last lines of ${r.label} ---" ::
          (math.max(0, n - 8) until n).map(i => all.get(i)).toList
      }.handleError(_ => Nil)
    }
    for
      _ <- IO.println("")
      _ <- IO.println("=== Summary ===")
      _ <- rows.traverse_(row => IO.println(row))
      lines <- tails
      _ <- if lines.nonEmpty then IO.println("") else IO.unit
      _ <- lines.traverse_(line => IO.println(line))
    yield if failed.isEmpty then ExitCode.Success else ExitCode.Error

  /** `pipeline <step>... --nodes a,b,c`: every node runs the steps in order
    * (each step is one orphera verb line, run against that node alone as a
    * child `orphera` process), the nodes in parallel — at most `parallel` at a
    * time, default min(4, number of nodes). A failed step stops that node only.
    * Output, per-node logs, summary and exit code work as in the multi-config
    * `cluster-playbook`.
    */
  private def runPipeline(
      steps: List[String],
      nodes: List[String],
      parallel: Option[Int],
      quiet: Boolean
  ): IO[ExitCode] =
    val targets = nodes.distinct
    val unknown = targets.filterNot(n => Inventory.all.exists(_.name == n))
    if unknown.nonEmpty then
      IO.println(
        s"Error: not in inventory.yaml: ${unknown.mkString(", ")}"
      ) >> IO.pure(ExitCode.Error)
    else
      val width = math.min(parallel.getOrElse(4), targets.size)
      for
        stamp <- IO.delay(
          java.time.LocalDateTime
            .now()
            .format(
              java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            )
        )
        logDir = Paths.get(".orphera-build-logs", stamp)
        _ <- IO.blocking(java.nio.file.Files.createDirectories(logDir))
        _ <- IO.println(
          s"Running ${steps.size} step(s) on ${targets.size} node(s), " +
            s"$width at a time — logs in $logDir"
        )
        sem <- cats.effect.std.Semaphore[IO](width.toLong)
        results <- targets.parTraverse { node =>
          sem.permit.use(_ => runPipelineForNode(steps, node, logDir, quiet))
        }
        code <- summarizeMatrix(results)
      yield code

  /** The command line that runs this same orchestrator with `args`. */
  private def selfCommand(args: List[String]): List[String] =
    List(
      Paths.get(System.getProperty("java.home"), "bin", "java").toString,
      "-cp",
      System.getProperty("java.class.path"),
      "orphera.orchestrator.Main"
    ) ++ args

  private def runPipelineForNode(
      steps: List[String],
      node: String,
      logDir: java.nio.file.Path,
      quiet: Boolean
  ): IO[MatrixResult] =
    val logFile = logDir.resolve(s"$node.log")

    def note(line: String): IO[Unit] =
      IO.blocking(
        java.nio.file.Files.write(
          logFile,
          java.util.Collections.singletonList(line),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND
        )
      ) >> IO.println(s"[$node] $line")

    // Returns the step that failed, or None if all passed.
    def go(remaining: List[(String, Int)]): IO[Option[String]] =
      remaining match
        case Nil                  => IO.pure(None)
        case (step, index) :: rest =>
          Cli.stepArgs(step, node) match
            case Left(err) =>
              note(s"Error: $err") >> IO.pure(Some(step))
            case Right(args) =>
              note(s"[$index/${steps.size}] $step") >> {
                val builder = new ProcessBuilder(selfCommand(args)*)
                runProcessCaptured(builder, node, logFile, quiet).flatMap {
                  case ExitCode.Success => go(rest)
                  case _                => IO.pure(Some(step))
                }
              }

    for
      start <- IO.monotonic
      failedAt <- go(steps.zipWithIndex.map { case (s, i) => (s, i + 1) })
      end <- IO.monotonic
    yield MatrixResult(
      node,
      failedAt.isEmpty,
      (end - start).toSeconds,
      failedAt,
      logFile
    )

  /** The `@bootstrap` chain step: installs the agent on the node named by the
    * config's `hostname` (which must be in inventory.yaml), over SSH, exactly
    * as `orphera bootstrap` does for that node. `emit` receives every progress
    * line and error message.
    */
  private def runBootstrapStep(
      config: Option[String],
      opts: BootstrapOpts,
      emit: String => IO[Unit]
  ): IO[ExitCode] =
    def fail(message: String): IO[ExitCode] =
      emit(s"Error: $message") >> IO.pure(ExitCode.Error)

    config match
      case None =>
        fail(
          s"${Cli.BootstrapStep} needs --config (the node to bootstrap is the config's hostname)"
        )
      case Some(path) =>
        ConfigYaml.load(path) match
          case Left(err)     => fail(err)
          case Right(params) =>
            Inventory.all.find(_.name == params.hostname) match
              case None =>
                fail(
                  s"${params.hostname} (hostname in $path) is not in inventory.yaml — add it first"
                )
              case Some(node) =>
                AgentPackages.resolve(opts.file) match
                  case Left(err)       => fail(err)
                  case Right(packages) =>
                    Orchestrator
                      .bootstrapAgent(
                        List(node),
                        packages,
                        opts.sshUser,
                        opts.sshKeyPath,
                        "/tmp/orphera-agent.deb",
                        opts.forgetHostKey,
                        (n, line) => emit(s"[${n.name}] $line")
                      )
                      .attempt
                      .flatMap {
                        case Right(_)  => IO.pure(ExitCode.Success)
                        case Left(err) =>
                          fail(
                            s"bootstrap of ${node.name} failed: ${err.getMessage}"
                          )
                      }

  private def runOneClusterPlaybook(
      path: String,
      resume: Boolean,
      config: Option[String]
  ): IO[ExitCode] =
    if path.endsWith(".scala") then runScalaPlaybookScript(path, resume, config)
    else
      // Same reasoning as RunPlaybook above: --config only reaches a
      // running .scala script, never a YAML cluster-playbook, which has
      // no code of its own to read ORPHERA_CONFIG.
      val configWarning =
        if config.isDefined then
          IO.println(
            "Note: --config has no effect on a .yaml cluster-playbook — only a .scala script can read it (via ConfigYaml.fromEnv())."
          )
        else IO.unit

      configWarning >> (ClusterPlaybookYaml.load(path) match
        case Left(err) =>
          IO.println(s"Error: $err") >> IO.pure(ExitCode.Error)
        case Right(pb) =>
          // Same --resume support as RunPlaybook above, via
          // ClusterPlaybookRunner/Checkpoint.
          ClusterPlaybookRunner
            .run(pb, resume)
            .map(ok => if ok then ExitCode.Success else ExitCode.Error))

  /** Compiles and runs a standalone `.scala` playbook script in a separate
    * `java` process (see scripting/Main.scala). `resume`/`config` are passed
    * via the `ORPHERA_RESUME`/`ORPHERA_CONFIG` environment variables rather
    * than as process arguments: OrpheraPlaybook/ OrpheraClusterPlaybook extend
    * `IOApp.Simple`, whose `run: IO[Unit]` has no access to the process's
    * command-line args at all, so an env var is the only way to reach them here
    * without changing that trait's shape (and without needing to touch
    * scripting/Main.scala's own arg-forwarding, which this file has no
    * visibility into). A script reads ORPHERA_CONFIG via ConfigYaml.fromEnv(),
    * same file.
    */
  private def runScalaPlaybookScript(
      scriptPath: String,
      resume: Boolean,
      config: Option[String] = None
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
          config
            .foreach(path => builder.environment().put("ORPHERA_CONFIG", path))
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

  /** Resolves write-file's --content/--content-file into real bytes. The
    * literal-string case is pure (just UTF-8 encoding) but is still routed
    * through IO here so both branches return the same type — the actual file
    * read (--content-file) is the one that genuinely needs IO.blocking, same as
    * every other local-disk read in this file.
    */
  private def resolveContentSource(
      source: Either[String, String]
  ): IO[Either[String, Array[Byte]]] =
    source match
      case Left(literal) =>
        IO.pure(
          Right(literal.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        )
      case Right(path) =>
        IO.blocking(
          java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
        ).attempt
          .map {
            case Left(err) =>
              Left(s"Could not read --content-file '$path': ${err.getMessage}")
            case Right(bytes) => Right(bytes)
          }

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
    else
      // A node reporting an unsuccessful RESULT (see ConsoleRenderer) makes
      // the command exit non-zero; previously this always returned Success.
      IO.delay(ConsoleRenderer.resetFailures()) >> action(targets) >>
        IO.delay(
          if ConsoleRenderer.failureCount == 0 then ExitCode.Success
          else ExitCode.Error
        )
