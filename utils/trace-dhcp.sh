#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Where do the DHCP packets of a booting test instance get lost?
#
# Captures DHCP traffic (udp 67/68) on the provider NIC (enp2s0) of tst17, tst18 and
# tst19 while manifests/test_nova_compute.sh boots a CirrOS instance (kept afterwards),
# then prints a count of requests (guest -> server) and replies (server -> guest) seen
# at each node. Reading the result:
#   - requests on the compute node but none on tst19  -> lost between them (hypervisor
#     bridges / vlan106 / the physical network)
#   - requests on tst19 but no replies leaving tst19  -> the DHCP agent / dnsmasq side
#   - replies on tst19 but none back at the compute node -> lost on the way back
# Installs tcpdump on the three VMs if missing. Leaves the test instance in place
# (the next test run removes it with boot-cleanup).
# Run from scala0, in orphera-pkg:   utils/trace-dhcp.sh
set -uo pipefail
NODES=(tst17 tst18 tst19)
NIC="enp2s0"
OUT="${TMPDIR:-/tmp}/dhcp-trace"
mkdir -p "$OUT"; rm -f "$OUT"/*.txt

for n in "${NODES[@]}"; do
  orphera run sh -c "command -v tcpdump >/dev/null || apt-get install -y -qq tcpdump >/dev/null 2>&1; command -v tcpdump" --nodes "$n" --timeout 180 --quiet
done

echo "starting captures on ${NODES[*]} (about 5 minutes)"
for n in "${NODES[@]}"; do
  orphera run sh -c "timeout 290 tcpdump -nel -i $NIC -c 200 'udp and (port 67 or port 68)' 2>&1; true" --nodes "$n" --timeout 330 > "$OUT/$n.txt" 2>&1 &
done
sleep 8

KEEP_BOOT=1 NO_APPLY=1 manifests/test_nova_compute.sh 2>&1 | tee "$OUT/test.txt" | grep -E "boot|FAIL|PASS: an instance|passed=" 
wait

echo
echo "=== DHCP packets seen on $NIC ==="
for n in "${NODES[@]}"; do
  req=$(grep -c 'BOOTP/DHCP, Request' "$OUT/$n.txt")
  rep=$(grep -c 'BOOTP/DHCP, Reply' "$OUT/$n.txt")
  printf '%s: requests=%s replies=%s\n' "$n" "$req" "$rep"
done
echo
echo "=== dnsmasq and DHCP namespace on tst19 ==="
orphera run sh -c "pgrep -a dnsmasq | cut -c1-200; ip netns list; ovs-vsctl list-ports br-int" --nodes tst19 --quiet
echo
echo "raw captures: $OUT/tst17.txt $OUT/tst18.txt $OUT/tst19.txt"
