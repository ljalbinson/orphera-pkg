// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_add_mons extends OrpheraClusterPlaybook:

  // The cluster's public key is made on tst0, so it cannot be written in this
  // file; it is copied to a fixed file on each new host and added to root's
  // authorized_keys only when that file's line is not there yet (before: the
  // key was appended on every run). The copy stays, as a file that is the same
  // on every run.
  private val keyFile = "/root/.ssh/orphera-cephadm.pub"

  private val keyPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "cephadm-authorized-key",
      List(
        command(
          "add the cephadm key to root's authorized_keys",
          s"cat $keyFile >> /root/.ssh/authorized_keys",
          unless = s"grep -qxF \"$$(cat $keyFile)\" /root/.ssh/authorized_keys"
        )
      )
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-add-mons")(
      stage("prep-additional-hosts", "tst1", "tst2")
        .task("install cephadm prerequisites")(
          Task.Install(
            packages = List("python3", "lvm2", "chrony", "podman"),
            updateCache = true
          )
        )
        .task("distribute cluster ssh public key")(
          Task.DistributeFile(
            sourceNode = "tst0",
            sourcePath = "/etc/ceph/ceph.pub",
            destPath = keyFile,
            owner = "root",
            group = "root",
            mode = 384 // 0600 in decimal
          )
        )
        .task("append cephadm key to authorized_keys")(
          Task.Apply(keyPlan)
        )
        .build,

      stage("register-hosts", "tst0")
        .task("add tst1 to cephadm-managed hosts")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "cephadm shell -- ceph orch host add tst1 {{nodes.tst1.cluster_ip}}"
            ),
            timeoutSeconds = 120
          )
        )
        .task("add tst2 to cephadm-managed hosts")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "cephadm shell -- ceph orch host add tst2 {{nodes.tst2.cluster_ip}}"
            ),
            timeoutSeconds = 120
          )
        )
        .task("apply mon placement across all three hosts")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "cephadm shell -- ceph orch apply mon --placement='tst0,tst1,tst2'"
            )
          )
        )
        .build,

      stage("confirm-quorum", "tst0")
        .waitFor(
          HealthCheck.Quorum(
            nodes = List("tst0", "tst1", "tst2"),
            command = List(
              "sh",
              "-c",
              "cephadm shell -- ceph mon stat 2>/dev/null | grep -Eq 'e[0-9]+: 3 mons'"
            ),
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
