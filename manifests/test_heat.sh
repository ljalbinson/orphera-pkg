#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for heat_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the three Heat units run as systemd units,
#  - both APIs answer their version documents without a token (8004 and 8000),
#  - an unauthenticated request to a protected path is refused (401),
#  - the stack list is readable with an admin token, and the engine reports in through the API,
#  - a stack made of a random string reaches CREATE_COMPLETE, gives its output back, and is deleted,
#  - a stack that also makes a Nova flavor does the same (the engine acts for the user through a Keystone
#    trust and calls Nova, so this needs Nova to be up),
#  - no test stack is left behind,
#  - Heat is in the Keystone catalog (service types orchestration and cloudformation),
#  - a second run of the playbook succeeds (idempotence), and a stack still works afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10) and Nova healthy; the node (tst20) has an agent.
# Run from scala0, in orphera-pkg:   manifests/test_heat.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst20"
PORT=8004
CFN_PORT=8000
HELPER="/usr/local/sbin/orphera-heat-api"

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
  if ! orphera cluster-playbook heat_single_node.scala; then
    log "FAIL: heat_single_node.scala did not complete"; exit 1
  fi
fi

for u in heat-engine heat-api heat-api-cfn; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

for p in "$PORT" "$CFN_PORT"; do
  OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$p/")
  if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
    log "PASS: the version document answers on $p"; PASS=$((PASS + 1))
  else
    log "FAIL: the version document does not answer on $p"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
  fi
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v1/nonexistent/stacks")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER stack-list")
assert_contains "stacks listed with an admin token" "stacks:" "$OUT"

OUT=$(run_on "sudo $HELPER engines-up")
assert_contains "the engine reports in through the API (RabbitMQ and database work)" "heat-engine is up" "$OUT"

OUT=$(run_on "sudo $HELPER stack-cycle basic")
assert_contains "a stack of a random string is created, read back and deleted" "stack cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER stack-cycle")
assert_contains "a stack that makes a Nova flavor works through the trust" "stack cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER stack-list")
if printf '%s' "$OUT" | grep -qF "orphera-test-"; then
  log "FAIL: a test stack is still listed after the cycles"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: no test stack is left behind"; PASS=$((PASS + 1))
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type orchestration" "orchestration" "$OUT"
assert_contains "the catalog lists service type cloudformation" "cloudformation" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook heat_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of heat_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "sudo $HELPER stack-cycle")
  assert_contains "a stack still works after the second run" "stack cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
