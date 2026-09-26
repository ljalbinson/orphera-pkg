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
        .task("bootstrap ceph cluster")(
          // Idempotent: if /etc/ceph/ceph.pub already exists, bootstrap already
          // happened (fresh installs or post-teardown re-runs both hit this).
          // This is the step that actually generates the cluster's cephadm SSH
          // keypair (/etc/ceph/ceph.pub) — without it, distribute-key tasks in
          // downstream playbooks (e.g. cephadm-add-mons) have nothing to copy.
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "test -f /etc/ceph/ceph.pub || cephadm bootstrap " +
                "--mon-ip {{nodes.tst0.cluster_ip}} " +
                "--skip-dashboard --skip-monitoring-stack --allow-fqdn-hostname"
            ),
            timeoutSeconds = 300
          )
        )
        .build
    )
