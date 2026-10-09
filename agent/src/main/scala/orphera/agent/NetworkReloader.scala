// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import java.nio.file.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import orphera.common.*

object NetworkReloader:

  /** Where a network backend keeps its configuration and how it re-reads it. */
  private case class Backend(
      dir: Path,
      extensions: Set[String],
      reload: IO[Unit]
  )

  private val backupRoot = Paths.get("/var/lib/orphera/network-backups")

  private def exists(path: String): Boolean = Files.exists(Paths.get(path))

  /** systemd-networkd (Ubuntu) unless only NetworkManager is installed
    * (Rocky/RHEL, which ship nmcli and no networkctl).
    */
  private def backend: Backend =
    if exists("/usr/bin/nmcli") && !exists("/usr/bin/networkctl") &&
      !exists("/bin/networkctl")
    then
      Backend(
        Paths.get("/etc/NetworkManager/system-connections"),
        Set(".nmconnection"),
        runNmcliReload()
      )
    else
      Backend(
        Paths.get("/etc/systemd/network"),
        Set(".network", ".netdev", ".link"),
        runNetworkctlReload()
      )

  def reload(
      request: NetworkReload,
      pending: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]
  ): IO[NetworkReloadResult] =
    for
      backupId <- IO(java.time.Instant.now.toEpochMilli.toString)
      backupDir = backupRoot.resolve(backupId)
      _ <- IO.blocking(Files.createDirectories(backupDir))
      be = backend
      _ <- backupCurrentConfig(be, backupDir)
      _ <- be.reload

      timeoutSeconds =
        if request.confirmTimeoutSeconds > 0 then request.confirmTimeoutSeconds
        else 60

      watchdog <- (IO.sleep(timeoutSeconds.seconds) >> rollback(
        be,
        backupDir
      ) >> pending.update(_ - backupId)).start
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

  private def backupCurrentConfig(be: Backend, backupDir: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(be.dir) then
        Files
          .list(be.dir)
          .iterator()
          .asScala
          .filter(p => be.extensions.exists(p.toString.endsWith))
          .foreach(p =>
            Files.copy(
              p,
              backupDir.resolve(p.getFileName),
              StandardCopyOption.COPY_ATTRIBUTES
            )
          )
    }

  private def runNetworkctlReload(): IO[Unit] =
    IO.blocking {
      val exit =
        new ProcessBuilder("networkctl", "reload").inheritIO().start().waitFor()
      if exit != 0 then
        throw new RuntimeException(s"networkctl reload failed with exit $exit")
    }

  /** NetworkManager: re-read the keyfiles, then activate each profile so the
    * change takes effect (a bare reload only updates stored profiles).
    * Activation of an individual profile is best effort; a bad profile must
    * not stop the rest.
    */
  private def runNmcliReload(): IO[Unit] =
    IO.blocking {
      def nmcli(args: String*): Int =
        new ProcessBuilder(("nmcli" +: args)*).inheritIO().start().waitFor()
      val exit = nmcli("connection", "reload")
      if exit != 0 then
        throw new RuntimeException(s"nmcli connection reload failed with exit $exit")
      val dir = Paths.get("/etc/NetworkManager/system-connections")
      if Files.exists(dir) then
        Files
          .list(dir)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".nmconnection"))
          .foreach { p =>
            val id = Files
              .readAllLines(p)
              .asScala
              .find(_.startsWith("id="))
              .map(_.stripPrefix("id=").trim)
              .getOrElse(p.getFileName.toString.stripSuffix(".nmconnection"))
            nmcli("connection", "up", id)
            ()
          }
    }

  private def rollback(be: Backend, backupDir: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(be.dir) then
        Files
          .list(be.dir)
          .iterator()
          .asScala
          .filter(p => be.extensions.exists(p.toString.endsWith))
          .foreach(Files.delete)

      Files
        .list(backupDir)
        .iterator()
        .asScala
        .foreach(p =>
          Files.copy(
            p,
            be.dir.resolve(p.getFileName),
            StandardCopyOption.COPY_ATTRIBUTES
          )
        )
    } >> be.reload
