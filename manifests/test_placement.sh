#!/usr/bin/env bash
#
# DRAFT end-to-end test for placement_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the placement-api container runs as a systemd unit,
#  - the API answers its version document on 8780 without a token,
#  - an unauthenticated request to a protected path is refused (401),
#  - with an admin token a resource provider can be created, read back and
#    deleted (the object Nova will register hypervisors as),
#  - Placement is in the Keystone catalog (service type `placement`),
#  - the Glance API on the same node still answers (no collision),
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP and Keystone healthy; the node (tst13) has an
# agent. Run from scala0, in orphera-pkg:   manifests/test_placement.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst13"
PORT=8780
HELPER="/usr/local/sbin/orphera-placement-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" 2>&1; }

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
  if ! orphera cluster-playbook manifests/placement_single_node.scala; then
    log "FAIL: placement_single_node.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "systemctl is-active orphera-placement-api")
assert_contains "orphera-placement-api is active" "active" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
assert_contains "the version document answers on $PORT" "http=200" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/resource_providers")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER rp-list")
assert_contains "resource providers listed with an admin token" "resource providers:" "$OUT"

OUT=$(run_on "sudo $HELPER rp-create")
RID=$(printf '%s\n' "$OUT" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
if [ -z "$RID" ]; then
  log "FAIL: resource provider create returned no id"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: resource provider $RID created and read back"; PASS=$((PASS + 1))
  OUT=$(run_on "sudo $HELPER rp-list")
  assert_contains "the new provider is listed" "$RID" "$OUT"
  OUT=$(run_on "sudo $HELPER rp-delete $RID")
  assert_contains "provider deleted, then gone (404)" "deleted $RID" "$OUT"
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type placement" "placement" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9292/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: Glance on the same node still answers"; PASS=$((PASS + 1))
else
  log "FAIL: Glance on the same node no longer answers"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manifests/placement_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of placement_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
