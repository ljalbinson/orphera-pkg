#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the Prometheus + Grafana monitoring stack on tst7
# (observability_stack.scala and observability_extended.scala).
#
# (test_observability.sh is a different test: the structured run log.)
#
# Applies both playbooks, then checks from the machine running this script,
# over HTTP, what a person using the stack would see:
#   - Prometheus is healthy and every scrape target is up, per job:
#     node_exporter 7 (tst0-tst6), haproxy 3 and mysqld 3 (tst3-tst5),
#     prometheus 1;
#   - the data is real, not just "up": CPU metrics from all seven nodes,
#     every Galera node reports a cluster size of 3, every mysqld exporter can
#     reach its database (mysql_up), every haproxy exports its own metrics;
#   - Grafana is healthy and its Prometheus datasource really answers a query
#     (asked through Grafana's own proxy API);
#   - re-applying both playbooks is idempotent and leaves every target up.
#
# Prerequisites: tst0-tst7 have agents; Galera and haproxy+keepalived are up
# (observability_extended.scala adds metrics to them); this machine can reach
# tst7 on ports 9090 and 3000; python3 and curl are installed here.
#
# Run from scala0, in orphera-pkg:   manifests/test_observability_stack.sh
# NO_APPLY=1 manifests/test_observability_stack.sh   checks only, applies nothing
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

HOST="tst7.ljalbinson.com"
PROM="http://$HOST:9090"
GRAF="http://$HOST:3000"
GUSER="admin"
GPASS=$(orphera secrets get observability/grafana-admin 2>/dev/null) || {
  echo "cannot read the secret observability/grafana-admin (run observability_stack.scala first)" >&2; exit 2; }

# job=up/total, sorted, as observability_*.scala should leave them.
EXPECTED_JOBS="haproxy=3/3 mysqld=3/3 node_exporter=7/7 prometheus=1/1"
EXPECTED_TOTAL=14

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

assert_equals() {
  local desc="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected '$want', got '$got')"
    FAIL=$((FAIL + 1))
  fi
}

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

# prom_query <promql>: number of result series, then the first value, as
# "count first"; "error" if the query itself failed.
prom_query() {
  curl -sG --max-time 20 --data-urlencode "query=$1" "$PROM/api/v1/query" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
    r = d["data"]["result"]
    if d["status"] != "success": raise ValueError
    print(len(r), r[0]["value"][1] if r else "-")
except Exception:
    print("error")'
}

# jobs_summary: "haproxy=3/3 mysqld=3/3 ..." from Prometheus's own target list,
# and the reason for any target that is not up.
jobs_summary() {
  curl -s --max-time 20 "$PROM/api/v1/targets" | python3 -c '
import sys, json, collections
try:
    t = json.load(sys.stdin)["data"]["activeTargets"]
except Exception:
    print("error"); sys.exit()
up = collections.Counter(); tot = collections.Counter()
for x in t:
    j = x["labels"]["job"]
    if j == "pushgateway": continue  # added by telemetry_single_node.scala, not part of this stack
    tot[j] += 1
    if x["health"] == "up": up[j] += 1
    else: print("DOWN:", x["scrapeUrl"], x.get("lastError", ""), file=sys.stderr)
print(" ".join("%s=%d/%d" % (j, up[j], tot[j]) for j in sorted(tot)))'
}

# Prometheus needs a scrape or two after a restart before targets show as up.
wait_for_targets() {
  local i s
  for i in $(seq 1 18); do
    s=$(jobs_summary 2>/dev/null)
    [ "$s" = "$EXPECTED_JOBS" ] && return 0
    sleep 5
  done
  return 1
}

apply_all() {
  orphera cluster-playbook observability_stack.scala || return 1
  orphera cluster-playbook observability_extended.scala || return 1
}

if [ -z "${NO_APPLY:-}" ]; then
  log "apply the monitoring stack, then the extended exporters"
  if ! apply_all; then
    log "FAIL: the observability playbooks did not complete"; exit 1
  fi
else
  log "NO_APPLY set: checking only"
fi

# ---- Prometheus -------------------------------------------------------------

OUT=$(curl -s --max-time 10 "$PROM/-/healthy")
assert_contains "prometheus answers /-/healthy from here" "Healthy" "$OUT"

wait_for_targets
SUMMARY=$(jobs_summary 2>/tmp/obs_down.txt)
[ -s /tmp/obs_down.txt ] && sed 's/^/    /' /tmp/obs_down.txt
assert_equals "every target is up, per job" "$EXPECTED_JOBS" "$SUMMARY"

R=$(prom_query 'count(up == 0)')
assert_equals "no target reports up == 0" "0 -" "$R"

R=$(prom_query 'count(up{job!="pushgateway"})')
assert_equals "$EXPECTED_TOTAL series in 'up'" "1 $EXPECTED_TOTAL" "$R"

R=$(prom_query 'count(count by (instance) (node_cpu_seconds_total))')
assert_equals "CPU metrics arrive from all 7 nodes" "1 7" "$R"

R=$(prom_query 'count(mysql_global_status_wsrep_cluster_size == 3)')
assert_equals "all 3 Galera nodes report a cluster size of 3" "1 3" "$R"

R=$(prom_query 'count(mysql_up == 1)')
assert_equals "all 3 mysqld exporters can reach their database" "1 3" "$R"

R=$(prom_query 'count(count by (instance) ({__name__=~"haproxy_.+", job="haproxy"}))')
assert_equals "all 3 haproxy instances export haproxy_* metrics" "1 3" "$R"

# ---- Grafana ------------------------------------------------------------------

OUT=$(curl -s --max-time 10 "$GRAF/api/health" | tr -d ' \n')
assert_contains "grafana reports a healthy database" '"database":"ok"' "$OUT"

DS=$(curl -s --max-time 10 -u "$GUSER:$GPASS" "$GRAF/api/datasources" | python3 -c '
import sys, json
try:
    for d in json.load(sys.stdin):
        if d.get("type") == "prometheus":
            print(d.get("uid", ""), d.get("id", "")); break
except Exception:
    pass')
assert_contains "grafana lists a Prometheus datasource" " " "$DS"

DS_UID=${DS%% *}
DS_ID=${DS##* }
# The datasource must answer a real query through Grafana (its proxy API).
# Newer Grafana addresses it by uid; older by numeric id.
for P in "uid/$DS_UID" "$DS_ID"; do
  R=$(curl -sG --max-time 20 -u "$GUSER:$GPASS" --data-urlencode 'query=count(up{job!="pushgateway"})' \
        "$GRAF/api/datasources/proxy/$P/api/v1/query" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
    print(d["status"], d["data"]["result"][0]["value"][1])
except Exception:
    print("error")')
  [ "$R" != "error" ] && break
done
assert_equals "a query through Grafana's datasource sees all $EXPECTED_TOTAL targets" "success $EXPECTED_TOTAL" "$R"

# ---- idempotence ----------------------------------------------------------------

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if apply_all; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run failed"; FAIL=$((FAIL + 1))
  fi
  if wait_for_targets; then
    log "PASS: every target is up again after the second run"; PASS=$((PASS + 1))
  else
    log "FAIL: targets not all up after the second run: $(jobs_summary 2>&1 | tr '\n' ' ')"; FAIL=$((FAIL + 1))
  fi
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
