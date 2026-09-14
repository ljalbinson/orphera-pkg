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
  case NetworkApply(nodes: Option[List[String]], timeoutSeconds: Int)
  case DeployAgent(
      localPath: String,
      remotePath: String,
      nodes: Option[List[String]]
  )
  case Bootstrap(
      localPath: String,
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
  case RunPlaybook(path: String)
  case Reboot(
      nodes: Option[List[String]],
      delaySeconds: Int,
      waitForReturn: Boolean,
      waitTimeoutSeconds: Int
  )
  case Version(nodes: Option[List[String]])
  case Uptime(nodes: Option[List[String]])
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
      case "network-apply" :: rest         => parseNetworkApply(rest, None, 60)
      case "deploy-agent" :: local :: rest =>
        parseDeployAgent(rest, local, "/tmp/orphera-agent.deb", None)
      case "bootstrap" :: local :: rest =>
        parseBootstrap(
          rest,
          local,
          None,
          "root",
          None,
          "/tmp/orphera-agent.deb"
        )
      case "teardown" :: rest =>
        parseTeardown(rest, Nil, "root", None, purge = false, confirmed = false)
      case "fetch" :: remote :: rest => parseFetch(rest, remote, ".", None)
      case "facts" :: rest           => parseFacts(rest, None)
      case "playbook" :: path :: Nil => Right(Command.RunPlaybook(path))
      case "version" :: rest         => parseVersion(rest, None)
      case "uptime" :: rest => parseUptime(rest, None)
      case "reboot" :: rest          =>
        parseReboot(rest, None, 5, waitForReturn = false, 300)
      case "help" :: _ | "--help" :: _ | Nil => Right(Command.Help)
      case other => Left(s"Unknown command: ${other.headOption.getOrElse("")}")

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
      local: String,
      remotePath: String,
      nodes: Option[List[String]]
  ): Either[String, Command] =
    args match
      case Nil => Right(Command.DeployAgent(local, remotePath, nodes))
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
      local: String,
      nodes: Option[List[String]],
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String
  ): Either[String, Command] =
    args match
      case Nil =>
        Right(Command.Bootstrap(local, nodes, sshUser, sshKeyPath, remotePath))
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

  private def parseUptime(args: List[String], nodes: Option[List[String]]): Either[String, Command] =
    args match
      case Nil => Right(Command.Uptime(nodes))
      case "--nodes" :: value :: rest =>
        parseUptime(rest, Some(value.split(",").toList.map(_.trim)))
      case other :: _ =>
        Left(s"Unknown argument to uptime: $other")

  val usage: String =
    """orphera-orchestrator - test CLI for the Orphera agent protocol
      |
      |Usage:
      |  install        <package> [<package> ...] [--nodes host1,host2] [--update-cache]
      |  remove         <package> [<package> ...] [--nodes host1,host2] [--purge]
      |  autoremove     [--nodes host1,host2] [--purge]
      |  copy           <local-path> <remote-path> [--owner user] [--group grp] [--mode 0644] [--nodes host1,host2]
      |  network-apply  [--nodes host1,host2] [--timeout 60]
      |  deploy-agent   <local.deb> [--remote-path /tmp/orphera-agent.deb] [--nodes host1,host2]
      |  bootstrap      <local.deb> --nodes host1,host2 [--ssh-user root] [--ssh-key ~/.ssh/id_ed25519] [--remote-path /tmp/x.deb]
      |  teardown       --nodes host1,host2 --yes [--purge] [--ssh-user root] [--ssh-key ~/.ssh/id_ed25519]
      |  fetch          <remote-path> [--out ./local-dir] [--nodes host1,host2]
      |  facts          [--nodes host1,host2]
      |  reboot         [--nodes host1,host2] [--delay 5] [--wait] [--wait-timeout 300]
      |  version        [--nodes host1,host2]
      |  uptime         [--nodes host1,host2]
      |  playbook       <file.yaml>
      |
      |Examples:
      |  install curl vim
      |  remove nginx --purge --nodes web1
      |  autoremove --purge
      |  copy /tmp/test.txt /etc/orphera-test.txt --owner root --group root --mode 0644
      |  network-apply --nodes web1 --timeout 90
      |""".stripMargin
