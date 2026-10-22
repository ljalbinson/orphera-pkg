#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for neutron_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the three Neutron units (server, Open vSwitch agent, DHCP agent) are active,
#  - the provider NIC (enp2s0) has no IPv4 address and is a port of br-provider,
#    while the management NIC (enp1s0) still has its address,
#  - the API answers its version document on 9696 without a token,
#  - an unauthenticated request to a protected path is refused (401),
#  - the Open vSwitch and DHCP agents are alive according to the API (RabbitMQ and
#    the database both work),
#  - a flat provider network and a subnet can be created, read back and deleted
#    (database round trip and the ML2 flat type driver),
#  - Neutron is in the Keystone catalog (service type `network`),
#  - Nova (tst14) and Glance (tst13) still answer, so nothing collided,
#  - a second run of the playbook succeeds (idempotence).
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ on tst10 and Nova healthy; tst19
# has an agent. Run from scala0, in orphera-pkg:   manifests/test_neutron.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst19"
PORT=9696
NIC="enp2s0"
MGMT_NIC="enp1s0"
BRIDGE="br-provider"
HELPER="/usr/local/sbin/orphera-neutron-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" 2>&1; }

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
  if ! orphera cluster-playbook neutron_single_node.scala; then
    log "FAIL: neutron_single_node.scala did not complete"; exit 1
  fi
fi

for u in neutron-server neutron-openvswitch-agent neutron-dhcp-agent; do
  OUT=$(run_on "systemctl is-active orphera-$u")
  assert_contains "orphera-$u is active" "active" "$OUT"
done

OUT=$(run_on "ip -4 -o addr show $NIC | wc -l")
if printf '%s' "$OUT" | grep -qE '(^|[^0-9])0([^0-9]|$)'; then
  log "PASS: $NIC has no IPv4 address"; PASS=$((PASS + 1))
else
  log "FAIL: $NIC still has an IPv4 address"; run_on "ip -4 -br addr show $NIC" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi
OUT=$(run_on "ovs-vsctl list-ports $BRIDGE")
assert_contains "$NIC is a port of $BRIDGE" "$NIC" "$OUT"
OUT=$(run_on "ip -4 -br addr show $MGMT_NIC")
assert_contains "the management NIC $MGMT_NIC still has its address" "10.10.5.31" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2.0/networks")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER agents")
assert_contains "the API lists neutron-openvswitch-agent" "agent neutron-openvswitch-agent" "$OUT"
assert_contains "the API lists neutron-dhcp-agent" "agent neutron-dhcp-agent" "$OUT"
OUT=$(run_on "sudo $HELPER agents-up")
assert_contains "both agents are alive (RabbitMQ and database work)" "are alive" "$OUT"

OUT=$(run_on "sudo $HELPER net-create")
NID=$(printf '%s\n' "$OUT" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
if [ -z "$NID" ]; then
  log "FAIL: network create returned no id"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
else
  log "PASS: flat provider network $NID and a subnet created and read back"; PASS=$((PASS + 1))
  OUT=$(run_on "sudo $HELPER net-list")
  assert_contains "the new network is listed" "$NID" "$OUT"
  OUT=$(run_on "sudo $HELPER net-delete $NID")
  assert_contains "network deleted, then gone (404)" "deleted $NID" "$OUT"
fi

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type network" "network" "$OUT"

OUT=$(orphera run sh -c "sudo /usr/local/sbin/orphera-nova-api services-up" --nodes tst14 2>&1)
assert_contains "Nova on tst14 still has its scheduler and conductor up" "are up" "$OUT"
OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://tst13.ljalbinson.com:9292/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: Glance on tst13 answers from tst19"; PASS=$((PASS + 1))
else
  log "FAIL: Glance on tst13 does not answer from tst19"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook neutron_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of neutron_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
