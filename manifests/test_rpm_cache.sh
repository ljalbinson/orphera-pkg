#!/usr/bin/env bash
#
# End-to-end test for the rpm cache (rpm_cache*.scala).
#
# Lifecycle: rpm_cache_teardown -> rpm_cache -> rpm_cache_clients, then
# independent checks run over `orphera run`:
#   - the cache answers, and the Rocky node's repositories come from it;
#   - a package downloaded through the cache is fetched from Rocky once (MISS)
#     and, after the client's own copy is cleaned, served from the cache (HIT),
#     counted in the cache's own access log;
#   - with the cache stopped, dnf on the client still works (falls back to the
#     official host);
#   - the playbooks are idempotent.
#
# Prerequisites: tst12 exists with an agent (config/tst12.yaml), DNS resolves
# tst12.ljalbinson.com from tst8, and tst8 (Rocky 10) is up.
#
# Run from scala0, in orphera-pkg:   manifests/test_rpm_cache.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

CACHE="tst12"
CACHE_FQDN="tst12.ljalbinson.com"
PORT=8080
CLIENT="tst8"
PKG="tree"   # small; removed from the client first so the download is real

PASS=0
FAIL=0
CACHE_STOPPED=""

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# run_on <node> <command>
run_on() { orphera run sh -c "$2" --nodes "$1" 2>&1; }

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

assert_equals() {
  local desc="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected '$want', got '$got')"
    FAIL=$((FAIL + 1))
  fi
}

# Whatever happens, do not leave the cache stopped.
cleanup() {
  if [ -n "$CACHE_STOPPED" ]; then
    log "cleanup: restarting nginx on $CACHE"
    run_on "$CACHE" "sudo systemctl start nginx" >/dev/null
  fi
}
trap cleanup EXIT

log "teardown (failures here are tolerated: nothing may be installed yet)"
orphera cluster-playbook manifests/rpm_cache_teardown.scala || true

log "install the cache"
if ! orphera cluster-playbook manifests/rpm_cache.scala; then
  log "FAIL: rpm_cache.scala did not complete"; exit 1
fi

log "point the Rocky client at it"
if ! orphera cluster-playbook manifests/rpm_cache_clients.scala; then
  log "FAIL: rpm_cache_clients.scala did not complete"; exit 1
fi

# ---- the cache and the client configuration -------------------------------

OUT=$(run_on "$CLIENT" "curl -s http://$CACHE_FQDN:$PORT/healthz")
assert_contains "the cache answers from $CLIENT" "ok" "$OUT"

OUT=$(run_on "$CLIENT" "dnf repolist")
assert_contains "dnf on $CLIENT lists the cache repositories" "orphera-baseos" "$OUT"

OUT=$(run_on "$CLIENT" "ls /etc/yum.repos.d/")
assert_contains "the stock Rocky repository files are set aside" ".orphera-off" "$OUT"

# ---- a real cache hit ------------------------------------------------------

# Download the package (a miss: the cache fetches it from Rocky), clean the
# client's copy, download again (a hit).
DL="sudo dnf install -y --downloadonly --downloaddir=/tmp/rpmdl $PKG"
run_on "$CLIENT" "sudo dnf remove -y $PKG" >/dev/null
run_on "$CLIENT" "sudo rm -rf /tmp/rpmdl && sudo dnf clean packages" >/dev/null
OUT=$(run_on "$CLIENT" "$DL && ls /tmp/rpmdl")
assert_contains "first download of $PKG through the cache" "${PKG}-" "$OUT"

run_on "$CLIENT" "sudo rm -rf /tmp/rpmdl && sudo dnf clean packages" >/dev/null
OUT=$(run_on "$CLIENT" "$DL && ls /tmp/rpmdl")
assert_contains "second download of $PKG through the cache" "${PKG}-" "$OUT"
run_on "$CLIENT" "sudo rm -rf /tmp/rpmdl" >/dev/null

# The access log ends each line with nginx's cache status.
LOG="/var/log/nginx/rpm-cache.log"
count_status() {
  run_on "$CACHE" "sudo grep -c '/${PKG}-.*\\.rpm.* $1\$' $LOG" \
    | grep -Eo '^\[[a-z0-9]+\] [0-9]+$' | awk '{print $2}' | tail -1
}
MISS=$(count_status MISS)
HIT=$(count_status HIT)
log "cache log lines for $PKG:"
run_on "$CACHE" "sudo grep '/${PKG}-' $LOG" | grep '\[' | sed 's/^/    /'
assert_equals "$PKG was fetched from Rocky once (MISS)" "1" "${MISS:-?}"
assert_equals "$PKG was then served from the cache (HIT)" "1" "${HIT:-?}"

OUT=$(run_on "$CACHE" "sudo find /var/cache/nginx/rpm -type f | wc -l")
assert_contains "the cache directory holds files" "[$CACHE]" "$OUT"

# ---- fallback when the cache is down ---------------------------------------

log "stopping the cache to test the fallback"
run_on "$CACHE" "sudo systemctl stop nginx" >/dev/null
CACHE_STOPPED=1

OUT=$(run_on "$CLIENT" "sudo dnf clean metadata -q; sudo dnf makecache >/tmp/dnf-fallback.log 2>&1; echo rc=\$?; tail -n 3 /tmp/dnf-fallback.log")
assert_contains "dnf makecache still works with the cache down" "rc=0" "$OUT"

run_on "$CACHE" "sudo systemctl start nginx" >/dev/null
CACHE_STOPPED=""
sleep 3
OUT=$(run_on "$CLIENT" "curl -s http://$CACHE_FQDN:$PORT/healthz")
assert_contains "the cache answers again when it is back" "ok" "$OUT"

# ---- idempotence -----------------------------------------------------------

log "re-apply (idempotence)"
if orphera cluster-playbook manifests/rpm_cache.scala && orphera cluster-playbook manifests/rpm_cache_clients.scala; then
  log "PASS: second run completed"; PASS=$((PASS + 1))
else
  log "FAIL: second run failed"; FAIL=$((FAIL + 1))
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
