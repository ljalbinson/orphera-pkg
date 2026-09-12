package orphera.orchestrator

import cats.effect.*
import java.io.*

object SshDeployer:

  def bootstrap(
      node: Node,
      localDebPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      _ <- scp(node, localDebPath, sshUser, sshKeyPath, remotePath, onLine)
      _ <- sshRun(
        node,
        sshUser,
        sshKeyPath,
        s"sudo dpkg -i $remotePath",
        onLine
      )
    yield ()

  def teardown(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      purgeConfig: Boolean,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    val purgeOrRemove = if purgeConfig then "purge" else "remove"
    sshRun(
      node,
      sshUser,
      sshKeyPath,
      s"sudo dpkg --$purgeOrRemove orphera-agent",
      onLine
    )

  private def sshBaseArgs(sshKeyPath: Option[String]): List[String] =
    val keyArgs = sshKeyPath.toList.flatMap(k => List("-i", k))
    List(
      "-o",
      "StrictHostKeyChecking=accept-new",
      "-o",
      "BatchMode=yes"
    ) ++ keyArgs

  private def scp(
      node: Node,
      localPath: String,
      sshUser: String,
      sshKeyPath: Option[String],
      remotePath: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    runProcess(
      List("scp") ++ sshBaseArgs(sshKeyPath) ++ List(
        localPath,
        s"$sshUser@${node.host}:$remotePath"
      ),
      onLine
    )

  private def sshRun(
      node: Node,
      sshUser: String,
      sshKeyPath: Option[String],
      remoteCommand: String,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    runProcess(
      List("ssh") ++ sshBaseArgs(sshKeyPath) ++ List(
        s"$sshUser@${node.host}",
        remoteCommand
      ),
      onLine
    )

  private def runProcess(
      command: List[String],
      onLine: String => IO[Unit]
  ): IO[Unit] =
    for
      process <- IO.blocking {
        new ProcessBuilder(command*).redirectErrorStream(true).start()
      }
      reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      _ <- read(reader, onLine)
      exit <- IO.interruptible(process.waitFor())
      _ <-
        if exit != 0 then
          IO.raiseError(
            new RuntimeException(
              s"Command failed (exit $exit): ${command.mkString(" ")}"
            )
          )
        else IO.unit
    yield ()

  private def read(
      reader: BufferedReader,
      onLine: String => IO[Unit]
  ): IO[Unit] =
    IO.interruptible(reader.readLine()).flatMap {
      case null => IO.unit
      case line => onLine(line) >> read(reader, onLine)
    }
