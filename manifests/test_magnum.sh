#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for magnum_single_node.scala.
#
# Applies the playbook (which itself makes a real Kubernetes cluster and deletes it), then checks
# independently:
#  - orphera-magnum-api and orphera-magnum-conductor are active on tst23,
#  - the API answers its version document without a token (9511), a protected path refuses without a token (401),
#  - Magnum is in the Keystone catalog (service type container-infra),
#  - the conductor is registered and up,
#  - the driver and helm are in the conductor image,
#  - the management cluster is reachable from the container's kubeconfig,
#  - Glance has the Kubernetes image, Nova the flavor orphera-k8s, and the template orphera-k8s exists,
#  - a second cluster cycle works (cluster created, API answers /version, cluster deleted),
#  - a second run of the playbook succeeds (idempotence) and the template is still there.
#
# Prerequisites: capi_management_cluster.scala applied; Keystone, Glance, Nova (with compute capacity for a
# control-plane node, a worker and an amphora), Neutron, Octavia, RabbitMQ and Galera healthy; tst17/tst18 and
# tst23 sized for it (see the sizing answer). A cluster cycle takes 15 to 40 minutes.
# Run from scala0, in orphera-pkg:   manifests/test_magnum.sh
# NO_APPLY=1 skips the playbook runs and only checks. SKIP_CYCLE=1 skips the additional cluster cycle.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst23"
PORT=9511
HELPER="/usr/local/sbin/orphera-magnum-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }

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
  if ! orphera cluster-playbook magnum_single_node.scala; then
    log "FAIL: magnum_single_node.scala did not complete"; exit 1
  fi
fi

for u in magnum-conductor magnum-api; do
  OUT=$(run_on "echo state=\$(systemctl is-active orphera-$u)")
  assert_contains "orphera-$u is active" "state=active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v1/clusters")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type container-infra" "container-infra" "$OUT"

OUT=$(run_on "sudo $HELPER mservices")
assert_contains "the conductor is registered and up" "magnum-conductor@" "$OUT"
assert_contains "the conductor state is up" "=up" "$OUT"

OUT=$(run_on "podman exec magnum-conductor sh -c 'helm version --short; /var/lib/kolla/venv/bin/python -c \"import magnum_capi_helm.driver; print(\\\"driver-ok\\\")\"'")
assert_contains "helm and the Cluster API driver are in the conductor" "driver-ok" "$OUT"

OUT=$(run_on "podman exec magnum-conductor sh -c 'test -s /etc/magnum/kubeconfig && echo kubeconfig-present'")
assert_contains "the conductor has the management cluster kubeconfig" "kubeconfig-present" "$OUT"

OUT=$(run_on "sudo $HELPER image-present")
assert_contains "Glance has an active Kubernetes image" "is active in Glance" "$OUT"

OUT=$(run_on "sudo $HELPER resources")
assert_contains "the flavor and key pair exist" "resources ready" "$OUT"

OUT=$(run_on "sudo $HELPER template")
assert_contains "the cluster template orphera-k8s exists" "cluster template orphera-k8s ready" "$OUT"

if [ -z "${SKIP_CYCLE:-}" ]; then
  OUT=$(RUN_TIMEOUT=5400 run_on "sudo $HELPER cluster-cycle")
  assert_contains "a cluster is created, its API answers, and it is deleted" "cluster cycle ok" "$OUT"
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if OUT=$(orphera cluster-playbook magnum_single_node.scala 2>&1); then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of magnum_single_node.scala failed"; printf '%s\n' "$OUT" | tail -20 | sed 's/^/    /'; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "sudo $HELPER template")
  assert_contains "the template is still there after the second run" "cluster template orphera-k8s ready" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
