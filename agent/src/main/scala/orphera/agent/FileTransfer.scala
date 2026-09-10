// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import fs2.Stream
import java.nio.file.*
import java.nio.file.attribute.*
import scala.jdk.CollectionConverters.*
import orphera.common.*

object FileTransfer:

  def receive(
      chunks: Stream[IO, FileChunk],
      queue: Queue[IO, Event]
  ): IO[Unit] =
    chunks.compile.toList.flatMap { all =>
      all.headOption.flatMap(_.payload.metadata) match

        case None =>
          queue.offer(
            Event(
              Event.Kind.RESULT,
              "No metadata received",
              exitCode = 1,
              success = false
            )
          )

        case Some(metadata) =>
          val contentChunks = all.drop(1).flatMap(_.payload.content)
          write(metadata, contentChunks, queue)
    }

  private def write(
      metadata: FileMetadata,
      contentChunks: List[com.google.protobuf.ByteString],
      queue: Queue[IO, Event]
  ): IO[Unit] =
    val destPath = Paths.get(metadata.destPath)
    val tmpPath =
      destPath.resolveSibling(s".${destPath.getFileName}.orphera-tmp")

    for
      _ <- queue.offer(
        Event(Event.Kind.PROGRESS, s"Writing ${metadata.destPath}")
      )

      _ <- IO.blocking {
        val out = Files.newOutputStream(
          tmpPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        try contentChunks.foreach(chunk => out.write(chunk.toByteArray))
        finally out.close()
      }

      _ <- applyAttributes(tmpPath, metadata)

      _ <- IO.blocking {
        Files.move(
          tmpPath,
          destPath,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      }

      _ <- queue.offer(
        Event(Event.Kind.RESULT, "OK", exitCode = 0, success = true)
      )
    yield ()

  private def applyAttributes(path: Path, metadata: FileMetadata): IO[Unit] =
    IO.blocking {
      val lookup = FileSystems.getDefault.getUserPrincipalLookupService

      if metadata.owner.nonEmpty then
        Files.setOwner(path, lookup.lookupPrincipalByName(metadata.owner))

      if metadata.group.nonEmpty then
        val groupPrincipal = lookup.lookupPrincipalByGroupName(metadata.group)
        val view =
          Files.getFileAttributeView(path, classOf[PosixFileAttributeView])
        view.setGroup(groupPrincipal)

      if metadata.mode != 0 then
        Files.setPosixFilePermissions(path, modeToPermissions(metadata.mode))
    }.handleErrorWith { err =>
      IO.raiseError(
        new RuntimeException(
          s"Failed to set file attributes: ${err.getMessage}",
          err
        )
      )
    }

  def check(request: FileCheck): IO[FileCheckResult] =
    IO.blocking {
      val path = Paths.get(request.destPath)

      if !Files.exists(path) then
        FileCheckResult(needsCopy = true, reason = "file does not exist")
      else
        val actualHash = sha256(path)

        if actualHash != request.sha256 then
          FileCheckResult(needsCopy = true, reason = "content differs")
        else
          attributeMismatch(path, request) match
            case Some(reason) =>
              FileCheckResult(needsCopy = true, reason = reason)
            case None =>
              FileCheckResult(needsCopy = false, reason = "unchanged")
    }.handleError { err =>
      FileCheckResult(
        needsCopy = true,
        reason = s"check failed: ${err.getMessage}"
      )
    }

  private def sha256(path: Path): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = Files.readAllBytes(path)
    digest.digest(bytes).map(b => f"$b%02x").mkString

  private def attributeMismatch(
      path: Path,
      request: FileCheck
  ): Option[String] =
    val view = Files.getFileAttributeView(path, classOf[PosixFileAttributeView])
    val currentOwner = view.getOwner.getName
    val currentGroup = view.readAttributes().group().getName
    val currentMode = permissionsToMode(Files.getPosixFilePermissions(path))

    if request.owner.nonEmpty && request.owner != currentOwner then
      Some(s"owner differs: expected ${request.owner}, got $currentOwner")
    else if request.group.nonEmpty && request.group != currentGroup then
      Some(s"group differs: expected ${request.group}, got $currentGroup")
    else if request.mode != 0 && request.mode != currentMode then
      Some(f"mode differs: expected ${request.mode}%o, got $currentMode%o")
    else None

  private def modeToPermissions(mode: Int): java.util.Set[PosixFilePermission] =
    val bits = List(
      (0x100, PosixFilePermission.OWNER_READ),
      (0x080, PosixFilePermission.OWNER_WRITE),
      (0x040, PosixFilePermission.OWNER_EXECUTE),
      (0x020, PosixFilePermission.GROUP_READ),
      (0x010, PosixFilePermission.GROUP_WRITE),
      (0x008, PosixFilePermission.GROUP_EXECUTE),
      (0x004, PosixFilePermission.OTHERS_READ),
      (0x002, PosixFilePermission.OTHERS_WRITE),
      (0x001, PosixFilePermission.OTHERS_EXECUTE)
    )
    val perms = bits.collect { case (bit, perm) if (mode & bit) != 0 => perm }
    perms.toSet.asJava

  private def permissionsToMode(
      perms: java.util.Set[PosixFilePermission]
  ): Int =
    val bits = List(
      (PosixFilePermission.OWNER_READ, 0x100),
      (PosixFilePermission.OWNER_WRITE, 0x080),
      (PosixFilePermission.OWNER_EXECUTE, 0x040),
      (PosixFilePermission.GROUP_READ, 0x020),
      (PosixFilePermission.GROUP_WRITE, 0x010),
      (PosixFilePermission.GROUP_EXECUTE, 0x008),
      (PosixFilePermission.OTHERS_READ, 0x004),
      (PosixFilePermission.OTHERS_WRITE, 0x002),
      (PosixFilePermission.OTHERS_EXECUTE, 0x001)
    )
    bits.foldLeft(0) { case (acc, (perm, bit)) =>
      if perms.asScala.contains(perm) then acc | bit else acc
    }
