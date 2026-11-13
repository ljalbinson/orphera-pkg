// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.file.Files

/** `orphera secrets ...`: look after the store from the command line. The
  * values are shown only by `get`.
  */
object SecretsTool:
  val usage: String =
    """orphera secrets init              make the age identity and an empty store (once)
      |orphera secrets list              the names of the secrets
      |orphera secrets generate <name>   make a random value if the name has none (never shown)
      |orphera secrets set <name>        store a value read from standard input (one line)
      |orphera secrets get <name>        print a value
      |orphera secrets rm <name>         remove a secret
      |orphera secrets recipients        who the store is encrypted to
      |orphera secrets rekey             re-encrypt the store to the recipients file (after editing it)
      |
      |Files: $ORPHERA_SECRETS        (default ~/.config/orphera/secrets.age, encrypted)
      |       $ORPHERA_AGE_KEY        (default ~/.config/orphera/age.key, the identity: back it up;
      |                                for a YubiKey, its age-plugin-yubikey identity stub)
      |       $ORPHERA_AGE_RECIPIENTS (default ~/.config/orphera/recipients, optional: one age
      |                                recipient per line, such as each YubiKey's age1yubikey1...;
      |                                without it the store is encrypted to the identity's key)""".stripMargin

  /** Returns the exit code. */
  def run(args: List[String]): Int =
    args match
      case "init" :: Nil => init()
      case "list" :: Nil => withStore(_.names.map(_.foreach(println)))
      case "generate" :: name :: Nil =>
        withStore { s =>
          s.get(name).flatMap {
            case Some(_) => println(s"$name exists"); Right(())
            case None => s.getOrCreate(name).map(_ => println(s"$name created"))
          }
        }
      case "set" :: name :: Nil =>
        val value =
          Option(scala.io.StdIn.readLine()).map(_.stripLineEnd).getOrElse("")
        withStore(_.set(name, value).map(_ => println(s"$name stored")))
      case "get" :: name :: Nil =>
        withStore(_.get(name).flatMap {
          case Some(v) => println(v); Right(())
          case None    => Left(s"no secret called $name")
        })
      case "rm" :: name :: Nil =>
        withStore(
          _.remove(name).map(r =>
            println(if r then s"$name removed" else s"$name was not there")
          )
        )
      case "recipients" :: Nil =>
        val file = SecretStore.recipientsFile
        SecretStore.defaultCipher.recipients match
          case Right(rs) =>
            println(
              if java.nio.file.Files.exists(file) then s"from $file:"
              else s"no $file: the identity's own key (${SecretStore.identityFile}):"
            )
            rs.foreach(r => println(s"  $r"))
            0
          case Left(msg) => System.err.println(s"Error: $msg"); 1
      case "rekey" :: Nil =>
        if !java.nio.file.Files.exists(SecretStore.recipientsFile) then
          System.err.println(s"Error: no ${SecretStore.recipientsFile}: list the recipients there first (one per line)")
          1
        else
          withStore(s =>
            for
              n <- s.rekey()
              rs <- SecretStore.defaultCipher.recipients
            yield
              println(s"$n secret(s) re-encrypted to ${rs.size} recipient(s); the old file is ${SecretStore.secretsFile}.before-rekey")
              println("Check that each recipient's identity reads it (ORPHERA_AGE_KEY=<its identity> orphera secrets list) before removing anything.")
          )
      case _ =>
        println(usage)
        1

  private def withStore(f: SecretStore => Either[String, Unit]): Int =
    SecretStore.default().flatMap(f) match
      case Right(_)  => 0
      case Left(msg) => System.err.println(s"Error: $msg"); 1

  private def init(): Int =
    val id = SecretStore.identityFile
    if Files.exists(id) then
      println(s"identity exists: $id")
      println(s"secrets file:    ${SecretStore.secretsFile}")
      0
    else
      AgeCipher.createIdentity(id) match
        case Right(pub) =>
          println(s"identity made:   $id")
          println(s"public key:      $pub")
          println(
            s"secrets file:    ${SecretStore.secretsFile} (made on first use)"
          )
          println(
            "Back the identity file up somewhere else. Without it the secrets cannot be read."
          )
          0
        case Left(msg) => System.err.println(s"Error: $msg"); 1
