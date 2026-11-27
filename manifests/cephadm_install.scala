// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_install extends OrpheraClusterPlaybook:

  // Ceph 19.2.6+ / 20.2.4+ (CVE-2025-30156) accepts only the new aes256k
  // cipher by default. OpenStack clients from the Kolla images (Ubuntu's Ceph
  // 19.2.3) only know the older `aes`, so they are refused with "RADOS
  // permission denied". TEST CLUSTER COMPROMISE: allow both until all clients
  // are upgraded, then enforce with `ceph mon set auth_allowed_ciphers aes256k`
  // and rotate client keys. It is only set where the release knows the option
  // (`ceph mon dump` mentions it); a release without it has nothing to set, and
  // the check says "done". The check also says "done" when the dump already
  // shows both ciphers on the option's line.
  private val cipherCheck =
    """OUT=$(cephadm shell -- ceph mon dump 2>/dev/null | tr -d '\r') || exit 1
      |LINE=$(echo "$OUT" | grep 'auth_allowed_ciphers') || exit 0
      |echo "$LINE" | grep -q 'aes256k' && echo "$LINE" | grep -Eq '(^|[^0-9a-z_])aes([^0-9a-z_]|$)'""".stripMargin

  private val cipherPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "cephx-ciphers",
      List(
        command(
          "allow the aes cephx cipher alongside aes256k",
          "cephadm shell -- ceph mon set auth_allowed_ciphers aes,aes256k && " +
            "echo 'cephx ciphers allowed: aes,aes256k'",
          unless = cipherCheck,
          timeout = 120
        )
      )
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-install")(
      stage("install-cephadm", "tst0")
        .task("add cephadm prerequisites")(
          Task.Install(
            packages = List("python3", "lvm2", "chrony", "podman"),
            updateCache = true
          )
        )
        .task("install cephadm package")(
          Task.Install(packages = List("cephadm"), version = "{{ceph_version}}")
        )
        .task("confirm cephadm is runnable")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "cephadm --help >/dev/null && echo cephadm binary is functional"
            )
          )
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
                // OSD replication and recovery traffic on its own VLAN (107,
                // enp2s0 on tst0-tst2, see config/tst0-2.yaml); MONs, MGRs and
                // clients stay on the public network 10.10.5.0/24.
                "--cluster-network 10.10.7.0/24 " +
                "--skip-dashboard --skip-monitoring-stack --allow-fqdn-hostname"
            ),
            timeoutSeconds = 300
          )
        )
        .task("allow the aes cephx cipher alongside aes256k (older clients)")(
          Task.Apply(cipherPlan)
        )
        .build
    )
