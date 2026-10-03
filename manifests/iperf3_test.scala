import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object iperf3_test extends OrpheraClusterPlaybook:

  // Runs iperf3 clients on tst1 and tst2 simultaneously against a
  // self-hosted iperf3 server on tst0 — a star topology (not full
  // mesh). tst0 isn't included as a client against itself — that's a
  // loopback test, not a network test.
  //
  // The server runs as a transient systemd unit (`systemd-run`), not a
  // backgrounded `nohup ... &` process. The nohup version hit exit=143
  // (SIGTERM) in testing: RunCommand waits for the command's process
  // tree/output to fully close before considering it done, and a
  // `nohup ... &` daemon that's meant to live forever never lets that
  // happen — eventually something times the whole task out and kills
  // it. `systemd-run` hands the process off to systemd immediately and
  // returns, so the task genuinely finishes right away, and the
  // server's lifecycle isn't tied to the task's own process-tree
  // tracking at all.
  //
  // A default `iperf3 -s` instance only serves ONE client connection
  // at a time — a second concurrent client gets "error - the server is
  // busy running a test" — so tst0 runs TWO server units, one per
  // client, on separate ports (5201 for tst1, 5202 for tst2), so both
  // client runs can genuinely happen at once.
  val portForClient: Map[String, Int] = Map(
    "tst1" -> 5201,
    "tst2" -> 5202
  )
  private val serverHost: String = "tst0"
  private val clientHosts: List[String] = portForClient.keys.toList.sorted

  private val installIperf3: Task.RunCommand =
    Task.RunCommand(
      List(
        "sh",
        "-c",
        "which iperf3 >/dev/null 2>&1 && exit 0; apt-get update -qq && apt-get install -y iperf3"
      ),
      timeoutSeconds = 120
    )

  private val startServersScript: String =
    val ports = portForClient.values.toList.sorted
    val portList = ports.mkString(" ")
    s"""
      |for PORT in $portList; do
      |  systemctl stop "iperf3-server-$$PORT" 2>/dev/null || true
      |  systemctl reset-failed "iperf3-server-$$PORT" 2>/dev/null || true
      |  systemd-run --unit="iperf3-server-$$PORT" --collect -- iperf3 -s -p "$$PORT"
      |done
      |sleep 1
      |for PORT in $portList; do
      |  iperf3 -c 127.0.0.1 -p "$$PORT" -t 1 >/dev/null 2>&1 \\
      |    && echo "iperf3 server on port $$PORT is up" \\
      |    || { echo "iperf3 server on port $$PORT failed to start — check: systemctl status iperf3-server-$$PORT" >&2; exit 1; }
      |done
      |""".stripMargin

  private val startServers: Task.RunCommand =
    Task.RunCommand(List("sh", "-c", startServersScript), timeoutSeconds = 30)

  // Per-client port lookup done in shell via a `case` on the node's own
  // hostname — ClusterPlaybookDsl's stage-level tasks are identical
  // across every node named in that stage's `stage(name, nodes*)` call,
  // so the differentiation has to happen inside the command itself.
  private val runClientScript: String =
    val portCases = portForClient.toList
      .sortBy(_._1)
      .map { case (host, port) => s"$host) PORT=$port ;;" }
      .mkString("\n                ")
    s"""
      |case "$$(hostname -s)" in
      |                $portCases
      |                *) echo "unexpected node running the client task: $$(hostname -s)" >&2; exit 1 ;;
      |esac
      |RESULT="/tmp/iperf3-result-$$(hostname -s).json"
      |iperf3 -c $serverHost.ljalbinson.com -p "$$PORT" -t 10 -P 1 -J > "$$RESULT"
      |python3 -c "
      |import json
      |d = json.load(open('$$RESULT'))
      |mbps = d['end']['sum_received']['bits_per_second'] / 1e6
      |print(f'$$(hostname -s) -> $serverHost (port $$PORT): {mbps:.1f} Mbps')
      |"
      |""".stripMargin

  private val runClient: Task.RunCommand =
    Task.RunCommand(List("sh", "-c", runClientScript), timeoutSeconds = 30)

  val playbook: ClusterPlaybook =
    clusterPlaybook("iperf3-network-test")(
      stage("install-iperf3", (clientHosts :+ serverHost).sorted*)
        .task("install iperf3 if missing")(installIperf3)
        .build,

      stage("start-iperf3-servers", serverHost)
        .task("start one iperf3 server per expected client, on separate ports")(
          startServers
        )
        .build,

      stage("run-clients-in-parallel", clientHosts*)
        .task("run iperf3 client against tst0 (own dedicated port)")(runClient)
        .build
    )
