import orphera.orchestrator.*
import orphera.orchestrator.PlaybookDsl.*

object arithmetic_demo extends OrpheraPlaybook:

  private val basePort = 9000
  private val replicas = 3
  private val targetPort = basePort + replicas * 10

  val playbook: Playbook =
    PlaybookDsl
      .playbook("arithmetic-demo", "tst0")
      .task("show computed port")(Task.Debug(s"computed target_port = $targetPort"))
      .build
