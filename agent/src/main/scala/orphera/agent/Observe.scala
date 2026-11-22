// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{BasicFileAttributes, PosixFileAttributes}
import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.util.{Failure, Success, Try}

import orphera.common.{Observation, ObservedItem}

import Fixed.*

/** ObserveNode: reads what the request lists, from a fixed set of kinds, and
  * runs no script, so a role that may only look can make the call. Each item is
  * answered by itself (values, or an error for that item). The same as the Go
  * agent (`observe.go`); keep the two the same.
  */
object Observe:

  val MaxItems = 2000

  /** Bounds the text of a file_text item. */
  val MaxText: Int = 256 * 1024

  val FstabPath: Path = Paths.get("/etc/fstab")
  val MountInfoPath: Path = Paths.get("/proc/self/mountinfo")

  /** Persisted settings are read only from these directories. */
  val SysctlDir = "/etc/sysctl.d/"
  val ModulesDir = "/etc/modules-load.d/"

  /** The files an item reads, for the role's path limits. */
  def paths(it: Observation): List[String] = it.kind match
    case "path" | "file_text" | "mount"           => List(it.target)
    case "sysctl" | "module" if it.extra.nonEmpty => List(it.extra)
    case _                                        => Nil

  def item(it: Observation): ObservedItem =
    Try(one(it)) match
      case Success(values) => ObservedItem(id = it.id, values = values)
      case Failure(e)      =>
        ObservedItem(
          id = it.id,
          error = Option(e.getMessage).getOrElse(e.toString)
        )

  def one(it: Observation): Map[String, String] = it.kind match
    case "path"      => observePath(it.target)
    case "package"   => observePackage(it.target)
    case "unit"      => observeUnit(it.target)
    case "user"      => observeUser(it.target)
    case "group"     => observeGroup(it.target)
    case "sysctl"    => observeSysctl(it.target, it.extra)
    case "module"    => observeModule(it.target, it.extra)
    case "file_text" => observeFileText(it.target)
    case "key"       => observeKey(it.target, it.extra)
    case "mount"     => observeMount(it.target)
    case other       => fail(s"unknown kind of observation: '$other'")

  /** What stat, readlink and sha256sum say of a path. */
  def observePath(path: String): Map[String, String] =
    needAbsolute(path)
    if ProtectedPaths.refusal(path).isDefined then fail("protected path")
    val p = Paths.get(path)
    lstat(p) match
      case None                        => Map("type" -> "absent")
      case Some(a) if a.isSymbolicLink =>
        Map("type" -> "link", "target" -> Files.readSymbolicLink(p).toString)
      case Some(a) =>
        val posix = Files.readAttributes(
          p,
          classOf[PosixFileAttributes],
          LinkOption.NOFOLLOW_LINKS
        )
        val vals = Map(
          "mode" -> Integer.toOctalString(unixMode(p, follow = false) & 4095),
          "owner" -> posix.owner.getName,
          "group" -> posix.group.getName
        )
        if a.isRegularFile then
          vals ++ Map("type" -> "file", "sha256" -> sha256File(p))
        else if a.isDirectory then vals + ("type" -> "directory")
        else Map("type" -> "other")

  /** The state of a package under dpkg or rpm. */
  def observePackage(name: String): Map[String, String] =
    needName("package name", name)
    if have("dpkg-query") && have("apt-get") then
      val (out, _) = capture(
        "dpkg-query",
        List("-W", "-f", "${db:Status-Abbrev}|${Version}", name)
      )
      val base = Map("manager" -> "apt", "installed" -> "no")
      // "?i*": the second character is "i" when it is installed.
      if out.length >= 2 && out.charAt(1) == 'i' then
        val versions = out.indexOf('|') match
          case i if i >= 0 => Map("versions" -> out.substring(i + 1))
          case _           => Map.empty[String, String]
        base ++ Map("installed" -> "yes") ++ versions
      else base
    else if have("rpm") then
      val (out, code) =
        capture("rpm", List("-q", "--qf", "%{VERSION}-%{RELEASE} ", name))
      val base = Map("manager" -> "dnf", "installed" -> "no")
      if code == 0 then
        base ++ Map(
          "installed" -> "yes",
          "versions" -> fields(out).mkString(",")
        )
      else base
    else Map("manager" -> "none")

  /** `systemctl is-enabled` and `is-active` for a unit. */
  def observeUnit(unit: String): Map[String, String] =
    needName("unit name", unit)
    val (en, _) = capture("systemctl", List("is-enabled", unit))
    val first = firstLine(en)
    val enabled0 = if first.isEmpty then "not-found" else first
    val enabled =
      if enabled0 == "not-found" &&
        capture("systemctl", List("cat", unit))._2 == 0
      then "static"
      else enabled0
    val (ac, _) = capture("systemctl", List("is-active", unit))
    Map("enabled" -> enabled, "active" -> firstLine(ac))

  def observeUser(name: String): Map[String, String] =
    needName("user name", name)
    passwd(name) match
      case None    => Map("exists" -> "no")
      case Some(f) =>
        val (g, _) = capture("id", List("-gn", name))
        val (gs, _) = capture("id", List("-Gn", name))
        Map(
          "exists" -> "yes",
          "uid" -> f(2),
          "home" -> f(5),
          "shell" -> f(6),
          "group" -> firstLine(g),
          "groups" -> fields(gs).mkString(",")
        )

  def observeGroup(name: String): Map[String, String] =
    needName("group name", name)
    val (out, code) = capture("getent", List("group", name))
    val f = firstLine(out).split(":", -1).toList
    if code != 0 || f.length < 3 then Map("exists" -> "no")
    else Map("exists" -> "yes", "gid" -> f(2))

  /** The first line of a small file, "" if it cannot be read. */
  private def firstLineOf(path: String): String =
    Try(Files.readAllBytes(Paths.get(path))).toOption
      .map(b => new String(b, StandardCharsets.UTF_8))
      .flatMap(_.linesIterator.nextOption())
      .getOrElse("")

  private def inDir(path: String, dir: String): Unit =
    needAbsolute(path)
    if !Option(Paths.get(path).normalize.getParent).contains(
        Paths.get(dir).normalize
      )
    then fail(s"$path is not directly in $dir")

  /** The running value of a key (`sysctl -n`) and the first line of the file it
    * is persisted in.
    */
  def observeSysctl(key: String, persisted: String): Map[String, String] =
    needName("sysctl key", key)
    val (out, code) = capture("sysctl", List("-n", key))
    val running =
      if code == 0 then
        Map("found" -> "yes", "runtime" -> fields(out).mkString(" "))
      else Map("found" -> "no")
    if persisted.nonEmpty then
      inDir(persisted, SysctlDir)
      running + ("persisted" -> firstLineOf(persisted))
    else running + ("persisted" -> "")

  /** Whether a kernel module is loaded and the first line of the file that
    * loads it at boot.
    */
  def observeModule(name: String, persisted: String): Map[String, String] =
    needName("module name", name)
    val dir = Paths.get("/sys/module/" + name.replace('-', '_'))
    val loaded = if Files.isDirectory(dir) then "yes" else "no"
    if persisted.nonEmpty then
      inDir(persisted, ModulesDir)
      Map("loaded" -> loaded, "persisted" -> firstLineOf(persisted))
    else Map("loaded" -> loaded, "persisted" -> "")

  /** The text of a regular file (up to `MaxText` bytes), for the orchestrator
    * to work out a line edit from.
    */
  def observeFileText(path: String): Map[String, String] =
    needAbsolute(path)
    if ProtectedPaths.refusal(path).isDefined then fail("protected path")
    val p = Paths.get(path)
    Try(Files.readAttributes(p, classOf[BasicFileAttributes])).toEither match
      case Left(_: java.nio.file.NoSuchFileException) =>
        Map("exists" -> "no")
      case Left(e)  => throw e
      case Right(a) =>
        if !a.isRegularFile then fail(s"$path is not a regular file")
        val in = Files.newInputStream(p)
        val buf =
          try in.readNBytes(MaxText + 1)
          finally in.close()
        val truncated = buf.length > MaxText
        val n = if truncated then MaxText else buf.length
        Map(
          "exists" -> "yes",
          "truncated" -> (if truncated then "yes" else "no"),
          "content" -> new String(buf, 0, n, StandardCharsets.UTF_8)
        )

  /** Counts the lines of a user's authorized_keys whose first two fields are
    * `keyId` ("type base64").
    */
  def observeKey(user: String, keyId: String): Map[String, String] =
    needName("user name", user)
    passwd(user) match
      case None    => Map("exists" -> "no")
      case Some(f) =>
        val file = Paths.get(f(5), ".ssh", "authorized_keys")
        val count = Try(Files.readAllBytes(file)).toOption.fold(0) { data =>
          new String(data, StandardCharsets.UTF_8).split("\n", -1).count {
            line =>
              val fs = fields(line)
              fs.length >= 2 && s"${fs(0)} ${fs(1)}" == keyId
          }
        }
        Map("exists" -> "yes", "count" -> count.toString)

  /** The fstab entry of a mount point and whether it is mounted. */
  def observeMount(
      path: String,
      fstab: Path = FstabPath,
      mountInfo: Path = MountInfoPath
  ): Map[String, String] =
    needAbsolute(path)
    val base = Map("fstab" -> "no", "mounted" -> "no")
    val withFstab = Try(Files.readAllBytes(fstab)).toOption match
      case None       => base
      case Some(data) =>
        val text = new String(data, StandardCharsets.UTF_8)
        val textVals =
          if data.length <= MaxText then Map("fstab_text" -> text)
          else Map.empty[String, String]
        val entry = text
          .split("\n", -1)
          .map(fields)
          .find(f => f.length >= 4 && !f(0).startsWith("#") && f(1) == path)
        entry match
          case Some(f) =>
            base ++ textVals ++ Map(
              "fstab" -> "yes",
              "dev" -> f(0),
              "fstype" -> f(2),
              "opts" -> f(3)
            )
          case None => base ++ textVals
    Try(Files.readAllBytes(mountInfo)).toOption match
      case None       => withFstab
      case Some(data) =>
        val mounted = new String(data, StandardCharsets.UTF_8)
          .split("\n", -1)
          .map(fields)
          .exists(f => f.length > 4 && unescapeMountField(f(4)) == path)
        if mounted then withFstab + ("mounted" -> "yes") else withFstab

  /** Undoes the octal escapes (\040 for a space) of /proc/self/mountinfo. */
  def unescapeMountField(s: String): String =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val out = new java.io.ByteArrayOutputStream
    var i = 0
    while i < bytes.length do
      val escaped =
        if bytes(i) == '\\'.toByte && i + 3 < bytes.length then
          Try(
            Integer.parseInt(
              new String(bytes, i + 1, 3, StandardCharsets.UTF_8),
              8
            )
          ).toOption.filter(_ <= 255)
        else None
      escaped match
        case Some(n) =>
          out.write(n)
          i += 4
        case None =>
          out.write(bytes(i).toInt)
          i += 1
    new String(out.toByteArray, StandardCharsets.UTF_8)
