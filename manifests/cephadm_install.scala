import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_install extends OrpheraClusterPlaybook:

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-install")(

      stage("install-cephadm", "tst0")
        .task("add cephadm prerequisites")(
          Task.Install(packages = List("python3", "lvm2", "chrony", "podman"), updateCache = true)
        )
        .task("install cephadm package")(
          Task.Install(packages = List("cephadm"), version = "{{ceph_version}}")
        )
        .task("confirm cephadm is runnable")(
          Task.RunCommand(List("sh", "-c", "cephadm --help >/dev/null && echo cephadm binary is functional"))
        )
        .build
    )
