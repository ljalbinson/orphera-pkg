// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import io.grpc.Status

/** Checks for TaskExecutor, the one executor that playbooks, cluster playbooks
  * and the direct commands share: templating on and off, a failure stopping
  * only that node's remaining tasks, `when:` without facts, and how a failure
  * is described. Needs no agent: the tasks used (set_fact, debug, and a
  * distribute_file from a node that is not in the inventory) never contact
  * one. Run with
  * `sbt "orchestrator/Test/runMain orphera.orchestrator.TaskExecutorCheck"`.
  */
object TaskExecutorCheck:
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

  /** An Output that records each line as "node|task|text" and each failure as
    * "node|task|FAILED|message".
    */
  private def recording(lines: Ref[IO, Vector[String]]): TaskExecutor.Output =
    TaskExecutor.Output(
      (node, task, event) =>
        lines.update(_ :+ s"${node.name}|$task|event|${event.message}"),
      (node, task, text) => lines.update(_ :+ s"${node.name}|$task|$text"),
      (node, task, err) =>
        lines.update(_ :+ s"${node.name}|$task|FAILED|${err.getMessage}")
    )

  /** Runs `tasks` on each of `nodes` and returns each node's result and the
    * recorded lines.
    */
  private def run(
      tasks: List[NamedTask],
      nodes: List[Node],
      templating: Boolean
  ): (List[Boolean], Vector[String]) =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      setFacts <- SetFacts.empty
      env = TaskExecutor.Env(
        ClusterContext(Map.empty),
        setFacts,
        recording(lines),
        templating
      )
      checkpoint <- Checkpoint.disabled
      runLog <- RunLog.disabled
      results <- nodes.parTraverse(
        TaskExecutor.runTasks(tasks, _, env, checkpoint, runLog)
      )
      recorded <- lines.get
    yield (results, recorded)).unsafeRunSync()

  private val a = Node("check-a", "check-a.invalid")
  private val b = Node("check-b", "check-b.invalid")

  def main(args: Array[String]): Unit =
    val greet = List(
      NamedTask("set", Task.SetFact("greeting", "hello")),
      NamedTask("say", Task.Debug("{{greeting}} world"))
    )

    val (onOk, onLines) = run(greet, List(a), templating = true)
    check("templating on: the tasks succeed", onOk == List(true), onOk.toString)
    check(
      "templating on: set_fact is shown",
      onLines.contains("check-a|set|set greeting = hello"),
      onLines.toString
    )
    check(
      "templating on: {{...}} is rendered with the node's set facts",
      onLines.contains("check-a|say|hello world"),
      onLines.toString
    )

    val (_, offLines) = run(greet, List(a), templating = false)
    check(
      "templating off (a direct command): {{...}} is sent as typed",
      offLines.contains("check-a|say|{{greeting}} world"),
      offLines.toString
    )

    val failing = List(
      NamedTask("first", Task.Debug("one")),
      NamedTask(
        "fetch",
        Task.DistributeFile("no-such-node", "/etc/hostname", "/tmp/x")
      ),
      NamedTask("after", Task.Debug("never"))
    )
    val (failOk, failLines) = run(failing, List(a, b), templating = true)
    check(
      "a failed task fails each node it ran on",
      failOk == List(false, false),
      failOk.toString
    )
    check(
      "a failure is reported once per node, with its message",
      failLines.count(l => l.contains("|fetch|FAILED|") && l.contains("no-such-node")) == 2,
      failLines.toString
    )
    check(
      "the tasks before a failure ran on every node",
      failLines.count(_.endsWith("|first|one")) == 2,
      failLines.toString
    )
    check(
      "a failure stops that node's remaining tasks",
      !failLines.exists(_.contains("|after|")),
      failLines.toString
    )

    val conditional = List(
      NamedTask(
        "only-ubuntu",
        Task.Debug("ran"),
        Some(FactCondition("os_id", "ubuntu"))
      ),
      NamedTask("always", Task.Debug("ran"))
    )
    val (whenOk, whenLines) = run(conditional, List(a), templating = true)
    check(
      "a when: with no facts for the node skips the task, and the run goes on",
      whenOk == List(true) &&
        whenLines.contains("check-a|only-ubuntu|skipped (condition not met)") &&
        whenLines.contains("check-a|always|ran"),
      whenLines.toString
    )

    val unavailable = Status.UNAVAILABLE.withDescription("io exception").asRuntimeException()
    check(
      "a failed agent call is described as unreachable",
      TaskExecutor.failureLine(unavailable).startsWith("unreachable: UNAVAILABLE"),
      TaskExecutor.failureLine(unavailable)
    )
    check(
      "an agent call failure wrapped in another exception is still recognised",
      TaskExecutor
        .failureLine(new RuntimeException("wrapped", unavailable))
        .startsWith("unreachable:"),
      TaskExecutor.failureLine(new RuntimeException("wrapped", unavailable))
    )
    check(
      "a rejected token is described as unauthenticated",
      TaskExecutor
        .failureLine(Status.UNAUTHENTICATED.asRuntimeException())
        .startsWith("unauthenticated:")
    )
    check(
      "any other failure is FAILED with its message",
      TaskExecutor.failureLine(new RuntimeException("No such file: x")) ==
        "FAILED: No such file: x",
      TaskExecutor.failureLine(new RuntimeException("No such file: x"))
    )

    changedChecks()
    handlerChecks()
    includeChecks()
    retryChecks()
    cliRetryChecks()
    yamlRetryChecks()
    nodeErrorChecks()

    if failures > 0 then
      println(s"$failures check(s) failed")
      sys.exit(1)
    else println(s"TaskExecutorCheck: all $passes checks passed")

  private def retryChecks(): Unit =
    val r = Retry(3, 5)
    check(
      "the pause doubles after each retry",
      List(1, 2, 3).map(r.delayBefore) == List(5, 10, 20),
      List(1, 2, 3).map(r.delayBefore).toString
    )
    check(
      "the pause is at most 300 seconds, however many retries",
      Retry(10, 100).delayBefore(4) == 300 && Retry(100, 5).delayBefore(90) == 300
    )
    check("retries 0 is no retry", Retry.of(0).isEmpty && Retry.of(2).contains(Retry(2, 5)))

    // An action that fails the first `failFirst` times it runs.
    def flaky(failFirst: Int, retry: Retry): (Either[Throwable, Unit], Int, Vector[(Int, Int)]) =
      (for
        runs <- Ref.of[IO, Int](0)
        seen <- Ref.of[IO, Vector[(Int, Int)]](Vector.empty)
        action = runs.updateAndGet(_ + 1).flatMap(n =>
          IO.raiseWhen(n <= failFirst)(new RuntimeException(s"failure $n"))
        )
        result <- TaskExecutor
          .retrying(retry)((n, _, pause) => seen.update(_ :+ (n -> pause)))(action)
          .attempt
        count <- runs.get
        retried <- seen.get
      yield (result, count, retried)).unsafeRunSync()

    val (ok, runs, retried) = flaky(2, Retry(3, 0))
    check(
      "a task that fails twice succeeds on its third attempt with 3 retries",
      ok.isRight && runs == 3 && retried == Vector(1 -> 0, 2 -> 0),
      s"$ok $runs $retried"
    )
    val (gaveUp, gaveUpRuns, _) = flaky(10, Retry(2, 0))
    check(
      "with 2 retries a task that keeps failing runs 3 times and fails with the last error",
      gaveUp.left.toOption.map(_.getMessage).contains("failure 3") && gaveUpRuns == 3,
      s"$gaveUp $gaveUpRuns"
    )

    val failingTask = List(
      NamedTask(
        "fetch",
        Task.DistributeFile("no-such-node", "/etc/hostname", "/tmp/x"),
        retry = Retry.of(2, 0)
      )
    )
    val (retryOk, retryLines) = run(failingTask, List(a), templating = true)
    check(
      "a task with retries shows each retry, then fails once",
      retryOk == List(false) &&
        retryLines.count(_.startsWith("check-a|fetch|attempt ")) == 2 &&
        retryLines.exists(_.contains("retry 2 of 2 in 0s")) &&
        retryLines.count(_.contains("|FAILED|")) == 1,
      retryLines.toString
    )

  private def cliRetryChecks(): Unit =
    check(
      "--retries and --retry-delay are read and taken off the arguments",
      Cli.extractRetry(List("install", "tree", "--retries", "3", "--retry-delay", "2", "--nodes", "a")) ==
        Right((Some(Retry(3, 2)), List("install", "tree", "--nodes", "a")))
    )
    check(
      "--retries without --retry-delay waits 5 seconds first",
      Cli.extractRetry(List("run", "--retries", "2", "--", "uptime")).map(_._1) == Right(Some(Retry(2, 5)))
    )
    check(
      "--retries 0 is no retry",
      Cli.extractRetry(List("install", "tree", "--retries", "0")).map(_._1) == Right(None)
    )
    check(
      "a command still parses with --retries",
      Cli.parse(List("install", "tree", "--retries", "2", "--nodes", "a")) ==
        Right(Command.Install(List("tree"), Some(List("a")), false)),
      Cli.parse(List("install", "tree", "--retries", "2", "--nodes", "a")).toString
    )
    check(
      "--retries is taken from anywhere in run's arguments, as --nodes is",
      Cli.parse(List("run", "--nodes", "a", "--", "uptime", "--retries", "1")) ==
        Right(Command.RunCommand(List("uptime"), Some(List("a")), 60, false)),
      Cli.parse(List("run", "--nodes", "a", "--", "uptime", "--retries", "1")).toString
    )
    check(
      "--retries is refused for a command that does not change a node",
      Cli.parse(List("facts", "--retries", "2")).left.exists(_.contains("apply only to"))
    )
    check(
      "--retry-delay alone is refused",
      Cli.parse(List("install", "tree", "--retry-delay", "2")).left.exists(_.contains("needs --retries"))
    )
    check(
      "a bad --retries value is refused",
      Cli.parse(List("install", "tree", "--retries", "x")).left.exists(_.contains("Invalid --retries"))
    )
    check(
      "a pipeline step may give --retries",
      Cli.stepArgs("install tree --retries 2", "tst0").isRight,
      Cli.stepArgs("install tree --retries 2", "tst0").toString
    )

  private def yamlRetryChecks(): Unit =
    def task(yaml: String): Either[String, NamedTask] =
      io.circe.yaml.parser
        .parse(yaml)
        .left
        .map(_.getMessage)
        .flatMap(j => TaskYaml.decodeNamedTask(j.hcursor).left.map(_.getMessage))

    check(
      "a YAML task reads retries and retry_delay",
      task("name: t\nretries: 3\nretry_delay: 10\ninstall:\n  packages: [tree]\n").map(_.retry) ==
        Right(Some(Retry(3, 10))),
      task("name: t\nretries: 3\nretry_delay: 10\ninstall:\n  packages: [tree]\n").toString
    )
    check(
      "a YAML task without retries has none",
      task("name: t\ninstall:\n  packages: [tree]\n").map(_.retry) == Right(None)
    )
    check(
      "retry_delay without retries is an error",
      task("name: t\nretry_delay: 10\ninstall:\n  packages: [tree]\n").isLeft
    )
    check(
      "negative retries is an error",
      task("name: t\nretries: -1\ninstall:\n  packages: [tree]\n").isLeft
    )
    check(
      "the DSL's .retry applies to the last task",
      PlaybookDsl
        .playbook("p", "a")
        .task("one")(Task.Debug("x"))
        .task("two")(Task.Debug("y"))
        .retry(4, delaySeconds = 1)
        .build
        .tasks
        .map(_.retry) == List(None, Some(Retry(4, 1)))
    )

  private def nodeErrorChecks(): Unit =
    def unavailable(cause: Throwable) =
      Status.UNAVAILABLE.withDescription("io exception").withCause(cause).asRuntimeException()
    val noRoute = NodeError.line(unavailable(new java.net.NoRouteToHostException("null: tst2.example.com/10.10.5.14:50051")))
    check(
      "a connection error with no message is named, not printed as null",
      noRoute == "unreachable: UNAVAILABLE: io exception (no route to host: tst2.example.com/10.10.5.14:50051)",
      noRoute
    )
    check(
      "no agent token here is reported as that, not as an unreachable node",
      NodeError.line(new RuntimeException("call failed", new AgentToken.NotConfiguredError)) == AgentToken.NotConfigured &&
        TaskExecutor.describe(new AgentToken.NotConfiguredError) == AgentToken.NotConfigured
    )
    check(
      "a playbook's FAILED line names a rejected token",
      TaskExecutor.describe(Status.UNAUTHENTICATED.asRuntimeException()).startsWith("unauthenticated:")
    )
    // What the orchestrator saw on scala0 from a node with tls/ca.crt, with no
    // client certificate.
    val noClientCert = Status.UNAVAILABLE
      .withDescription("ssl exception")
      .withCause(
        new javax.net.ssl.SSLException(
          "error:1000045c:SSL routines:OPENSSL_internal:TLSV1_ALERT_CERTIFICATE_REQUIRED"
        )
      )
      .asRuntimeException()
    check(
      "a node that requires a client certificate says so, not unreachable",
      NodeError.line(noClientCert).startsWith("client certificate required:"),
      NodeError.line(noClientCert)
    )
    val wrongCa = Status.UNAVAILABLE
      .withDescription("ssl exception")
      .withCause(new javax.net.ssl.SSLException("error:10000418:SSL routines:OPENSSL_internal:TLSV1_ALERT_UNKNOWN_CA"))
      .asRuntimeException()
    check(
      "a node that rejects the client certificate says so",
      NodeError.line(wrongCa).startsWith("client certificate rejected:"),
      NodeError.line(wrongCa)
    )
    check(
      "an orchestrator that does not trust the node's certificate is still unreachable",
      NodeError
        .line(
          Status.UNAVAILABLE
            .withCause(new javax.net.ssl.SSLHandshakeException("PKIX path building failed: unable to find valid certification path"))
            .asRuntimeException()
        )
        .startsWith("unreachable:")
    )
    val refused = NodeError.line(unavailable(new java.net.ConnectException("Connection refused: tst2/10.10.5.14:50051")))
    check(
      "a connection error with a message keeps it",
      refused.contains("(Connection refused: tst2/10.10.5.14:50051)"),
      refused
    )

  private def changedChecks(): Unit =
    import orphera.common.Event
    def result(msg: String) = Event(Event.Kind.RESULT, msg, 0, true)
    def out(msg: String) = Event(Event.Kind.OUTPUT, msg, 0, false)
    val copy = Task.Copy("a", "/b", "", "", 0)
    check("a copy that was skipped as unchanged changed nothing", !TaskExecutor.changed(copy, Vector(result("Skipped: unchanged"))))
    check("a copy that wrote the file changed something", TaskExecutor.changed(copy, Vector(result("OK"))))
    check(
      "a write_file that was skipped changed nothing",
      !TaskExecutor.changed(Task.WriteFile("x", "/b"), Vector(result("Skipped: unchanged")))
    )
    val install = Task.Install(List("tree"))
    check(
      "apt with nothing to do changed nothing",
      !TaskExecutor.changed(install, Vector(out("tree is already the newest version."), out("0 upgraded, 0 newly installed, 0 to remove and 3 not upgraded.")))
    )
    check(
      "apt that installed a package changed something",
      TaskExecutor.changed(install, Vector(out("1 upgraded, 0 newly installed, 0 to remove and 2 not upgraded.")))
    )
    check(
      "apt remove that removed a package changed something",
      TaskExecutor.changed(Task.Remove(List("tree")), Vector(out("0 upgraded, 0 newly installed, 1 to remove and 3 not upgraded.")))
    )
    check("dnf with nothing to do changed nothing", !TaskExecutor.changed(install, Vector(out("Package tree is already installed."), out("Nothing to do."))))
    check("dnf that installed changed something", TaskExecutor.changed(install, Vector(out("Installed:"), out("Complete!"))))
    check("debug never changes anything", !TaskExecutor.changed(Task.Debug("x"), Vector.empty))
    check("run_command always counts as a change", TaskExecutor.changed(Task.RunCommand(List("true")), Vector(result("exit=0"))))

  /** Runs `tasks` with `handlers` on node a, where a debug task's message says
    * what it does: "changed" (succeeds, changed), "same" (succeeds,
    * unchanged), "fail" (fails). Returns the result and the lines.
    */
  private def withHandlers(
      tasks: List[NamedTask],
      handlers: List[NamedTask]
  ): (Boolean, Vector[String]) =
    (for
      lines <- Ref.of[IO, Vector[String]](Vector.empty)
      setFacts <- SetFacts.empty
      fake = (nt: NamedTask, node: Node, env: TaskExecutor.Env) =>
        nt.task match
          case Task.Debug("fail") => IO.raiseError(new RuntimeException("failed on purpose"))
          case Task.Debug(what)   => env.output.line(node, nt.name, "ran").as(what == "changed")
          case _                  => IO.pure(false)
      env = TaskExecutor.Env(ClusterContext(Map.empty), setFacts, recording(lines), templating = true, perform = fake)
      checkpoint <- Checkpoint.disabled
      runLog <- RunLog.disabled
      ok <- TaskExecutor.runTasks(tasks, a, env, checkpoint, runLog, handlers = handlers)
      recorded <- lines.get
    yield (ok, recorded)).unsafeRunSync()

  private def handlerChecks(): Unit =
    val handlers = List(
      NamedTask("restart web", Task.Debug("same")),
      NamedTask("reload proxy", Task.Debug("same")),
      NamedTask("unused", Task.Debug("same"))
    )
    val tasks = List(
      NamedTask("config", Task.Debug("changed"), notifies = List("restart web")),
      NamedTask("proxy config", Task.Debug("same"), notifies = List("reload proxy")),
      NamedTask("more config", Task.Debug("changed"), notifies = List("restart web", "reload proxy"))
    )
    val (ok, lines) = withHandlers(tasks, handlers)
    val ran = lines.filter(_.endsWith("|ran")).map(_.split('|')(1))
    check("the run succeeds", ok, lines.toString)
    check(
      "handlers run after every task, in the order listed, each once, only when a changed task notified them",
      ran == Vector("config", "proxy config", "more config", "restart web (handler)", "reload proxy (handler)"),
      ran.toString
    )
    check(
      "a changed task that notifies says so",
      lines.contains("check-a|config|changed; notifies restart web"),
      lines.toString
    )
    check("an unchanged task notifies nothing", !lines.exists(_.startsWith("check-a|proxy config|changed")), lines.toString)

    val (failOk, failLines) = withHandlers(
      List(
        NamedTask("config", Task.Debug("changed"), notifies = List("restart web")),
        NamedTask("broken", Task.Debug("fail"))
      ),
      handlers
    )
    check(
      "when a task fails, the notified handlers do not run, and a line says so",
      !failOk && !failLines.exists(_.contains("(handler)")) &&
        failLines.contains("check-a|handlers|not run, because a task failed: restart web"),
      failLines.toString
    )

    val (hOk, hLines) = withHandlers(
      List(NamedTask("config", Task.Debug("changed"), notifies = List("restart web"))),
      List(NamedTask("restart web", Task.Debug("fail")))
    )
    check(
      "a handler that fails fails the node",
      !hOk && hLines.exists(_.startsWith("check-a|restart web (handler)|FAILED|")),
      hLines.toString
    )

    check(
      "a notify with no such handler is found before the run",
      Handlers.problems(List(NamedTask("t", Task.Debug("x"), notifies = List("nope"))), Nil).exists(_.contains("'nope'"))
    )
    check(
      "two handlers with one name are found",
      Handlers.problems(Nil, List(NamedTask("h", Task.Debug("x")), NamedTask("h", Task.Debug("y")))).exists(_.contains("two handlers"))
    )
    check(
      "a handler that notifies is found",
      Handlers.problems(Nil, List(NamedTask("h", Task.Debug("x"), notifies = List("h")))).exists(_.contains("do not notify"))
    )
    check("tasks and handlers that agree have no problems", Handlers.problems(tasks, handlers).isEmpty)

    val built = PlaybookDsl
      .playbook("p", "a")
      .includeTasks(List(NamedTask("shared", Task.Debug("x"))))
      .task("config")(Task.Debug("y"))
      .notifies("restart web")
      .handler("restart web")(Task.Debug("z"))
      .build
    check(
      "the DSL builds includeTasks, notifies and handler",
      built.tasks.map(t => t.name -> t.notifies) == List("shared" -> Nil, "config" -> List("restart web")) &&
        built.handlers.map(_.name) == List("restart web"),
      built.toString
    )
    val stage = ClusterPlaybookDsl
      .stage("s", "a")
      .task("config")(Task.Debug("y"))
      .notifies("restart web")
      .handler("restart web")(Task.Debug("z"))
      .build
    check("a stage builds notifies and handler", stage.handlers.map(_.name) == List("restart web") &&
      stage.tasks.flatMap(_.notifies) == List("restart web"))

  private def includeChecks(): Unit =
    import java.nio.file.{Files, Path}
    val dir = Files.createTempDirectory("orphera-include-check")
    def write(name: String, text: String): Path =
      val f = dir.resolve(name)
      Files.createDirectories(f.getParent)
      Files.writeString(f, text)
    write(
      "common/base.yaml",
      """tasks:
        |  - name: install tools
        |    install:
        |      packages: [tree]
        |    notify: note it
        |  - include: ../extra.yaml
        |handlers:
        |  - name: note it
        |    debug:
        |      message: noted
        |""".stripMargin
    )
    write(
      "extra.yaml",
      """- name: extra
        |  debug:
        |    message: extra
        |""".stripMargin
    )
    val main = write(
      "main.yaml",
      """name: included
        |nodes: [a]
        |tasks:
        |  - name: first
        |    debug:
        |      message: first
        |  - include: common/base.yaml
        |  - name: last
        |    debug:
        |      message: last
        |    notify: [restart]
        |handlers:
        |  - name: restart
        |    debug:
        |      message: restarted
        |""".stripMargin
    )
    val loaded = PlaybookYaml.load(main.toString)
    check(
      "includes are replaced by their tasks, in place, nested and relative to the including file",
      loaded.map(_.tasks.map(_.name)) == Right(List("first", "install tools", "extra", "last")),
      loaded.toString
    )
    check(
      "an included file's handlers are added to the playbook's",
      loaded.map(_.handlers.map(_.name)) == Right(List("restart", "note it")),
      loaded.toString
    )
    check(
      "notify: takes one name or a list",
      loaded.map(_.tasks.map(_.notifies)) == Right(List(Nil, List("note it"), Nil, List("restart"))),
      loaded.toString
    )

    write("loop1.yaml", "- include: loop2.yaml\n")
    write("loop2.yaml", "- include: loop1.yaml\n")
    val cyclic = write("cyclic.yaml", "name: c\nnodes: [a]\ntasks:\n  - include: loop1.yaml\n")
    val cycle = PlaybookYaml.load(cyclic.toString)
    check("an include cycle is an error that names it", cycle.left.exists(_.contains("include cycle")), cycle.toString)

    val extraKey = write("extrakey.yaml", "name: c\nnodes: [a]\ntasks:\n  - include: extra.yaml\n    when: os_id == \"ubuntu\"\n")
    check(
      "an include with other keys is an error",
      PlaybookYaml.load(extraKey.toString).left.exists(_.contains("only the include: key"))
    )
    val missing = write("missing.yaml", "name: c\nnodes: [a]\ntasks:\n  - include: nowhere.yaml\n")
    check("an include of a missing file is an error", PlaybookYaml.load(missing.toString).left.exists(_.contains("cannot read")))

    val cluster = write(
      "cluster.yaml",
      """name: staged
        |stages:
        |  - name: one
        |    nodes: [a]
        |    tasks:
        |      - include: common/base.yaml
        |    handlers:
        |      - name: extra handler
        |        debug:
        |          message: x
        |""".stripMargin
    )
    val staged = ClusterPlaybookYaml.load(cluster.toString)
    check(
      "a cluster-playbook stage takes includes and handlers",
      staged.map(_.stages.map(st => (st.tasks.map(_.name), st.handlers.map(_.name)))) ==
        Right(List((List("install tools", "extra"), List("extra handler", "note it")))),
      staged.toString
    )

    // The example in manifests/ (the check may run from the repository or the
    // module directory).
    List("manifests/handlers_test.yaml", "../manifests/handlers_test.yaml")
      .find(p => Files.isRegularFile(java.nio.file.Paths.get(p))) match
      case None => println("manifests/handlers_test.yaml not found from here: skipped loading it")
      case Some(path) =>
        val example = PlaybookYaml.load(path)
        check(
          "manifests/handlers_test.yaml loads, with its include and handlers, and has no handler problems",
          example.exists(p =>
            p.tasks.map(_.name) == List("make sure tree is installed", "write the test file") &&
              p.handlers.map(_.name) == List("say packages changed", "record the change") &&
              Handlers.problems(p.tasks, p.handlers).isEmpty
          ),
          example.toString
        )

