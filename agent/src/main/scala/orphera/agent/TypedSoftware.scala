// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

import orphera.common.*

import Fixed.*

/** ApplySoftware: makes the changes it lists, in order, to packages and systemd
  * units, from a fixed set of actions, and runs no script: install a package
  * (at a version), remove one, enable, disable, start, stop or restart a unit,
  * reload systemd. It is its own policy operation, `apply-software`, since a
  * package's own scripts run as root. The same as the Go agent
  * (`applysoftware.go`); keep the two the same.
  */
object TypedSoftware:

  /** A package name or version as both apt and dnf write them: no "=" or "/",
    * nothing that a package manager would read as an option or a file.
    */
  private val PackageWord = "^[A-Za-z0-9][A-Za-z0-9.+_:~-]*$".r

  /** systemd's own words for a unit's name: no path, no space. */
  private val UnitWord = "^[A-Za-z0-9][A-Za-z0-9:_.@-]*$".r

  private val UnitVerbs =
    Set("enable", "disable", "start", "stop", "restart", "try-restart")

  private val Slow = 15.minutes
  private val Short = 2.minutes

  private val PackageEnv = Map("DEBIAN_FRONTEND" -> "noninteractive")

  def describe(a: SoftwareAction): String = a.action match
    case SoftwareAction.Action.PackageInstall(x) => s"install ${x.name}"
    case SoftwareAction.Action.PackageRemove(x)  => s"remove ${x.name}"
    case SoftwareAction.Action.UnitControl(x)    => s"${x.verb} ${x.unit}"
    case SoftwareAction.Action.ReloadUnits(_)    => "reload systemd"
    case _                                       => "unknown action"

  def apply(actions: List[SoftwareAction]): ApplyReply =
    sequence(actions)(describe)(applyOne)

  private def valid(word: scala.util.matching.Regex, s: String): Boolean =
    word.matches(s)

  private def runSoftware(
      timeout: FiniteDuration,
      name: String,
      args: String*
  ): Unit = run(name, args.toList, timeout, PackageEnv)

  private def applyOne(a: SoftwareAction): Unit = a.action match
    case SoftwareAction.Action.PackageInstall(x) => installPackage(x)
    case SoftwareAction.Action.PackageRemove(x)  =>
      if !valid(PackageWord, x.name) then
        fail(s"not a valid package name: '${x.name}'")
      removePackage(x.name)
    case SoftwareAction.Action.UnitControl(u) =>
      if !valid(UnitWord, u.unit) then fail(s"not a valid unit name: '${u.unit}'")
      if !UnitVerbs.contains(u.verb) then fail(s"not a unit action: '${u.verb}'")
      runSoftware(Short, "systemctl", u.verb, u.unit)
    case SoftwareAction.Action.ReloadUnits(_) =>
      runSoftware(Short, "systemctl", "daemon-reload")
    case _ => fail("an action this agent does not know")

  private def installPackage(p: PackageInstall): Unit =
    if !valid(PackageWord, p.name) then
      fail(s"not a valid package name: '${p.name}'")
    if p.version.nonEmpty && !valid(PackageWord, p.version) then
      fail(s"not a valid package version: '${p.version}'")
    if have("apt-get") then
      waitAptIdle(Slow)
      val spec = if p.version.isEmpty then p.name else s"${p.name}=${p.version}"
      if p.updateCache then runSoftware(Slow, "apt-get", "update", "-q")
      runSoftware(
        Slow,
        "apt-get",
        "install",
        "-y",
        "-q",
        "-o",
        "DPkg::Lock::Timeout=300",
        "--allow-downgrades",
        spec
      )
    else if have("dnf") then
      val spec = if p.version.isEmpty then p.name else s"${p.name}-${p.version}"
      try runSoftware(Slow, "dnf", "-y", "-q", "install", spec)
      catch
        case e: ApplyFailure =>
          // The wanted version is older than the installed one.
          try runSoftware(Slow, "dnf", "-y", "-q", "downgrade", spec)
          catch case _: ApplyFailure => throw e
    else fail("neither apt nor dnf on this node")

  private def removePackage(name: String): Unit =
    if have("apt-get") then
      waitAptIdle(Slow)
      runSoftware(
        Slow,
        "apt-get",
        "remove",
        "-y",
        "-q",
        "-o",
        "DPkg::Lock::Timeout=300",
        name
      )
    else if have("dnf") then runSoftware(Slow, "dnf", "-y", "-q", "remove", name)
    else fail("neither apt nor dnf on this node")

  /** How often `waitAptIdle` looks again; the checks shorten it. */
  @volatile private[agent] var aptPoll: FiniteDuration = 5.seconds

  /** Whether apt-get, apt, dpkg or unattended-upgrades is running. The dpkg
    * lock alone is not enough: unattended-upgrades on a fresh VM can be between
    * two locks, and when it ends needrestart restarts the agent in the middle
    * of a task. A node without pgrep is taken to be idle. The checks replace it.
    */
  @volatile private[agent] var aptBusy: () => Boolean = () =>
    List(
      List("-x", "apt-get"),
      List("-x", "apt"),
      List("-x", "dpkg"),
      List("-f", "^/usr/bin/python3 /usr/bin/unattended-upgrade$")
    ).exists(args =>
      Try(
        new ProcessBuilder(("pgrep" :: args)*)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor() == 0
      ).getOrElse(false)
    )

  /** Waits until nothing is using apt, for at most `max`. */
  private[agent] def waitAptIdle(max: FiniteDuration): Unit =
    val deadline = System.nanoTime() + max.toNanos
    @tailrec def go(): Unit =
      if aptBusy() then
        if System.nanoTime() > deadline then
          fail(s"apt or dpkg still busy after $max (unattended-upgrades?)")
        Thread.sleep(aptPoll.toMillis)
        go()
    go()
