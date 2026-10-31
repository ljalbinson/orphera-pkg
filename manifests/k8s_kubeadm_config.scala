// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 14: make kubeadm's idea of the cluster match what
// was changed by hand, and open the control-plane metrics. Needs k8s_oidc.scala
// and k8s_monitoring.scala. Safe to repeat.
//
// 1. The ConfigMap kube-system/kubeadm-config is what `kubeadm upgrade` rebuilds
//    the control-plane manifests from. k8s_oidc.scala added two flags and a
//    volume to the kube-apiserver manifests by hand, so an upgrade would have
//    removed the Keycloak login and the Secret encryption. This playbook adds them
//    to the ConfigMap: apiServer extraArgs authentication-config and
//    encryption-provider-config, and the extraVolume holding /etc/kubernetes/orphera.
//    (The original ConfigMap is saved to /etc/kubernetes/orphera/kubeadm-config.before.json.)
// 2. kubeadm starts the controller manager, the scheduler and etcd with their
//    metrics on 127.0.0.1, where Prometheus cannot reach them. The ConfigMap
//    gets bind-address=0.0.0.0 for the first two and listen-metrics-urls=
//    http://0.0.0.0:2381 for etcd, and the running manifests on tst30, tst31 and
//    tst32 are changed the same way, one node at a time. If the node's etcd and
//    the two components are not healthy again within 3 minutes the three old
//    manifests are put back and the playbook stops. The etcd metrics port is plain
//    HTTP on the node network: acceptable in this lab, to be restricted elsewhere.
//    (New clusters get these settings at `kubeadm init`, in k8s_cluster.scala.)
// 3. `kubeadm upgrade diff` is run on each node for the version it already has:
//    it shows what an upgrade would change in the manifests, and the playbook
//    fails if the diff still touches any of the settings above.
// 4. Pods got the warning "Nameserver limits were exceeded": systemd-resolved lists
//    192.168.1.70 and .71 twice (once from gen_set_dns.scala's global setting, once
//    from the interface's cloud-init network configuration, true on every lab VM),
//    and Kubernetes keeps only three. On the ten Kubernetes nodes kubelet is given
//    its own resolver file, /etc/kubernetes/orphera/resolv.conf, with each
//    server once (made from systemd-resolved's list; it does not follow later DNS
//    changes, so re-run this playbook after changing the servers), through
//    `resolvConf` in /var/lib/kubelet/config.yaml and in the kube-system/kubelet-config
//    ConfigMap. CoreDNS is restarted to pick it up, and a test pod on the host
//    network must show exactly the servers once.
// 5. The Helm release kps is upgraded (same chart version, other values kept)
//    with the etcd, scheduler and controller-manager scrape jobs on; the last
//    task asks Prometheus whether all three are up on all three nodes.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The shape of the stored ClusterConfiguration (kubeadm.k8s.io/v1beta4,
// extraArgs as a list of name/value) is from memory; the script handles the older
// map form too.
object k8s_kubeadm_config extends OrpheraClusterPlaybook:

  private val cpNodes = List("tst30", "tst31", "tst32")
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  private val configMapPython =
    """import sys, json, yaml
cm = json.load(sys.stdin)
cc = yaml.safe_load(cm['data']['ClusterConfiguration'])
DIR = '@@DIR@@'


def add_arg(section, name, value):
    args = section.setdefault('extraArgs', [])
    if isinstance(args, dict):
        args[name] = value
        return
    for a in args:
        if a.get('name') == name:
            a['value'] = value
            return
    args.append({'name': name, 'value': value})


add_arg(cc.setdefault('apiServer', {}), 'authentication-config', DIR + '/authn.yaml')
add_arg(cc['apiServer'], 'encryption-provider-config', DIR + '/encryption.yaml')
vols = cc['apiServer'].setdefault('extraVolumes', [])
if not [v for v in vols if v.get('name') == 'orphera-auth']:
    vols.append({'name': 'orphera-auth', 'hostPath': DIR, 'mountPath': DIR, 'readOnly': True, 'pathType': 'Directory'})
add_arg(cc.setdefault('controllerManager', {}), 'bind-address', '0.0.0.0')
add_arg(cc.setdefault('scheduler', {}), 'bind-address', '0.0.0.0')
add_arg(cc.setdefault('etcd', {}).setdefault('local', {}), 'listen-metrics-urls', 'http://0.0.0.0:2381')
cm['data']['ClusterConfiguration'] = yaml.safe_dump(cc, default_flow_style=False)
print(json.dumps(cm))
"""

  private val configMapScript =
    s"""set -e
       |$kc -n kube-system get configmap kubeadm-config -o json > $stateDir/kubeadm-config.before.json
       |cat > /tmp/kubeadm-config-edit.py <<'PYEOF'
       |${configMapPython.replace("@@DIR@@", stateDir)}
       |PYEOF
       |python3 /tmp/kubeadm-config-edit.py < $stateDir/kubeadm-config.before.json > /tmp/kubeadm-config.after.json
       |rm -f /tmp/kubeadm-config-edit.py
       |$kc replace -f /tmp/kubeadm-config.after.json >/dev/null
       |rm -f /tmp/kubeadm-config.after.json
       |$kc -n kube-system get configmap kubeadm-config -o jsonpath='{.data.ClusterConfiguration}' | grep -E 'authentication-config|encryption-provider-config|bind-address|listen-metrics-urls|orphera-auth'
       |echo "kubeadm-config now carries the Keycloak login, the Secret encryption and the metrics settings"
       |""".stripMargin

  private val kubeletConfigPython =
    """import sys, json, yaml
cm = json.load(sys.stdin)
kc = yaml.safe_load(cm['data']['kubelet'])
kc['resolvConf'] = '@@FILE@@'
cm['data']['kubelet'] = yaml.safe_dump(kc, default_flow_style=False)
print(json.dumps(cm))
"""

  private val resolvFile = s"$stateDir/resolv.conf"

  private val kubeletResolvScript =
    s"""set -e
       |SRC=/run/systemd/resolve/resolv.conf
       |{ grep '^nameserver' $$SRC | awk '!seen[$$2]++' | head -3; grep '^search' $$SRC | head -1; } > $resolvFile.new
       |grep -q '^nameserver' $resolvFile.new || { echo "no nameserver found in $$SRC" >&2; exit 1; }
       |mv $resolvFile.new $resolvFile
       |chmod 644 $resolvFile
       |if grep -q '^resolvConf:' /var/lib/kubelet/config.yaml; then
       |  sed -i 's|^resolvConf:.*|resolvConf: $resolvFile|' /var/lib/kubelet/config.yaml
       |else
       |  echo 'resolvConf: $resolvFile' >> /var/lib/kubelet/config.yaml
       |fi
       |systemctl restart kubelet
       |for I in $$(seq 1 30); do systemctl is-active --quiet kubelet && break; sleep 2; done
       |systemctl is-active --quiet kubelet || { echo "kubelet did not restart" >&2; exit 1; }
       |echo "$$(hostname): kubelet resolver file: $$(grep -c '^nameserver' $resolvFile) nameserver(s)"
       |""".stripMargin

  private val kubeletConfigScript =
    s"""set -e
       |cat > /tmp/kubelet-config-edit.py <<'PYEOF'
       |${kubeletConfigPython.replace("@@FILE@@", resolvFile)}
       |PYEOF
       |$kc -n kube-system get configmap kubelet-config -o json | python3 /tmp/kubelet-config-edit.py > /tmp/kubelet-config.after.json
       |rm -f /tmp/kubelet-config-edit.py
       |$kc replace -f /tmp/kubelet-config.after.json >/dev/null
       |rm -f /tmp/kubelet-config.after.json
       |$kc -n kube-system get configmap kubelet-config -o jsonpath='{.data.kubelet}' | grep resolvConf
       |$kc -n kube-system rollout restart deployment/coredns
       |$kc -n kube-system rollout status deployment/coredns --timeout=180s
       |""".stripMargin

  private val dnsTestScript =
    s"""set -e
       |$kc delete pod dns-probe --ignore-not-found >/dev/null
       |cat <<EOF | $kc apply -f -
       |apiVersion: v1
       |kind: Pod
       |metadata:
       |  name: dns-probe
       |spec:
       |  hostNetwork: true
       |  dnsPolicy: Default
       |  restartPolicy: Never
       |  nodeSelector:
       |    pool: workload
       |  containers:
       |  - name: probe
       |    image: docker.io/library/busybox:stable
       |    command: ["sh", "-c", "grep -c ^nameserver /etc/resolv.conf"]
       |EOF
       |$kc wait --for=jsonpath='{.status.phase}'=Succeeded pod/dns-probe --timeout=180s
       |N=$$($kc logs dns-probe)
       |$kc delete pod dns-probe --wait=false >/dev/null
       |[ "$$N" = "2" ] || { echo "a host-network pod sees $$N nameservers, expected 2" >&2; exit 1; }
       |echo "a host-network pod sees exactly 2 nameservers"
       |""".stripMargin

  private val patchPython =
    """import yaml
M = '/etc/kubernetes/manifests/'


def patch(name, old, new):
    path = M + name
    d = yaml.safe_load(open(path))
    cmd = d['spec']['containers'][0]['command']
    if new in cmd:
        print(name + ': already ' + new)
        return
    if old not in cmd:
        raise SystemExit(name + ': does not contain ' + old + ': ' + ' '.join(cmd))
    cmd[cmd.index(old)] = new
    yaml.safe_dump(d, open(M + '.' + name + '.new', 'w'), default_flow_style=False)
    print(name + ': ' + old + ' -> ' + new)


patch('kube-controller-manager.yaml', '--bind-address=127.0.0.1', '--bind-address=0.0.0.0')
patch('kube-scheduler.yaml', '--bind-address=127.0.0.1', '--bind-address=0.0.0.0')
patch('etcd.yaml', '--listen-metrics-urls=http://127.0.0.1:2381', '--listen-metrics-urls=http://0.0.0.0:2381')
"""

  private val patchScript =
    s"""set -e
       |M=/etc/kubernetes/manifests
       |mkdir -p $stateDir/manifests-before-metrics
       |for F in kube-controller-manager kube-scheduler etcd; do
       |  [ -f $stateDir/manifests-before-metrics/$$F.yaml ] || cp $$M/$$F.yaml $stateDir/manifests-before-metrics/$$F.yaml
       |done
       |cat > /tmp/patch-metrics.py <<'PYEOF'
       |$patchPython
       |PYEOF
       |python3 /tmp/patch-metrics.py
       |rm -f /tmp/patch-metrics.py
       |# etcd goes last: the control plane components restart first, then etcd on this node
       |for F in kube-controller-manager kube-scheduler etcd; do
       |  [ -f $$M/.$$F.yaml.new ] && mv $$M/.$$F.yaml.new $$M/$$F.yaml && sleep 5
       |done
       |healthy() {
       |  ss -ltn 'sport = :10257' | grep -qE '(\\*|0\\.0\\.0\\.0):10257' &&
       |  ss -ltn 'sport = :10259' | grep -qE '(\\*|0\\.0\\.0\\.0):10259' &&
       |  curl -s --max-time 3 http://{{cluster_ip}}:2381/health | grep -q '"health":"true"' &&
       |  [ "$$(curl -sk --max-time 3 https://{{cluster_ip}}:6443/livez)" = "ok" ]
       |}
       |for I in $$(seq 1 36); do healthy && { echo "$$(hostname): controller manager, scheduler and etcd serve their metrics on the node address"; exit 0; }; sleep 5; done
       |echo "$$(hostname): not healthy within 3 minutes; restoring the old manifests" >&2
       |for F in kube-controller-manager kube-scheduler etcd; do cp $stateDir/manifests-before-metrics/$$F.yaml $$M/$$F.yaml; done
       |exit 1
       |""".stripMargin

  // kubeadm writes arguments and volume mounts sorted, the hand edits appended them, so the
  // diff shows each setting once removed and once added at another position. A setting is
  // kept when it is added as often as it is removed; it is lost when the counts differ.
  // (kubeadm also drops `host: 127.0.0.1` from the probes of components that no longer
  // listen on 127.0.0.1 only; that is expected and not checked.)
  private val diffPython =
    """import collections, re, sys
pat = re.compile(r'authentication-config|encryption-provider-config|admission-control-config-file|bind-address|listen-metrics-urls|orphera-auth')
plus, minus = collections.Counter(), collections.Counter()
for line in open('/tmp/upgrade-diff.txt'):
    line = line.rstrip('\n')
    if line[:3] in ('+++', '---') or not line or line[0] not in '+-':
        continue
    text = line[1:].strip()
    if pat.search(text):
        (plus if line[0] == '+' else minus)[text] += 1
bad = [t for t in set(plus) | set(minus) if plus[t] != minus[t]]
for t in sorted(bad):
    print('would change: %r (added %d, removed %d)' % (t, plus[t], minus[t]))
sys.exit(1 if bad else 0)
"""

  private val diffScript =
    s"""set -e
       |VER=$$(kubeadm version -o short)
       |kubeadm upgrade diff "$$VER" > /tmp/upgrade-diff.txt 2>&1 || true
       |cat > /tmp/diff-check.py <<'PYEOF'
       |$diffPython
       |PYEOF
       |if ! python3 /tmp/diff-check.py; then
       |  echo "$$(hostname): an upgrade would add or remove the settings above" >&2
       |  rm -f /tmp/upgrade-diff.txt /tmp/diff-check.py
       |  exit 1
       |fi
       |rm -f /tmp/upgrade-diff.txt /tmp/diff-check.py
       |echo "$$(hostname): an upgrade to $$VER would keep the Keycloak login, the encryption and the metrics settings"
       |""".stripMargin

  private val helmScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |CHART=$$(helm list -n monitoring -f '^kps$$' -o json | python3 -c 'import sys,json; r=json.load(sys.stdin); print(r[0]["chart"] if r else "")')
       |[ -n "$$CHART" ] || { echo "the Helm release kps is not installed; run k8s_monitoring.scala (it now has these jobs on)" >&2; exit 1; }
       |VER=$${CHART#kube-prometheus-stack-}
       |echo "upgrading kps ($$CHART) with the etcd, scheduler and controller-manager jobs on"
       |helm upgrade kps prometheus-community/kube-prometheus-stack --version "$$VER" --namespace monitoring --reuse-values \\
       |  --set kubeEtcd.enabled=true --set kubeScheduler.enabled=true --set kubeControllerManager.enabled=true --wait --timeout 10m
       |if [ -f $stateDir/monitoring-values.yaml ]; then
       |  python3 - <<'PYEOF'
       |p = '$stateDir/monitoring-values.yaml'
       |s = open(p).read()
       |for k in ('kubeEtcd', 'kubeScheduler', 'kubeControllerManager'):
       |    s = s.replace(k + ':\\n  enabled: false', k + ':\\n  enabled: true')
       |open(p, 'w').write(s)
       |PYEOF
       |fi
       |""".stripMargin

  private val testScript =
    s"""FAIL=0
       |q() {
       |  $kc get --raw "/api/v1/namespaces/monitoring/services/kps-prometheus:9090/proxy/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$1")" \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
       |}
       |for J in kube-etcd kube-scheduler kube-controller-manager; do
       |  GOT=0
       |  for I in $$(seq 1 40); do GOT=$$(q "count(up{job=\\"$$J\\"} == 1)" 2>/dev/null || echo 0); [ "$$GOT" -ge 3 ] && break; sleep 5; done
       |  if [ "$$GOT" -ge 3 ]; then echo "ok    $$J targets up: $$GOT"; else echo "FAIL  $$J targets up: $$GOT (wanted 3)" >&2; FAIL=1; fi
       |done
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  private def patchStage(n: String) =
    stage(s"metrics-$n", n)
      .task(
        "serve controller manager, scheduler and etcd metrics on the node address"
      )(
        Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-kubeadm-config")(
      stage("persist-in-kubeadm-config", "tst30")
        .task("add the hand-made settings to the kubeadm-config ConfigMap")(
          Task
            .RunCommand(List("sh", "-c", configMapScript), timeoutSeconds = 120)
        )
        .build,
      patchStage("tst30"),
      patchStage("tst31"),
      patchStage("tst32"),
      stage("check-upgrade-diff", cpNodes*)
        .task("kubeadm upgrade diff keeps the settings")(
          Task.RunCommand(List("sh", "-c", diffScript), timeoutSeconds = 300)
        )
        .build,
      stage("kubelet-resolver", (30 to 39).map(n => s"tst$n")*)
        .task("give kubelet a resolver file with each server once")(
          Task.RunCommand(
            List("sh", "-c", kubeletResolvScript),
            timeoutSeconds = 180
          )
        )
        .build,
      stage("kubelet-config-and-dns-test", "tst30")
        .task("record it in kubelet-config, restart CoreDNS")(
          Task.RunCommand(
            List("sh", "-c", kubeletConfigScript),
            timeoutSeconds = 300
          )
        )
        .task("a host-network pod sees two nameservers")(
          Task.RunCommand(List("sh", "-c", dnsTestScript), timeoutSeconds = 300)
        )
        .build,
      stage("scrape-control-plane", "tst30")
        .task("turn on the three scrape jobs")(
          Task.RunCommand(List("sh", "-c", helmScript), timeoutSeconds = 800)
        )
        .task("Prometheus sees them")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("kubeadm config ready")(
          Task.Debug(
            "kubeadm-config carries the Keycloak login, the Secret encryption and the metrics settings; " +
              "etcd, the scheduler and the controller manager are scraped on all three nodes."
          )
        )
        .build
    )
