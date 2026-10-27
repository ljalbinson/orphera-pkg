#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for rgw_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the RGW daemon is listed as running by the Ceph orchestrator,
#  - RGW answers on 8080,
#  - an unauthenticated Swift request is refused (401),
#  - the catalog lists the service types object-store and s3,
#  - a container and an object are made through the Swift API with a Keystone token, read back and deleted,
#  - a bucket and an object are made through the S3 API with EC2 credentials from Keystone, read back and
#    deleted,
#  - a second run of the playbook succeeds without restarting the daemon, and both APIs still work.
#
# Prerequisites: Keystone and Ceph healthy; tst0 has an agent and cephadm.
# Run from scala0, in orphera-pkg:   manifests/test_rgw.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst0"
RGW="10.10.5.12:8080"
HELPER="/usr/local/sbin/orphera-rgw-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" --timeout "${RUN_TIMEOUT:-120}" 2>&1; }

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
  if ! orphera cluster-playbook rgw_single_node.scala; then
    log "FAIL: rgw_single_node.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.orphera.*running' && echo daemon-state=\$(echo running)")
assert_contains "the orchestrator lists the RGW daemon as running" "daemon-state=running" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://$RGW/")
if printf '%s' "$OUT" | grep -qE 'http=[1-5][0-9][0-9]'; then
  log "PASS: RGW answers on 8080"; PASS=$((PASS + 1))
else
  log "FAIL: RGW does not answer on 8080"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://$RGW/swift/v1/AUTH_nobody/x")
assert_contains "an unauthenticated Swift request is refused" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type object-store" "object-store" "$OUT"
assert_contains "the catalog lists service type s3" " s3" "$OUT"

OUT=$(run_on "sudo $HELPER swift-cycle")
assert_contains "a container and an object are made with a Keystone token, read back and deleted" "swift cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER s3-cycle")
assert_contains "a bucket and an object are made with EC2 credentials, read back and deleted" "s3 cycle ok" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if OUT=$(orphera cluster-playbook rgw_single_node.scala 2>&1); then
    log "PASS: second run completed"; PASS=$((PASS + 1))
    assert_contains "the second run leaves the RGW daemon alone" "RGW configuration unchanged" "$OUT"
  else
    log "FAIL: second run of rgw_single_node.scala failed"; printf '%s\n' "$OUT" | tail -20 | sed 's/^/    /'; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "sudo $HELPER swift-cycle")
  assert_contains "Swift still works after the second run" "swift cycle ok" "$OUT"
  OUT=$(run_on "sudo $HELPER s3-cycle")
  assert_contains "S3 still works after the second run" "s3 cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
