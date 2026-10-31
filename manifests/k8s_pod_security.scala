// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 18: Pod Security. Needs k8s_oidc.scala (which put the
// directory /etc/kubernetes/orphera into the kube-apiserver pods) and
// k8s_kubeadm_config.scala. Safe to repeat.
//
// The built-in PodSecurity admission plugin is given a cluster-wide default
// (file /etc/kubernetes/orphera/admission.yaml, passed to each kube-apiserver with
// --admission-control-config-file):
//   enforce  baseline    pods that use privileged mode, host namespaces, host paths,
//                        added capabilities and similar are refused
//   audit    restricted  violations of the stricter standard are written to the audit log
//   warn     restricted  and returned to the client as a warning
//   exempt   the namespace kube-system (Cilium, kube-proxy replacement, etcd, the control plane)
// A namespace with its own pod-security.kubernetes.io/enforce label overrides the
// default. Two namespaces hold software that needs more than baseline and are
// labelled `privileged`: ceph-csi-rbd (the node plugin mounts volumes) and
// monitoring (node-exporter uses the host network, PID namespace and paths).
// Running pods are not touched; the rules apply when a pod is created or changed.
//
// Before the API servers are changed, every other namespace is checked with a
// server-side dry run of `enforce=baseline`: if a pod already running there would
// be refused (it would fail when recreated), the playbook lists it and stops, and
// nothing has been changed. The three kube-apiserver manifests are changed one
// node at a time, like k8s_oidc.scala does (and the old manifest is put back if the
// pod is not healthy within 3 minutes); the flag is added to the kubeadm-config
// ConfigMap, so `kubeadm upgrade` keeps it.
//
// The last stage tries things on the API with server-side dry runs: a privileged
// pod, a pod with a host path and a pod on the host network are refused in
// `default`; a plain pod is accepted but warned about; a hardened pod gets no
// warning; a privileged pod is accepted in kube-system and in a namespace labelled
// privileged; and every pod that was running before still is.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. This does NOT add network policies; that is the next step.
object k8s_pod_security extends OrpheraClusterPlaybook:

  private val cpNodes = List("tst30", "tst31", "tst32")
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val privilegedNamespaces = List("ceph-csi-rbd", "monitoring")
  private val exemptNamespaces = List("kube-system")
  private val flag = "admission-control-config-file"

  private val configScript =
    s"""set -e
       |cd $stateDir
       |umask 022
       |cat > admission.yaml <<EOF
       |apiVersion: apiserver.config.k8s.io/v1
       |kind: AdmissionConfiguration
       |plugins:
       |- name: PodSecurity
       |  configuration:
       |    apiVersion: pod-security.admission.config.k8s.io/v1
       |    kind: PodSecurityConfiguration
       |    defaults:
       |      enforce: baseline
       |      enforce-version: latest
       |      audit: restricted
       |      audit-version: latest
       |      warn: restricted
       |      warn-version: latest
       |    exemptions:
       |      usernames: []
       |      runtimeClasses: []
       |      namespaces: [${exemptNamespaces.mkString(", ")}]
       |EOF
       |chmod 644 admission.yaml
       |echo "admission.yaml written"
       |""".stripMargin

  // Label the namespaces that need privileged, then see which other namespaces
  // hold pods that baseline would refuse.
  private val precheckScript =
    s"""set -e
       |for N in ${privilegedNamespaces.mkString(" ")}; do
       |  if $kc get namespace "$$N" >/dev/null 2>&1; then
       |    $kc label namespace "$$N" pod-security.kubernetes.io/enforce=privileged --overwrite >/dev/null
       |    echo "namespace $$N labelled privileged"
       |  else
       |    echo "namespace $$N does not exist (skipped)"
       |  fi
       |done
       |BAD=0
       |for N in $$($kc get namespaces -o jsonpath='{.items[*].metadata.name}'); do
       |  case " ${exemptNamespaces.mkString(" ")} ${privilegedNamespaces
        .mkString(" ")} " in *" $$N "*) continue ;; esac
       |  # a namespace that already has its own enforce label keeps it
       |  L=$$($kc get namespace "$$N" -o jsonpath='{.metadata.labels.pod-security\\.kubernetes\\.io/enforce}')
       |  [ -z "$$L" ] || continue
       |  OUT=$$($kc label --dry-run=server --overwrite namespace "$$N" pod-security.kubernetes.io/enforce=baseline 2>&1 || true)
       |  if echo "$$OUT" | grep -q 'violate the new PodSecurity'; then
       |    echo "namespace $$N: pods that baseline would refuse:" >&2
       |    echo "$$OUT" | grep -i 'violate\\|Warning' >&2
       |    BAD=1
       |  fi
       |done
       |[ "$$BAD" -eq 0 ] || { echo "label the namespaces above (kubectl label namespace NAME pod-security.kubernetes.io/enforce=privileged) or fix their pods, then run this again; nothing has been changed" >&2; exit 1; }
       |$kc get pods -A --no-headers | awk '{print $$4}' | sort | uniq -c > $stateDir/pods-before-pod-security.txt
       |cat $stateDir/pods-before-pod-security.txt
       |echo "no running pod would be refused by baseline"
       |""".stripMargin

  private val patchPython =
    """import yaml
m = '/etc/kubernetes/manifests/kube-apiserver.yaml'
d = yaml.safe_load(open(m))
c = d['spec']['containers'][0]
c['command'] += ['--@@FLAG@@=@@DIR@@/admission.yaml']
yaml.safe_dump(d, open('/etc/kubernetes/manifests/.kube-apiserver.yaml.new', 'w'), default_flow_style=False)
"""

  private val patchScript =
    s"""set -e
       |M=/etc/kubernetes/manifests/kube-apiserver.yaml
       |if grep -q -- '--$flag' $$M; then echo "$$(hostname): kube-apiserver already has the flag"; exit 0; fi
       |[ -s $stateDir/admission.yaml ] || { echo "$stateDir/admission.yaml missing" >&2; exit 1; }
       |grep -q 'orphera-auth' $$M || { echo "the kube-apiserver pod does not mount $stateDir; run k8s_oidc.scala first" >&2; exit 1; }
       |cp $$M $stateDir/kube-apiserver.yaml.pre-pod-security
       |cat > /tmp/patch-apiserver.py <<'PYEOF'
       |${patchPython.replace("@@DIR@@", stateDir).replace("@@FLAG@@", flag)}
       |PYEOF
       |python3 /tmp/patch-apiserver.py
       |rm -f /tmp/patch-apiserver.py
       |mv /etc/kubernetes/manifests/.kube-apiserver.yaml.new $$M
       |healthy() {
       |  pgrep -af kube-apiserver | grep -q -- '--$flag' || return 1
       |  [ "$$(curl -sk --max-time 3 https://{{cluster_ip}}:6443/livez)" = "ok" ]
       |}
       |for I in $$(seq 1 36); do healthy && { echo "$$(hostname): kube-apiserver restarted with the Pod Security defaults"; exit 0; }; sleep 5; done
       |echo "$$(hostname): kube-apiserver did not become healthy within 3 minutes; restoring the old manifest" >&2
       |cp $stateDir/kube-apiserver.yaml.pre-pod-security $$M
       |for I in $$(seq 1 36); do
       |  [ "$$(curl -sk --max-time 3 https://{{cluster_ip}}:6443/livez)" = "ok" ] && pgrep -af kube-apiserver | grep -qv -- '--$flag' && break
       |  sleep 5
       |done
       |crictl ps -a --name kube-apiserver 2>/dev/null | head -5 >&2 || true
       |exit 1
       |""".stripMargin

  // After the last API server restarts, haproxy needs a few seconds to stop
  // sending requests to it, so wait for five successful answers in a row.
  private val waitApiScript =
    s"""for I in $$(seq 1 40); do
       |  OKS=0
       |  for J in 1 2 3 4 5; do $kc get --raw /readyz >/dev/null 2>&1 && OKS=$$((OKS+1)); done
       |  [ "$$OKS" -eq 5 ] && break
       |  sleep 3
       |done
       |[ "$$OKS" -eq 5 ] || { echo "the API server behind the VIP is not stable" >&2; exit 1; }
       |""".stripMargin

  private val configMapPython =
    """import sys, json, yaml
cm = json.load(sys.stdin)
cc = yaml.safe_load(cm['data']['ClusterConfiguration'])
args = cc.setdefault('apiServer', {}).setdefault('extraArgs', [])
value = '@@DIR@@/admission.yaml'
if isinstance(args, dict):
    args['@@FLAG@@'] = value
else:
    for a in args:
        if a.get('name') == '@@FLAG@@':
            a['value'] = value
            break
    else:
        args.append({'name': '@@FLAG@@', 'value': value})
cm['data']['ClusterConfiguration'] = yaml.safe_dump(cc, default_flow_style=False)
print(json.dumps(cm))
"""

  private val persistScript =
    s"""set -e
       |$waitApiScript
       |$kc -n kube-system get configmap kubeadm-config -o json > $stateDir/kubeadm-config.before-pod-security.json
       |cat > /tmp/kubeadm-config-edit.py <<'PYEOF'
       |${configMapPython
        .replace("@@DIR@@", stateDir)
        .replace("@@FLAG@@", flag)}
       |PYEOF
       |python3 /tmp/kubeadm-config-edit.py < $stateDir/kubeadm-config.before-pod-security.json > /tmp/kubeadm-config.after.json
       |rm -f /tmp/kubeadm-config-edit.py
       |$kc replace -f /tmp/kubeadm-config.after.json >/dev/null
       |rm -f /tmp/kubeadm-config.after.json
       |$kc -n kube-system get configmap kubeadm-config -o jsonpath='{.data.ClusterConfiguration}' | grep -E '$flag'
       |echo "kubeadm-config now carries the Pod Security defaults"
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |$waitApiScript
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |pod() {  # namespace, name, spec fragment; server-side dry run, prints the API's reply
       |  printf 'apiVersion: v1\\nkind: Pod\\nmetadata:\\n  name: %s\\n  namespace: %s\\nspec:\\n  containers:\\n  - name: c\\n    image: registry.k8s.io/pause:3.10\\n%s' "$$2" "$$1" "$$3" \\
       |    | $kc apply --dry-run=server -f - 2>&1 || true
       |}
       |# --- the three refusals in default
       |OUT=$$(pod default t-priv "$$(printf '    securityContext:\\n      privileged: true\\n')")
       |case "$$OUT" in *'violates PodSecurity "baseline:latest"'*) chk "a privileged pod is refused in default" a a ;; *) chk "a privileged pod is refused in default" refused "$$OUT" ;; esac
       |OUT=$$(pod default t-hostpath "$$(printf '  volumes:\\n  - name: h\\n    hostPath:\\n      path: /etc\\n')")
       |case "$$OUT" in *'violates PodSecurity "baseline:latest"'*) chk "a pod with a host path is refused in default" a a ;; *) chk "a pod with a host path is refused in default" refused "$$OUT" ;; esac
       |OUT=$$(pod default t-hostnet "$$(printf '  hostNetwork: true\\n')")
       |case "$$OUT" in *'violates PodSecurity "baseline:latest"'*) chk "a pod on the host network is refused in default" a a ;; *) chk "a pod on the host network is refused in default" refused "$$OUT" ;; esac
       |# --- accepted, with and without a warning
       |OUT=$$(pod default t-plain "")
       |case "$$OUT" in *'would violate PodSecurity "restricted:latest"'*) chk "a plain pod is accepted but warned about" a a ;; *) chk "a plain pod is accepted but warned about" warning "$$OUT" ;; esac
       |case "$$OUT" in *Forbidden*|*forbidden*) chk "a plain pod is not refused" accepted "$$OUT" ;; esac
       |OUT=$$(pod default t-hard "$$(printf '    securityContext:\\n      allowPrivilegeEscalation: false\\n      runAsNonRoot: true\\n      runAsUser: 1000\\n      capabilities:\\n        drop: ["ALL"]\\n      seccompProfile:\\n        type: RuntimeDefault\\n')")
       |case "$$OUT" in *'created (server dry run)'*) case "$$OUT" in *'would violate'*) chk "a hardened pod gets no warning" none "$$OUT" ;; *) chk "a hardened pod gets no warning" a a ;; esac ;; *) chk "a hardened pod is accepted" accepted "$$OUT" ;; esac
       |# --- exemption and the namespace label
       |OUT=$$(pod kube-system t-priv "$$(printf '    securityContext:\\n      privileged: true\\n')")
       |case "$$OUT" in *'created (server dry run)'*) chk "a privileged pod is accepted in kube-system" a a ;; *) chk "a privileged pod is accepted in kube-system" accepted "$$OUT" ;; esac
       |$kc create namespace psa-test --dry-run=client -o yaml | $kc apply -f - >/dev/null
       |$kc label namespace psa-test pod-security.kubernetes.io/enforce=privileged --overwrite >/dev/null
       |OUT=$$(pod psa-test t-priv "$$(printf '    securityContext:\\n      privileged: true\\n')")
       |case "$$OUT" in *'created (server dry run)'*) chk "a privileged pod is accepted in a namespace labelled privileged" a a ;; *) chk "a privileged pod is accepted in a namespace labelled privileged" accepted "$$OUT" ;; esac
       |$kc delete namespace psa-test --wait=false >/dev/null
       |# --- the API servers and the running pods
       |N=$$($kc -n kube-system get pods -l component=kube-apiserver -o jsonpath='{range .items[*]}{.spec.containers[0].command}{"\\n"}{end}' | grep -c -- '--$flag=')
       |chk "all three kube-apiserver pods carry the flag" 3 "$$N"
       |NOTRUN=$$($kc get pods -A --no-headers --field-selector=status.phase!=Running,status.phase!=Succeeded | wc -l)
       |chk "no pod is stuck outside Running or Succeeded" 0 "$$NOTRUN"
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-pod-security")(
      stage("python-yaml", cpNodes*)
        .task("install python3-yaml")(
          Task.Install(packages = List("python3-yaml"), updateCache = true)
        )
        .build,
      stage("write-admission-config", "tst30")
        .task("write admission.yaml")(
          Task.RunCommand(List("sh", "-c", configScript), timeoutSeconds = 60)
        )
        .build,
      stage("distribute-admission-config", "tst31", "tst32")
        .task("copy admission.yaml")(
          Task.DistributeFile(
            "tst30",
            s"$stateDir/admission.yaml",
            s"$stateDir/admission.yaml",
            "root",
            "root",
            420
          )
        )
        .build,
      stage("check-running-pods", "tst30")
        .task("label privileged namespaces, find pods baseline would refuse")(
          Task
            .RunCommand(List("sh", "-c", precheckScript), timeoutSeconds = 300)
        )
        .build,
      stage("apiserver-tst30", "tst30")
        .task("add the flag to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("apiserver-tst31", "tst31")
        .task("add the flag to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("apiserver-tst32", "tst32")
        .task("add the flag to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("persist-in-kubeadm-config", "tst30")
        .task("add the flag to the kubeadm-config ConfigMap")(
          Task.RunCommand(List("sh", "-c", persistScript), timeoutSeconds = 60)
        )
        .build,
      stage("test-pod-security", "tst30")
        .task(
          "try privileged, host-path, host-network, plain and hardened pods"
        )(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 300)
        )
        .task("pod security ready")(
          Task.Debug(
            s"Pod Security: baseline enforced and restricted warned by default, kube-system exempt, ${privilegedNamespaces.mkString(" and ")} privileged. Network policies are the next step."
          )
        )
        .build
    )
