// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import orphera.orchestrator.{Resource, Task}

/** Checks for the typed configuration spike. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.conf.ConfCheck"`.
  *
  * The catalogue below is a hand-written fixture shaped like part of Placement's
  * options. It is not the real list; that comes from the image.
  */
object ConfCheck:
  private var failures = 0
  private var passes = 0
  private val verbose = sys.env.contains("CHECK_VERBOSE")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then
      passes += 1
      if verbose then println(s"PASS: $name")
    else
      failures += 1
      println(s"FAIL: $name $detail")

  private val fixture = Catalogue(
    "placement",
    List(
      OptionSpec("DEFAULT", "debug", OptType.Flag, Some("false")),
      OptionSpec("DEFAULT", "log_file", OptType.Text, deprecated = List("logfile")),
      OptionSpec("DEFAULT", "bind_port", OptType.PortNumber, Some("8780")),
      OptionSpec("DEFAULT", "workers", OptType.Integer),
      OptionSpec("api", "auth_strategy", OptType.Choice(List("keystone", "noauth2")), Some("keystone")),
      OptionSpec("placement_database", "connection", OptType.Text, secret = true),
      OptionSpec("keystone_authtoken", "www_authenticate_uri", OptType.Text),
      OptionSpec("keystone_authtoken", "password", OptType.Text, secret = true)
    )
  )

  private def err(r: Either[ConfError, ConfFile]): Option[ConfError] = r.left.toOption

  def main(args: Array[String]): Unit =
    val empty = ConfFile.empty("/etc/placement/placement.conf", fixture)

    val ok = for
      a <- empty.set("DEFAULT", "debug", true)
      b <- a.set("placement_database", "connection", "mysql+pymysql://u:p@10.10.5.100/placement")
      c <- b.set("DEFAULT", "workers", 4)
    yield c
    check("valid settings are accepted", ok.isRight)
    check(
      "render groups by section in order of first use, and a second DEFAULT key joins the first",
      ok.map(_.render) == Right(
        "[DEFAULT]\ndebug = true\nworkers = 4\n\n[placement_database]\nconnection = mysql+pymysql://u:p@10.10.5.100/placement\n"
      ),
      ok.map(_.render).toString
    )

    val again = ok.flatMap(_.set("DEFAULT", "debug", false))
    check("setting a key again replaces it in place", again.map(_.render.linesIterator.toList.take(2)) == Right(List("[DEFAULT]", "debug = false")))

    check(
      "an unknown section is refused with a suggestion",
      err(empty.set("keystone_authtokn", "password", "x")) == Some(ConfError.UnknownSection("keystone_authtokn", List("keystone_authtoken")))
    )
    check(
      "an unknown option is refused with a suggestion",
      err(empty.set("DEFAULT", "debgu", true)) == Some(ConfError.UnknownOption("DEFAULT", "debgu", List("debug")))
    )
    check(
      "a deprecated name is refused and the new name given",
      err(empty.set("DEFAULT", "logfile", "/x")) == Some(ConfError.Deprecated("DEFAULT", "logfile", "log_file"))
    )
    check(
      "a switch given a string is refused",
      err(empty.set("DEFAULT", "debug", "yes")).exists(_.isInstanceOf[ConfError.WrongType])
    )
    check(
      "an integer option given a boolean is refused",
      err(empty.set("DEFAULT", "workers", true)).exists(_.isInstanceOf[ConfError.WrongType])
    )
    check("a port above 65535 is refused", err(empty.set("DEFAULT", "bind_port", 70000)).exists(_.isInstanceOf[ConfError.WrongType]))
    check("a port in range is accepted", empty.set("DEFAULT", "bind_port", 8780).isRight)
    check(
      "a value outside the allowed list is refused",
      err(empty.set("api", "auth_strategy", "none")) == Some(ConfError.NotAllowed("api", "auth_strategy", List("keystone", "noauth2")))
    )
    check("a value inside the allowed list is accepted", empty.set("api", "auth_strategy", "noauth2").isRight)

    val many = ConfFile.of("/p", fixture)(
      ("DEFAULT", "debgu", Value.Bool(true)),
      ("DEFAULT", "workers", Value.Whole(2)),
      ("nowhere", "x", Value.Str("y"))
    )
    check("of reports every error at once", many.left.map(_.size) == Left(2), many.toString)

    check(
      "error messages never contain a secret value",
      err(empty.set("keystone_authtoken", "passwrd", "hunter2")).forall(!_.message.contains("hunter2"))
    )
    check(
      "an error message reads as a sentence",
      ConfError.UnknownOption("DEFAULT", "debgu", List("debug")).message == "no option debgu in [DEFAULT] (did you mean debug?)"
    )

    val r = summon[Resource[ConfFile]]
    val file = ok.toOption.get
    check("a ConfFile writes mode 0640 to its path", r.write(file) == Task.WriteFile(file.render, "/etc/placement/placement.conf", "root", "root", 416))
    check(
      "a ConfFile is checked against its path",
      r.check(file) match
        case Task.RunCommand(List("sh", "-c", script), _) => script.contains("diff -u /etc/placement/placement.conf -")
        case _                                            => false
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"ConfCheck: all $passes checks passed")
