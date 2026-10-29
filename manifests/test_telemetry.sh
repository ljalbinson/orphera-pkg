#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for telemetry_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the four units on tst24 (ceilometer-central, aodh-evaluator, aodh-notifier, aodh-api) and
#    ceilometer-compute on tst17 and tst18 are active,
#  - the Ceilometer exporters answer on 9101 on tst24, tst17 and tst18, the Aodh API answers its version document on 8042 without a token and
#    refuses a protected path without one (401),
#  - Prometheus on tst7 has the ceilometer scrape job and its three targets are up,
#  - the helper finds all three exporters up in Prometheus and prints the Ceilometer metric names,
#  - two PromQL alarms made through Aodh reach the states alarm and ok, and are deleted,
#  - Keystone lists the service types alarming and prometheus,
#  - the observability playbooks still carry the scrape_config_files line,
#  - a second run of the playbook succeeds (idempotence), and the alarms still work afterwards.
#
# Prerequisites: Galera + VIP, Keystone, RabbitMQ (tst10), Prometheus on tst7 and nova_compute on tst17 and
# tst18; tst24 exists and has an agent; the Ceilometer and Aodh images are available (built on tst21 or on
# quay.io).
# Run from scala0, in orphera-pkg:   manifests/test_telemetry.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst24"
COMPUTE="tst17,tst18"
PROM="tst7"
PORT=8042
EXPORTER=9101
HELPER="/usr/local/sbin/orphera-telemetry-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }

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
  if ! orphera cluster-playbook telemetry_single_node.scala; then
    log "FAIL: telemetry_single_node.scala did not complete"; exit 1
  fi
fi

for u in ceilometer-central aodh-evaluator aodh-notifier aodh-api; do
  OUT=$(run_on "echo state=\$(systemctl is-active orphera-$u)")
  assert_contains "orphera-$u is active on $NODE" "state=active" "$OUT"
done

OUT=$(run_on "echo state=\$(systemctl is-active orphera-ceilometer-compute)" "$COMPUTE")
if [ "$(printf '%s\n' "$OUT" | grep -c 'state=active')" -eq 2 ]; then
  log "PASS: orphera-ceilometer-compute is active on tst17 and tst18"; PASS=$((PASS + 1))
else
  log "FAIL: orphera-ceilometer-compute is not active on both compute nodes"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$EXPORTER/metrics" "$NODE,$COMPUTE")
if [ "$(printf '%s\n' "$OUT" | grep -c 'http=200')" -eq 3 ]; then
  log "PASS: the Ceilometer exporter answers on $EXPORTER on all three nodes"; PASS=$((PASS + 1))
else
  log "FAIL: the Ceilometer exporter does not answer on $EXPORTER on all three nodes"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/")
if printf '%s' "$OUT" | grep -qE 'http=(200|300)'; then
  log "PASS: the Aodh version document answers on $PORT"; PASS=$((PASS + 1))
else
  log "FAIL: the Aodh version document does not answer on $PORT"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:$PORT/v2/alarms")
assert_contains "a protected path refuses a request without a token" "http=401" "$OUT"

OUT=$(run_on "curl -s http://localhost:9090/api/v1/targets | python3 -c \"import sys, json; t = json.load(sys.stdin)['data']['activeTargets']; print('ceilometer=' + ','.join(x['health'] for x in t if x['labels'].get('job') == 'ceilometer'))\"" "$PROM")
assert_contains "Prometheus scrapes the Ceilometer exporters and all three are up" "ceilometer=up,up,up" "$OUT"

OUT=$(run_on "grep -c '^scrape_config_files:' /etc/prometheus/prometheus.yml | sed 's/^/lines=/'" "$PROM")
assert_contains "prometheus.yml names the scrape.d files" "lines=1" "$OUT"

OUT=$(RUN_TIMEOUT=360 run_on "sudo $HELPER metrics")
assert_contains "all three Ceilometer exporters are up in Prometheus" "ceilometer exporters up: 3 of 3" "$OUT"

OUT=$(RUN_TIMEOUT=480 run_on "sudo $HELPER alarm-cycle")
assert_contains "two PromQL alarms reach the states alarm and ok" "alarm cycle ok" "$OUT"

OUT=$(run_on "sudo $HELPER catalog")
assert_contains "the catalog lists service type alarming" "alarming" "$OUT"
assert_contains "the catalog lists service type prometheus" "prometheus" "$OUT"

for f in observability_stack.scala observability_extended.scala; do
  if grep -q 'scrape_config_files' "manifests/$f"; then
    log "PASS: manifests/$f keeps the scrape_config_files line"; PASS=$((PASS + 1))
  else
    log "FAIL: manifests/$f would drop the Ceilometer scrape job when re-run"; FAIL=$((FAIL + 1))
  fi
done

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook telemetry_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of telemetry_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(RUN_TIMEOUT=480 run_on "sudo $HELPER alarm-cycle")
  assert_contains "the alarms still work after the second run" "alarm cycle ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
