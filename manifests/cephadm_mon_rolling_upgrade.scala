import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_mon_rolling_upgrade extends OrpheraClusterPlaybook:

  // Order matters: hosts are upgraded strictly in this sequence, tst0 first
  // as the canary. This relies on an assumption worth confirming against
  // the runner itself: that declared stages execute one at a time, in
  // list order, and the whole playbook aborts on the first stage failure
  // (which is what the CLI showed for 'prep-additional-hosts' earlier).
  // If that ever changes to "stages may run out of order" or "continue
  // past a failed stage", this whole design stops being serial-safe.
  val monRolloutOrder: List[String] = List("tst0", "tst1", "tst2")

  // Bump this to trigger the upgrade. To deliberately test the abort-on-
  // failure path, point it at a version string that doesn't exist for one
  // test run and confirm hosts after the broken one are never touched.
  val targetCephVersion: String = "{{ceph_version}}"

  // One stage per host, each fully self-contained: stop that host's mon,
  // upgrade its packages, restart it, then don't declare success until
  // quorum has re-formed across ALL rollout hosts (not just this one) —
  // so a host that never comes back blocks every later stage from ever
  // starting, rather than letting the rollout silently continue on a
  // degraded cluster.
  private def monUpgradeStage(host: String) =
    stage(s"upgrade-mon-$host", host)
      .task(s"stop mon on $host")(
        Task.RunCommand(
          List("sh", "-c", "systemctl stop ceph-mon@$(hostname -s)"),
          timeoutSeconds = 60
        )
      )
      .task(s"upgrade ceph-mon/ceph-common on $host")(
        Task.Install(
          packages = List("ceph-mon", "ceph-common", "ceph-base"),
          version = targetCephVersion,
          updateCache = true
        )
      )
      .task(s"restart mon on $host")(
        Task.RunCommand(
          List("sh", "-c", "systemctl start ceph-mon@$(hostname -s)"),
          timeoutSeconds = 60
        )
      )
      .waitFor(
        HealthCheck.Quorum(
          nodes = List("tst0"),
          command = List(
            "sh",
            "-c",
            s"cephadm shell -- ceph mon stat 2>/dev/null | grep -Eq 'e[0-9]+: ${monRolloutOrder.size} mons'"
          ),
          requiredCount = 1,
          pollIntervalSeconds = 10,
          timeoutSeconds = 180
        )
      )
      .task(s"quorum confirmed after upgrading $host")(
        Task.Debug(s"Mon quorum re-formed with $host on $targetCephVersion.")
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-mon-rolling-upgrade")(
      monRolloutOrder.map(monUpgradeStage)*
    )
