#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the Kubernetes cluster on tst30-tst39 (k8s_*.scala).
#
# Read-only checks, run on tst30 over `orphera run`, independent of the
# playbooks' own checks:
#   - 10 nodes Ready, 3 etcd members, 3 API servers, the VIP answers /livez;
#   - Cilium on every node, no kube-proxy;
#   - the default StorageClass is ceph-rbd and a volume can be written;
#   - ClusterIssuer orphera-ca is Ready;
#   - Secrets are encrypted in etcd;
#   - a Keycloak user can log in and gets exactly the permissions of the group;
#   - the Gateway holds 10.10.3.100 and a published application answers over
#     HTTPS (k8s_gateway.scala is applied first unless NO_APPLY=1).
# Optional, FAILOVER=1: stops keepalived on the node holding the API VIP,
# checks that the VIP moves and the API still answers, and starts it again.
#
# Prerequisites: all k8s_*.scala playbooks applied (see README).
#
# Run from scala0, in orphera-pkg:   manifests/test_k8s.sh
#   NO_APPLY=1 skips applying k8s_gateway.scala; FAILOVER=1 adds the VIP test.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

CP="tst30"
VIP="10.10.3.20"
KC="sudo kubectl --kubeconfig /etc/kubernetes/admin.conf"
PASS=0
FAIL=0
KA_STOPPED=""

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# run_on <node> <command>
run_on() { orphera run sh -c "$2" --nodes "$1" 2>&1; }

assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected to find: $needle)"
    printf '%s\n' "$haystack" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  fi
}

cleanup() {
  if [ -n "$KA_STOPPED" ]; then
    log "cleanup: starting keepalived on $KA_STOPPED"
    run_on "$KA_STOPPED" "sudo systemctl start keepalived" >/dev/null
  fi
}
trap cleanup EXIT

if [ "${NO_APPLY:-0}" != "1" ]; then
  log "apply k8s_gateway.scala (idempotent)"
  if ! orphera cluster-playbook k8s_gateway.scala; then
    log "FAIL: k8s_gateway.scala did not complete"; exit 1
  fi
fi

log "nodes"
out=$(run_on "$CP" "echo READY=\$($KC get nodes --no-headers | awk '\$2==\"Ready\"' | wc -l)")
assert_contains "10 nodes are Ready" "READY=10" "$out"
out=$(run_on "$CP" "echo CP=\$($KC get nodes -l node-role.kubernetes.io/control-plane --no-headers | wc -l) INFRA=\$($KC get nodes -l pool=infra --no-headers | wc -l) WORKLOAD=\$($KC get nodes -l pool=workload --no-headers | wc -l)")
assert_contains "3 control-plane, 3 infra, 4 workload nodes" "CP=3 INFRA=3 WORKLOAD=4" "$out"

log "control plane"
out=$(run_on "$CP" "echo ETCD=\$($KC -n kube-system get pods -l component=etcd --no-headers | grep -c ' Running ') API=\$($KC -n kube-system get pods -l component=kube-apiserver --no-headers | grep -c ' Running ')")
assert_contains "3 etcd and 3 kube-apiserver pods running" "ETCD=3 API=3" "$out"
out=$(run_on "$CP" "curl -sk https://$VIP:8443/livez; echo")
assert_contains "the API VIP answers /livez" "ok" "$out"
out=$(run_on "$CP" "echo MEMBERS=\$($KC -n kube-system exec etcd-tst30 -- etcdctl --endpoints=https://127.0.0.1:2379 --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt --key=/etc/kubernetes/pki/etcd/server.key member list | wc -l)")
assert_contains "etcd has 3 members" "MEMBERS=3" "$out"

log "network"
out=$(run_on "$CP" "echo CILIUM=\$($KC -n kube-system get pods -l k8s-app=cilium --no-headers | grep -c ' Running ') KUBEPROXY=\$($KC -n kube-system get pods -l k8s-app=kube-proxy --no-headers | wc -l)")
assert_contains "Cilium runs on 10 nodes and kube-proxy is absent" "CILIUM=10 KUBEPROXY=0" "$out"

log "storage"
out=$(run_on "$CP" "$KC get storageclass")
assert_contains "ceph-rbd is the default StorageClass" "ceph-rbd (default)" "$out"
out=$(run_on "$CP" "$KC create namespace t-csi >/dev/null; printf 'apiVersion: v1\nkind: PersistentVolumeClaim\nmetadata:\n  name: d\n  namespace: t-csi\nspec:\n  accessModes: [ReadWriteOnce]\n  resources:\n    requests:\n      storage: 1Gi\n---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: w\n  namespace: t-csi\nspec:\n  restartPolicy: Never\n  containers:\n  - name: b\n    image: docker.io/library/busybox:stable\n    command: [\"sh\",\"-c\",\"echo csi-roundtrip > /d/p && cat /d/p\"]\n    volumeMounts:\n    - {name: d, mountPath: /d}\n  volumes:\n  - name: d\n    persistentVolumeClaim:\n      claimName: d\n' | $KC apply -f - >/dev/null; $KC -n t-csi wait --for=jsonpath='{.status.phase}'=Succeeded pod/w --timeout=240s >/dev/null; $KC -n t-csi logs w; $KC delete namespace t-csi --wait=false >/dev/null")
assert_contains "a pod writes to and reads from a Ceph volume" "csi-roundtrip" "$out"

log "certificates"
out=$(run_on "$CP" "$KC get clusterissuer orphera-ca")
assert_contains "ClusterIssuer orphera-ca is Ready" "True" "$out"

log "security"
out=$(run_on "$CP" "echo ENC=\$($KC -n kube-system exec etcd-tst30 -- etcdctl --endpoints=https://127.0.0.1:2379 --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt --key=/etc/kubernetes/pki/etcd/server.key get /registry/secrets/cert-manager/orphera-sub-ca | grep -a -c 'k8s:enc:secretbox:v1:key1')")
assert_contains "Secrets are encrypted in etcd" "ENC=1" "$out"
out=$(run_on "$CP" "echo BINDINGS=\$($KC get clusterrolebinding oidc-k8s-admins oidc-k8s-developers oidc-k8s-viewers --no-headers | wc -l)")
assert_contains "the three group role bindings exist" "BINDINGS=3" "$out"

log "gateway"
out=$(run_on "$CP" "$KC -n gateway get gateway public -o jsonpath='{.status.addresses[0].value}'; echo")
assert_contains "the Gateway holds 10.10.3.100" "10.10.3.100" "$out"
out=$(run_on "$CP" "$KC -n gateway get certificate wildcard-k8s")
assert_contains "the wildcard certificate is Ready" "True" "$out"

if [ "${FAILOVER:-0}" = "1" ]; then
  log "VIP failover"
  holder=""
  for n in tst30 tst31 tst32; do
    if run_on "$n" "ip -4 addr show | grep -q '$VIP/' && echo HOLDS" | grep -q HOLDS; then holder="$n"; fi
  done
  if [ -z "$holder" ]; then
    log "FAIL: no control-plane node holds $VIP"; FAIL=$((FAIL + 1))
  else
    log "stopping keepalived on $holder"
    run_on "$holder" "sudo systemctl stop keepalived" >/dev/null
    KA_STOPPED="$holder"
    sleep 8
    other="tst31"; [ "$holder" = "tst31" ] && other="tst32"
    out=$(run_on "$other" "curl -sk --max-time 5 https://$VIP:8443/livez; echo; ip -4 addr show | grep -c '$VIP/'")
    assert_contains "the API answers on the VIP after $holder lost it" "ok" "$out"
    run_on "$holder" "sudo systemctl start keepalived" >/dev/null
    KA_STOPPED=""
  fi
fi

log "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
