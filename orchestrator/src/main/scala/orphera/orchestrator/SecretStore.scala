// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.Json
import io.circe.yaml.parser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

// The secrets a playbook needs (database and service passwords, keys), kept in
// one file on the machine that runs `orphera`, encrypted with age
// (https://age-encryption.org). A playbook asks for a secret by name,
// `Secrets.get("placement/db")`; the first time a name is asked for, a random value
// is made and stored, and every later run gets the same one. No secret is in a
// manifest or in the repository.
//
// The file is a JSON object of name to value, encrypted to the public key of an age
// identity file. Lose the identity file and the secrets are gone: back it up
// somewhere other than the machine the secrets file is on.

/** Encrypts and decrypts the store's bytes. `AgeCipher` is the real one; tests
  * use a trivial one so the store's own logic can be checked without age.
  */
trait Cipher:
  def encrypt(plain: String): Either[String, Array[Byte]]
  def decrypt(data: Array[Byte]): Either[String, String]

/** age, run as a program (`age`, `age-keygen`): install the `age` package. */
final class AgeCipher(identityFile: Path) extends Cipher:
  import AgeCipher.*

  private def recipient: Either[String, String] =
    run(List("age-keygen", "-y", identityFile.toString), Array.emptyByteArray)
      .map(b => String(b, UTF_8).trim)

  def encrypt(plain: String): Either[String, Array[Byte]] =
    recipient.flatMap(r =>
      run(List("age", "-a", "-r", r), plain.getBytes(UTF_8))
    )

  def decrypt(data: Array[Byte]): Either[String, String] =
    run(List("age", "-d", "-i", identityFile.toString), data)
      .map(String(_, UTF_8))

object AgeCipher:
  /** Makes an identity file (mode 0600) and returns its public key. */
  def createIdentity(identityFile: Path): Either[String, String] =
    scala.util
      .Try(Files.createDirectories(identityFile.getParent))
      .toEither
      .left
      .map(_.getMessage)
      .flatMap { _ =>
        for
          _ <- run(
            List("age-keygen", "-o", identityFile.toString),
            Array.emptyByteArray
          )
          pub <- run(
            List("age-keygen", "-y", identityFile.toString),
            Array.emptyByteArray
          )
        yield String(pub, UTF_8).trim
      }

  private def run(
      command: List[String],
      stdin: Array[Byte]
  ): Either[String, Array[Byte]] =
    val errFile = Files.createTempFile("orphera-age", ".err")
    try
      val pb = new ProcessBuilder(command*)
      pb.redirectError(errFile.toFile)
      val p: Either[String, Process] =
        try Right(pb.start())
        catch
          case e: java.io.IOException =>
            Left(
              s"cannot run ${command.head}: ${e.getMessage}; is the age package installed?"
            )
      p.flatMap { proc =>
        val writer = new Thread(() =>
          try
            proc.getOutputStream.write(stdin)
            proc.getOutputStream.close()
          catch case _: java.io.IOException => ()
        )
        writer.start()
        val out = proc.getInputStream.readAllBytes()
        val code = proc.waitFor()
        writer.join()
        if code == 0 then Right(out)
        else
          Left(
            s"${command.head} failed (exit $code): ${Files.readString(errFile).trim}"
          )
      }
    finally Files.deleteIfExists(errFile)

/** A name-to-value store in one encrypted file. Every change re-encrypts the
  * whole file and replaces it atomically, mode 0600.
  */
final class SecretStore(
    file: Path,
    cipher: Cipher,
    generate: () => String = SecretStore.randomPassword
):
  import SecretStore.*

  private def load(): Either[String, Map[String, String]] =
    if !Files.exists(file) then Right(Map.empty)
    else
      for
        text <- cipher.decrypt(Files.readAllBytes(file))
        json <- parser
          .parse(text)
          .left
          .map(e =>
            s"$file does not decrypt to a secrets table: ${e.getMessage}"
          )
        obj <- json.asObject.toRight(s"$file does not hold a secrets table")
      yield obj.toMap.collect {
        case (k, v) if v.isString => k -> v.asString.get
      }

  private def save(all: Map[String, String]): Either[String, Unit] =
    val json = Json
      .obj(all.toList.sortBy(_._1).map((k, v) => k -> Json.fromString(v))*)
      .spaces2
    cipher.encrypt(json).map { bytes =>
      val dir = file.toAbsolutePath.getParent
      Files.createDirectories(dir)
      val tmp = Files.createTempFile(dir, ".secrets-", ".tmp")
      Files.setPosixFilePermissions(
        tmp,
        PosixFilePermissions.fromString("rw-------")
      )
      Files.write(tmp, bytes)
      Files.move(
        tmp,
        file,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
      ()
    }

  def names: Either[String, List[String]] = load().map(_.keys.toList.sorted)

  def get(name: String): Either[String, Option[String]] =
    validName(name).flatMap(_ => load().map(_.get(name)))

  /** The value, made and stored if the name has none yet. */
  def getOrCreate(name: String): Either[String, String] =
    for
      _ <- validName(name)
      all <- load()
      value <- all.get(name) match
        case Some(v) => Right(v)
        case None    =>
          val v = generate()
          save(all + (name -> v)).map(_ => v)
    yield value

  def set(name: String, value: String): Either[String, Unit] =
    for
      _ <- validName(name)
      _ <- Either.cond(value.nonEmpty, (), "a secret cannot be empty")
      all <- load()
      _ <- save(all + (name -> value))
    yield ()

  def remove(name: String): Either[String, Boolean] =
    for
      _ <- validName(name)
      all <- load()
      removed = all.contains(name)
      _ <- if removed then save(all - name) else Right(())
    yield removed

object SecretStore:
  private val NamePattern = "[A-Za-z0-9][A-Za-z0-9_./-]*".r

  def validName(name: String): Either[String, Unit] =
    Either.cond(
      NamePattern.matches(name),
      (),
      s"`$name` is not a secret name: use letters, digits and _ . / -, like placement/db"
    )

  private val alphabet = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toArray
  private val random = new SecureRandom()

  /** 24 letters and digits: about 143 bits, and safe in a URL or a shell word.
    */
  def randomPassword(): String =
    String.valueOf(Array.fill(24)(alphabet(random.nextInt(alphabet.length))))

  def home: Path = Paths.get(sys.props.getOrElse("user.home", "."))

  def secretsFile: Path =
    sys.env
      .get("ORPHERA_SECRETS")
      .map(Paths.get(_))
      .getOrElse(home.resolve(".config/orphera/secrets.age"))

  def identityFile: Path =
    sys.env
      .get("ORPHERA_AGE_KEY")
      .map(Paths.get(_))
      .getOrElse(home.resolve(".config/orphera/age.key"))

  /** The store a playbook uses: the default files, age. Fails with what to do
    * when there is no identity yet.
    */
  def default(): Either[String, SecretStore] =
    Either.cond(
      Files.exists(identityFile),
      new SecretStore(secretsFile, new AgeCipher(identityFile)),
      s"no age identity at $identityFile: run `orphera secrets init` once (and back the file up)"
    )
