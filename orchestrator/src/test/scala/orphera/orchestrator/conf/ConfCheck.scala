// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import orphera.orchestrator.{Resource, Secrets, Task}

/** Checks for the typed configuration spike. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.conf.ConfCheck"`.
  *
  * The catalogue below is a hand-written fixture shaped like part of
  * Placement's options. It is not the real list; that comes from the image.
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
      OptionSpec(
        "DEFAULT",
        "log_file",
        OptType.Text,
        deprecated = List("logfile")
      ),
      OptionSpec("DEFAULT", "bind_port", OptType.PortNumber, Some("8780")),
      OptionSpec("DEFAULT", "workers", OptType.Integer),
      OptionSpec(
        "api",
        "auth_strategy",
        OptType.Choice(List("keystone", "noauth2")),
        Some("keystone")
      ),
      OptionSpec(
        "placement_database",
        "connection",
        OptType.Text,
        secret = true
      ),
      OptionSpec("keystone_authtoken", "www_authenticate_uri", OptType.Text),
      OptionSpec("keystone_authtoken", "password", OptType.Text, secret = true)
    )
  )

  // Shaped like the real `oslo-config-generator --format json` output from the
  // placement-api image (two sections, trimmed).
  private val osloSample =
    """{"deprecated_options": {}, "generator_options": {}, "options": {
      |"DEFAULT": {"help": "", "opts": [
      |  {"name": "log-dir", "dest": "log_dir", "type": "string value", "default": null, "secret": false, "choices": [], "deprecated_opts": [{"group": "DEFAULT", "name": "logdir"}]},
      |  {"name": "log-dir", "dest": "log_dir", "type": "string value", "default": null, "secret": false, "choices": [], "deprecated_opts": [{"group": "DEFAULT", "name": "logdir"}]}]},
      |"api": {"help": "", "opts": [
      |  {"name": "auth_strategy", "type": "string value", "default": "keystone", "secret": false,
      |   "choices": [["keystone", null], ["noauth2", null]], "deprecated_opts": [{"group": "DEFAULT", "name": "auth_strategy"}]}]},
      |"placement_database": {"help": "", "opts": [
      |  {"name": "connection", "type": "string value", "default": null, "secret": true, "choices": [], "deprecated_opts": []},
      |  {"name": "max_retries", "type": "integer value", "default": 10, "secret": false, "choices": [], "deprecated_opts": []},
      |  {"name": "use_db_reconnect", "type": "boolean value", "default": false, "secret": false, "choices": [], "deprecated_opts": []},
      |  {"name": "slave_connection", "type": "string value", "default": null, "secret": true, "choices": [],
      |   "deprecated_opts": [{"group": "placement_database", "name": "slave"}]}]},
      |"keystone_authtoken": {"help": "", "opts": [
      |  {"name": "memcached_servers", "type": "list of string values", "default": null, "secret": false, "choices": [], "deprecated_opts": []},
      |  {"name": "timeout", "type": "floating point value", "default": 1.5, "secret": false, "choices": [], "deprecated_opts": []}]}
      |}}""".stripMargin

  private def err(r: Either[ConfError, ConfFile]): Option[ConfError] =
    r.left.toOption

  def main(args: Array[String]): Unit =
    val empty = ConfFile.empty("/etc/placement/placement.conf", fixture)

    val ok = for
      a <- empty.set("DEFAULT", "debug", true)
      b <- a.set(
        "placement_database",
        "connection",
        "mysql+pymysql://u:p@10.10.5.100/placement"
      )
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
    check(
      "setting a key again replaces it in place",
      again.map(_.render.linesIterator.toList.take(2)) == Right(
        List("[DEFAULT]", "debug = false")
      )
    )

    check(
      "an unknown section is refused with a suggestion",
      err(empty.set("keystone_authtokn", "password", "x")) == Some(
        ConfError.UnknownSection(
          "keystone_authtokn",
          List("keystone_authtoken")
        )
      )
    )
    check(
      "an unknown option is refused with a suggestion",
      err(empty.set("DEFAULT", "debgu", true)) == Some(
        ConfError.UnknownOption("DEFAULT", "debgu", List("debug"))
      )
    )
    check(
      "a deprecated name is refused and the new name given",
      err(empty.set("DEFAULT", "logfile", "/x")) == Some(
        ConfError.Deprecated("DEFAULT", "logfile", "log_file")
      )
    )
    check(
      "a switch given a string is refused",
      err(empty.set("DEFAULT", "debug", "yes"))
        .exists(_.isInstanceOf[ConfError.WrongType])
    )
    check(
      "an integer option given a boolean is refused",
      err(empty.set("DEFAULT", "workers", true))
        .exists(_.isInstanceOf[ConfError.WrongType])
    )
    check(
      "a port above 65535 is refused",
      err(empty.set("DEFAULT", "bind_port", 70000))
        .exists(_.isInstanceOf[ConfError.WrongType])
    )
    check(
      "a port in range is accepted",
      empty.set("DEFAULT", "bind_port", 8780).isRight
    )
    check(
      "a value outside the allowed list is refused",
      err(empty.set("api", "auth_strategy", "none")) == Some(
        ConfError.NotAllowed(
          "api",
          "auth_strategy",
          List("keystone", "noauth2")
        )
      )
    )
    check(
      "a value inside the allowed list is accepted",
      empty.set("api", "auth_strategy", "noauth2").isRight
    )

    val many = ConfFile.of("/p", fixture)(
      ("DEFAULT", "debgu", Value.Bool(true)),
      ("DEFAULT", "workers", Value.Whole(2)),
      ("nowhere", "x", Value.Str("y"))
    )
    check(
      "of reports every error at once",
      many.left.map(_.size) == Left(2),
      many.toString
    )

    check(
      "error messages never contain a secret value",
      err(empty.set("keystone_authtoken", "passwrd", "hunter2"))
        .forall(!_.message.contains("hunter2"))
    )
    check(
      "an error message reads as a sentence",
      ConfError
        .UnknownOption("DEFAULT", "debgu", List("debug"))
        .message == "no option debgu in [DEFAULT] (did you mean debug?)"
    )

    val loaded = OsloJson.load("placement", osloSample)
    check("an oslo JSON dump loads", loaded.isRight, loaded.toString)
    loaded.foreach { cat =>
      check(
        "sections and options are read",
        cat.sections == Set(
          "DEFAULT",
          "api",
          "placement_database",
          "keystone_authtoken"
        ) && cat.options.size == 8,
        cat.options.size.toString
      )
      check(
        "choices make a Choice type",
        cat.find("api", "auth_strategy").map(_.tpe) == Some(
          OptType.Choice(List("keystone", "noauth2"))
        )
      )
      check(
        "oslo type words map to ours",
        List(
          "max_retries" -> OptType.Integer,
          "use_db_reconnect" -> OptType.Flag,
          "connection" -> OptType.Text
        )
          .forall((n, t) =>
            cat.find("placement_database", n).map(_.tpe).contains(t)
          ) &&
          cat
            .find("keystone_authtoken", "memcached_servers")
            .map(_.tpe) == Some(OptType.Items) &&
          cat.find("keystone_authtoken", "timeout").map(_.tpe) == Some(
            OptType.Real
          )
      )
      check(
        "defaults and secrets are kept",
        cat
          .find("placement_database", "max_retries")
          .flatMap(_.default) == Some("10") && cat
          .find("placement_database", "connection")
          .exists(_.secret)
      )
      check(
        "an old name in the same section is recorded, one from another section is not",
        cat
          .find("placement_database", "slave_connection")
          .map(_.deprecated) == Some(List("slave")) && cat
          .find("api", "auth_strategy")
          .map(_.deprecated) == Some(Nil)
      )
      val f = ConfFile.empty("/etc/placement/placement.conf", cat)
      check(
        "a name written with _ matches an option declared with -",
        f.set("DEFAULT", "log_dir", "/var/log/x").isRight && f
          .set("DEFAULT", "log-dir", "/x")
          .isRight
      )
      check(
        "the same option from two namespaces is listed once",
        cat.optionsIn("DEFAULT") == List("log-dir"),
        cat.optionsIn("DEFAULT").toString
      )
      val open = cat.copy(openSections = Set("keystone_authtoken"))
      val fo = ConfFile.empty("/p", open)
      check(
        "an open section accepts a plugin option, a closed one does not",
        fo.set("keystone_authtoken", "username", "placement").isRight && f
          .set("keystone_authtoken", "username", "placement")
          .isLeft
      )
      check(
        "an open section refuses a near miss of a known option, with a suggestion",
        fo.set("keystone_authtoken", "timeot", "1")
          .left
          .toOption
          .exists(_.message.contains("timeout"))
      )
      check(
        "an open section still checks the options it knows",
        fo.set("keystone_authtoken", "timeout", "slow").isLeft
      )
      val fc = ConfFile.empty("/p", cat.copy(openSections = Set("ceph")))
      check(
        "an open section that the image does not declare at all takes its options",
        fc.set("ceph", "rbd_pool", "volumes").isRight && f
          .set("ceph", "rbd_pool", "volumes")
          .isLeft
      )
      check(
        "a loaded catalogue refuses a wrong type",
        f.set("placement_database", "max_retries", "many").isLeft
      )
      check(
        "a loaded catalogue refuses an unknown key with a suggestion",
        f.set("placement_database", "conection", "x")
          .left
          .toOption
          .exists(_.message.contains("connection"))
      )
    }
    val withOld = Catalogue(
      "x",
      List(
        OptionSpec(
          "keystone_authtoken",
          "www_authenticate_uri",
          OptType.Text,
          deprecated = List("auth_uri")
        ),
        OptionSpec("keystone_authtoken", "auth_uri", OptType.Text)
      ),
      Set("keystone_authtoken")
    )
    val fw = ConfFile.empty("/p", withOld)
    check(
      "an open section accepts a plugin key that is one edit from an old name (auth_url vs auth_uri)",
      fw.set("keystone_authtoken", "auth_url", "https://x").isRight
    )
    check(
      "an open section still refuses a near miss of a current name",
      fw.set("keystone_authtoken", "www_authenticate_url", "x").isLeft
    )
    check(
      "text that is not oslo output is refused",
      OsloJson.load("x", "{\"a\": 1}").isLeft && OsloJson.load("x", "][").isLeft
    )

    val r = summon[Resource[ConfFile]]
    val file = ok.toOption.get
    check(
      "a ConfFile writes mode 0640 to its path",
      r.write(file) == Task.WriteFile(
        file.render,
        "/etc/placement/placement.conf",
        "root",
        "root",
        416
      )
    )
    check(
      "a ConfFile is checked against its path",
      r.check(file) match
        case Task.RunCommand(List("sh", "-c", script), _, _) =>
          script.contains(
            """diff -u --label "node: /etc/placement/placement.conf""""
          )
        case _ => false
    )

    // Text values, as a script gives them, and the catalogue loader's errors.
    {
      val cat = loaded.toOption.get
      def ok(section: String, key: String, v: String) =
        scala.util.Try(ConfFile.text("/p", cat)((section, key, v))).isSuccess
      check(
        "text for an integer, a switch and a list reads as its type",
        ok("placement_database", "max_retries", "60") && ok(
          "placement_database",
          "use_db_reconnect",
          "true"
        ) && ok("keystone_authtoken", "memcached_servers", "a:1,b:2")
      )
      check(
        "text that does not read as the type is refused",
        !ok("placement_database", "max_retries", "many") && !ok(
          "placement_database",
          "use_db_reconnect",
          "yes please"
        ) && !ok("keystone_authtoken", "timeout", "slow")
      )
      check(
        "ConfFile.text names the path and lists every error",
        scala.util
          .Try(
            ConfFile.text("/etc/x.conf", cat)(
              ("api", "auth_strategi", "keystone"),
              ("nowhere", "k", "v")
            )
          )
          .failed
          .toOption
          .exists(e =>
            e.getMessage.startsWith("/etc/x.conf: ") && e.getMessage
              .contains("auth_strategy") && e.getMessage.contains("[nowhere]")
          )
      )
      check(
        "a missing catalogue file says where to get one",
        scala.util
          .Try(
            Catalogues
              .load("nova", from = java.nio.file.Paths.get("/nonexistent-dir"))
          )
          .failed
          .toOption
          .exists(_.getMessage.contains("dump_oslo_options.sh"))
      )
    }

    // Secrets: nothing secret goes to the node in a check, and nothing is printed.
    {
      val secretPw = "hunter2-hunter2"
      val dbUrl = "mysql+pymysql://u:dbsecretpw@10.0.0.1/db"
      val memKey = "memkey-memkey"
      val file2 = ConfFile.empty(
        "/p/x.conf",
        fixture.copy(openSections = Set("keystone_authtoken"))
      )
      val withPw = for
        a <- file2.set("DEFAULT", "debug", true)
        b <- a.set("keystone_authtoken", "password", secretPw)
        c <- b.set("placement_database", "connection", dbUrl)
        d <- c.set("keystone_authtoken", "memcache_secret_key", memKey)
      yield d
      val wf = withPw.toOption.get
      check(
        "secret keys are the catalogue's secret options and names that say so",
        wf.secretKeys.toSet == Set(
          "password",
          "connection",
          "memcache_secret_key"
        ),
        wf.secretKeys.toString
      )
      check(
        "secret values are listed for masking",
        wf.secretValues.toSet == Set(secretPw, dbUrl, memKey)
      )
      val chk = r.check(wf) match
        case Task.RunCommand(List("sh", "-c", script), _, _) => script
        case other                                        => other.toString
      check(
        "the check command contains no secret value",
        !chk.contains(secretPw) && !chk.contains("dbsecretpw") && !chk.contains(
          dbUrl
        ) && !chk.contains(memKey),
        chk
      )
      check(
        "the check command shows the secret lines as <redacted> and still shows the others",
        chk.contains("password = <redacted>") && chk.contains(
          "connection = <redacted>"
        ) && chk.contains("debug = true"),
        chk
      )
      check(
        "the check command compares a hash of the whole file",
        chk.contains(Resource.sha256(wf.render)) && chk.contains("sha256sum")
      )
      check(
        "redact leaves other lines and the line endings alone",
        Resource.redact(
          "a = 1\npassword =  x y\nb = 2\n",
          List("password")
        ) == "a = 1\npassword =  <redacted>\nb = 2\n"
      )
      check(
        "redact keys are matched whole, not as a suffix",
        Resource.redact(
          "xpassword = 1\n",
          List("password")
        ) == "xpassword = 1\n"
      )
      check(
        "a secret key with odd characters is refused",
        scala.util.Try(Resource.redact("a", List("a;b"))).isFailure
      )
      Secrets.clear()
      r.write(wf)
      check(
        "writing a secret file registers its values for masking",
        Secrets.mask(s"x $secretPw y $dbUrl") == "x <redacted> y <redacted>"
      )
      check(
        "short values are not registered", {
          Secrets.clear(); Secrets.register("yes");
          Secrets.mask("yes yes") == "yes yes"
        }
      )
      check(
        "a value that contains another is masked whole", {
          Secrets.clear(); Secrets.register("abcdef");
          Secrets.register("abcdefghij");
          Secrets.mask("abcdefghij") == "<redacted>"
        }
      )
      Secrets.clear()
    }

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"ConfCheck: all $passes checks passed")
