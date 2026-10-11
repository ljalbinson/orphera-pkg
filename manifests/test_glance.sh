#!/usr/bin/env bash
#
# DRAFT end-to-end test for glance_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the glance-api container runs as a systemd unit,
#  - the API answers on 9292 and lists images through Keystone,
#  - an image can be created and uploaded through the API, reaches `active`,
#    and really exists as an RBD image in Ceph (checked on tst0 with `rbd ls`),
#    then deletes cleanly and the RBD image is gone,
#  - Glance is in the Keystone catalog (service type `image`),
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP, Ceph with OSDs, Keystone (keystone_single_node)
# all healthy; tst13 has an agent. Run from scala0, in orphera-pkg:
#   manifests/test_glance.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst13"
POOL="images"
HELPER="/usr/local/sbin/orphera-glance-api"

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
  log "install"
  if ! orphera cluster-playbook manifests/glance_single_node.scala; then
    log "FAIL: glance_single_node.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "systemctl is-active orphera-glance-api")
assert_contains "orphera-glance-api is active" "active" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9292/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the API answers on 9292"; PASS=$((PASS + 1))
else
  log "FAIL: the API did not answer on 9292"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "sudo $HELPER image-list")
assert_contains "images listed through Keystone" "images:" "$OUT"

OUT=$(run_ceph "ceph osd pool ls")
assert_contains "Ceph pool $POOL exists" "$POOL" "$OUT"

OUT=$(run_on "sudo $HELPER image-create")
IID=$(printf '%s\n' "$OUT" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
if [ -z "$IID" ]; then
  log "FAIL: image create returned no id"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: image $IID reached 'active'"; PASS=$((PASS + 1))
  OUT=$(run_ceph "rbd ls $POOL")
  assert_contains "image exists as an RBD image in pool $POOL" "$IID" "$OUT"
  OUT=$(run_on "sudo $HELPER image-list")
  assert_contains "the new image is listed" "$IID" "$OUT"
  OUT=$(run_on "sudo $HELPER image-delete $IID")
  assert_contains "image deleted through the API" "deleted $IID" "$OUT"
  sleep 5
  OUT=$(run_ceph "rbd ls $POOL")
  assert_not_contains "RBD image removed from pool $POOL" "$IID" "$OUT"
fi

# Glance is in the Keystone catalog.
OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type image" "image" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manifests/glance_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of glance_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
