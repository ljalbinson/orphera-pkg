#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for trove_guest_image.scala (part 2 of Trove: the guest image and the MySQL datastore).
#
# Applies trove_guest_image.scala (tst21) and trove_datastore.scala (tst25); the first run builds the image on
# tst21, which takes a while. Then it checks
# independently of its own checks:
#  - the Trove guest image is active in Glance with the tags trove and mysql, and the flavor orphera-trove exists,
#  - the Trove API lists the datastore mysql with the version as its default,
#  - a real database instance can be made: ACTIVE and HEALTHY, mysqld in its Docker container (reached by ssh
#    with the trove-mgmt key) reports the expected version, and the instance is deleted again. Whether port 3306
#    is reachable from tst25 is printed, not asserted,
#  - a second run of both descriptions changes nothing (no new build, no new upload), and the datastore is still there.
#
# Prerequisites: trove_single_node.scala has run (test_trove.sh passes); tst21 reaches GitHub, opendev.org and
# download.docker.com; the guests reach Docker Hub and RabbitMQ; Nova has capacity for a 2048 MB guest.
# Run from scala0, in orphera-pkg:   manifests/test_trove_guest.sh
# NO_APPLY=1 skips the apply runs; NO_INSTANCE=1 skips making a database instance.
# TROVE_MYSQL_VERSION must be the same as for the playbook (default 8.0.29).
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

BUILD_NODE="tst21"
NODE="tst25"
IMAGE_HELPER="/usr/local/sbin/orphera-trove-image"
DB_HELPER="/usr/local/sbin/orphera-trove-db"
VERSION="${TROVE_MYSQL_VERSION:-8.0.29}"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-120}" 2>&1; }

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
  log "install (the first run builds the image: 10 to 30 minutes)"
  for d in trove_guest_image.scala trove_datastore.scala; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

OUT=$(RUN_TIMEOUT=120 run_on "$IMAGE_HELPER present" "$BUILD_NODE")
assert_contains "the guest image is active in Glance with the tags trove and mysql, and the flavor exists" "is active and flavor orphera-trove exists" "$OUT"

OUT=$(run_on "$DB_HELPER datastores")
assert_contains "the Trove API lists the datastore mysql with $VERSION as the default version" "default $VERSION" "$OUT"

if [ -z "${NO_INSTANCE:-}" ]; then
  log "making a database instance (several minutes: Nova server, Cinder volume, Docker pull of MySQL)"
  OUT=$(RUN_TIMEOUT=2400 run_on "$DB_HELPER instance-test")
  assert_contains "a MySQL instance reached ACTIVE and HEALTHY" "operating_status HEALTHY" "$OUT"
  assert_contains "mysqld in the guest container reports $VERSION" "server version $VERSION" "$OUT"
  assert_contains "the instance was deleted again" "instance test ok" "$OUT"
  OUT=$(run_on "$DB_HELPER instance-cleanup")
  assert_contains "no test instance is left" "no test instance" "$OUT"
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "second run (must change nothing and not build again)"
  for d in trove_guest_image.scala trove_datastore.scala; do
    SECOND=$(orphera apply "$d" 2>&1); RC=$?
    printf '%s\n' "$SECOND"
    if [ "$RC" -ne 0 ]; then log "FAIL: second run of $d failed"; FAIL=$((FAIL + 1)); fi
    assert_contains "second run of $d changed nothing" "0 changed" "$SECOND"
  done
  OUT=$(run_on "$DB_HELPER datastores")
  assert_contains "the datastore is still there after the second run" "default $VERSION" "$OUT"
fi

log "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
