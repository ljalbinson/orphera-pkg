#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Did a test guest take the address DHCP gave it?
#
# Usage:  utils/check-guest-dhcp.sh <ip> <mac> <network-id>
#
# The guest console cannot answer this: CirrOS prints its network debug a second after
# dhcpcd starts and syslog never reaches the console, so a lease granted a moment later is
# not shown. Instead this asks from the other side. On tst19, in the DHCP namespace of the
# test network, it pings the guest and looks at the neighbour table: an entry with the
# guest's MAC means the guest answered ARP for the address, and nothing but DHCP gave it
# that address. (The ping itself is blocked by the default security group, the ARP reply is
# not.) Retries for about a minute. Exit 0 if the guest answers, 1 if not.
set -uo pipefail
IP="${1:?ip}"; MAC="${2:?mac}"; NET="${3:?network id}"

REMOTE='ns=qdhcp-@NET@; for i in $(seq 1 20); do ip netns exec $ns ping -c 1 -W 1 @IP@ >/dev/null 2>&1; if ip netns exec $ns ip neigh show @IP@ | grep -qi "lladdr @MAC@"; then echo "guest @IP@ answers ARP as @MAC@"; exit 0; fi; sleep 3; done; echo "no ARP answer from @IP@ (@MAC@) in qdhcp-@NET@:"; ip netns exec $ns ip neigh show @IP@; exit 1'
REMOTE=${REMOTE//@NET@/$NET}
REMOTE=${REMOTE//@IP@/$IP}
REMOTE=${REMOTE//@MAC@/$MAC}
orphera run sh -c "$REMOTE" --nodes tst19 --timeout 120 2>&1
