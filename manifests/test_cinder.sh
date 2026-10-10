#!/usr/bin/env bash
#
# DRAFT end-to-end test for cinder_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the three services and RabbitMQ are running as systemd units,
#  - scheduler and volume report `up` through the Cinder API,
#  - a 1 GB volume can be created through the API and really exists as an RBD
#    image in Ceph (checked on tst0 with `rbd ls`), then deletes cleanly and the
#    image is gone,
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP, Ceph with OSDs, Keystone (keystone_single_node)
# all healthy.  Run from scala0, in orphera-pkg:   manifests/test_cinder.sh
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

log "install"
if ! orphera cluster-playbook manifests/cinder_single_node.scala; then
  log "FAIL: cinder_single_node.scala did not complete"; exit 1
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

log "re-apply (idempotence)"
if orphera cluster-playbook manifests/cinder_single_node.scala; then
  log "PASS: second run completed"; PASS=$((PASS + 1))
else
  log "FAIL: second run of cinder_single_node.scala failed"; FAIL=$((FAIL + 1))
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
