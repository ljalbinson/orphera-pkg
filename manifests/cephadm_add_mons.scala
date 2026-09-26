import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_add_mons extends OrpheraClusterPlaybook:

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-add-mons")(

      stage("prep-additional-hosts", "tst1", "tst2")
        .task("install cephadm prerequisites")(
          Task.Install(packages = List("python3", "lvm2", "chrony", "podman"), updateCache = true)
        )
        .task("distribute cluster ssh public key")(
          Task.DistributeFile(
            sourceNode = "tst0",
            sourcePath = "/etc/ceph/ceph.pub",
            destPath = "/root/.ssh/authorized_keys_cephadm_tmp",
            owner = "root",
            group = "root",
            mode = 384 // 0600 in decimal
          )
        )
        .task("append cephadm key to authorized_keys")(
          Task.RunCommand(List(
            "sh",
            "-c",
            "cat /root/.ssh/authorized_keys_cephadm_tmp >> /root/.ssh/authorized_keys && rm /root/.ssh/authorized_keys_cephadm_tmp"
          ))
        )
        .build,

      stage("register-hosts", "tst0")
        .task("add tst1 to cephadm-managed hosts")(
          Task.RunCommand(
            List("sh", "-c", "cephadm shell -- ceph orch host add tst1 {{nodes.tst1.cluster_ip}}"),
            timeoutSeconds = 120
          )
        )
        .task("add tst2 to cephadm-managed hosts")(
          Task.RunCommand(
            List("sh", "-c", "cephadm shell -- ceph orch host add tst2 {{nodes.tst2.cluster_ip}}"),
            timeoutSeconds = 120
          )
        )
        .task("apply mon placement across all three hosts")(
          Task.RunCommand(List("sh", "-c", "cephadm shell -- ceph orch apply mon --placement='tst0,tst1,tst2'"))
        )
        .build,

      stage("confirm-quorum", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List("sh", "-c", "cephadm shell -- ceph mon stat 2>/dev/null | grep -Eq 'e[0-9]+: 3 mons'"),
            requiredCount = 1,
            pollIntervalSeconds = 10,
            timeoutSeconds = 300
          )
        )
        .task("quorum confirmed")(
          Task.Debug("Cephadm-managed 3-mon quorum reached.")
        )
        .build
    )
