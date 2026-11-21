// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  Paths,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.{
  PosixFileAttributeView,
  PosixFileAttributes,
  PosixFilePermissions
}

import scala.util.Try

import orphera.common.*

import Fixed.*

/** ApplyFiles: makes the changes it lists, in order, from a fixed set of
  * actions, and runs no script, so a role that may change files and kernel
  * settings does not need `run`. The same as the Go agent (`applyfiles.go`);
  * keep the two the same.
  */
object TypedFiles:

  val MaxReplaceText: Int = 4 * 1024 * 1024

  /** The files an action touches, for the role's path limits. */
  def paths(a: FileAction): List[String] = a.action match
    case FileAction.Action.MakeDirectory(x) => List(x.path)
    case FileAction.Action.SetAttributes(x) => List(x.path)
    case FileAction.Action.RemovePath(x)    => List(x.path)
    case FileAction.Action.MakeSymlink(x)   => List(x.path)
    case FileAction.Action.ReplaceText(x)   =>
      if x.source.nonEmpty then List(x.path, x.source) else List(x.path)
    case _ => Nil

  def describe(a: FileAction): String = a.action match
    case FileAction.Action.MakeDirectory(x) => s"make directory ${x.path}"
    case FileAction.Action.SetAttributes(x) => s"set attributes of ${x.path}"
    case FileAction.Action.RemovePath(x)    => s"remove ${x.path}"
    case FileAction.Action.MakeSymlink(x)   => s"link ${x.path}"
    case FileAction.Action.SetSysctl(x)     => s"set sysctl ${x.key}"
    case FileAction.Action.LoadModule(x)    => s"load module ${x.name}"
    case FileAction.Action.ReplaceText(x)   => s"write ${x.path}"
    case _                                  => "unknown action"

  def apply(actions: List[FileAction]): ApplyReply =
    sequence(actions)(describe)(applyOne)

  private def applyOne(a: FileAction): Unit = a.action match
    case FileAction.Action.MakeDirectory(x) => makeDirectory(x)
    case FileAction.Action.SetAttributes(x) =>
      needChangeablePath(x.path)
      setAttributes(x.path, x.mode, x.owner, x.group)
    case FileAction.Action.RemovePath(x)  => removePath(x)
    case FileAction.Action.MakeSymlink(x) => makeSymlink(x)
    case FileAction.Action.SetSysctl(x)   => setSysctl(x)
    case FileAction.Action.LoadModule(x)  =>
      if !validArg(x.name) then fail(s"not a valid module name: '${x.name}'")
      run("modprobe", List(x.name))
    case FileAction.Action.ReplaceText(x) =>
      replaceText(
        x.path,
        x.content,
        x.expectedSha256,
        x.createMode,
        x.overwrite,
        x.source
      )
    case _ => fail("an action this agent does not know")

  private def makeDirectory(m: MakeDirectory): Unit =
    needChangeablePath(m.path)
    parseMode(m.mode)
    Files.createDirectories(
      Paths.get(m.path),
      PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwxr-xr-x")
      )
    )
    setAttributes(m.path, m.mode, m.owner, m.group)

  /** Removes a file or link, an empty directory, or (directory and recursive) a
    * directory with everything in it. Something already gone is not an error.
    */
  private def removePath(r: RemovePath): Unit =
    needChangeablePath(r.path)
    if r.recursive then
      if !r.directory then
        fail(s"${r.path}: recursive removal is only for a directory")
      needSafeTree(r.path)
    val p = Paths.get(r.path)
    lstat(p).foreach { a =>
      if r.directory then
        if !a.isDirectory then fail(s"${r.path} is not a directory")
      else if a.isDirectory then fail(s"${r.path} is a directory: not removed")
      if r.recursive then removeTree(p) else Files.delete(p)
    }

  /** Directories this near the root are never removed with their contents. */
  val MinTreeDepth: Int = 2

  /** Refuses a path that is not absolute and clean, or is too near the root. */
  def needSafeTree(path: String): Unit =
    if !path.startsWith("/") then
      fail(s"'$path' is not an absolute path: not removed with its contents")
    if Paths.get(path).normalize().toString != path then
      fail(s"'$path' is not a clean path: not removed with its contents")
    if path.split('/').count(_.nonEmpty) < MinTreeDepth then
      fail(s"$path is too near the root to be removed with its contents")

  private def deviceOf(p: Path): Long =
    Files.getAttribute(p, "unix:dev", LinkOption.NOFOLLOW_LINKS) match
      case n: java.lang.Long => n.longValue
      case other => fail(s"cannot tell which filesystem $p is on ($other)")

  /** Removes `dir` and everything under it. Refuses, before removing anything,
    * when `dir` or a directory below it is on another filesystem than `dir`'s
    * parent (something is mounted there). Links are removed, never followed.
    */
  private def removeTree(dir: Path): Unit =
    val parent = deviceOf(dir.getParent)
    val mounted = scala.collection.mutable.ListBuffer.empty[Path]
    Files.walkFileTree(
      dir,
      new java.nio.file.SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            d: Path,
            a: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          if deviceOf(d) != parent then
            mounted += d
            java.nio.file.FileVisitResult.TERMINATE
          else java.nio.file.FileVisitResult.CONTINUE
    )
    mounted.headOption.foreach(m =>
      fail(s"$m is on another filesystem (a mount): nothing removed")
    )
    Files.walkFileTree(
      dir,
      new java.nio.file.SimpleFileVisitor[Path]:
        override def visitFile(
            f: Path,
            a: java.nio.file.attribute.BasicFileAttributes
        ): java.nio.file.FileVisitResult =
          Files.delete(f)
          java.nio.file.FileVisitResult.CONTINUE
        override def postVisitDirectory(
            d: Path,
            e: java.io.IOException
        ): java.nio.file.FileVisitResult =
          if e != null then throw e
          Files.delete(d)
          java.nio.file.FileVisitResult.CONTINUE
    )

  /** Makes `path` a link to `target`, at once: the new link is made beside it
    * and renamed over it.
    */
  private def makeSymlink(l: MakeSymlink): Unit =
    needChangeablePath(l.path)
    if l.target.isEmpty || l.target.contains('\u0000') then
      fail(s"not a link target: '${l.target}'")
    val p = Paths.get(l.path)
    if lstat(p).exists(_.isDirectory) then
      fail(s"${l.path} is a directory: not replaced by a link")
    val tmp = p.resolveSibling(s".${p.getFileName}.orphera-${ProcessHandle.current().pid()}")
    Files.deleteIfExists(tmp)
    Files.createSymbolicLink(tmp, Paths.get(l.target))
    try Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE)
    catch
      case e: Exception =>
        Files.deleteIfExists(tmp)
        throw e

  private def setSysctl(s: SetSysctl): Unit =
    if !validArg(s.key) then fail(s"not a valid sysctl key: '${s.key}'")
    if s.value.exists(c => c == '\n' || c == '\u0000') || s.value.trim.isEmpty
    then fail(s"not a valid sysctl value: '${s.value}'")
    run("sysctl", List("-w", s"${s.key}=${s.value}"))

  /** Writes a file's new text through a temporary file beside it, with the old
    * mode and owner, and renames it into place.
    */
  def replaceText(
      path: String,
      content: String,
      expectedSha256: String,
      createMode: String,
      overwrite: Boolean,
      source: String = ""
  ): Unit =
    needChangeablePath(path)
    val bytes = replacementBytes(path, content, source)
    val p = Paths.get(path)
    // The mode, and the owner and group to give the new file (none: root).
    val (mode, keep): (Int, Option[PosixFileAttributes]) = lstat(p) match
      case None =>
        if expectedSha256.nonEmpty then
          fail(s"the file is gone (expected one with hash $expectedSha256)")
        (parseMode(createMode).getOrElse(420), None) // 420 = 0644
      case Some(a) =>
        if !a.isRegularFile then fail(s"$path is not a regular file")
        if expectedSha256.isEmpty && !overwrite then
          fail("the file exists now (expected it not to)")
        if expectedSha256.nonEmpty then
          val sum = sha256File(p)
          if sum != expectedSha256 then
            fail(
              s"the file has changed since it was read (hash $sum, expected $expectedSha256): not edited"
            )
        val posix = Files.readAttributes(
          p,
          classOf[PosixFileAttributes],
          LinkOption.NOFOLLOW_LINKS
        )
        (unixMode(p, follow = false), Some(posix))
    val tmp = Files.createTempFile(p.getParent, ".orphera-edit-", "")
    try
      writeSynced(tmp, bytes)
      keep.foreach { posix =>
        Files.setOwner(tmp, posix.owner)
        Files
          .getFileAttributeView(tmp, classOf[PosixFileAttributeView])
          .setGroup(posix.group)
      }
      setMode(tmp, mode)
      Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE)
    catch
      case e: Exception =>
        Try(Files.deleteIfExists(tmp))
        throw e

  /** The new content: the text sent, or (source) the bytes of a regular file
    * on this node, which never leave it. A link or a directory is refused, and
    * so is the agent's own directory (its keys).
    */
  private def replacementBytes(
      path: String,
      content: String,
      source: String
  ): Array[Byte] =
    if source.isEmpty then
      val bytes = content.getBytes(StandardCharsets.UTF_8)
      if bytes.length > MaxReplaceText then
        fail(s"text is larger than $MaxReplaceText bytes")
      bytes
    else
      if content.nonEmpty then fail(s"$path: a source and text were both given")
      needChangeablePath(source)
      val p = Paths.get(source)
      lstat(p) match
        case None => fail(s"source $source: no such file")
        case Some(a) =>
          if !a.isRegularFile then fail(s"source $source is not a regular file")
          if a.size > MaxReplaceText then
            fail(s"source $source is larger than $MaxReplaceText bytes")
      Files.readAllBytes(p)

  def writeSynced(p: Path, bytes: Array[Byte]): Unit =
    val ch = FileChannel.open(p, StandardOpenOption.WRITE)
    try
      val buf = ByteBuffer.wrap(bytes)
      while buf.hasRemaining do ch.write(buf)
      ch.force(true)
    finally ch.close()
