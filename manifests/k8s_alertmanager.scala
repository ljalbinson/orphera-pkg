// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 12 (addition): Alertmanager receivers. Needs
// k8s_monitoring.scala (the Helm release `kps` in the namespace monitoring). Safe to
// repeat. This changes only the Alertmanager configuration of the existing release
// (`helm upgrade --reuse-values` with the same chart version); nothing else moves.
//
// Alerts are sent by e-mail through the relay mail.ljalbinson.com, from
// alert@ljalbinson.com to lawrence@ljalbinson.com. Settings, by environment variable
// where the playbook is started (the defaults are in brackets):
//   ORPHERA_ALERT_SMTP_HOST   relay host        [mail.ljalbinson.com]
//   ORPHERA_ALERT_SMTP_PORT   relay port        [25]
//   ORPHERA_ALERT_SMTP_HELO   name Alertmanager gives in HELO [alertmanager.k8s.ljalbinson.com]
//                             (the relay refuses "localhost", Alertmanager's default; the
//                             name should resolve in DNS: *.k8s.ljalbinson.com does)
//   ORPHERA_ALERT_FROM        sender            [alert@ljalbinson.com]
//   ORPHERA_ALERT_TO          recipient         [lawrence@ljalbinson.com]
//   ORPHERA_ALERT_REQUIRE_TLS 1 = refuse a relay that does not offer STARTTLS [0]
// There is no SMTP login: the relay is expected to accept mail from the cluster nodes'
// network. (Alertmanager upgrades to TLS by itself when the relay offers STARTTLS.)
//
// Routing: every alert goes to the receiver `email`, grouped by alert name and
// namespace (first mail after 30 s, more for the same group every 5 min, a reminder
// every 12 h while it keeps firing), and a resolved mail follows. Watchdog (always
// firing, by design) and InfoInhibitor go to the receiver `null`. A critical alert
// silences the warning alert with the same name and namespace.
//
// The test sends a warning alert named OrpheraTestAlert to Alertmanager (through the
// API server's service proxy), then reads Alertmanager's own counters: the e-mail
// notifications delivered (attempts minus failures) must go up. Expect one mail with the
// subject "[FIRING:1] OrpheraTestAlert ..." and, about 5 minutes later when the alert
// expires, a "[RESOLVED]" one. The namespace monitoring must allow the relay: its
// network policies include `world` (egress outside the cluster).
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_alertmanager extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ns = "monitoring"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  private def env(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private val smtpHost = env("ORPHERA_ALERT_SMTP_HOST", "mail.ljalbinson.com")
  private val smtpPort = env("ORPHERA_ALERT_SMTP_PORT", "25")
  private val smtpHelo = env("ORPHERA_ALERT_SMTP_HELO", "alertmanager.k8s.ljalbinson.com")
  private val from = env("ORPHERA_ALERT_FROM", "alert@ljalbinson.com")
  private val to = env("ORPHERA_ALERT_TO", "lawrence@ljalbinson.com")
  private val requireTls = if env("ORPHERA_ALERT_REQUIRE_TLS", "0") == "1" then "true" else "false"

  private val applyScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |$kc -n $ns get statefulset alertmanager-kps-alertmanager >/dev/null \\
       |  || { echo "Alertmanager of the release kps not found in $ns; run k8s_monitoring.scala first" >&2; exit 1; }
       |CHART=$$(helm list -n $ns --filter '^kps$$' -o json | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["chart"])')
       |VER=$${CHART#kube-prometheus-stack-}
       |echo "release kps uses chart $$VER"
       |cat > $stateDir/alertmanager-values.yaml <<EOF
       |alertmanager:
       |  config:
       |    global:
       |      resolve_timeout: 5m
       |      smtp_smarthost: '$smtpHost:$smtpPort'
       |      smtp_from: '$from'
       |      smtp_hello: '$smtpHelo'
       |      smtp_require_tls: $requireTls
       |    route:
       |      receiver: email
       |      group_by: [alertname, namespace]
       |      group_wait: 30s
       |      group_interval: 5m
       |      repeat_interval: 12h
       |      routes:
       |      - receiver: 'null'
       |        matchers:
       |        - alertname =~ "Watchdog|InfoInhibitor"
       |    inhibit_rules:
       |    - source_matchers: ['severity = critical']
       |      target_matchers: ['severity = warning']
       |      equal: [alertname, namespace]
       |    receivers:
       |    - name: 'null'
       |    - name: email
       |      email_configs:
       |      - to: '$to'
       |        send_resolved: true
       |EOF
       |helm upgrade kps prometheus-community/kube-prometheus-stack --version "$$VER" \\
       |  --namespace $ns --reuse-values -f $stateDir/alertmanager-values.yaml --wait --timeout 10m
       |$kc -n $ns rollout status statefulset/alertmanager-kps-alertmanager --timeout=300s
       |echo "Alertmanager configured: e-mail to $to through $smtpHost:$smtpPort"
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |AM="/api/v1/namespaces/$ns/services/kps-alertmanager:9093/proxy"
       |counts() {  # prints: sent failed (e-mail integration, from Alertmanager's own metrics)
       |  $kc get --raw "$$AM/metrics" | python3 -c '
       |import sys,re
       |sent=failed=0
       |for l in sys.stdin:
       |    if l.startswith("alertmanager_notifications_total{") and "integration=\\"email\\"" in l: sent+=int(float(l.rsplit(" ",1)[1]))
       |    if l.startswith("alertmanager_notifications_failed_total{") and "integration=\\"email\\"" in l: failed+=int(float(l.rsplit(" ",1)[1]))
       |print(sent,failed)'
       |}
       |# the new configuration is loaded
       |CFG=""
       |for I in $$(seq 1 30); do
       |  CFG=$$($kc get --raw "$$AM/api/v2/status" 2>/dev/null | python3 -c 'import sys,json; c=json.load(sys.stdin)["config"]["original"]; print("loaded" if "$smtpHost:$smtpPort" in c and "$to" in c else "")' 2>/dev/null || true)
       |  [ "$$CFG" = "loaded" ] && break
       |  sleep 5
       |done
       |if [ "$$CFG" = "loaded" ]; then echo "ok    Alertmanager runs the new configuration"; else echo "FAIL  Alertmanager does not show the new configuration" >&2; FAIL=1; fi
       |read SENT0 FAILED0 <<EOF
       |$$(counts)
       |EOF
       |END=$$(date -u -d '+5 minutes' +%Y-%m-%dT%H:%M:%SZ)
       |cat > /tmp/am-test.json <<EOF
       |[{"labels":{"alertname":"OrpheraTestAlert","severity":"warning","namespace":"$ns"},
       |  "annotations":{"summary":"Test alert from k8s_alertmanager.scala - nothing is wrong"},
       |  "endsAt":"$$END"}]
       |EOF
       |# kubectl create --raw sends no JSON content type (415), so post through kubectl proxy with curl
       |$kc proxy --port=18001 >/dev/null 2>&1 &
       |PROXY=$$!
       |sleep 3
       |CODE=$$(curl -s -o /tmp/am-test.out -w '%{http_code}' -X POST -H 'Content-Type: application/json' \\
       |  --data @/tmp/am-test.json "http://127.0.0.1:18001$$AM/api/v2/alerts" || true)
       |kill $$PROXY 2>/dev/null || true
       |if [ "$$CODE" != "200" ]; then echo "FAIL  posting the test alert returned HTTP $$CODE: $$(cat /tmp/am-test.out)" >&2; rm -f /tmp/am-test.json /tmp/am-test.out; exit 1; fi
       |rm -f /tmp/am-test.json /tmp/am-test.out
       |echo "test alert sent; waiting for the notification (group_wait is 30 s)"
       |SENT=$$SENT0; FAILED=$$FAILED0
       |# notifications_total counts attempts, failed ones too: a delivery is an attempt that did not fail
       |for I in $$(seq 1 60); do
       |  read SENT FAILED <<EOF
       |$$(counts)
       |EOF
       |  [ $$((SENT - FAILED)) -gt $$((SENT0 - FAILED0)) ] && break
       |  sleep 5
       |done
       |if [ $$((SENT - FAILED)) -gt $$((SENT0 - FAILED0)) ]; then
       |  echo "ok    e-mail delivered: attempts $$SENT0 -> $$SENT, failed $$FAILED0 -> $$FAILED"
       |else
       |  echo "FAIL  no e-mail was delivered: attempts $$SENT0 -> $$SENT, failed $$FAILED0 -> $$FAILED" >&2
       |  $kc -n $ns logs statefulset/alertmanager-kps-alertmanager -c alertmanager --tail=40 2>&1 | grep -i -E 'err|fail' | tail -5 >&2 || true
       |  FAIL=1
       |fi
       |[ "$$FAIL" -eq 0 ] || exit 1
       |echo "ok    the relay accepted the mail; check the inbox of $to for [FIRING:1] OrpheraTestAlert"
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-alertmanager")(
      stage("alertmanager-config", node)
        .task("set the Alertmanager receivers (helm upgrade --reuse-values)")(
          Task.RunCommand(List("sh", "-c", applyScript), timeoutSeconds = 1000)
        )
        .build,
      stage("alertmanager-test", node)
        .task("send a test alert and check the e-mail counters")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("alertmanager ready")(
          Task.Debug(
            s"Alertmanager sends alerts by e-mail to $to through $smtpHost:$smtpPort; a test alert was sent, so check the inbox."
          )
        )
        .build
    )
