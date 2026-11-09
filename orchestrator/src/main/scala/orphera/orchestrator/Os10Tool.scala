// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.{Files, Paths}

import io.circe.{Json, Printer}

/** `orphera os10 <command> <switch> ...`: read, and change, a Dell OS10 switch
  * through its RESTCONF API. The same commands as utils/os10.py, on the Os10
  * client library. Changes are to the running configuration; `save` keeps
  * them over a reload.
  */
object Os10Tool:
  val usage: String =
    """orphera os10 <command> <switch> [arguments] [options]
      |
      |Reading (changes nothing):
      |  interfaces <switch>              every port: mode, speed, MTU and its VLANs
      |  vlans <switch>                   every VLAN: how many ports, tagged and untagged
      |  vlan <switch> <vlan>             one VLAN's ports
      |  port <switch> <port>             one port's settings and VLANs (1/1/49:1 or ethernet1/1/49:1)
      |  get <switch> <path>              any path under /restconf/data
      |  operations <switch>              the actions the switch offers
      |  schema <switch> <module>         a YANG module's source, from the switch
      |  backup <switch> [file]           the whole RESTCONF data tree as JSON
      |
      |Changing (the running configuration; asks for `yes`, --yes skips it, --dry-run sends nothing):
      |  set-description <switch> <port> <text>   "" removes it
      |  set-mtu <switch> <port> <bytes>
      |  set-speed <switch> <port> <10G|25G|40G|50G|100G|auto>
      |  set-mode <switch> <port> <trunk|access>  a port must be a trunk to carry tagged VLANs
      |  vlan-create <switch> <vlan>              2-4093
      |  vlan-delete <switch> <vlan>              only a VLAN with no ports
      |  vlan-add <switch> <vlan> <port> [--untagged]   tagged unless --untagged
      |  vlan-remove <switch> <vlan> <port>
      |  save <switch>                    copy the running to the startup configuration
      |
      |Options:
      |  -u, --user NAME      user of the switch (OS10_USER; default admin)
      |  --password-file F    read the password from a file
      |  --secret NAME        the Orphera secret that holds it (default: the one named after the switch)
      |  -k, --insecure       do not check the switch's certificate (OS10_INSECURE=1)
      |  --cacert FILE        check it against this certificate
      |  --json               interfaces, vlans, vlan and port as JSON
      |  --yes, --dry-run     for the changes
      |
      |The password is OS10_PASSWORD, or --password-file, or the secret named after the switch as typed
      |(echo 'the password' | orphera secrets set swt4), or asked for on a terminal.
      |RESTCONF is off on the switch until `rest api restconf` in configuration mode (then write memory).""".stripMargin

  private final case class Opts(
      user: String,
      passwordFile: Option[String],
      secret: Option[String],
      insecure: Boolean,
      cacert: Option[String],
      json: Boolean,
      yes: Boolean,
      dryRun: Boolean,
      untagged: Boolean,
      positional: List[String]
  )

  private val valueFlags =
    Set("-u", "--user", "--password-file", "--secret", "--cacert")
  private val boolFlags =
    Set("-k", "--insecure", "--json", "--yes", "--dry-run", "--untagged")

  /** (command word -> what its first argument is called, or None) */
  private val needs: Map[String, Option[String]] = Map(
    "interfaces" -> None, "vlans" -> None, "operations" -> None,
    "save" -> None, "backup" -> None,
    "vlan" -> Some("VLAN number"), "port" -> Some("port, e.g. 1/1/49:1"),
    "get" -> Some("path under /restconf/data"),
    "schema" -> Some("module name, e.g. yuma-netconf"),
    "set-description" -> Some("port, e.g. 1/1/5"), "set-mtu" -> Some("port, e.g. 1/1/5"),
    "set-speed" -> Some("port, e.g. 1/1/5"), "set-mode" -> Some("port, e.g. 1/1/5"),
    "vlan-create" -> Some("VLAN number"), "vlan-delete" -> Some("VLAN number"),
    "vlan-add" -> Some("VLAN number and a port"), "vlan-remove" -> Some("VLAN number and a port")
  )

  private def parse(
      args: List[String],
      env: Map[String, String]
  ): Either[String, Opts] =
    @scala.annotation.tailrec
    def go(rest: List[String], o: Opts): Either[String, Opts] =
      rest match
        case Nil => Right(o.copy(positional = o.positional.reverse))
        case f :: v :: tail if valueFlags(f) =>
          f match
            case "-u" | "--user"   => go(tail, o.copy(user = v))
            case "--password-file" => go(tail, o.copy(passwordFile = Some(v)))
            case "--secret"        => go(tail, o.copy(secret = Some(v)))
            case _                 => go(tail, o.copy(cacert = Some(v)))
        case f :: Nil if valueFlags(f) => Left(s"$f needs a value")
        case f :: tail if boolFlags(f) =>
          f match
            case "-k" | "--insecure" => go(tail, o.copy(insecure = true))
            case "--json"            => go(tail, o.copy(json = true))
            case "--yes"             => go(tail, o.copy(yes = true))
            case "--dry-run"         => go(tail, o.copy(dryRun = true))
            case _                   => go(tail, o.copy(untagged = true))
        case f :: _ if f.startsWith("-") && f.length > 1 =>
          Left(s"unknown option $f")
        case p :: tail => go(tail, o.copy(positional = p :: o.positional))
    go(
      args,
      Opts(
        env.getOrElse("OS10_USER", "admin"), None, None,
        env.get("OS10_INSECURE").contains("1"), None, false, false, false, false, Nil
      )
    )

  /** The secret that holds a switch's password: its address or name as given, or the host of a URL. */
  def secretKey(target: String): String =
    val t = target.trim.stripSuffix("/")
    if t.contains("://") then
      scala.util.Try(java.net.URI(t).getHost).toOption.filter(h => h != null && h.nonEmpty).getOrElse(t)
    else t

  private def password(
      o: Opts,
      target: String,
      env: Map[String, String]
  ): Either[Os10Error, String] =
    env.get("OS10_PASSWORD").filter(_.nonEmpty) match
      case Some(p) => Right(p)
      case None =>
        o.passwordFile match
          case Some(f) =>
            try Right(Files.readString(Paths.get(f)).stripSuffix("\n").stripSuffix("\r"))
            catch case e: Exception => Left(Os10Error(s"cannot read $f: ${e.getMessage}"))
          case None =>
            val name = o.secret.getOrElse(secretKey(target))
            val (value, why) = SecretStore.default().flatMap(_.get(name)) match
              case Right(Some(v)) if v.nonEmpty => (Some(v), "")
              case Right(_)                     => (None, s"no secret called $name")
              case Left(msg)                    => (None, msg)
            value match
              case Some(v) => Right(v)
              case None if System.console() != null =>
                val pw = System.console().readPassword(
                  s"password for ${o.user} on $target (no secret $name: $why): "
                )
                if pw == null then Left(Os10Error("no password given")) else Right(String(pw))
              case None =>
                Left(
                  Os10Error(
                    s"no password for $target: no secret `$name` ($why); store it with: echo 'the password' | orphera secrets set $name"
                  )
                )

  private def confirm(o: Opts, command: String): Either[Os10Error, Unit] =
    if o.yes then Right(())
    else if System.console() == null then
      Left(Os10Error(s"$command needs --yes when there is no terminal"))
    else
      print("type yes to go on: ")
      if Option(scala.io.StdIn.readLine()).exists(_.trim == "yes") then Right(())
      else Left(Os10Error("not confirmed, nothing sent"))

  private def applyPlan(
      sw: Os10Switch,
      o: Opts,
      command: String,
      plan: Either[Os10Error, Os10Plan]
  ): Either[Os10Error, Unit] =
    plan.flatMap {
      case Os10NoChange(m) => println(m); Right(())
      case Os10Apply(change) =>
        println(change.headline)
        if o.dryRun then
          change.steps.foreach(s => println(s"dry run, would send: ${s.show}"))
          change.retryNote.foreach(n => println(n))
          Right(())
        else
          confirm(o, command).flatMap(_ =>
            Os10.execute(sw, change, m => println(m)).map(m => println(m))
          )
    }

  /** Two-space indent and `"name": "x"`, as utils/os10.py prints it (circe's own spaces2 puts a space before the colon). */
  private val printer: Printer = Printer.spaces2.copy(colonLeft = "")

  private def printJson(j: Json): Unit = println(printer.print(j))

  private def command(sw: Os10Switch, o: Opts, cmd: String, args: List[String]): Either[Os10Error, Unit] =
    val a1 = args.headOption
    val a2 = args.drop(1).headOption
    cmd match
      case "interfaces" =>
        Os10.portRows(sw).map { rows =>
          if o.json then
            printJson(
              Json.arr(rows.map(r =>
                Json.obj(
                  "name" -> Json.fromString(r.name),
                  "mode" -> Json.fromString(r.mode),
                  "speed" -> Json.fromString(r.speed),
                  "mtu" -> r.mtu.toIntOption.fold(Json.fromString(r.mtu))(Json.fromInt),
                  "untagged" -> Json.arr(r.untagged.map(Json.fromInt)*),
                  "tagged" -> Json.arr(r.tagged.map(Json.fromInt)*)
                )
              )*)
            )
          else
            println(
              Os10.table(
                List("PORT", "MODE", "SPEED", "MTU", "UNTAGGED", "TAGGED"),
                rows.map(r =>
                  List(
                    r.name, r.mode, r.speed, r.mtu,
                    Some(Os10.ranges(r.untagged)).filter(_.nonEmpty).getOrElse("-"),
                    Some(Os10.ranges(r.tagged)).filter(_.nonEmpty).getOrElse("-")
                  )
                )
              )
            )
        }
      case "vlans" =>
        Os10.allInterfaces(sw).map { ifaces =>
          val vlans = Os10.vlansOf(ifaces).toList.sortBy(_._1)
          if o.json then
            printJson(
              Json.arr(vlans.map((vid, v) =>
                Json.obj(
                  "vlan" -> Json.fromInt(vid),
                  "untagged_ports" -> Json.fromInt(Os10.strings(v, Os10.Untagged).size),
                  "tagged_ports" -> Json.fromInt(Os10.strings(v, Os10.Tagged).size)
                )
              )*)
            )
          else
            println(
              Os10.table(
                List("VLAN", "UNTAGGED", "TAGGED"),
                vlans.map((vid, v) =>
                  List(
                    vid.toString,
                    Os10.strings(v, Os10.Untagged).size.toString,
                    Os10.strings(v, Os10.Tagged).size.toString
                  )
                )
              )
            )
        }
      case "vlan" =>
        val n = a1.getOrElse("")
        if n.isEmpty || !n.forall(_.isDigit) then
          Left(Os10Error("a VLAN is given by its number, e.g. 228"))
        else
          Os10.getInterface(sw, s"vlan$n").flatMap {
            case None => Left(Os10Error(s"${sw.name}: no VLAN $n"))
            case Some(v) =>
              val un = Os10.strings(v, Os10.Untagged).sorted(using Os10.natural)
              val tg = Os10.strings(v, Os10.Tagged).sorted(using Os10.natural)
              if o.json then
                printJson(
                  Json.obj(
                    "vlan" -> Json.fromInt(n.toInt),
                    "untagged" -> Json.arr(un.map(Json.fromString)*),
                    "tagged" -> Json.arr(tg.map(Json.fromString)*)
                  )
                )
              else
                println(s"VLAN $n")
                println(s"  untagged (${un.size}): ${if un.isEmpty then "-" else un.mkString(" ")}")
                println(s"  tagged   (${tg.size}): ${if tg.isEmpty then "-" else tg.mkString(" ")}")
              Right(())
          }
      case "port" =>
        for
          name <- Os10.portName(a1.getOrElse(""))
          port <- Os10.getInterface(sw, name).flatMap(_.toRight(Os10Error(s"${sw.name}: no port $name")))
          ifaces <- Os10.allInterfaces(sw)
        yield
          val (un, tg) = Os10.membership(Os10.vlansOf(ifaces)).getOrElse(name, (Nil, Nil))
          if o.json then
            printJson(
              Json.obj(
                "interface" -> port,
                "untagged" -> Json.arr(un.map(Json.fromInt)*),
                "tagged" -> Json.arr(tg.map(Json.fromInt)*)
              )
            )
          else
            printJson(port)
            println(s"untagged VLANs: ${Some(Os10.ranges(un)).filter(_.nonEmpty).getOrElse("-")}")
            println(s"tagged VLANs:   ${Some(Os10.ranges(tg)).filter(_.nonEmpty).getOrElse("-")}")
      case "get" =>
        sw.data(a1.getOrElse("")).map(printJson)
      case "operations" =>
        Os10.operations(sw).map(_.foreach(n => println(n)))
      case "schema" =>
        Os10.schema(sw, a1.getOrElse("")).map(t => println(t))
      case "backup" =>
        val path = a1.getOrElse(
          s"${sw.name.replace(":", "_")}-${java.time.LocalDateTime.now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json"
        )
        sw.json("/data").flatMap { doc =>
          try
            Files.writeString(Paths.get(path), printer.print(doc))
            println(s"${sw.name}: saved ${Files.size(Paths.get(path))} bytes to $path")
            Right(())
          catch case e: Exception => Left(Os10Error(s"cannot write $path: ${e.getMessage}"))
        }
      case "set-description" =>
        a2 match
          case None => Left(Os10Error("set-description needs a value after the port"))
          case Some(t) => applyPlan(sw, o, cmd, Os10.planDescription(sw, a1.getOrElse(""), t))
      case "set-mtu" =>
        a2 match
          case None => Left(Os10Error("set-mtu needs a value after the port"))
          case Some(v) => applyPlan(sw, o, cmd, Os10.planMtu(sw, a1.getOrElse(""), v))
      case "set-speed" =>
        a2 match
          case None => Left(Os10Error("set-speed needs a value after the port"))
          case Some(v) => applyPlan(sw, o, cmd, Os10.planSpeed(sw, a1.getOrElse(""), v))
      case "set-mode" =>
        a2 match
          case None => Left(Os10Error("set-mode needs a value after the port"))
          case Some(v) => applyPlan(sw, o, cmd, Os10.planMode(sw, a1.getOrElse(""), v))
      case "vlan-create" =>
        applyPlan(sw, o, cmd, Os10.planVlanCreate(sw, a1.getOrElse("")))
      case "vlan-delete" =>
        applyPlan(sw, o, cmd, Os10.planVlanDelete(sw, a1.getOrElse("")))
      case "vlan-add" =>
        applyPlan(sw, o, cmd, Os10.planVlanAdd(sw, a1.getOrElse(""), a2, o.untagged))
      case "vlan-remove" =>
        applyPlan(sw, o, cmd, Os10.planVlanRemove(sw, a1.getOrElse(""), a2))
      case "save" =>
        applyPlan(sw, o, cmd, Right(Os10.planSave(sw)))
      case other =>
        Left(Os10Error(s"unknown command $other"))

  /** Returns the exit code: 0 done, 1 the switch failed or refused, 2 wrong usage. */
  def run(args: List[String], env: Map[String, String] = sys.env): Int =
    args match
      case Nil | ("help" | "-h" | "--help") :: _ =>
        println(usage)
        if args.isEmpty then 2 else 0
      case cmd :: _ if !needs.contains(cmd) =>
        Console.err.println(s"orphera os10: unknown command '$cmd' (orphera os10 --help)")
        2
      case cmd :: rest =>
        parse(rest, env) match
          case Left(msg) =>
            Console.err.println(s"orphera os10: $msg")
            2
          case Right(o) =>
            o.positional match
              case Nil =>
                Console.err.println(s"orphera os10: $cmd needs a switch (orphera os10 --help)")
                2
              case target :: cmdArgs =>
                needs(cmd).filter(_ => cmdArgs.isEmpty && cmd != "backup") match
                  case Some(what) =>
                    Console.err.println(s"orphera os10: $cmd needs a $what")
                    2
                  case None =>
                    val result =
                      password(o, target, env).flatMap { pw =>
                        val sw = Os10Switch(target, o.user, pw, o.insecure, o.cacert)
                        command(sw, o, cmd, cmdArgs)
                      }
                    result match
                      case Right(_) => 0
                      case Left(e)  =>
                        Console.err.println(s"FAIL  ${e.message}")
                        1
