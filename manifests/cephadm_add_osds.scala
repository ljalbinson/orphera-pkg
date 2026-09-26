import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object cephadm_add_osds extends OrpheraClusterPlaybook:

  val playbook: ClusterPlaybook =
    clusterPlaybook("cephadm-add-osds")(

      stage("apply-osd-spec", "tst0")
        .task("apply OSD spec across all available devices")(
          Task.RunCommand(
            List("sh", "-c", "cephadm shell -- ceph orch apply osd --all-available-devices"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-osds", "tst0")
        .waitFor(
          // NOTE: deliberately not reusing the mon-quorum grep pattern here —
          // OSD readiness has nothing to do with mon count. This asserts the
          // actual condition we care about: every registered OSD is both up
          // and in, and at least one exists (so an empty/not-yet-scheduled
          // spec doesn't vacuously pass). JSON output is used instead of
          // grepping formatted text, since the human-readable "6 osds: 6 up
          // (since 7m)..." string varies with OSD count and elapsed time.
          HealthCheck.Quorum(
            nodes = List("tst0"),
            command = List(
              "sh",
              "-c",
              "cephadm shell -- ceph osd stat -f json 2>/dev/null | " +
                "python3 -c 'import json,sys; d=json.load(sys.stdin); " +
                "sys.exit(0 if d[\"num_osds\"]==d[\"num_up_osds\"]==d[\"num_in_osds\"]>0 else 1)'"
            ),
            requiredCount = 1,
            pollIntervalSeconds = 10,
            timeoutSeconds = 300
          )
        )
        .task("osds confirmed")(
          Task.Debug("All registered OSDs are up and in.")
        )
        .build
    )
