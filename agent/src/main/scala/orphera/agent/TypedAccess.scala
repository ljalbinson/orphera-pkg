// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}
import java.nio.file.{
  Files,
  NoSuchFileException,
  Path,
  Paths,
  StandardCopyOption
}

import scala.util.Try

import orphera.common.*

import Fixed.*

/** ApplyAccess: makes the changes it lists, in order, to users, groups,
  * authorized keys and mounts, from a fixed set of actions, and runs no script.
  * These give or take away ways to log in and change what is mounted, so they
  * are a different call (and policy operation) from ApplyFiles. The same as the
  * Go agent (`applyaccess.go`); keep the two the same.
  */
object TypedAccess:

  val FstabPath = "/etc/fstab"

  /** Where a user's keys are, None if the user is not found. */
  private def authorizedKeysPath(user: String): Option[String] =
    if !validArg(user) then None
    else
      passwd(user).map(f => Paths.get(f(5), ".ssh", "authorized_keys").toString)

  /** The files an action touches, for the role's path limits. */
  def paths(a: AccessAction): List[String] = a.action match
    case AccessAction.Action.AddAuthorizedKey(x) =>
      authorizedKeysPath(x.user).toList
    case AccessAction.Action.RemoveAuthorizedKey(x) =>
      authorizedKeysPath(x.user).toList
    case AccessAction.Action.ReplaceFstab(_) => List(FstabPath)
    case AccessAction.Action.MountPath(x)    => List(x.path)
    case AccessAction.Action.Remount(x)      => List(x.path)
    case AccessAction.Action.Unmount(x)      => List(x.path)
    case _                                   => Nil

  def describe(a: AccessAction): String = a.action match
    case AccessAction.Action.CreateUser(x)       => s"create user ${x.name}"
    case AccessAction.Action.ModifyUser(x)       => s"change user ${x.name}"
    case AccessAction.Action.DeleteUser(x)       => s"delete user ${x.name}"
    case AccessAction.Action.CreateGroup(x)      => s"create group ${x.name}"
    case AccessAction.Action.ModifyGroup(x)      => s"change group ${x.name}"
    case AccessAction.Action.DeleteGroup(x)      => s"delete group ${x.name}"
    case AccessAction.Action.AddAuthorizedKey(x) => s"add a key for ${x.user}"
    case AccessAction.Action.RemoveAuthorizedKey(x) =>
      s"remove a key of ${x.user}"
    case AccessAction.Action.ReplaceFstab(_) => s"write $FstabPath"
    case AccessAction.Action.MountPath(x)    => s"mount ${x.path}"
    case AccessAction.Action.Remount(x)      => s"remount ${x.path}"
    case AccessAction.Action.Unmount(x)      => s"unmount ${x.path}"
    case _                                   => "unknown action"

  def apply(actions: List[AccessAction]): ApplyReply =
    sequence(actions)(describe)(applyOne)

  private def applyOne(a: AccessAction): Unit = a.action match
    case AccessAction.Action.CreateUser(x) => createUser(x)
    case AccessAction.Action.ModifyUser(x) => modifyUser(x)
    case AccessAction.Action.DeleteUser(x) =>
      if !validArg(x.name) then fail(s"not a valid user name: '${x.name}'")
      run("userdel", List(x.name))
    case AccessAction.Action.CreateGroup(x) => createGroup(x)
    case AccessAction.Action.ModifyGroup(x) =>
      if !validArg(x.name) || !isNumber(x.gid) then
        fail(s"not a valid group or gid: '${x.name}' '${x.gid}'")
      run("groupmod", List("-g", x.gid, x.name))
    case AccessAction.Action.DeleteGroup(x) =>
      if !validArg(x.name) then fail(s"not a valid group name: '${x.name}'")
      run("groupdel", List(x.name))
    case AccessAction.Action.AddAuthorizedKey(x)    => addAuthorizedKey(x)
    case AccessAction.Action.RemoveAuthorizedKey(x) => removeAuthorizedKey(x)
    case AccessAction.Action.ReplaceFstab(x)        =>
      TypedFiles.replaceText(
        FstabPath,
        x.content,
        x.expectedSha256,
        "0644",
        overwrite = false
      )
    case AccessAction.Action.MountPath(x) =>
      needAbsolute(x.path)
      if x.makeDirectory then
        Files.createDirectories(
          Paths.get(x.path),
          PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwxr-xr-x")
          )
        )
      run("mount", List(x.path))
    case AccessAction.Action.Remount(x) =>
      needAbsolute(x.path)
      run("mount", List("-o", "remount", x.path))
    case AccessAction.Action.Unmount(x) =>
      needAbsolute(x.path)
      run("umount", List(x.path))
    case _ => fail("an action this agent does not know")

  private def validGroups(groups: Seq[String]): Unit =
    groups.foreach { g =>
      if !validArg(g) || g.contains(',') then fail(s"not a valid group: '$g'")
    }

  private def createUser(u: CreateUser): Unit =
    if !validArg(u.name) then fail(s"not a valid user name: '${u.name}'")
    val uid =
      if u.uid.isEmpty then Nil
      else if !isNumber(u.uid) then fail(s"not a uid: '${u.uid}'")
      else List("-u", u.uid)
    val group =
      if u.group.isEmpty then Nil
      else if !validArg(u.group) then fail(s"not a valid group: '${u.group}'")
      else List("-g", u.group)
    val groups =
      if u.groups.isEmpty then Nil
      else
        validGroups(u.groups)
        List("-G", u.groups.mkString(","))
    val home =
      if u.home.isEmpty then Nil
      else if !validPathArg(u.home) then
        fail(s"not a valid home directory: '${u.home}'")
      else List("-d", u.home)
    val createHome = if u.createHome then List("-m") else Nil
    val shell =
      if u.shell.isEmpty then Nil
      else if !validPathArg(u.shell) then
        fail(s"not a valid shell: '${u.shell}'")
      else List("-s", u.shell)
    val system = if u.system then List("-r") else Nil
    run(
      "useradd",
      uid ++ group ++ groups ++ home ++ createHome ++ shell ++ system :+ u.name
    )

  private def modifyUser(u: ModifyUser): Unit =
    if !validArg(u.name) then fail(s"not a valid user name: '${u.name}'")
    val uid =
      if u.uid.isEmpty then Nil
      else if !isNumber(u.uid) then fail(s"not a uid: '${u.uid}'")
      else List("-u", u.uid)
    val group =
      if u.group.isEmpty then Nil
      else if !validArg(u.group) then fail(s"not a valid group: '${u.group}'")
      else List("-g", u.group)
    val addGroups =
      if u.addGroups.isEmpty then Nil
      else
        validGroups(u.addGroups)
        List("-a", "-G", u.addGroups.mkString(","))
    val home =
      if u.home.isEmpty then Nil
      else if !validPathArg(u.home) then
        fail(s"not a valid home directory: '${u.home}'")
      else List("-d", u.home)
    val shell =
      if u.shell.isEmpty then Nil
      else if !validPathArg(u.shell) then
        fail(s"not a valid shell: '${u.shell}'")
      else List("-s", u.shell)
    val args = uid ++ group ++ addGroups ++ home ++ shell
    if args.isEmpty then fail("nothing to change")
    run("usermod", args :+ u.name)

  private def createGroup(g: CreateGroup): Unit =
    if !validArg(g.name) then fail(s"not a valid group name: '${g.name}'")
    val gid =
      if g.gid.isEmpty then Nil
      else if !isNumber(g.gid) then fail(s"not a gid: '${g.gid}'")
      else List("-g", g.gid)
    val system = if g.system then List("-r") else Nil
    run("groupadd", gid ++ system :+ g.name)

  /** A user's home .ssh directory and keys file, and the user's ids. */
  private def keyOwner(user: String): (Path, Path, String, String) =
    if !validArg(user) then fail(s"not a valid user name: '$user'")
    val f = passwd(user).getOrElse(fail(s"user $user does not exist"))
    if f(5).isEmpty || !f(5).startsWith("/") then
      fail(s"user $user has no home directory")
    val dir = Paths.get(f(5), ".ssh")
    (dir, dir.resolve("authorized_keys"), f(2), f(3))

  /** Writes the keys file through a temporary file beside it, mode 0600 and
    * owned by `uid` and `gid` (numbers).
    */
  private def writeKeys(
      dir: Path,
      file: Path,
      text: String,
      uid: String,
      gid: String
  ): Unit =
    val tmp = Files.createTempFile(dir, ".orphera-keys-", "")
    try
      TypedFiles.writeSynced(tmp, text.getBytes(StandardCharsets.UTF_8))
      chown(tmp, uid, gid)
      setMode(tmp, 384) // 0600
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
    catch
      case e: Exception =>
        Try(Files.deleteIfExists(tmp))
        throw e

  /** chown by number (a user name that is a number is looked up as one). */
  private def chown(p: Path, uid: String, gid: String): Unit =
    val lookup = p.getFileSystem.getUserPrincipalLookupService
    Files.setOwner(p, lookup.lookupPrincipalByName(uid))
    Files
      .getFileAttributeView(p, classOf[PosixFileAttributeView])
      .setGroup(lookup.lookupPrincipalByGroupName(gid))

  private def addAuthorizedKey(k: AddAuthorizedKey): Unit =
    val (dir, file, uid, gid) = keyOwner(k.user)
    val line = k.key.trim
    if line.isEmpty || line.exists(c => c == '\n' || c == '\u0000') ||
      fields(line).length < 2
    then fail("not a public key line")
    Files.createDirectories(
      dir,
      PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")
      )
    )
    chown(dir, uid, gid)
    setMode(dir, 448) // 0700
    val old =
      try new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      catch case _: NoSuchFileException => ""
    val base = if old.nonEmpty && !old.endsWith("\n") then s"$old\n" else old
    writeKeys(dir, file, s"$base$line\n", uid, gid)

  private def removeAuthorizedKey(k: RemoveAuthorizedKey): Unit =
    val (dir, file, uid0, gid0) = keyOwner(k.user)
    val data =
      try Some(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      catch case _: NoSuchFileException => None
    data.foreach { text =>
      val kept = text
        .split("\n", -1)
        .filterNot { line =>
          val f = fields(line)
          f.length >= 2 && s"${f(0)} ${f(1)}" == k.keyId
        }
      // Splitting a text that ends in a newline leaves an empty last element,
      // which the join turns back into that newline.
      val uid = Try(Files.getAttribute(file, "unix:uid")).toOption match
        case Some(i: Integer) => i.toString
        case _                => uid0
      val gid = Try(Files.getAttribute(file, "unix:gid")).toOption match
        case Some(i: Integer) => i.toString
        case _                => gid0
      writeKeys(dir, file, kept.mkString("\n"), uid, gid)
    }
