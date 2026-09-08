package orphera.agent

import cats.effect.*
import java.nio.file.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import orphera.common.*

object NetworkReloader:

  private val networkDir = Paths.get("/etc/systemd/network")
  private val backupRoot = Paths.get("/var/lib/orphera/network-backups")
  private val trackedExtensions = Set(".network", ".netdev", ".link")

  def reload(
      request: NetworkReload,
      pending: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]
  ): IO[NetworkReloadResult] =
    for
      backupId <- IO(java.time.Instant.now.toEpochMilli.toString)
      backupDir = backupRoot.resolve(backupId)
      _ <- IO.blocking(Files.createDirectories(backupDir))
      _ <- backupCurrentConfig(backupDir)
      _ <- runNetworkctlReload()

      timeoutSeconds = if request.confirmTimeoutSeconds > 0 then request.confirmTimeoutSeconds else 60

      watchdog <- (IO.sleep(timeoutSeconds.seconds) >> rollback(backupDir) >> pending.update(_ - backupId)).start
      _ <- pending.update(_ + (backupId -> watchdog))
    yield NetworkReloadResult(backupId)

  def confirm(
      backupId: String,
      pending: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]
  ): IO[Unit] =
    pending.modify { current =>
      current.get(backupId) match
        case Some(fiber) => (current - backupId, fiber.cancel)
        case None        => (current, IO.unit)
    }.flatten

  private def backupCurrentConfig(backupDir: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(networkDir) then
        Files.list(networkDir).iterator().asScala
          .filter(p => trackedExtensions.exists(p.toString.endsWith))
          .foreach(p => Files.copy(p, backupDir.resolve(p.getFileName), StandardCopyOption.COPY_ATTRIBUTES))
    }

  private def runNetworkctlReload(): IO[Unit] =
    IO.blocking {
      val exit = new ProcessBuilder("networkctl", "reload").inheritIO().start().waitFor()
      if exit != 0 then throw new RuntimeException(s"networkctl reload failed with exit $exit")
    }

  private def rollback(backupDir: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(networkDir) then
        Files.list(networkDir).iterator().asScala
          .filter(p => trackedExtensions.exists(p.toString.endsWith))
          .foreach(Files.delete)

      Files.list(backupDir).iterator().asScala
        .foreach(p => Files.copy(p, networkDir.resolve(p.getFileName), StandardCopyOption.COPY_ATTRIBUTES))
    } >> runNetworkctlReload()
