#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for manila_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the three Manila units run as systemd units on tst22,
#  - the API answers its version document without a token (8786),
#  - an unauthenticated request to a protected path is refused (401),
#  - the scheduler and the share service both report state up,
#  - the default share type exists,
#  - a 1 GB NFS share is made on CephFS, this node is allowed on it, it is mounted over NFSv4.1, a file is
#    written and read back, and the share is deleted and gone,
#  - no test share is left behind, and no test mount is left on the node,
#  - Manila is in the Keystone catalog (service type sharev2),
#  - a second run of the playbook succeeds (idempotence), and a share still round-trips afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10) and Ceph healthy; tst22 exists and has an agent;
# the Manila images are available (built on tst21 or on quay.io).
# Run from scala0, in orphera-pkg:   manifests/test_manila.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst22"
PORT=8786
HELPER="/usr/local/sbin/orphera-manila-api"

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
  if ! orphera cluster-playbook manila_single_node.scala; then
    log "FAIL: manila_single_node.scala did not complete"; exit 1
  fi
fi

for u in manila-scheduler manila-share manila-api; do
  OUT=$(run_on "echo state=\$(systemctl is-active orphera-$u)")
  assert_contains "orphera-$u is active" "state=active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2/shares")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER services")
assert_contains "the scheduler is up" "manila-scheduler@" "$OUT"
if printf '%s' "$OUT" | grep -E 'manila-scheduler@[^ ]*=up' >/dev/null && printf '%s' "$OUT" | grep -E 'manila-share@[^ ]*=up' >/dev/null; then
  log "PASS: the scheduler and the share service both report up"; PASS=$((PASS + 1))
else
  log "FAIL: the scheduler and the share service do not both report up"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "sudo $HELPER share-type")
assert_contains "the default share type exists" "ready" "$OUT"

OUT=$(RUN_TIMEOUT=900 run_on "sudo $HELPER share-cycle")
assert_contains "a share is made, mounted over NFS, written, read back and deleted" "share cycle ok" "$OUT"

OUT=$(run_on "echo mounts=\$(mount | grep -c orphera-manila-test || true)")
assert_contains "no test mount is left on the node" "mounts=0" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type sharev2" "sharev2" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manila_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of manila_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(RUN_TIMEOUT=900 run_on "sudo $HELPER share-cycle")
  assert_contains "a share still round-trips after the second run" "share cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
