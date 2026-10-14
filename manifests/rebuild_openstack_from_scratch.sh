#!/usr/bin/env bash
#
# DESTRUCTIVE: destroys and re-creates the OpenStack virtual machines, then builds OpenStack on them.
#
# Usage: manifests/rebuild_openstack_from_scratch.sh [--yes] [--dry-run] [-j N] [-f agent.deb]
#                                                    [--tests] [--wordpress]
#   --yes        do not ask for confirmation (otherwise you must type "rebuild")
#   --dry-run    show the plan (nodes, hypervisors, how each hypervisor stores VMs) and stop
#   -j N         VMs built at the same time (default 6)
#   -f FILE      agent package to install (default: newest orphera-agent_*_amd64.deb here)
#   --tests      afterwards run the per-service test scripts (keystone ... nova_compute)
#   --wordpress  afterwards rebuild WordPress on tst7 (its database lives on Galera)
#
# The VMs: tst0-tst2 (Ceph), tst3-tst5 (Galera), tst6 (Keystone), tst10 (Cinder, RabbitMQ), tst13
# (Glance, Placement, Horizon), tst14 (Nova), tst17-tst18 (compute), tst19 (Neutron). NOT touched:
# the caches (tst9, tst11, tst12), tst7 (WordPress, Prometheus/Grafana), tst8 (Rocky), the
# hypervisors and the machine running this. Everything on the rebuilt VMs is lost: the Ceph data,
# every OpenStack database and every instance and image.
#
# What it does:
#   1. par-mk-new-and-empty.sh: destroy and create each VM, install the agent, DNS, dist-upgrade,
#      base packages (the provisioning script is chosen per hypervisor: ZFS or plain directory)
#   2. build_openstack_stack.sh: Ceph, Galera, Keystone, Cinder, Glance, Placement, Nova, Neutron,
#      compute nodes, Horizon, clients, smoke checks and a CirrOS boot
#   3. optional: the test scripts, WordPress
# Run from the repository root, on the machine that runs `orphera`. Does not need the caches
# (tst9, tst11, tst12) or tst7: the build skips the cache steps unless run with --with-caches, and those
# VMs may not exist. A failed step stops the run and says how to resume.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

NODES=(tst0 tst1 tst2 tst3 tst4 tst5 tst6 tst10 tst13 tst14 tst17 tst18 tst19)
YES="" DRY="" JOBS=6 DEB="" TESTS="" WORDPRESS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) YES=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -j) JOBS="${2:?-j needs a number}"; shift 2 ;;
    -f) DEB="${2:?-f needs a file}"; shift 2 ;;
    --tests) TESTS=1; shift ;;
    --wordpress) WORDPRESS=1; shift ;;
    -h|--help) sed -n '2,29p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
T0=$(date +%s)
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
die() { log "STOPPED: $1"; exit 1; }

[ -n "$DEB" ] || DEB=$(ls -t orphera-agent_*_amd64.deb 2>/dev/null | head -n 1)
[ -f "$DEB" ] || die "no agent package found (orphera-agent_*_amd64.deb in this directory, or -f FILE)"
for n in "${NODES[@]}"; do [ -f "config/$n.yaml" ] || die "missing config/$n.yaml"; done

hypervisor_of() { awk -F: '/^[[:space:]]*hypervisor:/ {gsub(/[[:space:]"'\'']/, "", $2); print $2; exit}' "config/$1.yaml"; }

log "=== rebuild OpenStack from scratch ==="
log "agent package: $DEB"
printf '%-7s %-11s %s\n' node hypervisor ip
for n in "${NODES[@]}"; do
  printf '%-7s %-11s %s\n' "$n" "$(hypervisor_of "$n")" "$(awk '/ipaddress:/ {gsub(/[\x27"]/, "", $2); print $2; exit}' "config/$n.yaml")"
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
manifests/par-mk-new-and-empty.sh -q -j "$JOBS" -f "$DEB" "${NODES[@]}" \
  || die "building the VMs failed for some nodes (see the list above and .orphera-build-logs/); fix, then re-run with only those nodes: manifests/par-mk-new-and-empty.sh <nodes>, and continue with: manifests/build_openstack_stack.sh"
log "VMs done in $(( $(date +%s) - S ))s"

log "--- step 2: OpenStack ---"
S=$(date +%s)
manifests/build_openstack_stack.sh \
  || die "the OpenStack build stopped; fix it and resume with the command it printed"
log "OpenStack done in $(( $(date +%s) - S ))s"

FAILED=""
if [ -n "$TESTS" ]; then
  log "--- step 3: test scripts ---"
  for t in keystone cinder glance placement nova neutron nova_compute horizon; do
    if manifests/test_$t.sh >"/tmp/rebuild-test-$t.log" 2>&1; then log "PASS: test_$t.sh"
    else log "FAIL: test_$t.sh (output: /tmp/rebuild-test-$t.log)"; FAILED="$FAILED $t"; fi
  done
fi
if [ -n "$WORDPRESS" ]; then
  log "--- WordPress on tst7 ---"
  orphera cluster-playbook manifests/wordpress_site.scala || { log "FAIL: wordpress_site.scala"; FAILED="$FAILED wordpress"; }
fi

log "=== finished in $(( $(date +%s) - T0 ))s ==="
[ -z "$FAILED" ] || die "failed:$FAILED"
log "done. Dashboard: http://tst13.ljalbinson.com:8080/ (admin, domain Default). CLI: source ~/.config/openstack/admin-openrc.sh"
