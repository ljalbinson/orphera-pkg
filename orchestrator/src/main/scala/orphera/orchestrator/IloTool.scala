// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

/** `orphera ilo <action> <ilo>...`: power control of HPE ProLiant servers
  * through the iLO. The same commands as utils/ilo.py, on the Ilo client.
  */
object IloTool:
  val usage: String =
    """orphera ilo <action> <ilo> [<ilo> ...] [options]
      |
      |Actions:
      |  status       show the power state
      |  on           power on
      |  off          hard power off: the power is cut, the OS is not asked      (asks for yes)
      |  shutdown     graceful power off: the OS is asked to shut down (ACPI)     (asks for yes)
      |  reset        hard reset: the server restarts at once                     (asks for yes)
      |  soft-reset   the OS shuts down, then the server is powered on again      (asks for yes)
      |
      |An iLO is named by its address or DNS name, or a full http(s):// URL. Several can be named:
      |they are handled one after the other, and the exit code is 1 if any failed.
      |
      |Options:
      |  -u, --user NAME      user of the iLO (ILO_USER; default Administrator)
      |  --password-file F    read the password from a file, for every iLO named
      |  --secret NAME        the Orphera secret that holds it (default: one named after each iLO)
      |  -k, --insecure       do not check the iLO's certificate (ILO_INSECURE=1)
      |  --cacert FILE        check it against this certificate
      |  -y, --yes            do not ask for confirmation (needed when there is no terminal)
      |  -n, --dry-run        show what would be sent, send nothing
      |  --no-wait            return once the request is accepted
      |  --timeout SECONDS    how long to wait for the new power state (default 300)
      |
      |The password is ILO_PASSWORD, or --password-file, or the secret named after the iLO as typed
      |(echo 'the password' | orphera secrets set ilo-tst50), or asked for on a terminal.
      |`shutdown` and `soft-reset` never cut the power: if the OS has not shut down in time they fail,
      |and `off` is the way to force it.
      |Exit code: 0 done, 1 an iLO failed, 2 wrong usage.""".stripMargin

  private final case class Opts(
      user: String,
      passwordFile: Option[String],
      secret: Option[String],
      insecure: Boolean,
      cacert: Option[String],
      yes: Boolean,
      dryRun: Boolean,
      waits: Boolean,
      timeout: Int,
      positional: List[String]
  )

  private val valueFlags =
    Set("-u", "--user", "--password-file", "--secret", "--cacert", "--timeout")
  private val boolFlags =
    Set("-k", "--insecure", "-y", "--yes", "-n", "--dry-run", "--no-wait")

  /** (action -> whether it asks for confirmation, what it does) */
  private val actions: Map[String, (Boolean, String)] = Map(
    "status" -> (false, "show the power state"),
    "on" -> (false, "power on"),
    "off" -> (true, "hard power off"),
    "shutdown" -> (true, "graceful power off"),
    "reset" -> (true, "hard reset"),
    "soft-reset" -> (true, "soft reset: graceful shutdown, then power on")
  )

  private def parse(args: List[String], env: Map[String, String]): Either[String, Opts] =
    @scala.annotation.tailrec
    def go(rest: List[String], o: Opts): Either[String, Opts] =
      rest match
        case Nil => Right(o.copy(positional = o.positional.reverse))
        case f :: v :: tail if valueFlags(f) =>
          f match
            case "-u" | "--user"   => go(tail, o.copy(user = v))
            case "--password-file" => go(tail, o.copy(passwordFile = Some(v)))
            case "--secret"        => go(tail, o.copy(secret = Some(v)))
            case "--cacert"        => go(tail, o.copy(cacert = Some(v)))
            case _ =>
              v.toIntOption.filter(_ > 0) match
                case Some(n) => go(tail, o.copy(timeout = n))
                case None    => Left(s"--timeout needs a number of seconds, not $v")
        case f :: Nil if valueFlags(f) => Left(s"$f needs a value")
        case f :: tail if boolFlags(f) =>
          f match
            case "-k" | "--insecure" => go(tail, o.copy(insecure = true))
            case "-y" | "--yes"      => go(tail, o.copy(yes = true))
            case "-n" | "--dry-run"  => go(tail, o.copy(dryRun = true))
            case _                   => go(tail, o.copy(waits = false))
        case f :: _ if f.startsWith("-") && f.length > 1 => Left(s"unknown option $f")
        case p :: tail => go(tail, o.copy(positional = p :: o.positional))
    go(
      args,
      Opts(
        env.getOrElse("ILO_USER", "Administrator"), None, None,
        env.get("ILO_INSECURE").contains("1"), None, false, false, true, 300, Nil
      )
    )

  /** The secret that holds an iLO's password: its address or name as given, or the host of a URL. */
  def secretKey(target: String): String = DeviceAccess.secretKey(target)

  private def done(ilo: Ilo, sent: Boolean, o: Opts, text: String): Unit =
    if sent then ilo.log(s"${ilo.name}: $text${if o.waits then "" else " requested"}")

  private val ok: Either[IloError, Unit] = Right(())

  private def waitIf(ilo: Ilo, sent: Boolean, o: Opts, want: String): Either[IloError, Unit] =
    if sent && o.waits then ilo.waitFor(want, o.timeout) else ok

  /** After a restart request: give the server a moment to go down, then wait for it to be on. */
  private def waitRestart(ilo: Ilo, sent: Boolean, o: Opts): Either[IloError, Unit] =
    if sent && o.waits then
      Thread.sleep(math.min(ilo.pollMillis, 5000L))
      ilo.waitFor("On", o.timeout)
    else ok

  private def requireOn(ilo: Ilo, doc: io.circe.Json): Either[IloError, Unit] =
    Ilo.stateOf(doc) match
      case "On" => ok
      case s =>
        Left(
          IloError(
            s"${ilo.name}: the server is ${if s.isEmpty then "in an unknown power state" else s}, not on; " +
              "this needs a running server (use `on`)"
          )
        )

  /** Asks the OS to shut down. Only for a server that is on: pressing the button of one that is off powers it on. */
  private def gracefulOff(ilo: Ilo, o: Opts, doc: io.circe.Json): Either[IloError, Boolean] =
    val kind =
      if Ilo.allowed(doc).contains("GracefulShutdown") then "GracefulShutdown" else "PushPowerButton"
    ilo.sendReset(kind).flatMap { sent =>
      waitIf(ilo, sent, o, "Off").left
        .map(e => IloError(s"${e.message}; the OS has not shut down (`off` cuts the power)"))
        .map(_ => sent)
    }

  private def opStatus(ilo: Ilo): Either[IloError, Unit] =
    ilo.powerState().map(s => ilo.log(s"${ilo.name}: $s"))

  private def opOn(ilo: Ilo, o: Opts): Either[IloError, Unit] =
    ilo.powerState().flatMap {
      case "On" => Right(ilo.log(s"${ilo.name}: already on"))
      case _ =>
        for
          sent <- ilo.sendReset("On")
          _ <- waitIf(ilo, sent, o, "On")
        yield done(ilo, sent, o, "powered on")
    }

  private def opOff(ilo: Ilo, o: Opts): Either[IloError, Unit] =
    ilo.powerState().flatMap {
      case "Off" => Right(ilo.log(s"${ilo.name}: already off"))
      case _ =>
        for
          sent <- ilo.sendReset("ForceOff")
          _ <- waitIf(ilo, sent, o, "Off")
        yield done(ilo, sent, o, "hard power off")
    }

  private def opShutdown(ilo: Ilo, o: Opts): Either[IloError, Unit] =
    ilo.read().flatMap { doc =>
      if Ilo.stateOf(doc) == "Off" then Right(ilo.log(s"${ilo.name}: already off"))
      else
        for
          _ <- requireOn(ilo, doc)
          sent <- gracefulOff(ilo, o, doc)
        yield done(ilo, sent, o, "graceful shutdown")
    }

  private def opReset(ilo: Ilo, o: Opts): Either[IloError, Unit] =
    for
      doc <- ilo.read()
      _ <- requireOn(ilo, doc)
      sent <- ilo.sendReset("ForceRestart")
      _ <- waitRestart(ilo, sent, o)
    yield done(ilo, sent, o, "hard reset")

  private def opSoftReset(ilo: Ilo, o: Opts): Either[IloError, Unit] =
    ilo.read().flatMap { doc =>
      requireOn(ilo, doc).flatMap { _ =>
        if Ilo.allowed(doc).contains("GracefulRestart") then
          for
            sent <- ilo.sendReset("GracefulRestart")
            _ <- waitRestart(ilo, sent, o)
          yield done(ilo, sent, o, "soft reset")
        else
          // iLO 4 has no graceful restart: shut the OS down, then power on
          gracefulOff(ilo, o, doc).flatMap { sent =>
            if !sent then
              Right(ilo.log(s"${ilo.name}: would then wait for the shutdown and send POST On"))
            else if !o.waits then
              Right(ilo.log(s"${ilo.name}: shutdown requested; --no-wait leaves the power on to you"))
            else
              for
                _ <- ilo.sendReset("On")
                _ <- ilo.waitFor("On", o.timeout)
              yield ilo.log(s"${ilo.name}: soft reset (shut down, then powered on)")
          }
      }
    }

  private def operate(action: String, ilo: Ilo, o: Opts): Either[IloError, Unit] =
    action match
      case "status"     => opStatus(ilo)
      case "on"         => opOn(ilo, o)
      case "off"        => opOff(ilo, o)
      case "shutdown"   => opShutdown(ilo, o)
      case "reset"      => opReset(ilo, o)
      case _            => opSoftReset(ilo, o)

  private def confirm(action: String, o: Opts, targets: List[String]): Either[String, Unit] =
    val (destructive, desc) = actions.getOrElse(action, (false, ""))
    if !destructive || o.yes || o.dryRun then Right(())
    else if System.console() == null then Left(s"$action needs --yes when there is no terminal")
    else
      println(s"$action ($desc): ${targets.mkString(", ")}")
      print("type yes to go on: ")
      if Option(scala.io.StdIn.readLine()).exists(_.trim == "yes") then Right(())
      else Left("not confirmed, nothing sent")

  /** Returns the exit code: 0 done, 1 an iLO failed, 2 wrong usage.
    * `pollMillis` is how often the power state is read while waiting.
    */
  def run(args: List[String], env: Map[String, String] = sys.env, pollMillis: Long = 3000L): Int =
    args match
      case Nil | ("help" | "-h" | "--help") :: _ =>
        println(usage)
        if args.isEmpty then 2 else 0
      case action :: _ if !actions.contains(action) =>
        Console.err.println(s"orphera ilo: unknown action '$action' (orphera ilo --help)")
        2
      case action :: rest =>
        parse(rest, env) match
          case Left(msg) =>
            Console.err.println(s"orphera ilo: $msg")
            2
          case Right(o) if o.positional.isEmpty =>
            Console.err.println(s"orphera ilo: $action needs an iLO (orphera ilo --help)")
            2
          case Right(o) =>
            confirm(action, o, o.positional) match
              case Left(msg) =>
                Console.err.println(s"orphera ilo: $msg")
                2
              case Right(_) =>
                val failed = o.positional.count { target =>
                  val result =
                    DeviceAccess
                      .password(env, "ILO_PASSWORD", o.passwordFile, o.secret, target, o.user)
                      .left
                      .map(m => IloError(m))
                      .flatMap { pw =>
                        val ilo = Ilo(
                          target, o.user, pw, o.insecure, o.cacert,
                          dryRun = o.dryRun, pollMillis = pollMillis
                        )
                        operate(action, ilo, o)
                      }
                  result.left.exists { e =>
                    Console.err.println(s"FAIL  ${e.message}")
                    true
                  }
                }
                if failed > 0 then 1 else 0
