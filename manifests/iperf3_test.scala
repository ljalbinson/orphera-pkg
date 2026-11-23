// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object iperf3_test extends OrpheraClusterPlaybook:

  // Runs iperf3 clients on tst1 and tst2 simultaneously against a
  // self-hosted iperf3 server on tst0 — a star topology (not full
  // mesh). tst0 isn't included as a client against itself — that's a
  // loopback test, not a network test.
  //
  // The servers are systemd units (a unit file and a `service` resource
  // each, applied with Task.Apply), not a backgrounded `nohup ... &`
  // process: a task that starts a daemon that is meant to live forever
  // never finishes (the nohup version hit exit=143, SIGTERM, in testing).
  // They used to be transient units made by `systemd-run`, stopped and
  // made again on every run; now a server is started once and left
  // running (it is restarted only if its unit file changes), and it is not
  // enabled, so it does not outlive a reboot. A transient unit of the same
  // name left by an older run is stopped first.
  //
  // A default `iperf3 -s` instance only serves ONE client connection
  // at a time — a second concurrent client gets "error - the server is
  // busy running a test" — so tst0 runs TWO server units, one per
  // client, on separate ports (5201 for tst1, 5202 for tst2), so both
  // client runs can genuinely happen at once.
  val portForClient: Map[String, Int] = Map(
    "tst1" -> 5201,
    "tst2" -> 5202,
    "tst3" -> 5203,
    "tst4" -> 5204,
    "tst5" -> 5205,
    "tst6" -> 5206,
    "tst7" -> 5207
  )
  private val serverHost: String = "tst0"
  private val clientHosts: List[String] = portForClient.keys.toList.sorted

  private val installPlan: ApplyPlan =
    import Resources.*
    ApplyPlan.orFail("install-iperf3", List(pkg("iperf3", updateCache = true)))

  // One unit per client port. The check waits for the server to accept a
  // one-second test from the node itself, and only runs when it does not yet.
  private val serversPlan: ApplyPlan =
    import Resources.*
    val resources = portForClient.values.toList.sorted.flatMap { port =>
      val unitName = s"iperf3-server-$port"
      val retire = command(
        s"stop the transient $unitName unit of an older run",
        s"systemctl stop $unitName 2>/dev/null || true; systemctl reset-failed $unitName 2>/dev/null || true",
        unless = s"[ ! -e /run/systemd/transient/$unitName.service ]"
      )
      val running = service(s"$unitName.service", enabled = None, running = Some(true))
      val unitFile = file(
        s"/etc/systemd/system/$unitName.service",
        s"""[Unit]
           |Description=iperf3 server on port $port (orphera iperf3_test)
           |After=network-online.target
           |
           |[Service]
           |ExecStart=/usr/bin/iperf3 -s -p $port
           |Restart=on-failure
           |""".stripMargin,
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(retire).notifies(running)
      val answers = command(
        s"iperf3 server on port $port answers",
        s"""for i in $$(seq 1 10); do
           |  iperf3 -c 127.0.0.1 -p $port -t 1 >/dev/null 2>&1 && { echo "iperf3 server on port $port is up"; exit 0; }
           |  sleep 1
           |done
           |echo "iperf3 server on port $port failed to start — check: systemctl status $unitName" >&2
           |exit 1""".stripMargin,
        unless = s"iperf3 -c 127.0.0.1 -p $port -t 1 >/dev/null 2>&1"
      ).after(running.after(unitFile, retire))
      List(retire, unitFile, running.after(unitFile, retire), answers)
    }
    ApplyPlan.orFail("iperf3-servers", resources)

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
        .task("install iperf3 if missing")(Task.Apply(installPlan))
        .build,

      stage("start-iperf3-servers", serverHost)
        .task("start one iperf3 server per expected client, on separate ports")(
          Task.Apply(serversPlan)
        )
        .build,

      stage("run-clients-in-parallel", clientHosts*)
        .task("run iperf3 client against tst0 (own dedicated port)")(runClient)
        .build
    )
