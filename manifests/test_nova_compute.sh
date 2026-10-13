#!/usr/bin/env bash
#
# DRAFT end-to-end test for nova_compute.scala (nova-compute + Neutron OVS agent on
# tst17 and tst18).
#
# Applies the playbook, then checks independently of its own health check, on each
# node:
#  - orphera-nova-compute and orphera-neutron-openvswitch-agent are active,
#  - /dev/kvm exists (nested virtualization) and libvirtd answers,
#  - the provider NIC (enp2s0) has no IPv4 address and is a port of br-provider, while
#    the management NIC (enp1s0) still has its address,
# and then, through the APIs:
#  - both nova-compute services are up and both OVS agents are alive,
#  - both nodes are listed as hypervisors,
#  - Nova (tst14) and Neutron (tst19) are still healthy,
#  - an instance boots: a CirrOS image is uploaded to Glance, a flat provider network
#    with DHCP (10.10.6.0/24, pool .200-.220) is created, the instance is started
#    with a config drive, its console shows the login prompt and a DHCP lease from
#    that pool, and everything is deleted again (this exercises the scheduler,
#    Placement, Glance, Neutron port binding, libvirt/KVM and the DHCP agent),
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ, Glance, Placement, Nova (tst14) and
# Neutron (tst19) healthy, no flat network left on the physical network `provider`
# (only one is allowed), agents on tst17 and tst18, internet access from scala0 for
# the CirrOS image (or CIRROS_IMAGE=/path/to/cirros.qcow2).
# Run from scala0, in orphera-pkg:   manifests/test_nova_compute.sh
# NO_APPLY=1 skips the playbook runs; SKIP_BOOT=1 skips the instance boot;
# KEEP_BOOT=1 leaves the test instance, image, flavor and network in place for inspection.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODES=(tst17 tst18)
MGMT_IPS=(10.10.5.29 10.10.5.30)
NIC="enp2s0"
MGMT_NIC="enp1s0"
BRIDGE="br-provider"
HELPER="/usr/local/sbin/orphera-compute-api"
CIRROS_URL="https://download.cirros-cloud.net/0.6.2/cirros-0.6.2-x86_64-disk.img"
CIRROS_LOCAL="${CIRROS_IMAGE:-/tmp/cirros-0.6.2-x86_64-disk.img}"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { local node="$1"; shift; orphera run sh -c "$1" --nodes "$node" 2>&1; }

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
  if ! orphera cluster-playbook manifests/nova_compute.scala; then
    log "FAIL: nova_compute.scala did not complete"; exit 1
  fi
fi

for i in "${!NODES[@]}"; do
  node="${NODES[$i]}"
  for u in nova-compute neutron-openvswitch-agent libvirtd; do
    unit="orphera-$u"; [ "$u" = "libvirtd" ] && unit="libvirtd"
    OUT=$(run_on "$node" "systemctl is-active $unit")
    assert_contains "$node: $unit is active" "active" "$OUT"
  done
  OUT=$(run_on "$node" "test -c /dev/kvm && echo kvm-present")
  assert_contains "$node: /dev/kvm exists (nested virtualization)" "kvm-present" "$OUT"
  OUT=$(run_on "$node" "virsh -c qemu:///system version")
  assert_contains "$node: libvirt answers and runs QEMU" "hypervisor" "$OUT"

  OUT=$(run_on "$node" "ip -4 -o addr show $NIC | wc -l")
  if printf '%s' "$OUT" | grep -qE '(^|[^0-9])0([^0-9]|$)'; then
    log "PASS: $node: $NIC has no IPv4 address"; PASS=$((PASS + 1))
  else
    log "FAIL: $node: $NIC still has an IPv4 address"; run_on "$node" "ip -4 -br addr show $NIC" | sed 's/^/    /'; FAIL=$((FAIL + 1))
  fi
  OUT=$(run_on "$node" "ovs-vsctl list-ports $BRIDGE")
  assert_contains "$node: $NIC is a port of $BRIDGE" "$NIC" "$OUT"
  OUT=$(run_on "$node" "ip -4 -br addr show $MGMT_NIC")
  assert_contains "$node: the management NIC $MGMT_NIC still has its address" "${MGMT_IPS[$i]}" "$OUT"
done

OUT=$(run_on tst17 "sudo $HELPER compute-up tst17 tst18")
assert_contains "both nova-compute services are up and enabled" "nova-compute is up on tst17, tst18" "$OUT"
OUT=$(run_on tst17 "sudo $HELPER ovs-up tst17 tst18")
assert_contains "both Open vSwitch agents are alive" "alive on tst17, tst18" "$OUT"
OUT=$(run_on tst17 "sudo $HELPER hypervisors")
assert_contains "tst17 is a hypervisor" "hypervisor tst17" "$OUT"
assert_contains "tst18 is a hypervisor" "hypervisor tst18" "$OUT"

OUT=$(run_on tst14 "sudo /usr/local/sbin/orphera-nova-api services-up")
assert_contains "Nova on tst14 still has its scheduler and conductor up" "are up" "$OUT"
OUT=$(run_on tst19 "sudo /usr/local/sbin/orphera-neutron-api agents-up")
assert_contains "Neutron on tst19 still has its OVS and DHCP agents alive" "are alive" "$OUT"

if [ -z "${SKIP_BOOT:-}" ]; then
  if [ ! -s "$CIRROS_LOCAL" ]; then
    log "downloading CirrOS to $CIRROS_LOCAL"
    curl -fL -o "$CIRROS_LOCAL" "$CIRROS_URL" || { log "FAIL: could not download $CIRROS_URL (set CIRROS_IMAGE=/path)"; rm -f "$CIRROS_LOCAL"; FAIL=$((FAIL + 1)); }
  fi
  if [ -s "$CIRROS_LOCAL" ]; then
    if orphera copy "$CIRROS_LOCAL" /root/cirros-boot-test.qcow2 --owner root --group root --mode 0644 --nodes tst17 >/dev/null 2>&1; then
      run_on tst17 "sudo $HELPER boot-cleanup" >/dev/null
      log "booting a CirrOS instance (can take a few minutes)"
      OUT=$(orphera run sh -c "sudo $HELPER boot-test /root/cirros-boot-test.qcow2 --keep" --nodes tst17 --timeout 900 2>&1)
      assert_contains "an instance boots on KVM and its Neutron port goes ACTIVE" "instance ready:" "$OUT"
      case "$OUT" in
        *"instance ready:"*)
          READY=$(printf '%s\n' "$OUT" | grep -o 'instance ready: .*' | head -n 1)
          G_IP=$(printf '%s' "$READY" | sed -n 's/.*ip=\([^ ]*\).*/\1/p')
          G_MAC=$(printf '%s' "$READY" | sed -n 's/.*mac=\([^ ]*\).*/\1/p')
          G_NET=$(printf '%s' "$READY" | sed -n 's/.*network=\([^ ]*\).*/\1/p')
          CHK=$(utils/check-guest-dhcp.sh "$G_IP" "$G_MAC" "$G_NET")
          assert_contains "the guest took its DHCP address ($G_IP) and answers ARP" "answers ARP" "$CHK"
          case "$CHK" in *"answers ARP"*) ;; *) printf '%s\n' "$CHK" | sed 's/^/    /' ;; esac ;;
        *) printf '%s\n' "$OUT" | sed 's/^/    /' ;;
      esac
      if [ -z "${KEEP_BOOT:-}" ]; then run_on tst17 "sudo $HELPER boot-cleanup" >/dev/null; fi
      run_on tst17 "rm -f /root/cirros-boot-test.qcow2" >/dev/null
    else
      log "FAIL: could not copy the CirrOS image to tst17"; FAIL=$((FAIL + 1))
    fi
  fi
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook manifests/nova_compute.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of nova_compute.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
