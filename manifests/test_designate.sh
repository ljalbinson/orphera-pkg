#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for designate_single_node.scala (with designate_database.scala and
# designate_rabbitmq.scala, which it applies first).
#
# Tears Designate down (designate_teardown.scala: it drops the designate database, so every zone is gone),
# applies the three descriptions, then checks independently of their own health checks:
#  - the five Designate units and BIND9 run as systemd units,
#  - the API answers its version document on 9001 without a token,
#  - an unauthenticated request to a protected path is refused (401),
#  - the zone list is readable with an admin token,
#  - BIND9 answers on port 53 of the node's address and takes rndc commands,
#  - mdns listens on 5354,
#  - a zone and an A record made through the API reach ACTIVE and BIND9 answers for the record
#    (the whole path: API, RabbitMQ, central, the database, worker, rndc, mdns, AXFR), then the
#    zone is deleted and is gone,
#  - Designate is in the Keystone catalog (service type `dns`),
#  - a second run of the description changes nothing (idempotence), and a zone still goes through afterwards.
#
# Prerequisites: Galera + VIP, Keystone and RabbitMQ (tst10) healthy; the node (tst16) has an agent.
# Run from scala0, in orphera-pkg:   manifests/test_designate.sh
# NO_APPLY=1 skips the teardown and the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst16"
NODE_IP="10.10.5.28"
PORT=9001
HELPER="/usr/local/sbin/orphera-designate-api"

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
  log "teardown (failures here are tolerated: nothing may be installed yet)"
  orphera apply designate_teardown.scala || true

  log "install"
  for d in designate_database.scala designate_rabbitmq.scala designate_single_node.scala; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

for u in designate-backend-bind9 designate-central designate-mdns designate-api designate-producer designate-worker; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2/zones")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER zone-list")
assert_contains "zones listed with an admin token" "zones:" "$OUT"

OUT=$(run_on "dig +time=3 +tries=1 @$NODE_IP version.bind txt chaos | grep -o 'status: [A-Z]*'")
assert_contains "BIND9 answers on $NODE_IP:53" "status:" "$OUT"

OUT=$(run_on "sudo podman exec designate-backend-bind9 rndc -s 127.0.0.1 -p 953 -k /etc/bind/rndc.key status")
assert_contains "rndc reaches BIND9 with the shared key" "server is up and running" "$OUT"

OUT=$(run_on "ss -Hlnt 'sport = :5354'; ss -Hlnu 'sport = :5354'")
assert_contains "mdns listens on 5354" "5354" "$OUT"

OUT=$(run_on "sudo $HELPER zone-cycle")
assert_contains "a zone and a record go through the API to BIND9 and back" "zone cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER zone-list")
if printf '%s' "$OUT" | grep -qF "orphera-test-"; then
  log "FAIL: a test zone is still listed after the cycle"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: no test zone is left behind"; PASS=$((PASS + 1))
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type dns" "dns" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  SECOND=$(orphera apply designate_single_node.scala 2>&1); RC=$?
  printf '%s\n' "$SECOND"
  if [ "$RC" -ne 0 ]; then
    log "FAIL: second run of designate_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  assert_contains "second run changed nothing" "0 changed" "$SECOND"
  OUT=$(run_on "sudo $HELPER zone-cycle")
  assert_contains "a zone still goes through after the second run" "zone cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
