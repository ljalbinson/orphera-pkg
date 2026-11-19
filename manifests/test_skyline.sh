#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for skyline_single_node.scala (with skyline_database.scala, which it applies first).
#
# Applies the two descriptions, then checks independently of their own health checks:
#  - the unit orphera-skyline is active on tst13,
#  - the console page answers on 9999 over HTTPS (HTTP 200, certificate verified with the test CA) and Horizon on 8080 still answers,
#  - the API docs at /api/openstack/skyline/docs answer,
#  - an unauthenticated call to a protected Skyline API path is refused (401 or 403),
#  - the admin user logs in through the login API (HTTP 200),
#  - the skyline tables exist in the database,
#  - the container reads a CA bundle that contains the Keystone CA,
#  - a second run of the description changes nothing (idempotence), and the login still works afterwards.
#
# Prerequisites: Galera + VIP and Keystone up; the certificate on tst13 (dashboard_tls.scala, which Horizon
# also needs); tst13 has an agent and Docker Hub is reachable from it.
# Run from scala0, in orphera-pkg:   manifests/test_skyline.sh
# NO_APPLY=1 skips the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst13"
PORT=9999
FQDN="tst13.ljalbinson.com"
# The dashboards are on HTTPS with a certificate from the test CA; on tst13 the CA is in /etc/orphera/tls.
CURL="curl -s --cacert /etc/orphera/tls/ca.crt --resolve $FQDN:$PORT:127.0.0.1 --resolve $FQDN:8080:127.0.0.1"
HELPER="/usr/local/sbin/orphera-skyline-api"

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

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  for d in skyline_database.scala skyline_single_node.scala; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

OUT=$(run_on "echo state=\$(systemctl is-active orphera-skyline)")
assert_contains "orphera-skyline is active on $NODE" "state=active" "$OUT"

OUT=$(run_on "$CURL -o /dev/null -w 'http=%{http_code}' https://$FQDN:$PORT/")
assert_contains "the console page answers on $PORT over HTTPS" "http=200" "$OUT"

OUT=$(run_on "$CURL -o /dev/null -w 'http=%{http_code}' https://$FQDN:8080/")
if printf '%s' "$OUT" | grep -qE 'http=(200|301|302)'; then
  log "PASS: Horizon still answers on 8080"; PASS=$((PASS + 1))
else
  log "FAIL: Horizon does not answer on 8080"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "$CURL -o /dev/null -w 'http=%{http_code}' https://$FQDN:$PORT/api/openstack/skyline/docs")
assert_contains "the API docs answer" "http=200" "$OUT"

OUT=$(run_on "$CURL -o /dev/null -w 'http=%{http_code}' https://$FQDN:$PORT/api/openstack/skyline/api/v1/profile")
if printf '%s' "$OUT" | grep -qE 'http=(401|403)'; then
  log "PASS: a protected path refuses a request without a session"; PASS=$((PASS + 1))
else
  log "FAIL: a protected path does not refuse a request without a session"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "sudo $HELPER login-check")
assert_contains "the admin user logs in through the login API" "login ok" "$OUT"

OUT=$(run_on "mariadb -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='skyline'\"" tst3)
if printf '%s\n' "$OUT" | grep -qE '(^|\] )[1-9][0-9]*$'; then
  log "PASS: the skyline database has tables"; PASS=$((PASS + 1))
else
  log "FAIL: the skyline database has no tables"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "sudo podman exec skyline sh -c 'grep -c BEGIN /etc/ssl/certs/ca-certificates.crt' | sed 's/^/certs=/'")
if printf '%s' "$OUT" | grep -qE 'certs=[1-9]'; then
  log "PASS: the container reads a CA bundle (it is the host's, with the Keystone CA)"; PASS=$((PASS + 1))
else
  log "FAIL: the container has no CA bundle"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  SECOND=$(orphera apply skyline_single_node.scala 2>&1); RC=$?
  printf '%s\n' "$SECOND"
  if [ "$RC" -ne 0 ]; then
    log "FAIL: second run of skyline_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  assert_contains "second run changed nothing" "0 changed" "$SECOND"
  OUT=$(run_on "sudo $HELPER login-check")
  assert_contains "the admin login still works after the second run" "login ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
