// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Tenth infrastructure exercise: an Orphera-orchestrated equivalent of
// `cephperf.sh`/`cephadmperf.sh` (both plain, unchecked shell scripts
// with no idempotency, no pass/fail assertions, and no automated
// cleanup) — same `rados bench` workload, but as a proper
// ClusterPlaybook: real tasks, real timeouts, and a check that the
// numbers it prints came from a benchmark that actually ran rather than
// from a command that silently no-opped or errored early. The intent is
// a repeatable load-generation run against the SAME cephadm cluster
// cephadm_install/cephadm_add_mons/cephadm_add_osds.scala bring up — run
// it a few times across different `observability_extended.scala` /
// `ceph_observability.scala` deploys and the Ceph dashboard in Grafana
// should show the bandwidth/IOPS/latency spikes from each run.
//
// Deliberately run entirely through `cephadm shell -- ...` on tst0 (the
// bootstrap/admin host), the same access pattern test_ceph_lifecycle.sh
// already uses — no bare host-level `rados`/`rbd` CLI, no host-side
// `/etc/ceph/ceph.conf` assumption, so this needs nothing beyond what
// cephadm_install.scala already guarantees exists.
//
// Benchmark pool is created and torn down by THIS file, not left behind
// — re-running this playbook (e.g. for a second data point in Grafana)
// starts from a clean pool every time rather than accumulating leftover
// objects across runs, same "don't let repeated runs quietly change
// behavior" principle as every idempotent config write elsewhere in
// this project.
//
// Scope, deliberately: object-storage (RADOS) throughput only via
// `rados bench` — write, sequential-read, and random-read passes. A
// block-device (`rbd bench`) or filesystem (CephFS) pass would need
// `rbd map`/kernel client access on the host rather than inside the
// transient `cephadm shell` container, which is a different, unconfirmed
// set of assumptions (host-level `ceph-common`, host-level keyring
// placement) — a natural follow-up, not folded into this first pass.
object ceph_performance_test extends OrpheraClusterPlaybook:

  private val adminNode = "tst0"
  private val poolName = "orphera-perf-test"
  private val pgCount = 128
  private val benchSeconds = 10

  // Idempotent: a leftover pool from an interrupted earlier run is
  // removed and recreated, rather than erroring out or silently
  // benchmarking whatever state that leftover pool happened to be in.
  // No `\` line-continuation here — a backslash immediately before a
  // newline inside a triple-quoted literal is rejected by the Scala 3
  // compiler as an invalid escape sequence (hit for real in
  // observability_stack.scala; fixed there by never using one). The
  // `if`/`fi` spans multiple complete statements instead.
  private val createPoolScript =
    s"""if cephadm shell -- ceph osd pool ls | grep -qx '$poolName'; then
       |  cephadm shell -- ceph osd pool rm $poolName $poolName --yes-i-really-really-mean-it
       |fi
       |cephadm shell -- ceph osd pool create $poolName $pgCount $pgCount
       |echo "benchmark pool '$poolName' ready ($pgCount PGs)"""".stripMargin

  // `--no-cleanup` leaves the written objects in place for the
  // sequential/random READ passes below to read back — cleaned up
  // explicitly in cleanupScript once all three passes are done, not
  // implicitly by whichever pass happens to run last.
  //
  // rados bench's own summary lines are the one stable, parseable
  // contract here (unlike a JSON flag, which not every rados bench
  // subcommand/version supports) — same "grep the tool's own real
  // output rather than assume a flag exists" caution test_ceph_lifecycle.sh's
  // EXPECTED_OSD_COUNT comment already documents for this project.
  private def benchScript(mode: String, extraArgs: String = ""): String =
    s"""OUTPUT=$$(cephadm shell -- rados bench -p $poolName $benchSeconds $mode $extraArgs 2>&1)
       |echo "$$OUTPUT"
       |BANDWIDTH=$$(echo "$$OUTPUT" | grep -F 'Bandwidth (MB/sec):' | awk '{print $$NF}')
       |if [ -z "$$BANDWIDTH" ]; then
       |  echo "rados bench ($mode) produced no 'Bandwidth (MB/sec):' summary line — it likely errored before finishing, see output above" >&2
       |  exit 1
       |fi
       |echo "rados bench $mode: $${BANDWIDTH} MB/sec"""".stripMargin

  private val writeBenchScript = benchScript("write", "--no-cleanup --run-name orphera-perf-run")
  private val seqReadBenchScript = benchScript("seq")
  private val randReadBenchScript = benchScript("rand")

  private val cleanupDataScript =
    s"""cephadm shell -- rados -p $poolName cleanup
       |echo "benchmark objects cleaned up"""".stripMargin

  private val teardownPoolScript =
    s"""cephadm shell -- ceph osd pool rm $poolName $poolName --yes-i-really-really-mean-it
       |echo "benchmark pool '$poolName' removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-performance-test")(

      stage("create-benchmark-pool", adminNode)
        .task("create (or recreate) the disposable benchmark pool")(
          Task.RunCommand(List("sh", "-c", createPoolScript), timeoutSeconds = 60)
        )
        .build,

      stage("run-write-benchmark", adminNode)
        .task(s"rados bench write, ${benchSeconds}s")(
          Task.RunCommand(List("sh", "-c", writeBenchScript), timeoutSeconds = benchSeconds + 60)
        )
        .build,

      stage("run-sequential-read-benchmark", adminNode)
        .task(s"rados bench seq read, ${benchSeconds}s")(
          Task.RunCommand(List("sh", "-c", seqReadBenchScript), timeoutSeconds = benchSeconds + 60)
        )
        .build,

      stage("run-random-read-benchmark", adminNode)
        .task(s"rados bench rand read, ${benchSeconds}s")(
          Task.RunCommand(List("sh", "-c", randReadBenchScript), timeoutSeconds = benchSeconds + 60)
        )
        .build,

      stage("cleanup-benchmark", adminNode)
        .task("clean up benchmark objects")(
          Task.RunCommand(List("sh", "-c", cleanupDataScript), timeoutSeconds = 60)
        )
        .task("remove benchmark pool")(
          Task.RunCommand(List("sh", "-c", teardownPoolScript), timeoutSeconds = 60)
        )
        .task("performance test complete")(
          Task.Debug(
            "rados bench write/seq/rand passes complete against a disposable pool, now removed. " +
              "If ceph_observability.scala is applied, the bandwidth/IOPS/latency spikes from this run " +
              "should show up in Grafana's Ceph dashboard (http://tst6.ljalbinson.com:3000) within one " +
              "Prometheus scrape interval (15s)."
          )
        )
        .build
    )
