#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT test for capi_management_cluster.scala. Applies the playbook, then checks on tst23, independently:
#  - the k3s service is active and its node is Ready,
#  - the kubeconfig for Magnum works against https://tst23.ljalbinson.com:6443,
#  - the Cluster API, CAPO and addon provider deployments are all available,
#  - the Cluster API, OpenStack and addon custom resource definitions exist,
#  - a second run of the playbook succeeds and leaves the cluster working.
# Run from scala0, in orphera-pkg:   manifests/test_capi_management_cluster.sh
# NO_APPLY=1 skips the playbook runs. Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst23"
KC="/var/lib/orphera/capi/kubeconfig"
PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" --timeout "${RUN_TIMEOUT:-90}" 2>&1; }

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

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  if ! orphera cluster-playbook capi_management_cluster.scala; then
    log "FAIL: capi_management_cluster.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "echo k3s=\$(systemctl is-active k3s)")
assert_contains "the k3s service is active" "k3s=active" "$OUT"

OUT=$(run_on "echo ready-nodes=\$(KUBECONFIG=$KC k3s kubectl get nodes --no-headers | grep -cw Ready)")
assert_contains "the node is Ready, through the kubeconfig for Magnum" "ready-nodes=1" "$OUT"

for ns in cert-manager capi-system capi-kubeadm-bootstrap-system capi-kubeadm-control-plane-system capo-system capi-addon-system capi-janitor-system; do
  OUT=$(run_on "echo $ns-unavailable=\$(KUBECONFIG=$KC k3s kubectl -n $ns get deployments --no-headers | awk '\$4 == 0' | wc -l) total=\$(KUBECONFIG=$KC k3s kubectl -n $ns get deployments --no-headers | wc -l)")
  if printf '%s' "$OUT" | grep -q "$ns-unavailable=0 total=[1-9]"; then
    log "PASS: every deployment in $ns is available"; PASS=$((PASS + 1))
  else
    log "FAIL: deployments in $ns are not all available"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
  fi
done

for crd in clusters.cluster.x-k8s.io openstackclusters.infrastructure.cluster.x-k8s.io; do
  OUT=$(run_on "KUBECONFIG=$KC k3s kubectl get crd $crd -o name")
  assert_contains "the CRD $crd exists" "customresourcedefinition" "$OUT"
done
OUT=$(run_on "echo addon-crds=\$(KUBECONFIG=$KC k3s kubectl get crd | grep -c addons.stackhpc.com)")
if printf '%s' "$OUT" | grep -qE 'addon-crds=[1-9]'; then
  log "PASS: the addon provider's CRDs exist"; PASS=$((PASS + 1))
else
  log "FAIL: no addons.stackhpc.com CRDs"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "cat /var/lib/orphera/capi/versions.txt")
log "versions installed:"; printf '%s\n' "$OUT" | sed 's/^/    /'

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook capi_management_cluster.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "echo ready-nodes=\$(KUBECONFIG=$KC k3s kubectl get nodes --no-headers | grep -cw Ready)")
  assert_contains "the cluster still works after the second run" "ready-nodes=1" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
