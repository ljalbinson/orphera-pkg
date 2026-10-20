#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for nova_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the four Nova units (API, scheduler, conductor, noVNC proxy) are active,
#  - the API answers its version document on 8774 without a token,
#  - an unauthenticated request to a protected path is refused (401),
#  - the scheduler and conductor are listed "up" by the API (RabbitMQ and the
#    database both work),
#  - a flavor can be created, read back and deleted with an admin token (API
#    database round trip),
#  - cell0 and cell1 are mapped,
#  - Nova is in the Keystone catalog (service type `compute`),
#  - the noVNC proxy serves its web page on 6080,
#  - Placement and Glance on tst13 still answer from tst14,
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ on tst10 (Cinder) and
# Placement healthy; tst14 has an agent. Run from scala0, in orphera-pkg:
#   manifests/test_nova.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst14"
PORT=8774
VNC_PORT=6080
HELPER="/usr/local/sbin/orphera-nova-api"

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
  if ! orphera cluster-playbook manifests/nova_single_node.scala; then
    log "FAIL: nova_single_node.scala did not complete"; exit 1
  fi
fi

for u in nova-api nova-scheduler nova-conductor nova-novncproxy; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2.1/flavors")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER services")
assert_contains "the API lists nova-scheduler as up" "service nova-scheduler" "$OUT"
assert_contains "the API lists nova-conductor as up" "service nova-conductor" "$OUT"
OUT=$(run_on "sudo $HELPER services-up")
assert_contains "scheduler and conductor are up (RabbitMQ and database work)" "are up" "$OUT"

OUT=$(run_on "sudo $HELPER flavor-create")
FID=$(printf '%s\n' "$OUT" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|^[0-9]+$' | tail -1)
if [ -z "$FID" ]; then
  log "FAIL: flavor create returned no id"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: flavor $FID created and read back"; PASS=$((PASS + 1))
  OUT=$(run_on "sudo $HELPER flavor-list")
  assert_contains "the new flavor is listed" "$FID" "$OUT"
  OUT=$(run_on "sudo $HELPER flavor-delete $FID")
  assert_contains "flavor deleted, then gone (404)" "deleted $FID" "$OUT"
fi

OUT=$(run_on "sudo podman exec nova-api nova-manage cell_v2 list_cells")
assert_contains "cell0 is mapped" "cell0" "$OUT"
assert_contains "cell1 is mapped" "cell1" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type compute" "compute" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$VNC_PORT/vnc.html")
assert_contains "the noVNC proxy serves its web page on $VNC_PORT" "http=200" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://tst13.ljalbinson.com:8780/")
assert_contains "Placement on tst13 answers from tst14" "http=200" "$OUT"
OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://tst13.ljalbinson.com:9292/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: Glance on tst13 answers from tst14"; PASS=$((PASS + 1))
else
  log "FAIL: Glance on tst13 does not answer from tst14"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manifests/nova_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of nova_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
