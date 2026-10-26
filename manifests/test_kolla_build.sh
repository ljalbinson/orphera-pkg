#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for kolla_build.scala. Applies the playbook, then checks on tst21:
#  - the registry, devpi and nginx answer,
#  - the base image and the source tarballs are cached (MANIFEST lists them),
#  - kolla-build is installed,
#  - a second run is idempotent.
# BUILD=1 also builds one small image (keystone) from the cached sources and checks that it reaches the
# registry and that a node can pull it. That takes a long time the first time.
# NO_APPLY=1 skips the playbook runs. Run from scala0, in orphera-pkg:   manifests/test_kolla_build.sh
set -uo pipefail

NODE="tst21"
PASS=0
FAIL=0
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }
assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then log "PASS: $desc"; PASS=$((PASS + 1))
  else log "FAIL: $desc (expected to find: $needle)"; printf '%s\n' "$haystack" | sed 's/^/    /'; FAIL=$((FAIL + 1)); fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  orphera cluster-playbook kolla_build.scala || { log "FAIL: kolla_build.scala did not complete"; exit 1; }
fi

assert_contains "the registry answers" "http=200" "$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:5000/v2/")"
assert_contains "devpi answers" "http=200" "$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:3141/")"
assert_contains "the source tarballs are served" "keystone-stable-2025.1.tar.gz" "$(run_on "curl -s http://localhost:8081/")"
assert_contains "the base image is in the local registry" "24.04" "$(run_on "curl -s http://localhost:5000/v2/library/ubuntu/tags/list")"
assert_contains "the checksum manifest lists the keystone tarball" "keystone-stable-2025.1.tar.gz" "$(run_on "cat /srv/kolla-sources/MANIFEST")"
assert_contains "kolla-build is installed" "version=20" "$(run_on "echo version=\$(/opt/kolla-build/bin/kolla-build --version)")"
assert_contains "a repeat of sources downloads nothing" "unchanged" "$(run_on "orphera-kolla-build sources refresh | head -3")"

if [ -n "${BUILD:-}" ]; then
  log "build keystone (long the first time)"
  OUT=$(RUN_TIMEOUT=3600 run_on "sudo orphera-kolla-build build keystone")
  assert_contains "the build finished" "build finished" "$OUT"
  assert_contains "keystone is in the registry" "openstack.kolla/keystone" "$(run_on "curl -s http://localhost:5000/v2/_catalog")"
  OUT=$(run_on "sudo podman pull --tls-verify=false tst21.ljalbinson.com:5000/openstack.kolla/keystone:2025.1-ubuntu-noble >/dev/null 2>&1 && echo pull-ok-\$((6*7))" tst6)
  assert_contains "a node can pull the built image" "pull-ok-42" "$OUT"
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook kolla_build.scala; then log "PASS: second run completed"; PASS=$((PASS + 1))
  else log "FAIL: second run failed"; FAIL=$((FAIL + 1)); fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
