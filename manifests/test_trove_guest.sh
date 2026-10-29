#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for trove_guest_image.scala (part 2 of Trove: the guest image and the MySQL datastore).
#
# Applies the playbook (the first run builds the image on tst21, which takes a while), then checks
# independently of its own checks:
#  - the Trove guest image is active in Glance with the tags trove and mysql, and the flavor orphera-trove exists,
#  - the Trove API lists the datastore mysql with the version as its default,
#  - a real database instance can be made: ACTIVE and HEALTHY, the MySQL greeting on port 3306 shows the
#    expected server version, and the instance is deleted again,
#  - a second run of the playbook succeeds without building again (idempotence), and the datastore is still there.
#
# Prerequisites: trove_single_node.scala has run (test_trove.sh passes); tst21 reaches GitHub, opendev.org and
# download.docker.com; the guests reach Docker Hub and RabbitMQ; Nova has capacity for a 2048 MB guest.
# Run from scala0, in orphera-pkg:   manifests/test_trove_guest.sh
# NO_APPLY=1 skips the playbook runs; NO_INSTANCE=1 skips making a database instance.
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
  if ! orphera cluster-playbook trove_guest_image.scala; then
    log "FAIL: trove_guest_image.scala did not complete"; exit 1
  fi
fi

OUT=$(RUN_TIMEOUT=120 run_on "$IMAGE_HELPER present" "$BUILD_NODE")
assert_contains "the guest image is active in Glance with the tags trove and mysql, and the flavor exists" "is active and flavor orphera-trove exists" "$OUT"

OUT=$(run_on "$DB_HELPER datastores")
assert_contains "the Trove API lists the datastore mysql with $VERSION as the default version" "default $VERSION" "$OUT"

if [ -z "${NO_INSTANCE:-}" ]; then
  log "making a database instance (several minutes: Nova server, Cinder volume, Docker pull of MySQL)"
  OUT=$(RUN_TIMEOUT=2400 run_on "$DB_HELPER instance-test")
  assert_contains "a MySQL instance reached ACTIVE and HEALTHY" "operating_status HEALTHY" "$OUT"
  assert_contains "MySQL $VERSION answered on port 3306" "server version $VERSION" "$OUT"
  assert_contains "the instance was deleted again" "instance test ok" "$OUT"
  OUT=$(run_on "$DB_HELPER instance-cleanup")
  assert_contains "no test instance is left" "no test instance" "$OUT"
fi

if [ -z "${NO_APPLY:-}" ]; then
  IMAGE_FILE="/var/lib/orphera/trove-image/trove-guest-ubuntu-noble.qcow2"
  BEFORE=$(run_on "stat -c %Y $IMAGE_FILE" "$BUILD_NODE" | tail -1)
  log "second run (must not build again)"
  if orphera cluster-playbook trove_guest_image.scala >/dev/null 2>&1; then
    log "PASS: second run of the playbook succeeds"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of the playbook failed"; FAIL=$((FAIL + 1))
  fi
  AFTER=$(run_on "stat -c %Y $IMAGE_FILE" "$BUILD_NODE" | tail -1)
  if [ -n "$BEFORE" ] && [ "$BEFORE" = "$AFTER" ]; then
    log "PASS: the second run did not build the image again"; PASS=$((PASS + 1))
  else
    log "FAIL: the image file changed or could not be read (before: $BEFORE, after: $AFTER)"; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "$DB_HELPER datastores")
  assert_contains "the datastore is still there after the second run" "default $VERSION" "$OUT"
fi

log "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
