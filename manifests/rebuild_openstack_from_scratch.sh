#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DESTRUCTIVE: destroys and re-creates the OpenStack virtual machines, then builds OpenStack on them.
#
# Usage: manifests/rebuild_openstack_from_scratch.sh [--yes] [--dry-run] [-j N] [-a agent.deb]
#                                                    [--tests] [--wordpress]
#   --yes        do not ask for confirmation (otherwise you must type "rebuild")
#   --dry-run    show the plan (nodes, hypervisors, how each hypervisor stores VMs) and stop
#   -j N         VMs built at the same time (default 6)
#   -a FILE      agent package to install (-f also works; default: newest orphera-agent_*_amd64.deb here)
#   --tests      afterwards run the per-service test scripts (keystone ... nova_compute), then the
#                closing checks again (clients, smoke, CirrOS boot). test_keystone.sh tears Keystone
#                down (new database, new CA) and the later scripts re-register their services, so
#                only the closing checks show the finished cluster works together.
#   --wordpress  afterwards rebuild WordPress on tst7 (its database lives on Galera)
#
# The VMs: tst0-tst2 (Ceph), tst3-tst5 (Galera), tst6 (Keystone), tst10 (Cinder, RabbitMQ), tst13
# (Glance, Placement, Horizon), tst14 (Nova), tst16 (Designate), tst17-tst18 (compute), tst19 (Neutron), tst20 (Heat, Barbican), tst22 (Manila). NOT touched:
# the caches (tst9, tst11, tst12), tst7 (WordPress, Prometheus/Grafana), tst8 (Rocky), the
# hypervisors and the machine running this. Everything on the rebuilt VMs is lost: the Ceph data,
# every OpenStack database and every instance and image.
#
# What it does:
#   1. par-mk-new-and-empty.sh: destroy and create each VM, install the agent, DNS, dist-upgrade,
#      base packages (the provisioning script is chosen per hypervisor: ZFS or plain directory)
#   2. build_openstack_stack.sh: Ceph, Galera, Keystone, Cinder, Glance, Placement, Nova, Neutron,
#      compute nodes, Horizon, Designate, Heat, Barbican, Manila, clients, smoke checks and a CirrOS boot
#   3. optional: the test scripts, WordPress
# Run from the repository root, on the machine that runs `orphera`. Does not need the caches
# (tst9, tst11, tst12) or tst7: the build skips the cache steps unless run with --with-caches, and those
# VMs may not exist. A failed step stops the run and says how to resume.
set -uo pipefail
START_DIR=$PWD
HERE=$(cd "$(dirname "$0")" && pwd) || exit 1
cd "$HERE/.." || exit 1
. utils/orphera-sources.sh
CONF_DIR=$(config_dir) || exit 1

NODES=(tst0 tst1 tst2 tst3 tst4 tst5 tst6 tst10 tst13 tst14 tst16 tst17 tst18 tst19 tst20 tst22)
YES="" DRY="" JOBS=6 DEB="" TESTS="" WORDPRESS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) YES=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -j) JOBS="${2:?-j needs a number}"; shift 2 ;;
    -a|-f) DEB="${2:?-a needs a file}"; shift 2 ;;
    --tests) TESTS=1; shift ;;
    --wordpress) WORDPRESS=1; shift ;;
    -h|--help) sed -n '2,/^set -uo pipefail/{/^set -uo pipefail/!p}' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
T0=$(date +%s)
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
die() { log "STOPPED: $1"; exit 1; }

# A relative -a is relative to where the script was started, not to the directory it moves into.
case "$DEB" in /*|"") ;; *) [ -f "$START_DIR/$DEB" ] && DEB="$START_DIR/$DEB" ;; esac
[ -n "$DEB" ] || DEB=$(ls -t "$START_DIR"/orphera-agent_*_amd64.deb orphera-agent_*_amd64.deb 2>/dev/null | head -n 1)
[ -f "$DEB" ] || die "no agent package found (orphera-agent_*_amd64.deb in this directory, or -a FILE)"
for n in "${NODES[@]}"; do [ -f "$CONF_DIR/$n.yaml" ] || die "missing $CONF_DIR/$n.yaml"; done

hypervisor_of() { awk -F: '/^[[:space:]]*hypervisor:/ {gsub(/[[:space:]"'\'']/, "", $2); print $2; exit}' "$CONF_DIR/$1.yaml"; }

log "=== rebuild OpenStack from scratch ==="
log "agent package: $DEB"
printf '%-7s %-11s %s\n' node hypervisor ip
for n in "${NODES[@]}"; do
  printf '%-7s %-11s %s\n' "$n" "$(hypervisor_of "$n")" "$(awk '/ipaddress:/ {gsub(/[\x27"]/, "", $2); print $2; exit}' "$CONF_DIR/$n.yaml")"
done
log "NOT touched: tst7, tst8, tst9, tst11, tst12 and the hypervisors"
[ -z "$WORDPRESS" ] && log "note: tst7's WordPress keeps pointing at a Galera that is about to be rebuilt; add --wordpress to rebuild it afterwards"
[ -n "$DRY" ] && { log "dry run: nothing was changed"; exit 0; }

if [ -z "$YES" ]; then
  printf 'This DESTROYS the %d VMs above and everything on them. Type "rebuild" to go on: ' "${#NODES[@]}"
  read -r answer
  [ "$answer" = "rebuild" ] || die "not confirmed"
fi

log "--- step 1: virtual machines (destroy, create, agent, DNS, dist-upgrade, packages) ---"
S=$(date +%s)
"$HERE/par-mk-new-and-empty.sh" -q -j "$JOBS" -a "$DEB" "${NODES[@]}" \
  || die "building the VMs failed for some nodes (see the list above and .orphera-build-logs/); fix, then re-run with only those nodes: manifests/par-mk-new-and-empty.sh <nodes>, and continue with: manifests/build_openstack_stack.sh"
log "VMs done in $(( $(date +%s) - S ))s"

log "--- step 2: OpenStack ---"
S=$(date +%s)
"$HERE/build_openstack_stack.sh" \
  || die "the OpenStack build stopped; fix it and resume with the command it printed"
log "OpenStack done in $(( $(date +%s) - S ))s"

FAILED=""
if [ -n "$TESTS" ]; then
  log "--- step 3: test scripts ---"
  for t in keystone cinder glance placement nova neutron nova_compute horizon designate heat barbican manila; do
    if "$HERE/test_$t.sh" >"/tmp/rebuild-test-$t.log" 2>&1; then log "PASS: test_$t.sh"
    else log "FAIL: test_$t.sh (output: /tmp/rebuild-test-$t.log)"; FAILED="$FAILED $t"; fi
  done
  # test_keystone.sh rebuilt Keystone (and its CA) after the stack was checked, so check the
  # cluster again as it now stands: the CLI's CA, real objects in every service, a booted instance.
  log "--- step 3b: the cluster after the tests (clients, smoke, boot) ---"
  if "$HERE/build_openstack_stack.sh" --from clients; then log "PASS: the cluster works after the tests"
  else log "FAIL: the closing checks after the tests (see above; re-run: manifests/build_openstack_stack.sh --from clients)"; FAILED="$FAILED closing-checks"; fi
fi
if [ -n "$WORDPRESS" ]; then
  log "--- WordPress on tst7 ---"
  orphera cluster-playbook wordpress_site.scala || { log "FAIL: wordpress_site.scala"; FAILED="$FAILED wordpress"; }
fi

log "=== finished in $(( $(date +%s) - T0 ))s ==="
[ -z "$FAILED" ] || die "failed:$FAILED"
log "done. Dashboard: http://tst13.ljalbinson.com:8080/ (admin, domain Default). CLI: source ~/.config/openstack/admin-openrc.sh"
