#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for cinder_single_node.scala (with cinder_database.scala,
# cinder_ceph.scala and rabbitmq_single_node.scala, which it applies first).
#
# Tears Cinder down (cinder_teardown.scala: it drops the cinder database and
# deletes the `volumes` pool, so every volume is lost; the RabbitMQ broker is not
# touched), applies the four descriptions, then checks independently of their own
# health checks:
#  - the three services and RabbitMQ are running as systemd units,
#  - scheduler and volume report `up` through the Cinder API,
#  - a 1 GB volume can be created through the API and really exists as an RBD
#    image in Ceph (checked on tst0 with `rbd ls`), then deletes cleanly and the
#    image is gone,
#  - Cinder is in the Keystone catalog (service type volumev3),
#  - a second run of the description changes nothing (idempotence).
#
# Prerequisites: Galera + VIP, Ceph with OSDs, Keystone (keystone_single_node)
# all healthy.  Run from scala0, in orphera-pkg:   manifests/test_cinder.sh
# NO_APPLY=1 skips the teardown and the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst10"
POOL="volumes"
HELPER="/usr/local/sbin/orphera-openstack-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" 2>&1; }
run_ceph() { orphera run sh -c "sudo cephadm shell -- $1" --nodes tst0 2>&1; }

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

assert_not_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    log "FAIL: $desc (found: $needle)"
    printf '%s\n' "$haystack" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  else
    log "PASS: $desc"; PASS=$((PASS + 1))
  fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "teardown (failures here are tolerated: nothing may be installed yet)"
  orphera apply cinder_teardown.scala || true

  log "install"
  for d in cinder_database.scala cinder_ceph.scala rabbitmq_single_node.scala cinder_single_node.scala; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

for u in rabbitmq cinder-api cinder-scheduler cinder-volume; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

OUT=$(run_on "sudo $HELPER volume-services")
assert_contains "cinder-scheduler is up" "cinder-scheduler" "$OUT"
assert_contains "cinder-volume is up" "cinder-volume" "$OUT"
assert_contains "volume service state is up" "state=up" "$OUT"

OUT=$(run_on "sudo $HELPER volume-create")
VID=$(printf '%s\n' "$OUT" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
if [ -z "$VID" ]; then
  log "FAIL: volume create returned no id"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: volume $VID reached 'available'"; PASS=$((PASS + 1))
  OUT=$(run_ceph "rbd ls $POOL")
  assert_contains "volume exists as an RBD image in pool $POOL" "volume-$VID" "$OUT"
  OUT=$(run_on "sudo $HELPER volume-delete $VID")
  assert_contains "volume deleted through the API" "deleted $VID" "$OUT"
  sleep 5
  OUT=$(run_ceph "rbd ls $POOL")
  assert_not_contains "RBD image removed from pool $POOL" "volume-$VID" "$OUT"
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type volumev3" "volumev3" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  SECOND=$(orphera apply cinder_single_node.scala 2>&1); RC=$?
  printf '%s\n' "$SECOND"
  if [ "$RC" -ne 0 ]; then
    log "FAIL: second run of cinder_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  assert_contains "second run changed nothing" "0 changed" "$SECOND"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
