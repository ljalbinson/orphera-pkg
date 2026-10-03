// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

enum Command:
  case Install(
      packages: List[String],
      nodes: Option[List[String]],
      updateCache: Boolean
  )
  case Remove(
      packages: List[String],
      nodes: Option[List[String]],
      purge: Boolean
  )
  case AutoRemove(nodes: Option[List[String]], purge: Boolean)
  case Copy(
      localPath: String,
      destPath: String,
      nodes: Option[List[String]],
      owner: String,
      group: String,
      mode: Int
  )
  // `contentSource` is Left(literal) for `--content <string>` or
  // Right(localPath) for `--content-file <path>` — mutually exclusive,
  // see parseWriteFile. A file path is only read (in Main.scala, at
  // dispatch time) once a --content-file is actually given; parsing
  // itself stays pure, same as every other Command here.
  case WriteFile(
      destPath: String,
      contentSource: Either[String, String],
      nodes: Option[List[String]],
      owner: String,
      group: String,
      mode: Int
  )
  case NetworkApply(nodes: Option[List[String]], timeoutSeconds: Int)
  case DeployAgent(
      localPath: Option[String],
      remotePath: String,
      nodes: Option[List[String]]
  )
  case Bootstrap(
      localPath: Option[String],
      nodes: Option[List[String]],
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String
  )
  case Teardown(
      nodes: List[String],
      sshUser: String,
      sshKeyPath: Option[String],
      purge: Boolean,
      confirmed: Boolean
  )
  case Fetch(remotePath: String, localDir: String, nodes: Option[List[String]])
  case Facts(nodes: Option[List[String]])
  case RunPlaybook(path: String, resume: Boolean)
  case Reboot(
      nodes: Option[List[String]],
      delaySeconds: Int,
      waitForReturn: Boolean,
      waitTimeoutSeconds: Int
  )
  case Version(nodes: Option[List[String]])
  case Uptime(nodes: Option[List[String]])
  // `paths` is one or more — `cluster-playbook a.scala b.scala c.scala`
  // runs each in turn, in the order given, stopping at the first one that
  // fails (same "don't press on past a failure" convention as a single
  // playbook's own stages). Saves typing multiple full invocations for
  // the common case of a fixed sequence (teardown, then rebuild, then a
  // follow-up exercise) without needing a wrapper shell script.
  case RunClusterPlaybook(paths: List[String], resume: Boolean)
  case RunCommand(
      command: List[String],
      nodes: Option[List[String]],
      timeoutSeconds: Int
  )
  // Reads a .orphera-logs/*.jsonl run log and prints a human-readable
  // summary table — see LogSummary.scala. `target` is either a direct
  // .jsonl path or a playbook name (resolves to that playbook's most
  // recently modified log file).
  case LogSummary(target: String)
  // Reads .orphera-audit/audit.jsonl (see AuditLog.scala) and prints a
  // human-readable table of recent mutating-command invocations —
  // command, nodes, outcome, duration — correlated from its
  // command_start/command_end pairs. Distinct from LogSummary: that's
  // one playbook run's task-by-task detail, this is a flat trail
  // across every mutating command ever run here.
  case ShowAuditLog(limit: Int)
  case Help

object Cli:

  def parse(args: List[String]): Either[String, Command] =
    args match
      case "install" :: rest =>
        parseInstall(rest, Nil, None, updateCache = false)
      case "remove" :: rest     => parseRemove(rest, Nil, None, purge = false)
      case "autoremove" :: rest => parseAutoRemove(rest, None, purge = false)
      case "copy" :: local :: dest :: rest =>
        parseCopy(rest, local, dest, None, "", "", 0)
      case "write-file" :: dest :: rest =>
        parseWriteFile(rest, dest, None, None, None, "", "", 0)
      case "network-apply" :: rest => parseNetworkApply(rest, None, 60)
      case "deploy-agent" :: rest  =>
        parseDeployAgent(rest, None, "/tmp/orphera-agent.deb", None)
      case "bootstrap" :: rest =>
        parseBootstrap(
          rest,
          None,
          None,
          "root",
          None,
          "/tmp/orphera-agent.deb"
        )
      case "teardown" :: rest =>
        parseTeardown(rest, Nil, "root", None, purge = false, confirmed = false)
      case "fetch" :: remote :: rest  => parseFetch(rest, remote, ".", None)
      case "facts" :: rest            => parseFacts(rest, None)
      case "playbook" :: path :: rest =>
        parsePlaybookFlags(rest, resume = false).map(
          Command.RunPlaybook(path, _)
        )
      case "version" :: rest => parseVersion(rest, None)
      case "uptime" :: rest  => parseUptime(rest, None)
      case "reboot" :: rest  =>
        parseReboot(rest, None, 5, waitForReturn = false, 300)
      case "cluster-playbook" :: rest =>
        // One or more paths, all before any flag — `--resume` (the only
        // flag) only makes sense once, applied to the whole sequence, not
        // interleaved per-path, so paths.takeWhile/dropWhile on "starts
        // with --" is enough: no path is ever expected to itself start
        // with "--".
        val paths = rest.takeWhile(!_.startsWith("--"))
        val flagArgs = rest.drop(paths.length)
        if paths.isEmpty then
          Left("cluster-playbook requires at least one playbook path")
        else
          parsePlaybookFlags(flagArgs, resume = false).map(
            Command.RunClusterPlaybook(paths, _)
          )
      case "log-summary" :: Nil =>
        Left(
          "log-summary requires a target: a .jsonl file path, or a playbook name (finds its most recent run)"
        )
      case "log-summary" :: target :: rest =>
        parseLogSummary(rest, target)
      case "audit-log" :: rest               => parseAuditLog(rest, 20)
      case "help" :: _ | "--help" :: _ | Nil => Right(Command.Help)
      case "run" :: rest => parseRunCommand(rest, Nil, None, 60)
      case other => Left(s"Unknown command: ${other.headOption.getOrElse("")}")

  /** Shared flag parser for `playbook`/`cluster-playbook`'s only current flag.
    * Kept separate rather than inlined since both commands need identical
    * handling and neither previously took any arguments past the file path at
    * all — see `Command.RunPlaybook`/`RunClusterPlaybook` for what `resume`
    * does (checkpoint-based restart skipping, `Checkpoint.scala`).
    */
  private def parsePlaybookFlags(
      args: List[String],
      resume: Boolean
  ): Either[String, Boolean] =
    args match
      case Nil                => Right(resume)
      case "--resume" :: rest => parsePlaybookFlags(rest, resume = true)
      case other :: _         =>
        Left(s"Unknown argument: $other (expected at most --resume)")

  private def parseLogSummary(
      args: List[String],
      target: String
  ): Either[String, Command] =
    args match
      case Nil        => Right(Command.LogSummary(target))
      case other :: _ => Left(s"Unknown argument to log-summary: $other")

  private def parseAuditLog(
      args: List[String],
      limit: Int
  ): Either[String, Command] =
    args match
      case Nil                        => Right(Command.ShowAuditLog(limit))
      case "--limit" :: value :: rest =>
        scala.util.Try(value.toInt).toOption match
          case Some(parsed) if parsed > 0 => parseAuditLog(rest, parsed)
          case _ => Left(s"Invalid limit: $value (expected a positive integer)")
      case other :: _ =>
        Left(s"Unknown argument to audit-log: $other")

  private def parseInstall(
      args: List[String],
      packages: List[String],
      nodes: Option[List[String]],
      updateCache: Boolean
  ): Either[String, Command] =
    args match
      case Nil =>
        if packages.isEmpty then Left("install requires at least one package")
        else Right(Command.Install(packages, nodes, updateCache))
      case "--nodes" :: value :: rest =>
        parseInstall(
          rest,
          packages,
          Some(value.split(",").toList.map(_.trim)),
          updateCache
        )
      case "--update-cache" :: rest =>
        parseInstall(rest, packages, nodes, updateCache = true)
      case pkg :: rest =>
        parseInstall(rest, packages :+ pkg, nodes, updateCache)

  private def parseRemove(
      args: List[String],
      packages: List[String],
      nodes: Option[List[String]],
      purge: Boolean
  ): Either[String, Command] =
    args match
      case Nil =>
        if packages.isEmpty then Left("remove requires at least one package")
        else Right(Command.Remove(packages, nodes, purge))
      case "--nodes" :: value :: rest =>
        parseRemove(
          rest,
          packages,
          Some(value.split(",").toList.map(_.trim)),
          purge
        )
      case "--purge" :: rest =>
        parseRemove(rest, packages, nodes, purge = true)
      case pkg :: rest =>
        parseRemove(rest, packages :+ pkg, nodes, purge)

  private def parseAutoRemove(
      args: List[String],
      nodes: Option[List[String]],
      purge: Boolean
  ): Either[String, Command] =
    args match
      case Nil                        => Right(Command.AutoRemove(nodes, purge))
      case "--nodes" :: value :: rest =>
        parseAutoRemove(rest, Some(value.split(",").toList.map(_.trim)), purge)
      case "--purge" :: rest =>
        parseAutoRemove(rest, nodes, purge = true)
      case other :: _ =>
        Left(s"Unknown argument to autoremove: $other")

  private def parseCopy(
      args: List[String],
      local: String,
      dest: String,
      nodes: Option[List[String]],
      owner: String,
      group: String,
      mode: Int
  ): Either[String, Command] =
    args match
      case Nil => Right(Command.Copy(local, dest, nodes, owner, group, mode))
      case "--nodes" :: value :: rest =>
        parseCopy(
          rest,
          local,
          dest,
          Some(value.split(",").toList.map(_.trim)),
          owner,
          group,
          mode
        )
      case "--owner" :: value :: rest =>
        parseCopy(rest, local, dest, nodes, value, group, mode)
      case "--group" :: value :: rest =>
        parseCopy(rest, local, dest, nodes, owner, value, mode)
      case "--mode" :: value :: rest =>
        scala.util.Try(Integer.parseInt(value, 8)).toOption match
          case Some(parsed) =>
            parseCopy(rest, local, dest, nodes, owner, group, parsed)
          case None => Left(s"Invalid mode: $value (expected octal, e.g. 0644)")
      case other :: _ =>
        Left(s"Unknown argument to copy: $other")

  private def parseWriteFile(
      args: List[String],
      dest: String,
      contentLiteral: Option[String],
      contentFile: Option[String],
      nodes: Option[List[String]],
      owner: String,
      group: String,
      mode: Int
  ): Either[String, Command] =
    args match
      case Nil =>
        (contentLiteral, contentFile) match
          case (Some(_), Some(_)) =>
            Left("write-file: pass only one of --content or --content-file")
          case (Some(literal), None) =>
            Right(Command.WriteFile(dest, Left(literal), nodes, owner, group, mode))
          case (None, Some(path)) =>
            Right(Command.WriteFile(dest, Right(path), nodes, owner, group, mode))
          case (None, None) =>
            Left("write-file requires --content <string> or --content-file <path>")
      case "--content" :: value :: rest =>
        parseWriteFile(rest, dest, Some(value), contentFile, nodes, owner, group, mode)
      case "--content-file" :: value :: rest =>
        parseWriteFile(rest, dest, contentLiteral, Some(value), nodes, owner, group, mode)
      case "--owner" :: value :: rest =>
        parseWriteFile(rest, dest, contentLiteral, contentFile, nodes, value, group, mode)
      case "--group" :: value :: rest =>
        parseWriteFile(rest, dest, contentLiteral, contentFile, nodes, owner, value, mode)
      case "--mode" :: value :: rest =>
        scala.util.Try(Integer.parseInt(value, 8)).toOption match
          case Some(parsed) =>
            parseWriteFile(rest, dest, contentLiteral, contentFile, nodes, owner, group, parsed)
          case None => Left(s"Invalid mode: $value (expected octal, e.g. 0644)")
      case "--nodes" :: value :: rest =>
        parseWriteFile(
          rest,
          dest,
          contentLiteral,
          contentFile,
          Some(value.split(",").toList.map(_.trim)),
          owner,
          group,
          mode
        )
      case other :: _ =>
        Left(s"Unknown argument to write-file: $other")

  private def parseNetworkApply(
      args: List[String],
      nodes: Option[List[String]],
      timeoutSeconds: Int
  ): Either[String, Command] =
    args match
      case Nil => Right(Command.NetworkApply(nodes, timeoutSeconds))
      case "--nodes" :: value :: rest =>
        parseNetworkApply(
          rest,
          Some(value.split(",").toList.map(_.trim)),
          timeoutSeconds
        )
      case "--timeout" :: value :: rest =>
        scala.util.Try(value.toInt).toOption match
          case Some(parsed) => parseNetworkApply(rest, nodes, parsed)
          case None         => Left(s"Invalid timeout: $value")
      case other :: _ =>
        Left(s"Unknown argument to network-apply: $other")

  private def parseDeployAgent(
      args: List[String],
      local: Option[String],
      remotePath: String,
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil => Right(Command.DeployAgent(local, remotePath, nodes))
      case "--file" :: value :: rest =>
        parseDeployAgent(rest, Some(value), remotePath, nodes)
      case "--remote-path" :: value :: rest =>
        parseDeployAgent(rest, local, value, nodes)
      case "--nodes" :: value :: rest =>
        parseDeployAgent(
          rest,
          local,
          remotePath,
          Some(value.split(",").toList.map(_.trim))
        )
      case other :: _ =>
        Left(s"Unknown argument to deploy-agent: $other")

  private def parseBootstrap(
      args: List[String],
      local: Option[String],
      nodes: Option[List[String]],
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String
  ): Either[String, Command] =
    args match
      case Nil =>
        Right(Command.Bootstrap(local, nodes, sshUser, sshKeyPath, remotePath))
      case "--file" :: value :: rest =>
        parseBootstrap(
          rest,
          Some(value),
          nodes,
          sshUser,
          sshKeyPath,
          remotePath
        )
      case "--nodes" :: value :: rest =>
        parseBootstrap(
          rest,
          local,
          Some(value.split(",").toList.map(_.trim)),
          sshUser,
          sshKeyPath,
          remotePath
        )
      case "--ssh-user" :: value :: rest =>
        parseBootstrap(rest, local, nodes, value, sshKeyPath, remotePath)
      case "--ssh-key" :: value :: rest =>
        parseBootstrap(rest, local, nodes, sshUser, Some(value), remotePath)
      case "--remote-path" :: value :: rest =>
        parseBootstrap(rest, local, nodes, sshUser, sshKeyPath, value)
      case other :: _ =>
        Left(s"Unknown argument to bootstrap: $other")

  private def parseTeardown(
      args: List[String],
      nodes: List[String],
      sshUser: String,
      sshKeyPath: Option[String],
      purge: Boolean,
      confirmed: Boolean
  ): Either[String, Command] =
    args match
      case Nil =>
        if nodes.isEmpty then
          Left("teardown requires --nodes (no default — this is destructive)")
        else
          Right(Command.Teardown(nodes, sshUser, sshKeyPath, purge, confirmed))

      case "--nodes" :: value :: rest =>
        parseTeardown(
          rest,
          value.split(",").toList.map(_.trim),
          sshUser,
          sshKeyPath,
          purge,
          confirmed
        )

      case "--ssh-user" :: value :: rest =>
        parseTeardown(rest, nodes, value, sshKeyPath, purge, confirmed)

      case "--ssh-key" :: value :: rest =>
        parseTeardown(rest, nodes, sshUser, Some(value), purge, confirmed)

      case "--purge" :: rest =>
        parseTeardown(rest, nodes, sshUser, sshKeyPath, purge = true, confirmed)

      case "--yes" :: rest =>
        parseTeardown(rest, nodes, sshUser, sshKeyPath, purge, confirmed = true)

      case other :: _ =>
        Left(s"Unknown argument to teardown: $other")

  private def parseFetch(
      args: List[String],
      remote: String,
      localDir: String,
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil => Right(Command.Fetch(remote, localDir, nodes))
      case "--out" :: value :: rest =>
        parseFetch(rest, remote, value, nodes)
      case "--nodes" :: value :: rest =>
        parseFetch(
          rest,
          remote,
          localDir,
          Some(value.split(",").toList.map(_.trim))
        )
      case other :: _ =>
        Left(s"Unknown argument to fetch: $other")

  private def parseFacts(
      args: List[String],
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil                        => Right(Command.Facts(nodes))
      case "--nodes" :: value :: rest =>
        parseFacts(rest, Some(value.split(",").toList.map(_.trim)))
      case other :: _ =>
        Left(s"Unknown argument to facts: $other")

  private def parseReboot(
      args: List[String],
      nodes: Option[List[String]],
      delaySeconds: Int,
      waitForReturn: Boolean,
      waitTimeoutSeconds: Int
  ): Either[String, Command] =
    args match
      case Nil =>
        Right(
          Command.Reboot(nodes, delaySeconds, waitForReturn, waitTimeoutSeconds)
        )
      case "--nodes" :: value :: rest =>
        parseReboot(
          rest,
          Some(value.split(",").toList.map(_.trim)),
          delaySeconds,
          waitForReturn,
          waitTimeoutSeconds
        )
      case "--delay" :: value :: rest =>
        scala.util.Try(value.toInt).toOption match
          case Some(parsed) =>
            parseReboot(rest, nodes, parsed, waitForReturn, waitTimeoutSeconds)
          case None => Left(s"Invalid delay: $value")
      case "--wait" :: rest =>
        parseReboot(
          rest,
          nodes,
          delaySeconds,
          waitForReturn = true,
          waitTimeoutSeconds
        )
      case "--wait-timeout" :: value :: rest =>
        scala.util.Try(value.toInt).toOption match
          case Some(parsed) =>
            parseReboot(rest, nodes, delaySeconds, waitForReturn, parsed)
          case None => Left(s"Invalid wait-timeout: $value")
      case other :: _ =>
        Left(s"Unknown argument to reboot: $other")

  private def parseVersion(
      args: List[String],
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil                        => Right(Command.Version(nodes))
      case "--nodes" :: value :: rest =>
        parseVersion(rest, Some(value.split(",").toList.map(_.trim)))
      case other :: _ =>
        Left(s"Unknown argument to version: $other")

  private def parseUptime(
      args: List[String],
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil                        => Right(Command.Uptime(nodes))
      case "--nodes" :: value :: rest =>
        parseUptime(rest, Some(value.split(",").toList.map(_.trim)))
      case other :: _ =>
        Left(s"Unknown argument to uptime: $other")

  private def parseRunCommand(
      args: List[String],
      command: List[String],
      nodes: Option[List[String]],
      timeoutSeconds: Int
  ): Either[String, Command] =
    args match
      case Nil =>
        if command.isEmpty then Left("run requires a command")
        else Right(Command.RunCommand(command, nodes, timeoutSeconds))

      case "--nodes" :: value :: rest =>
        parseRunCommand(
          rest,
          command,
          Some(value.split(",").toList.map(_.trim)),
          timeoutSeconds
        )

      case "--timeout" :: value :: rest =>
        scala.util.Try(value.toInt).toOption match
          case Some(parsed) => parseRunCommand(rest, command, nodes, parsed)
          case None         => Left(s"Invalid timeout: $value")

      case "--" :: rest =>
        parseRunCommand(rest, command, nodes, timeoutSeconds)

      case arg :: rest =>
        parseRunCommand(rest, command :+ arg, nodes, timeoutSeconds)

  val usage: String =
    """orphera-orchestrator - test CLI for the Orphera agent protocol
      |
      |Usage:
      |  install           <package> [<package> ...] [--nodes host1,host2] [--update-cache]
      |  remove            <package> [<package> ...] [--nodes host1,host2] [--purge]
      |  autoremove        [--nodes host1,host2] [--purge]
      |  copy              <local-path> <remote-path> [--owner user] [--group grp] [--mode 0644] [--nodes host1,host2]
      |  write-file        <remote-path> (--content <string> | --content-file <local-path>)
      |                    [--owner user] [--group grp] [--mode 0644] [--nodes host1,host2]
      |                    — writes a literal string straight to a file on the agent, no
      |                      local source file required first unlike copy (--content-file
      |                      is for when the string is awkward to pass inline, e.g.
      |                      multi-line; it still reads that file and sends its bytes,
      |                      same as copy, just under this command's own flags)
      |  network-apply     [--nodes host1,host2] [--timeout 60]
      |  deploy-agent      [--file <local.deb>] [--remote-path /tmp/orphera-agent.deb] [--nodes host1,host2]
      |                    — installs only the orphera-agent package (verified against
      |                      its control metadata, refused otherwise); auto-discovers
      |                      the freshly built orphera-agent_*.deb in the current
      |                      directory if --file is omitted
      |  bootstrap         [--file <local.deb>] --nodes host1,host2 [--ssh-user root] [--ssh-key ~/.ssh/id_ed25519] [--remote-path /tmp/x.deb]
      |                    — same package check and auto-discovery as deploy-agent
      |  teardown          --nodes host1,host2 --yes [--purge] [--ssh-user root] [--ssh-key ~/.ssh/id_ed25519]
      |  playbook          <file.yaml | file.scala | compiled-name> [--resume]
      |                    — .scala files are compiled at run time; --resume skips
      |                      tasks already completed in a previous run (per
      |                      .orphera-state/ checkpoint)
      |  cluster-playbook  <file.yaml | file.scala> [<file2> ...] [--resume]
      |                    — same --resume semantics, per stage/task/node; one or
      |                      more files run in order, stopping at the first failure
      |  run               <command...> [--nodes host1,host2] [--timeout 60]
      |                    — run an arbitrary command, capturing stdout/stderr
      |  fetch             <remote-path> [--out ./local-dir] [--nodes host1,host2]
      |  facts             [--nodes host1,host2]
      |  reboot            [--nodes host1,host2] [--delay 5] [--wait] [--wait-timeout 300]
      |  version           [--nodes host1,host2]
      |  uptime            [--nodes host1,host2]
      |  log-summary       <playbook-name | file.jsonl>
      |                    — summarizes a run's .orphera-logs/*.jsonl output as a
      |                      table (latest run for that playbook, if a name is given)
      |  audit-log         [--limit 20]
      |                    — lists recent mutating-command invocations from
      |                      .orphera-audit/audit.jsonl (command, nodes, outcome,
      |                      duration); see log-summary for one playbook run's
      |                      task-level detail
      |
      |Examples:
      |  install curl vim
      |  remove nginx --purge --nodes web1
      |  autoremove --purge
      |  copy /tmp/test.txt /etc/orphera-test.txt --owner root --group root --mode 0644
      |  write-file /etc/motd --content "Welcome to tst0" --nodes tst0
      |  network-apply --nodes web1 --timeout 90
      |  cluster-playbook manifests/cephadm_add_osds.scala --resume
      |  cluster-playbook manifests/etcd_teardown.scala manifests/etcd_cluster.scala
      |  log-summary cephadm-add-osds
      |""".stripMargin
