import orphera.orchestrator.*
import orphera.orchestrator.PlaybookDsl.*

// Fixture for test_observability.sh: exercises the one event the
// restartability fixture doesn't — task_skipped/reason=condition_not_met.
// task2 is gated on a fact condition that's guaranteed never true on
// any real node, so it always gets skipped for that reason (never
// resume_checkpoint — there's no checkpoint involved here at all), and
// task3 proves execution carries on normally afterwards.
object observability_condition_test extends OrpheraPlaybook:

  private val alwaysRuns: Task.RunCommand =
    Task.RunCommand(List("sh", "-c", "true"), timeoutSeconds = 10)

  val playbook: Playbook =
    PlaybookDsl
      .playbook("observability-condition-test", "tst0")
      .task("task1 always runs")(alwaysRuns)
      .task("task2 gated on an impossible condition")(alwaysRuns)
      .when("facts.os_id" === "this-os-id-does-not-exist")
      .task("task3 always runs")(alwaysRuns)
      .build
