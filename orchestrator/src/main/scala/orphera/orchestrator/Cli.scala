package orphera.orchestrator

enum Command:
  case Install(
      packages: List[String],
      nodes: Option[List[String]],
      updateCache: Boolean
  )
  case Help

object Cli:

  def parse(args: List[String]): Either[String, Command] =
    args match
      case "install" :: rest =>
        parseInstall(rest, Nil, None, updateCache = false)
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

  val usage: String =
    """orphera-orchestrator - test CLI for the Orphera agent protocol
      |
      |Usage:
      |  install <package> [<package> ...] [--nodes host1,host2] [--update-cache]
      |
      |Examples:
      |  install curl vim
      |  install nginx --nodes web1,web2 --update-cache
      |""".stripMargin
