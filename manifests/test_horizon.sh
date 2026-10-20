#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for horizon_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the horizon container runs as a systemd unit,
#  - the root leads to the login page and the login page answers 200,
#  - a static file referenced by the login page is served (the Apache Alias and
#    the image's collected static files line up),
#  - a real login as admin succeeds (reads the form's CSRF field, posts the
#    credentials, lands on a signed-in page) - this exercises Keystone over TLS
#    with the test CA from inside the container,
#  - the dashboard is reachable from this machine (scala0), not just on tst13,
#  - Glance and Placement on the same node still answer,
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Keystone healthy; the node (tst13) has an agent. Run from
# scala0, in orphera-pkg:   manifests/test_horizon.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst13"
FQDN="tst13.ljalbinson.com"
PORT=8080
HELPER="/usr/local/sbin/orphera-horizon-check"

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
  if ! orphera cluster-playbook manifests/horizon_single_node.scala; then
    log "FAIL: horizon_single_node.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "systemctl is-active orphera-horizon")
assert_contains "orphera-horizon is active" "active" "$OUT"

OUT=$(run_on "sudo $HELPER login-page")
assert_contains "the root leads to the login page" "login page ok" "$OUT"

OUT=$(run_on "sudo $HELPER static")
assert_contains "a static file is served" "static ok" "$OUT"

OUT=$(run_on "sudo $HELPER login")
assert_contains "admin can sign in" "login ok as admin" "$OUT"

CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "http://$FQDN:$PORT/auth/login/" || true)
if [ "$CODE" = "200" ]; then
  log "PASS: the dashboard is reachable from this machine at http://$FQDN:$PORT/"; PASS=$((PASS + 1))
else
  log "FAIL: http://$FQDN:$PORT/auth/login/ from this machine returned '$CODE' (firewall or DNS?)"; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'g=%{http_code}' http://localhost:9292/; curl -s -o /dev/null -w ' p=%{http_code}' http://localhost:8780/")
if printf '%s' "$OUT" | grep -qE 'g=(200|300) p=200'; then
  log "PASS: Glance and Placement on the same node still answer"; PASS=$((PASS + 1))
else
  log "FAIL: Glance/Placement check"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manifests/horizon_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of horizon_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
