package orphera.orchestrator

import cats.effect.*

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
        val targets = nodeNames match
          case Some(names) => Inventory.all.filter(n => names.contains(n.name))
          case None        => Inventory.all

        if targets.isEmpty then
          IO.println("No matching nodes found in inventory.") >> IO.pure(
            ExitCode.Error
          )
        else
          Orchestrator.installPackages(targets, packages, updateCache) >> IO
            .pure(ExitCode.Success)
