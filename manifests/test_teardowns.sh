#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs the teardown of one or more OpenStack modules on the lab, then builds the module again, so that one run
# shows that the teardown removes what the description made, that it can be run twice, and that the module comes
# back from nothing.
#
# For each module, in the order given:
#  1. the teardown descriptions are applied (a failed apply stops the module),
#  2. nothing is left of it: its systemd units (or the RGW daemon) are gone from the node(s), and, if the
#     OpenStack CLI works on this machine (utils/openstack-client-setup.sh), its service type is gone from the
#     Keystone catalog,
#  3. the teardown is applied a second time and changes nothing,
#  4. the module is applied again (build_openstack_stack.sh --only STEP, or the description itself) and its
#     own test script runs (it applies the descriptions once more and checks the service).
#
# Usage:   manifests/test_teardowns.sh [--no-rebuild] MODULE...
#          manifests/test_teardowns.sh --list
# Modules: keycloak skyline trove telemetry magnum octavia manila rgw nova_compute kolla_cache kolla_build
# Order:   take a module before the ones it depends on: keycloak, skyline, trove, telemetry, magnum (needs
#          Octavia), octavia, manila, rgw, nova_compute (removes the compute services and any instance disks),
#          kolla_cache, kolla_build. Keystone, Glance, Placement, Nova, Neutron, Cinder, Heat, Horizon, Barbican
#          and Designate are not here: their test scripts already tear down and rebuild.
# Environment: the purge variables of the teardowns (KOLLA_BUILD_PURGE, RGW_PURGE_POOLS, MANILA_PURGE_CEPH,
#          TROVE_REMOVE_BUILD) are passed on, but they are not set here; by default data that takes long to make
#          again is kept (built images, RGW pools, the CephFS filesystem, the Trove guest image).
# Run from scala0, in orphera-pkg. Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PASS=0
FAIL=0
REBUILD=1

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
pass() { log "PASS: $1"; PASS=$((PASS + 1)); }
fail() { log "FAIL: $1"; FAIL=$((FAIL + 1)); }
run_on() { orphera run sh -c "$2" --nodes "$1" --timeout "${RUN_TIMEOUT:-120}" 2>&1; }

# Per module: TEARDOWNS (applied in this order), NODES and UNITS (unit-name patterns that must be gone from each
# node), CATALOG (service types that must be gone from Keystone), REBUILD (command), TEST (script).
MODULES="keycloak skyline trove telemetry magnum octavia manila rgw nova_compute kolla_cache kolla_build"

define() {
  TEARDOWNS="" NODES="" UNITS="" CATALOG="" REBUILD_CMD="" TEST="" EXTRA=""
  case "$1" in
    keycloak)
      TEARDOWNS="keycloak_teardown.scala"; NODES="tst26"; UNITS="orphera-keycloak*"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only keycloak"; TEST="manifests/test_keycloak.sh" ;;
    skyline)
      TEARDOWNS="skyline_teardown.scala"; NODES="tst13"; UNITS="orphera-skyline*"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only skyline"; TEST="manifests/test_skyline.sh" ;;
    trove)
      TEARDOWNS="trove_guest_image_teardown.scala trove_teardown.scala"; NODES="tst25"; UNITS="orphera-trove*"
      CATALOG="database"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only trove"; TEST="manifests/test_trove.sh manifests/test_trove_guest.sh" ;;
    telemetry)
      TEARDOWNS="telemetry_teardown.scala"; NODES="tst24 tst17 tst18"; UNITS="orphera-ceilometer* orphera-aodh*"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only telemetry"; TEST="manifests/test_telemetry.sh" ;;
    magnum)
      TEARDOWNS="magnum_teardown.scala"; NODES="tst23"; UNITS="orphera-magnum*"; CATALOG="container-infra"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only magnum"; TEST="manifests/test_magnum.sh" ;;
    octavia)
      TEARDOWNS="octavia_teardown.scala"; NODES="tst19"; UNITS="orphera-octavia*"; CATALOG="load-balancer"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only octavia"; TEST="manifests/test_octavia.sh" ;;
    manila)
      TEARDOWNS="manila_teardown.scala"; NODES="tst22"; UNITS="orphera-manila*"; CATALOG="sharev2 share"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only manila"; TEST="manifests/test_manila.sh" ;;
    rgw)
      TEARDOWNS="rgw_teardown.scala"; NODES=""; UNITS=""; CATALOG="object-store s3"; EXTRA="rgw"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only rgw"; TEST="manifests/test_rgw.sh" ;;
    nova_compute)
      TEARDOWNS="nova_compute_teardown.scala"; NODES="tst17 tst18"; UNITS="orphera-nova-compute* orphera-neutron-openvswitch-agent*"
      REBUILD_CMD="manifests/build_openstack_stack.sh --only compute"; TEST="manifests/test_nova_compute.sh" ;;
    kolla_cache)
      TEARDOWNS="kolla_cache_teardown.scala"; NODES="tst9"; UNITS="orphera-kolla-cache*"
      REBUILD_CMD="orphera apply kolla_cache.scala"; TEST="manifests/test_kolla_cache.sh" ;;
    kolla_build)
      TEARDOWNS="kolla_build_teardown.scala"; NODES="tst21"; UNITS="orphera-kolla-registry* orphera-devpi*"
      REBUILD_CMD="orphera apply kolla_build.scala"; TEST="manifests/test_kolla_build.sh" ;;
    *) return 1 ;;
  esac
}

# Apply one description, keep its output in $OUT, and fail on a non-zero exit or a failed apply.
apply_td() {
  local f="$1"
  log "applying $f"
  local tmp rc
  tmp=$(mktemp)
  orphera apply "$f" 2>&1 | tee "$tmp"
  rc=${PIPESTATUS[0]}
  OUT=$(cat "$tmp"); rm -f "$tmp"
  if [ "$rc" -ne 0 ] || printf '%s\n' "$OUT" | grep -qE "Apply '.*' failed"; then return 1; fi
  return 0
}

catalog_types() {
  command -v openstack >/dev/null 2>&1 || return 1
  # shellcheck disable=SC1090
  [ -f "$HOME/.config/openstack/admin-openrc.sh" ] && . "$HOME/.config/openstack/admin-openrc.sh"
  openstack service list -f value -c Type 2>/dev/null
}

test_module() {
  local m="$1" td node pat out left types t
  define "$m" || { echo "unknown module: $m (modules: $MODULES)" >&2; return 2; }
  log "=== $m: teardown ==="

  for td in $TEARDOWNS; do
    if ! apply_td "manifests/$td"; then fail "$td did not complete"; return 1; fi
  done
  pass "the teardown of $m completed"

  for node in $NODES; do
    left=""
    for pat in $UNITS; do
      out=$(run_on "$node" "systemctl list-unit-files '$pat' --no-legend | awk '{print \$1}'")
      left="$left $(printf '%s\n' "$out" | grep -oE 'orphera-[A-Za-z0-9@._-]*\.(service|timer)' | sort -u | tr '\n' ' ')"
    done
    if [ -z "$(printf '%s' "$left" | tr -d ' ')" ]; then pass "no $m unit is left on $node"
    else fail "units left on $node:$left"; fi
  done
  if [ "$EXTRA" = "rgw" ]; then
    out=$(run_on tst0 "sudo cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -c 'rgw.orphera'")
    if printf '%s\n' "$out" | grep -qE '(^|[] ])0$'; then pass "the RGW daemon is gone"; else fail "the RGW daemon is still listed: $out"; fi
  fi

  if [ -n "$CATALOG" ]; then
    if types=$(catalog_types) && [ -n "$types" ]; then
      for t in $CATALOG; do
        if printf '%s\n' "$types" | grep -qx "$t"; then fail "service type $t is still in the Keystone catalog"
        else pass "service type $t is gone from the Keystone catalog"; fi
      done
    else
      log "SKIP: the OpenStack CLI does not work here (utils/openstack-client-setup.sh): catalog not checked"
    fi
  fi

  log "=== $m: the teardown a second time ==="
  for td in $TEARDOWNS; do
    if ! apply_td "manifests/$td"; then fail "second run of $td did not complete"; continue; fi
    if printf '%s\n' "$OUT" | grep -qE ': [1-9][0-9]* changed'; then fail "second run of $td changed something"
    else pass "second run of $td changed nothing"; fi
  done

  if [ -z "$REBUILD" ] || [ "$REBUILD" = 0 ]; then log "skipping the rebuild and the test (--no-rebuild)"; return 0; fi

  log "=== $m: build it again ==="
  if ! $REBUILD_CMD; then fail "$REBUILD_CMD did not complete"; return 1; fi
  pass "$m was built again"
  for t in $TEST; do
    log "=== $m: $t ==="
    if NO_APPLY= "$t"; then pass "$t passed after the rebuild"; else fail "$t failed after the rebuild"; fi
  done
}

case "${1:-}" in
  --list) echo "$MODULES"; exit 0 ;;
  -h|--help|"") sed -n '2,/^set -uo pipefail/{/^set -uo pipefail/!p}' "$0"; exit 0 ;;
esac

MODS=()
for a in "$@"; do
  case "$a" in
    --no-rebuild) REBUILD=0 ;;
    *) MODS+=("$a") ;;
  esac
done
for m in "${MODS[@]}"; do define "$m" || { echo "unknown module: $m (modules: $MODULES)" >&2; exit 2; }; done

for m in "${MODS[@]}"; do
  test_module "$m" || true
done

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
