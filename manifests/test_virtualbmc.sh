#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT test for virtualbmc.scala and utils/vbmc-vm.sh.
#
# Applies the playbook, then checks on each hypervisor that orphera-vbmcd is active and `vbmc list` answers.
# With TEST_VM=tstN (a VM that can be left alone for a minute; its state is only read) it also makes the VM's
# endpoint, checks that `ipmitool chassis power status` agrees with libvirt, that a second `add` changes nothing,
# and removes the endpoint again. POWER_CYCLE=1 also powers the VM off and on through IPMI and waits for it to
# come back to the state it had (this stops the VM for a short time).
#
# Run from scala0, in orphera-pkg:   TEST_VM=tst11 manifests/test_virtualbmc.sh
# NO_APPLY=1 skips the playbook run. VBMC_HOSTS limits the hypervisors, as for the playbook.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE/.." || exit 1
. utils/vm-common.sh

HOSTS=$(printf '%s' "${VBMC_HOSTS:-gs1,gs2,gs3,st0}" | tr ',' ' ')
PASS=0
FAIL=0
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then log "PASS: $desc"; PASS=$((PASS + 1))
  else log "FAIL: $desc (expected to find: $needle)"; printf '%s\n' "$haystack" | sed 's/^/    /'; FAIL=$((FAIL + 1)); fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  orphera cluster-playbook virtualbmc.scala || { log "FAIL: virtualbmc.scala did not complete"; exit 1; }
fi

for h in $HOSTS; do
  OUT=$(orphera run --nodes "$h" --quiet --timeout 60 -- sh -c "echo state=\$(systemctl is-active orphera-vbmcd); HOME=/root /opt/virtualbmc/bin/vbmc list >/dev/null 2>&1 && echo list=ok" 2>&1)
  assert_contains "orphera-vbmcd is active on $h" "state=active" "$OUT"
  assert_contains "vbmc list answers on $h" "list=ok" "$OUT"
done

if [ -n "${TEST_VM:-}" ]; then
  vm_lookup "$TEST_VM" || exit 1
  OUT=$(utils/vbmc-vm.sh add "$vm" 2>&1)
  assert_contains "the endpoint of $vm is made and running" "running" "$OUT"
  OUT=$(utils/vbmc-vm.sh add "$vm" 2>&1)
  assert_contains "a second add succeeds" "running" "$OUT"
  vm_run "$hv" 'echo "RESULT|ok|$(virsh --connect qemu:///system domstate '"$vm"' | head -1)"' 30
  libvirt_state=${result#ok|}
  OUT=$(utils/vbmc-vm.sh power "$vm" status 2>&1)
  if [ "$libvirt_state" = "running" ]; then want="Chassis Power is on"; else want="Chassis Power is off"; fi
  assert_contains "IPMI power status agrees with libvirt ($libvirt_state)" "$want" "$OUT"
  if [ -n "${POWER_CYCLE:-}" ] && [ "$libvirt_state" = "running" ]; then
    utils/vbmc-vm.sh power "$vm" off >/dev/null 2>&1
    sleep 5
    assert_contains "IPMI power off stops the VM" "Chassis Power is off" "$(utils/vbmc-vm.sh power "$vm" status 2>&1)"
    utils/vbmc-vm.sh power "$vm" on >/dev/null 2>&1
    sleep 5
    assert_contains "IPMI power on starts the VM" "Chassis Power is on" "$(utils/vbmc-vm.sh power "$vm" status 2>&1)"
  fi
  OUT=$(utils/vbmc-vm.sh remove "$vm" 2>&1)
  assert_contains "the endpoint is removed" "removed" "$OUT"
else
  log "TEST_VM not set: the per-VM checks are skipped"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
