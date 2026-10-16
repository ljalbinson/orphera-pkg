// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.{OWNER_READ, OWNER_WRITE}

/** Checks for the secret store. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.SecretsCheck"`.
  *
  * The store's own logic is checked with a trivial cipher. The age part runs only
  * when the `age` and `age-keygen` programs are installed.
  */
object SecretsCheck:
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

  /** Not secure; reverses the bytes so the file is not the plain text. */
  private object ReverseCipher extends Cipher:
    def encrypt(plain: String): Either[String, Array[Byte]] = Right(plain.getBytes(UTF_8).reverse)
    def decrypt(data: Array[Byte]): Either[String, String] = Right(String(data.reverse, UTF_8))

  private def haveAge: Boolean =
    scala.util.Try {
      val p = new ProcessBuilder("age", "--version").redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor() == 0
    }.getOrElse(false)

  def main(args: Array[String]): Unit =
    val dir = Files.createTempDirectory("secretcheck")
    val file = dir.resolve("secrets.age")
    var n = 0
    val store = new SecretStore(file, ReverseCipher, () => { n += 1; s"generated-value-$n" })

    check("a store with no file has no names", store.names == Right(Nil))
    check("a name that is not there reads as absent", store.get("a/b") == Right(None))
    check("getOrCreate makes a value the first time", store.getOrCreate("placement/db") == Right("generated-value-1"))
    check("getOrCreate returns the same value afterwards, without generating", store.getOrCreate("placement/db") == Right("generated-value-1") && n == 1)
    check("set stores a value that get reads back", store.set("keystone/admin", "chosen pw 1!") == Right(()) && store.get("keystone/admin") == Right(Some("chosen pw 1!")))
    check("set replaces a value", store.set("keystone/admin", "second") == Right(()) && store.get("keystone/admin") == Right(Some("second")))
    check("names are listed sorted", store.names == Right(List("keystone/admin", "placement/db")), store.names.toString)
    check("remove reports whether it removed", store.remove("keystone/admin") == Right(true) && store.remove("keystone/admin") == Right(false))
    check("an empty value is refused", store.set("x/y", "").isLeft)
    check("names with spaces, quotes or a leading dot are refused", List("a b", "a\"b", ".hidden", "", "a;b").forall(nm => store.getOrCreate(nm).isLeft))
    check("a stored value is not readable in the file", !String(Files.readAllBytes(file), UTF_8).contains("generated-value-1"))
    check(
      "the file is mode 0600 and no temporary file is left",
      Files.getPosixFilePermissions(file) == java.util.Set.of(OWNER_READ, OWNER_WRITE) &&
        Files.list(dir).filter(_.getFileName.toString.endsWith(".tmp")).count() == 0
    )
    check("a second store on the same file sees the same values", new SecretStore(file, ReverseCipher).get("placement/db") == Right(Some("generated-value-1")))

    val random = SecretStore.randomPassword()
    check("generated passwords are 24 letters and digits, and differ", random.matches("[A-Za-z0-9]{24}") && random != SecretStore.randomPassword(), random)

    // Secrets.get: from the store, and hidden in the output.
    val s2 = new SecretStore(dir.resolve("s2.age"), ReverseCipher, () => "abcdefghijkl")
    Secrets.clear()
    Secrets.useStore(s2)
    val v = Secrets.get("svc/pw")
    check("Secrets.get returns the stored value", v == "abcdefghijkl" && Secrets.get("svc/pw") == v)
    check("Secrets.get registers the value for masking", Secrets.mask(s"password is $v here") == "password is <redacted> here")
    check("Secrets.get names the secret in an error", scala.util.Try(Secrets.get("bad name")).failed.toOption.exists(_.getMessage.contains("bad name")))
    Secrets.clear()

    // Secret: does not print; the text only through reveal.
    val sec = Secrets.secret("svc/pw")
    check("a Secret prints as <redacted>", sec.toString == "<redacted>" && s"password $sec x" == "password <redacted> x")
    check("a Secret gives its text through reveal", sec.reveal == "abcdefghijkl")
    check("a Secret is not in its own hash or in a collection's text", sec.hashCode == 0 && List(sec).toString == "List(<redacted>)" && Map("k" -> sec).toString == "Map(k -> <redacted>)")
    check("two Secrets with the same text are equal, others are not", sec == Secret("abcdefghijkl") && sec != Secret("another-value"))
    check("Secrets.secret registers the value for masking", { Secrets.clear(); Secrets.secret("svc/pw"); Secrets.mask(s"x ${sec.reveal} y") == "x <redacted> y" })
    check("Secret and Some(Secret) print redacted too", Some(sec).toString == "Some(<redacted>)" && (sec, 1).toString == "(<redacted>,1)")
    Secrets.clear()

    // The agent token: environment first, then the store, nothing else.
    check("AgentToken uses the environment first", AgentToken.resolve(Some("from-env"), Some("from-store")) == Some("from-env"))
    check("AgentToken falls back to the store", AgentToken.resolve(None, Some("from-store")) == Some("from-store") && AgentToken.resolve(Some(""), Some("from-store")) == Some("from-store"))
    check("AgentToken has nothing when neither is set", AgentToken.resolve(None, None).isEmpty && AgentToken.resolve(Some(""), Some("")).isEmpty)
    check("AgentToken does not read the store when the environment has a value", { var read = false; AgentToken.resolve(Some("x"), { read = true; Some("y") }); !read })

    // The real thing, when age is installed.
    if haveAge then
      val id = dir.resolve("age.key")
      val created = AgeCipher.createIdentity(id)
      check("an age identity can be made", created.exists(_.startsWith("age1")) && Files.exists(id), created.toString)
      val cipher = new AgeCipher(id)
      val enc = cipher.encrypt("""{"a":"hunter2-hunter2"}""")
      check("age encrypts, and the result does not contain the text", enc.exists(b => !String(b, UTF_8).contains("hunter2")))
      check("age decrypts what it encrypted", enc.flatMap(cipher.decrypt) == Right("""{"a":"hunter2-hunter2"}"""))
      val other = dir.resolve("other.key")
      AgeCipher.createIdentity(other)
      check("another identity cannot decrypt it", enc.flatMap(new AgeCipher(other).decrypt).isLeft)
      val real = new SecretStore(dir.resolve("real.age"), cipher)
      val pw = real.getOrCreate("db/pw")
      check("a store with age makes, keeps and reads a secret", pw.exists(_.length == 24) && real.get("db/pw") == pw.map(Some(_)), pw.toString)
      check("the age file is armoured text without the secret in it", pw.exists(p => { val t = Files.readString(dir.resolve("real.age")); t.startsWith("-----BEGIN AGE ENCRYPTED FILE-----") && !t.contains(p) }))
      check("a store cannot read a file made for another identity", new SecretStore(dir.resolve("real.age"), new AgeCipher(other)).get("db/pw").isLeft)
    else println("age is not installed: the age checks were skipped")

    check("an identity that is not there is reported", new AgeCipher(dir.resolve("none.key")).encrypt("x").isLeft)

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"SecretsCheck: all $passes checks passed")
