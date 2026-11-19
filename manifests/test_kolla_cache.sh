#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for kolla_cache.scala (the pull-through cache of quay.io on tst9).
#
# Applies the description, then checks independently of its own steps:
#  - orphera-kolla-cache is active on tst9,
#  - the registry answers on port 5000,
#  - the catalog lists openstack.kolla/keystone (the warm-up pulled it through the cache),
#  - the data directory is not empty,
#  - a node that uses the mirror (tst6) can pull the Keystone image,
#  - a second run changes nothing.
#
# Prerequisites: tst9 has an agent and can reach quay.io; tst6 has the mirror setting (keystone_single_node.scala).
# Run from scala0, in orphera-pkg:   manifests/test_kolla_cache.sh
# NO_APPLY=1 skips the apply runs. Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst9"
TAG="2025.1-ubuntu-noble"
PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-120}" 2>&1; }
assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then log "PASS: $desc"; PASS=$((PASS + 1))
  else log "FAIL: $desc (expected to find: $needle)"; printf '%s\n' "$haystack" | sed 's/^/    /'; FAIL=$((FAIL + 1)); fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  orphera apply kolla_cache.scala || { log "FAIL: kolla_cache.scala did not complete"; exit 1; }
fi

assert_contains "orphera-kolla-cache is active" "active" "$(run_on "systemctl is-active orphera-kolla-cache")"
assert_contains "the registry answers" "http=200" "$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:5000/v2/")"
assert_contains "the catalog lists openstack.kolla/keystone" "openstack.kolla/keystone" "$(run_on "curl -s http://localhost:5000/v2/_catalog")"
assert_contains "the cache holds data" "cache-has-data" "$(run_on "[ -n \"\$(ls -A /var/lib/orphera/kolla-cache)\" ] && echo cache-has-data")"
OUT=$(RUN_TIMEOUT=600 run_on "sudo podman pull quay.io/openstack.kolla/keystone:$TAG >/dev/null 2>&1 && echo pull-ok-\$((6*7))" tst6)
assert_contains "a node using the mirror can pull the Keystone image" "pull-ok-42" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  SECOND=$(orphera apply kolla_cache.scala 2>&1); RC=$?
  printf '%s\n' "$SECOND"
  if [ "$RC" -ne 0 ]; then log "FAIL: second run of kolla_cache.scala failed"; FAIL=$((FAIL + 1)); fi
  assert_contains "second run changed nothing" "0 changed" "$SECOND"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
