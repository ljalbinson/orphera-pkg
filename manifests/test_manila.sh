#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for manila_single_node.scala.
#
# Applies the four descriptions (manila_database, manila_rabbitmq, manila_ceph, manila_single_node), then checks
# independently of their own health checks:
#  - the three Manila units run as systemd units on tst22,
#  - the API answers its version document without a token (8786),
#  - an unauthenticated request to a protected path is refused (401),
#  - the scheduler and the share service both report state up,
#  - the default share type exists,
#  - a 1 GB NFS share is made on CephFS, this node is allowed on it, it is mounted over NFSv4.1, a file is
#    written and read back, and the share is deleted and gone,
#  - no test share is left behind, and no test mount is left on the node,
#  - Manila is in the Keystone catalog (service type sharev2),
#  - the vhost manila exists on RabbitMQ (tst10), and CephFS, the NFS cluster manila and client.manila exist
#    on Ceph (tst0),
#  - a second run of each description changes nothing, and a share still round-trips afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10) and Ceph healthy; tst22 exists and has an agent;
# the Manila images are available (built on tst21 or on quay.io).
# Run from scala0, in orphera-pkg:   manifests/test_manila.sh
# NO_APPLY=1 skips the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst22"
PORT=8786
HELPER="/usr/local/sbin/orphera-manila-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }

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

DESCRIPTIONS="manila_database.scala manila_rabbitmq.scala manila_ceph.scala manila_single_node.scala"

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  for d in $DESCRIPTIONS; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
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

OUT=$(run_on "sudo podman exec rabbitmq rabbitmqctl list_vhosts -q" tst10)
assert_contains "the vhost manila exists on RabbitMQ" "manila" "$OUT"

OUT=$(orphera run sh -c "cephadm shell -- ceph fs ls 2>/dev/null; cephadm shell -- ceph nfs cluster ls 2>/dev/null; cephadm shell -- ceph auth get client.manila 2>/dev/null" --nodes tst0 --timeout 120 2>&1)
assert_contains "CephFS cephfs exists" "name: cephfs," "$OUT"
assert_contains "the NFS cluster manila exists" "manila" "$OUT"
assert_contains "client.manila has the mgr capability" 'caps mgr = "allow rw"' "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  for d in $DESCRIPTIONS; do
    SECOND=$(orphera apply "$d" 2>&1); RC=$?
    printf '%s\n' "$SECOND"
    if [ "$RC" -ne 0 ]; then log "FAIL: second run of $d failed"; FAIL=$((FAIL + 1)); fi
    assert_contains "second run of $d changed nothing" "0 changed" "$SECOND"
  done
  OUT=$(RUN_TIMEOUT=900 run_on "sudo $HELPER share-cycle")
  assert_contains "a share still round-trips after the second run" "share cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
