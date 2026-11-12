// SPDX-License-Identifier: Apache-2.0
//
// The Scala form of handlers_test.yaml: handlers, a shared task list (YAML's
// include:) and change detection, on tst0 and tst1, touching only /tmp and the
// `tree` package. It uses its own files, so it and the YAML one can both be
// run without each undoing the other's change.
//
//   orphera playbook manifests/handlers_demo.scala   # first run: the file is written, so "record the change" runs
//   orphera playbook manifests/handlers_demo.scala   # again: the file is unchanged (Skipped), so no handler runs
//   orphera remove tree --nodes tst0                 # then a run installs tree on tst0 only,
//   orphera playbook manifests/handlers_demo.scala   #   and "say packages changed" runs on tst0 only
//
// The handler's log on each node: /tmp/orphera-handlers-demo.log (one line per run of it).

import orphera.orchestrator.*

object handlers_demo extends OrpheraPlaybook:

  // Tasks shared between playbooks are an ordinary value, added with
  // .includeTasks: what an include: file is in YAML. A task in it can notify a
  // handler of the playbook that includes it.
  private val testFile: List[NamedTask] = List(
    NamedTask(
      "write the test file",
      Task.WriteFile(
        "written by handlers_demo.scala, version 1\n",
        "/tmp/orphera-handlers-demo.txt",
        mode = Integer.parseInt("644", 8)
      ),
      notifies = List("record the change")
    )
  )

  val playbook: Playbook =
    PlaybookDsl
      .playbook("handlers-demo", "tst0", "tst1")
      .task("make sure tree is installed")(Task.Install(List("tree")))
      .notifies("say packages changed")
      .includeTasks(testFile)
      .handler("say packages changed")(
        Task.Debug("tree was installed or upgraded on {{facts.hostname}}")
      )
      .handler("record the change")(
        Task.RunCommand(
          List(
            "sh",
            "-c",
            "date >> /tmp/orphera-handlers-demo.log; echo handler ran"
          )
        )
      )
      .build
