#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for octavia_single_node.scala.
#
# Applies the three descriptions (octavia_database, octavia_rabbitmq, octavia_single_node; the last one itself
# boots an amphora and checks a load balancer), then checks independently of their own health checks:
#  - the four Octavia units and the o-hm0 unit are active on tst19,
#  - o-hm0 has an address on br-int and the health manager listens on UDP 5555 at that address,
#  - the API answers its version document without a token (9876),
#  - a protected path refuses a request without a token (401),
#  - Octavia is in the Keystone catalog (service type load-balancer),
#  - the amphora provider is listed,
#  - Glance has an active image with the tag `amphora`, Nova the flavor orphera-amphora,
#  - a load balancer is made on a throwaway network (listener, pool, one member that is a web server in the
#    DHCP namespace); an HTTP request to its address is answered by that member through the amphora; the load
#    balancer and the network are deleted,
#  - no test load balancer is left behind,
#  - a second run of each description changes nothing (the load balancer test is made again only when its stamp
#    changes), leaves the certificates alone, and a load balancer still works afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10), Glance, Nova with compute nodes, and Neutron (with
# the L3 and DHCP agents on tst19) healthy; tst19 has an agent; the Octavia images are available (built on
# tst21 or on quay.io); tst19 can download the amphora image from tarballs.opendev.org (or it is already at
# /var/lib/orphera/octavia/amphora.qcow2). A load balancer needs a booted VM: allow several minutes per cycle.
# Run from scala0, in orphera-pkg:   manifests/test_octavia.sh
# NO_APPLY=1 skips the apply runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst19"
PORT=9876
HELPER="/usr/local/sbin/orphera-octavia-api"
DESCRIPTIONS="octavia_database.scala octavia_rabbitmq.scala octavia_single_node.scala"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "$NODE" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }

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
  for d in $DESCRIPTIONS; do
    if ! orphera apply "$d"; then
      log "FAIL: $d did not complete"; exit 1
    fi
  done
fi

for u in octavia-housekeeping octavia-worker octavia-health-manager octavia-api octavia-hm-port; do
  OUT=$(run_on "echo state=\$(systemctl is-active orphera-$u)")
  assert_contains "orphera-$u is active" "state=active" "$OUT"
done

OUT=$(run_on "ip -4 -o addr show dev o-hm0 | awk '{print \"hm-address=\" \$4}'")
assert_contains "o-hm0 has an address in 192.168.200.0/24" "hm-address=192.168.200." "$OUT"

HM=$(printf '%s\n' "$OUT" | sed -n 's|.*hm-address=\([0-9.]*\)/.*|\1|p' | head -n 1)
OUT=$(run_on "echo hm-listening=\$(ss -H -lun | grep -c '$HM:5555')")
assert_contains "the health manager listens on UDP 5555 at $HM" "hm-listening=1" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2/lbaas/loadbalancers")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type load-balancer" "load-balancer" "$OUT"

OUT=$(run_on "sudo $HELPER providers")
assert_contains "the amphora provider is listed" "providers: amphora" "$OUT"

OUT=$(run_on "sudo /usr/local/sbin/orphera-image-amphora-x64-haproxy-test check")
assert_contains "Glance has an active amphora image" "is active" "$OUT"

OUT=$(RUN_TIMEOUT=1800 run_on "sudo $HELPER lb-cycle")
assert_contains "a load balancer answers an HTTP request through its amphora and is deleted" "lb cycle ok" "$OUT"

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  BEFORE=$(run_on "sha256sum /etc/kolla/octavia/certs/server_ca.cert.pem /etc/kolla/octavia/certs/client.cert-and-key.pem | awk '{print \$1}' | tr -d '\n'")
  for d in $DESCRIPTIONS; do
    SECOND=$(orphera apply "$d" 2>&1); RC=$?
    printf '%s\n' "$SECOND"
    if [ "$RC" -ne 0 ]; then log "FAIL: second run of $d failed"; FAIL=$((FAIL + 1)); fi
    assert_contains "second run of $d changed nothing" "0 changed" "$SECOND"
  done
  AFTER=$(run_on "sha256sum /etc/kolla/octavia/certs/server_ca.cert.pem /etc/kolla/octavia/certs/client.cert-and-key.pem | awk '{print \$1}' | tr -d '\n'")
  if [ -n "$BEFORE" ] && [ "$BEFORE" = "$AFTER" ]; then
    log "PASS: the second run kept the certificates"; PASS=$((PASS + 1))
  else
    log "FAIL: the certificates changed in the second run"; FAIL=$((FAIL + 1))
  fi
  OUT=$(RUN_TIMEOUT=1800 run_on "sudo $HELPER lb-cycle")
  assert_contains "a load balancer still works after the second run" "lb cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
