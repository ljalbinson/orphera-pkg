// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.{OWNER_READ, OWNER_WRITE}

import io.grpc.{Status, StatusRuntimeException}

import orphera.common.Auth

/** Checks for the secret store. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.SecretsCheck"`.
  *
  * The store's own logic is checked with a trivial cipher. The age part runs
  * only when the `age` and `age-keygen` programs are installed.
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
    def encrypt(plain: String): Either[String, Array[Byte]] = Right(
      plain.getBytes(UTF_8).reverse
    )
    def decrypt(data: Array[Byte]): Either[String, String] = Right(
      String(data.reverse, UTF_8)
    )

  private def haveAge: Boolean =
    scala.util
      .Try {
        val p = new ProcessBuilder("age", "--version")
          .redirectErrorStream(true)
          .start()
        p.getInputStream.readAllBytes()
        p.waitFor() == 0
      }
      .getOrElse(false)

  def main(args: Array[String]): Unit =
    val dir = Files.createTempDirectory("secretcheck")
    val file = dir.resolve("secrets.age")
    var n = 0
    val store = new SecretStore(
      file,
      ReverseCipher,
      () => { n += 1; s"generated-value-$n" }
    )

    check("a store with no file has no names", store.names == Right(Nil))
    check(
      "a name that is not there reads as absent",
      store.get("a/b") == Right(None)
    )
    check(
      "getOrCreate makes a value the first time",
      store.getOrCreate("placement/db") == Right("generated-value-1")
    )
    check(
      "getOrCreate returns the same value afterwards, without generating",
      store.getOrCreate("placement/db") == Right("generated-value-1") && n == 1
    )
    check(
      "set stores a value that get reads back",
      store.set("keystone/admin", "chosen pw 1!") == Right(()) && store.get(
        "keystone/admin"
      ) == Right(Some("chosen pw 1!"))
    )
    check(
      "set replaces a value",
      store.set("keystone/admin", "second") == Right(()) && store.get(
        "keystone/admin"
      ) == Right(Some("second"))
    )
    check(
      "names are listed sorted",
      store.names == Right(List("keystone/admin", "placement/db")),
      store.names.toString
    )
    check(
      "remove reports whether it removed",
      store.remove("keystone/admin") == Right(true) && store.remove(
        "keystone/admin"
      ) == Right(false)
    )
    check("an empty value is refused", store.set("x/y", "").isLeft)
    check(
      "names with spaces, quotes or a leading dot are refused",
      List("a b", "a\"b", ".hidden", "", "a;b").forall(nm =>
        store.getOrCreate(nm).isLeft
      )
    )
    check(
      "a stored value is not readable in the file",
      !String(Files.readAllBytes(file), UTF_8).contains("generated-value-1")
    )
    check(
      "the file is mode 0600 and no temporary file is left",
      Files.getPosixFilePermissions(file) == java.util.Set
        .of(OWNER_READ, OWNER_WRITE) &&
        Files
          .list(dir)
          .filter(_.getFileName.toString.endsWith(".tmp"))
          .count() == 0
    )
    check(
      "a second store on the same file sees the same values",
      new SecretStore(file, ReverseCipher).get("placement/db") == Right(
        Some("generated-value-1")
      )
    )

    val random = SecretStore.randomPassword()
    check(
      "generated passwords are 24 letters and digits, and differ",
      random
        .matches("[A-Za-z0-9]{24}") && random != SecretStore.randomPassword(),
      random
    )

    // Secrets.get: from the store, and hidden in the output.
    val s2 = new SecretStore(
      dir.resolve("s2.age"),
      ReverseCipher,
      () => "abcdefghijkl"
    )
    Secrets.clear()
    Secrets.useStore(s2)
    val v = Secrets.get("svc/pw")
    check(
      "Secrets.get returns the stored value",
      v == "abcdefghijkl" && Secrets.get("svc/pw") == v
    )
    check(
      "Secrets.get registers the value for masking",
      Secrets.mask(s"password is $v here") == "password is <redacted> here"
    )
    check(
      "Secrets.get names the secret in an error",
      scala.util
        .Try(Secrets.get("bad name"))
        .failed
        .toOption
        .exists(_.getMessage.contains("bad name"))
    )
    Secrets.clear()

    // Secret: does not print; the text only through reveal.
    val sec = Secrets.secret("svc/pw")
    check(
      "a Secret prints as <redacted>",
      sec.toString == "<redacted>" && s"password $sec x" == "password <redacted> x"
    )
    check(
      "a Secret gives its text through reveal",
      sec.reveal == "abcdefghijkl"
    )
    check(
      "a Secret is not in its own hash or in a collection's text",
      sec.hashCode == 0 && List(sec).toString == "List(<redacted>)" && Map(
        "k" -> sec
      ).toString == "Map(k -> <redacted>)"
    )
    check(
      "two Secrets with the same text are equal, others are not",
      sec == Secret("abcdefghijkl") && sec != Secret("another-value")
    )
    check(
      "Secrets.secret registers the value for masking", {
        Secrets.clear(); Secrets.secret("svc/pw");
        Secrets.mask(s"x ${sec.reveal} y") == "x <redacted> y"
      }
    )
    check(
      "Secret and Some(Secret) print redacted too",
      Some(sec).toString == "Some(<redacted>)" && (
        sec,
        1
      ).toString == "(<redacted>,1)"
    )
    Secrets.clear()

    // The agent token: environment first, then the store, nothing else.
    check(
      "AgentToken uses the environment first",
      AgentToken.resolve(Some("from-env"), Some("from-store")) == Some(
        "from-env"
      )
    )
    check(
      "AgentToken falls back to the store",
      AgentToken.resolve(None, Some("from-store")) == Some(
        "from-store"
      ) && AgentToken.resolve(Some(""), Some("from-store")) == Some(
        "from-store"
      )
    )
    check(
      "AgentToken has nothing when neither is set",
      AgentToken.resolve(None, None).isEmpty && AgentToken
        .resolve(Some(""), Some(""))
        .isEmpty
    )
    check(
      "AgentToken does not read the store when the environment has a value", {
        var read = false;
        AgentToken.resolve(Some("x"), { read = true; Some("y") }); !read
      }
    )

    // An agent will not run without a token of its own, nor with the built-in one.
    check(
      "an agent refuses to start with no token, or an empty one",
      Auth.requireToken(None).isLeft && Auth.requireToken(Some("")).isLeft
    )
    check(
      "an agent refuses the built-in token and says so",
      Auth.requireToken(Some(Auth.DevToken)).swap.exists(_.contains("built-in"))
    )
    check(
      "an agent starts with a token of its own",
      Auth.requireToken(Some("a-real-token")) == Right("a-real-token")
    )
    check(
      "the agent accepts the token it holds",
      Auth.accepts("a-real-token", Some("a-real-token"))
    )
    check(
      "the agent rejects a wrong, missing, shorter or longer token",
      !Auth.accepts("a-real-token", Some("another-token")) && !Auth
        .accepts("a-real-token", None) &&
        !Auth.accepts("a-real-token", Some("a-real")) && !Auth.accepts(
          "a-real-token",
          Some("a-real-token-2")
        )
    )

    // The CLI tells a rejected token apart from a node that did not answer.
    val rejected = new StatusRuntimeException(
      Status.UNAUTHENTICATED.withDescription("missing or invalid token")
    )
    check(
      "a rejected token is reported as unauthenticated",
      NodeError.line(rejected).startsWith("unauthenticated")
    )
    check(
      "a rejected token is found under another exception",
      NodeError
        .line(new RuntimeException("call failed", rejected))
        .startsWith("unauthenticated")
    )
    check(
      "an unavailable node is reported as unreachable with its status", {
        val l = NodeError.line(
          new StatusRuntimeException(
            Status.UNAVAILABLE.withDescription("io exception")
          )
        );
        l.startsWith("unreachable") && l.contains("UNAVAILABLE")
      }
    )
    check(
      "any other failure is reported as unreachable",
      NodeError
        .line(new java.io.IOException("no route to host"))
        .startsWith("unreachable")
    )

    // The file is decrypted once while it is unchanged, and read again when another store changes it.
    var decrypts = 0
    object CountingCipher extends Cipher:
      def encrypt(plain: String): Either[String, Array[Byte]] =
        ReverseCipher.encrypt(plain)
      def decrypt(data: Array[Byte]): Either[String, String] = {
        decrypts += 1; ReverseCipher.decrypt(data)
      }
    val counted = dir.resolve("counted.age")
    new SecretStore(counted, ReverseCipher).set("a/b", "first-value")
    val cs = new SecretStore(counted, CountingCipher)
    val reads = List(cs.get("a/b"), cs.get("a/b"), cs.names)
    check(
      "an unchanged file is decrypted once for several reads",
      decrypts == 1 && reads.forall(_.isRight),
      s"$decrypts decrypts"
    )
    Thread.sleep(20)
    new SecretStore(counted, ReverseCipher).set("a/b", "second-value")
    check(
      "a file changed by another store is read again",
      cs.get("a/b") == Right(Some("second-value")) && decrypts == 2,
      s"$decrypts decrypts"
    )
    val c2 = cs.set("c/d", "third-value")
    check(
      "a write after reading keeps the other store's change",
      c2.isRight && new SecretStore(counted, ReverseCipher).get("a/b") == Right(
        Some("second-value")
      )
    )

    // The agent: a socket another machine (the one with the YubiKey) serves, as ssh-agent forwarding does.
    def serving(sock: java.nio.file.Path, c: Cipher): Unit =
      val t = new Thread(() => { val _ = AgeAgent.serve(sock, c, _ => ()) })
      t.setDaemon(true)
      t.start()
      var i = 0
      while !Files.exists(sock) && i < 100 do { Thread.sleep(20); i += 1 }
    val sock = dir.resolve("agent.sock")
    serving(sock, ReverseCipher)
    val sealedText = ReverseCipher
      .encrypt("{\"x\":\"through the agent\"}")
      .getOrElse(Array.emptyByteArray)
    check(
      "agent: decrypts what is sent through the socket",
      AgeAgent.decrypt(sock, sealedText) == Right(
        "{\"x\":\"through the agent\"}"
      )
    )
    object NoCipher extends Cipher:
      def encrypt(plain: String): Either[String, Array[Byte]] = Left("no")
      def decrypt(data: Array[Byte]): Either[String, String] = Left(
        "no PIN given"
      )
    val refusing = dir.resolve("refusing.sock")
    serving(refusing, NoCipher)
    check(
      "agent: its failure comes back as refused, with its reason",
      AgeAgent.decrypt(refusing, sealedText) == Left(
        AgeAgent.Failure.Refused("no PIN given")
      )
    )
    val stale = dir.resolve("stale.sock")
    val dead = java.nio.channels.ServerSocketChannel
      .open(java.net.StandardProtocolFamily.UNIX)
    dead.bind(java.net.UnixDomainSocketAddress.of(stale))
    dead.close()
    check(
      "agent: a socket file nothing listens on is unreachable",
      AgeAgent.decrypt(stale, sealedText) match
        case Left(AgeAgent.Failure.Unreachable(_)) => true
        case _                                     => false
    )
    // What a forwarded socket does with no agent at the far end: ssh accepts
    // the connection, then closes it.
    val farEnd = dir.resolve("far-end.sock")
    val closing = java.nio.channels.ServerSocketChannel
      .open(java.net.StandardProtocolFamily.UNIX)
    closing.bind(java.net.UnixDomainSocketAddress.of(farEnd))
    val closer = new Thread(() => while true do closing.accept().close())
    closer.setDaemon(true)
    closer.start()
    check(
      "agent: a forwarded socket with no agent behind it is unreachable",
      AgeAgent.decrypt(farEnd, sealedText) match
        case Left(AgeAgent.Failure.Unreachable(m)) => m.contains("far end")
        case _                                     => false
    )
    // What the login check in ~/.profile does (README).
    def ping(socket: java.nio.file.Path): String =
      val ch = java.nio.channels.SocketChannel
        .open(java.net.StandardProtocolFamily.UNIX)
      try
        scala.util
          .Try {
            ch.connect(java.net.UnixDomainSocketAddress.of(socket))
            val out = java.nio.channels.Channels.newOutputStream(ch)
            out.write("PING\n".getBytes(UTF_8))
            out.flush()
            val in = java.nio.channels.Channels.newInputStream(ch)
            String(in.readAllBytes, UTF_8)
          }
          .getOrElse("")
      finally ch.close()
    check("agent: answers PING with PONG", ping(sock) == "PONG\n")
    check(
      "agent: a socket with no agent behind it does not answer PING",
      ping(farEnd) == ""
    )
    object SlowCipher extends Cipher:
      def encrypt(plain: String): Either[String, Array[Byte]] = Left("no")
      def decrypt(data: Array[Byte]): Either[String, String] = {
        Thread.sleep(5000); Right("late")
      }
    val slow = dir.resolve("slow.sock")
    serving(slow, SlowCipher)
    val started = System.nanoTime
    val late = AgeAgent.decrypt(slow, sealedText, timeout = 1)
    check(
      "agent: no answer within the time limit is refused, after about that long",
      late.left.exists {
        case AgeAgent.Failure.Refused(m) => m.contains("within 1s")
        case _                           => false
      } && (System.nanoTime - started) < 4000000000L,
      late.toString
    )
    // A cache: the same encrypted text is decrypted once; a different one is decrypted afresh.
    var agentDecrypts = 0
    object CountedCipher extends Cipher:
      def encrypt(plain: String): Either[String, Array[Byte]] =
        ReverseCipher.encrypt(plain)
      def decrypt(data: Array[Byte]): Either[String, String] = {
        agentDecrypts += 1; ReverseCipher.decrypt(data)
      }
    val cached = dir.resolve("cached.sock")
    val ct = new Thread(() => {
      val _ = AgeAgent.serve(cached, CountedCipher, _ => (), cacheMinutes = 10)
    })
    ct.setDaemon(true)
    ct.start()
    var w = 0
    while !Files.exists(cached) && w < 100 do { Thread.sleep(20); w += 1 }
    val firstTwo = List(
      AgeAgent.decrypt(cached, sealedText),
      AgeAgent.decrypt(cached, sealedText)
    )
    check(
      "agent cache: the same store twice asks the key once",
      firstTwo.forall(
        _ == Right("{\"x\":\"through the agent\"}")
      ) && agentDecrypts == 1,
      s"$agentDecrypts"
    )
    val other = ReverseCipher
      .encrypt("{\"x\":\"changed\"}")
      .getOrElse(Array.emptyByteArray)
    check(
      "agent cache: a changed store is decrypted afresh",
      AgeAgent.decrypt(cached, other) == Right(
        "{\"x\":\"changed\"}"
      ) && agentDecrypts == 2,
      s"$agentDecrypts"
    )
    check(
      "--cache: minutes, or a number with m, h or d",
      AgeAgent.parseCacheMinutes("90") == Some(90) &&
        AgeAgent.parseCacheMinutes("90m") == Some(90) &&
        AgeAgent.parseCacheMinutes("48h") == Some(2880) &&
        AgeAgent.parseCacheMinutes("2d") == Some(2880) &&
        AgeAgent.parseCacheMinutes("2D") == Some(2880) &&
        AgeAgent.parseCacheMinutes("0") == Some(0) &&
        AgeAgent.parseCacheMinutes("0d") == Some(0),
      s"${AgeAgent.parseCacheMinutes("48h")}"
    )
    check(
      "--cache: a bad value is refused",
      List("", "d", "-1", "1.5", "5x", "1 d", "h2", "+5", "2dd", "99999999d", "1000000000")
        .forall(v => AgeAgent.parseCacheMinutes(v).isEmpty),
      List("", "d", "-1", "1.5", "5x", "1 d", "h2", "+5", "2dd", "99999999d", "1000000000")
        .filter(v => AgeAgent.parseCacheMinutes(v).nonEmpty)
        .mkString(",")
    )
    check(
      "--cache: minutes are shown as days, hours and minutes",
      AgeAgent.minutesText(3000) == "2d 2h" &&
        AgeAgent.minutesText(10) == "10m" &&
        AgeAgent.minutesText(1440) == "1d" &&
        AgeAgent.minutesText(1501) == "1d 1h 1m",
      AgeAgent.minutesText(3000)
    )
    val utc = java.time.ZoneId.of("UTC")
    val noon = java.time.Instant.parse("2026-11-20T12:00:00Z")
    check(
      "agent cache: an expiry today shows the time only",
      AgeAgent.expiry(noon.plusSeconds(600), noon, utc) == "12:10:00",
      AgeAgent.expiry(noon.plusSeconds(600), noon, utc)
    )
    check(
      "agent cache: an expiry on another day shows the date",
      AgeAgent.expiry(noon.plusSeconds(25 * 3600L), noon, utc) ==
        "2026-11-21 13:00:00" &&
        AgeAgent.expiry(noon.plusSeconds(3000 * 60L), noon, utc) ==
          "2026-11-22 14:00:00",
      AgeAgent.expiry(noon.plusSeconds(25 * 3600L), noon, utc)
    )
    val big = "y" * 300000
    check(
      "agent: a large store comes back whole",
      AgeAgent.decrypt(
        sock,
        ReverseCipher.encrypt(big).getOrElse(Array.emptyByteArray)
      ) == Right(big)
    )

    // The real thing, when age is installed.
    if haveAge then
      val id = dir.resolve("age.key")
      val created = AgeCipher.createIdentity(id)
      check(
        "an age identity can be made",
        created.exists(_.startsWith("age1")) && Files.exists(id),
        created.toString
      )
      val cipher = new AgeCipher(id)
      val enc = cipher.encrypt("""{"a":"hunter2-hunter2"}""")
      check(
        "age encrypts, and the result does not contain the text",
        enc.exists(b => !String(b, UTF_8).contains("hunter2"))
      )
      check(
        "age decrypts what it encrypted",
        enc.flatMap(cipher.decrypt) == Right("""{"a":"hunter2-hunter2"}""")
      )
      val other = dir.resolve("other.key")
      AgeCipher.createIdentity(other)
      check(
        "another identity cannot decrypt it",
        enc.flatMap(new AgeCipher(other).decrypt).isLeft
      )
      val real = new SecretStore(dir.resolve("real.age"), cipher)
      val pw = real.getOrCreate("db/pw")
      check(
        "a store with age makes, keeps and reads a secret",
        pw.exists(_.length == 24) && real.get("db/pw") == pw.map(Some(_)),
        pw.toString
      )
      check(
        "the age file is armoured text without the secret in it",
        pw.exists: p =>
          val t = Files.readString(dir.resolve("real.age"))
          t.startsWith("-----BEGIN AGE ENCRYPTED FILE-----") && !t.contains(p)
      )
      check(
        "a store cannot read a file made for another identity",
        new SecretStore(dir.resolve("real.age"), new AgeCipher(other))
          .get("db/pw")
          .isLeft
      )
      // A recipients file: encrypted to every recipient listed, so either identity reads it; rekey moves a store onto it.
      val id2 = dir.resolve("second.key")
      val pub1 = AgeCipher.createIdentity(dir.resolve("first.key"))
      val pub2 = AgeCipher.createIdentity(id2)
      val rfile = dir.resolve("recipients")
      val before = new SecretStore(
        dir.resolve("rk.age"),
        new AgeCipher(dir.resolve("first.key"))
      )
      before.set("k/v", "rekeyed-value")
      check(
        "before rekey, the second identity cannot read the store",
        new SecretStore(dir.resolve("rk.age"), new AgeCipher(id2))
          .get("k/v")
          .isLeft
      )
      Files.writeString(
        rfile,
        s"# both keys\n${pub1.getOrElse("")}\n${pub2.getOrElse("")}\n"
      )
      val withList = new AgeCipher(dir.resolve("first.key"), Some(rfile))
      check(
        "the recipients are the file's lines, comments left out",
        withList.recipients == Right(
          List(pub1.getOrElse(""), pub2.getOrElse(""))
        ),
        withList.recipients.toString
      )
      val rekeyed = new SecretStore(dir.resolve("rk.age"), withList).rekey()
      check(
        "rekey re-encrypts every secret and keeps the old file",
        rekeyed == Right(1) && Files.exists(dir.resolve("rk.age.before-rekey")),
        rekeyed.toString
      )
      check(
        "after rekey, each identity reads the store",
        new SecretStore(dir.resolve("rk.age"), new AgeCipher(id2))
          .get("k/v") == Right(Some("rekeyed-value")) &&
          new SecretStore(
            dir.resolve("rk.age"),
            new AgeCipher(dir.resolve("first.key"))
          ).get("k/v") == Right(Some("rekeyed-value"))
      )
      check(
        "without a recipients file, the identity's own key is the recipient",
        new AgeCipher(id2, Some(dir.resolve("none"))).recipients == pub2.map(
          List(_)
        )
      )
      // Decrypting through an agent that holds the identity, with none here (as on the orphera host
      // when the YubiKey is on the Mac); and falling back to a local identity when the agent is gone.
      val agentSock = dir.resolve("age-agent.sock")
      serving(agentSock, new AgeCipher(dir.resolve("first.key")))
      val remoteOnly = new AgeCipher(
        dir.resolve("no-identity-here.key"),
        Some(rfile),
        Some(agentSock)
      )
      check(
        "agent: a store is read through the agent with no identity here",
        new SecretStore(dir.resolve("rk.age"), remoteOnly).get("k/v") == Right(
          Some("rekeyed-value")
        )
      )
      val notices = scala.collection.mutable.ListBuffer.empty[String]
      val viaStale =
        new AgeCipher(id2, Some(rfile), Some(stale), notice = notices += _)
      check(
        "agent: an unreachable agent falls back to the identity here",
        new SecretStore(dir.resolve("rk.age"), viaStale).get("k/v") == Right(
          Some("rekeyed-value")
        )
      )
      val said = List("nothing answers on", stale.toString, id2.toString)
      check(
        "agent: the fallback says so, naming the socket and the identity",
        notices.size == 1 && said.forall(t => notices.exists(_.contains(t)))
      )
      check(
        "agent: unreachable with no identity here says both",
        new SecretStore(
          dir.resolve("rk.age"),
          new AgeCipher(dir.resolve("none.key"), Some(rfile), Some(stale))
        ).get("k/v").left.exists(_.contains("not answering"))
      )
    else println("age is not installed: the age checks were skipped")

    check(
      "an identity that is not there is reported",
      new AgeCipher(dir.resolve("none.key")).encrypt("x").isLeft
    )

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"SecretsCheck: all $passes checks passed")
