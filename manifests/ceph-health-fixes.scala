// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_health_fixes extends OrpheraClusterPlaybook:

  // Insecure global_id reclaim refused: a config option, unchanged while
  // `ceph config get mon ...` already says false.
  private val reclaimPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "global-id-reclaim",
      List(
        cephConfig(
          "refuse insecure global_id reclaim",
          "mon",
          List("auth_allow_insecure_global_id_reclaim" -> "false")
        )
      )
    )

  // msgr2 is on when every monitor in `ceph mon dump` has a v2 address.
  // A monitor list that cannot be read counts as "not done", so the
  // command runs and fails with Ceph's own message.
  private val msgr2Check =
    """OUT=$(cephadm shell -- ceph mon dump 2>/dev/null | tr -d '\r') || exit 1
      |echo "$OUT" | grep -q '^[0-9][0-9]*: ' || exit 1
      |! echo "$OUT" | grep '^[0-9][0-9]*: ' | grep -qv 'v2:'""".stripMargin

  private val msgr2Plan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail(
      "msgr2",
      List(
        command(
          "enable msgr2 on the monitors",
          "cephadm shell -- ceph mon enable-msgr2",
          unless = msgr2Check,
          timeout = 120
        )
      )
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-health-fixes")(
      stage("apply-fixes", "tst0")
        .task("disable insecure global_id reclaim")(Task.Apply(reclaimPlan))
        .task("enable msgr2")(Task.Apply(msgr2Plan))
        .task("confirm clean health")(
          Task.RunCommand(List("sh", "-c", "ceph -s"))
        )
        .build
    )
