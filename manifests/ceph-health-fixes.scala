import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_health_fixes extends OrpheraClusterPlaybook:

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-health-fixes")(
      stage("apply-fixes", "tst0")
        .task("disable insecure global_id reclaim")(
          Task.RunCommand(List("sh", "-c", "ceph config set mon auth_allow_insecure_global_id_reclaim false"))
        )
        .task("enable msgr2")(
          Task.RunCommand(List("sh", "-c", "ceph mon enable-msgr2"))
        )
        .task("confirm clean health")(
          Task.RunCommand(List("sh", "-c", "ceph -s"))
        )
        .build
    )
