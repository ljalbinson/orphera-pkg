#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for trove_single_node.scala (part 1: the service; the database instance test comes with
# trove_guest_image.scala).
#
# Applies the three descriptions (trove_database, trove_rabbitmq, trove_single_node), then checks independently
# of their own health checks:
#  - the three units on tst25 (trove-api, trove-taskmanager, trove-conductor) are active,
#  - the Trove API answers its version document on 8779 without a token and refuses a protected path without
#    one (401),
#  - the helper's `check` passes: an admin request to the API works and the guest network, security group and
#    keypair exist,
#  - Keystone lists the service type database,
#  - the vhost trove exists on RabbitMQ (tst10),
#  - the taskmanager and the conductor have connected to RabbitMQ (no connection error in their logs),
#  - a second run of trove_single_node.scala changes nothing, and the check still passes afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10), Glance, Nova, Neutron with the network `provider`,
# Cinder; tst25 exists and has an agent; the Trove images are available (built on tst21 or on quay.io).
# Run from scala0, in orphera-pkg:   manifests/test_trove.sh
# NO_APPLY=1 skips the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst25"
PORT=8779
HELPER="/usr/local/sbin/orphera-trove-api"

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
  for d in trove_database.scala trove_rabbitmq.scala trove_single_node.scala; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

for u in trove-api trove-taskmanager trove-conductor; do
  OUT=$(run_on "echo state=\$(systemctl is-active orphera-$u)")
  assert_contains "orphera-$u is active on $NODE" "state=active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the Trove version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the Trove version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v1.0/none/instances")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(RUN_TIMEOUT=180 run_on "sudo $HELPER check")
assert_contains "an admin request works and the guest network, group and keypair exist" "trove check ok" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type database" "database" "$OUT"

OUT=$(run_on "sudo podman exec rabbitmq rabbitmqctl list_vhosts -q" tst10)
assert_contains "the vhost trove exists on RabbitMQ" "trove" "$OUT"

for u in trove-taskmanager trove-conductor; do
  OUT=$(run_on "sudo podman logs --since 10m $u 2>&1 | grep -i -c 'AMQP server on .* is unreachable\|ACCESS_REFUSED\|Connection refused' | sed 's/^/connection_errors=/'")
  assert_contains "$u has no RabbitMQ connection errors" "connection_errors=0" "$OUT"
done

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  SECOND=$(orphera apply trove_single_node.scala 2>&1); RC=$?
  printf '%s\n' "$SECOND"
  if [ "$RC" -ne 0 ]; then log "FAIL: second run of trove_single_node.scala failed"; FAIL=$((FAIL + 1)); fi
  assert_contains "second run changed nothing" "0 changed" "$SECOND"
  OUT=$(RUN_TIMEOUT=180 run_on "sudo $HELPER check")
  assert_contains "the check still passes after the second run" "trove check ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
