#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for barbican_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the two Barbican units run as systemd units,
#  - the API answers its version document without a token (9311),
#  - an unauthenticated request to a protected path is refused (401),
#  - the secret list is readable with an admin token,
#  - a secret is stored, its payload read back identical, and it is deleted and gone,
#  - a key order (256 bit AES) is made by the worker through RabbitMQ, ends ACTIVE, gives 32 bytes, and is
#    cleaned up,
#  - Barbican is in the Keystone catalog (service type key-manager),
#  - a second run of the playbook succeeds (idempotence), and a stored secret still round-trips afterwards.
#
# Prerequisites: Galera + VIP, Keystone and RabbitMQ (tst10) healthy; the node (tst20) has an agent.
# Run from scala0, in orphera-pkg:   manifests/test_barbican.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst20"
PORT=9311
HELPER="/usr/local/sbin/orphera-barbican-api"

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
  if ! orphera cluster-playbook barbican_single_node.scala; then
    log "FAIL: barbican_single_node.scala did not complete"; exit 1
  fi
fi

for u in barbican-worker barbican-api; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v1/secrets")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER secret-list")
assert_contains "secrets listed with an admin token" "secrets:" "$OUT"

OUT=$(run_on "sudo $HELPER secret-cycle")
assert_contains "a secret is stored, read back and deleted" "secret cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER order-cycle")
assert_contains "the worker makes a 256 bit AES key from an order" "order cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER secret-list")
if printf '%s' "$OUT" | grep -qF "orphera-test"; then
  log "FAIL: a test secret is still listed after the cycles"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: no test secret is left behind"; PASS=$((PASS + 1))
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type key-manager" "key-manager" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook barbican_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of barbican_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "sudo $HELPER secret-cycle")
  assert_contains "a secret still round-trips after the second run" "secret cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
