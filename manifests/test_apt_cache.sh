#!/usr/bin/env bash
#
# End-to-end test for the apt cache (apt_cache*.scala).
#
# Lifecycle: apt_cache_teardown -> apt_cache -> apt_cache_clients, then
# independent checks run over `orphera run`:
#   - the cache answers, and clients are configured to use it;
#   - a package downloaded by one node is fetched from the mirror once and then
#     served from the cache to a second node (counted in the cache's own log);
#   - with the cache stopped, apt on a client still works (falls back to direct);
#   - the playbooks are idempotent.
#
# Prerequisites: tst11 exists with an agent (config/tst11.yaml), DNS resolves
# tst11.ljalbinson.com from the client nodes.
#
# Run from scala0, in orphera-pkg:   manifests/test_apt_cache.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

CACHE="tst11"
CACHE_FQDN="tst11.ljalbinson.com"
PORT=3142
CLIENT_A="tst0"
CLIENT_B="tst1"
PKG="figlet"   # small, and not installed or cached on a fresh fleet

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
    log "cleanup: restarting apt-cacher-ng on $CACHE"
    run_on "$CACHE" "sudo systemctl start apt-cacher-ng" >/dev/null
  fi
}
trap cleanup EXIT

log "teardown (failures here are tolerated: nothing may be installed yet)"
orphera cluster-playbook manifests/apt_cache_teardown.scala || true

log "install the cache"
if ! orphera cluster-playbook manifests/apt_cache.scala; then
  log "FAIL: apt_cache.scala did not complete"; exit 1
fi

log "point the clients at it"
if ! orphera cluster-playbook manifests/apt_cache_clients.scala; then
  log "FAIL: apt_cache_clients.scala did not complete"; exit 1
fi

# ---- the cache and the client configuration -------------------------------

OUT=$(run_on "$CLIENT_A" "curl -s -o /dev/null -w 'http=%{http_code}' http://$CACHE_FQDN:$PORT/acng-report.html")
assert_contains "the cache answers from $CLIENT_A" "http=200" "$OUT"

OUT=$(run_on "$CLIENT_A" "/usr/local/sbin/orphera-apt-proxy")
assert_contains "auto-detect script picks the cache" "http://$CACHE_FQDN:$PORT" "$OUT"

OUT=$(run_on "$CLIENT_A" "apt-config dump | grep -i 'Proxy-Auto-Detect'")
assert_contains "apt is configured with the auto-detect script" "orphera-apt-proxy" "$OUT"

# ---- a real cache hit ------------------------------------------------------

# $CLIENT_A downloads the package (a miss: the cache fetches it from the
# mirror), then $CLIENT_B downloads the same file (a hit).
OUT=$(run_on "$CLIENT_A" "cd /tmp && sudo apt-get update -qq && apt-get download $PKG && ls -l ${PKG}_*.deb && rm -f ${PKG}_*.deb")
assert_contains "$CLIENT_A can download $PKG through the cache" "${PKG}_" "$OUT"

OUT=$(run_on "$CLIENT_B" "cd /tmp && sudo apt-get update -qq && apt-get download $PKG && ls -l ${PKG}_*.deb && rm -f ${PKG}_*.deb")
assert_contains "$CLIENT_B can download $PKG through the cache" "${PKG}_" "$OUT"

# apt-cacher-ng's log: 'I' lines are data fetched into the cache from the
# mirror, 'O' lines are data delivered out to clients.
LOG="/var/log/apt-cacher-ng/apt-cacher.log"
IN=$(run_on "$CACHE" "sudo grep -c '|I|.*/${PKG}_' $LOG" | grep -Eo '^\[[a-z0-9]+\] [0-9]+$' | awk '{print $2}' | tail -1)
OUTN=$(run_on "$CACHE" "sudo grep -c '|O|.*/${PKG}_' $LOG" | grep -Eo '^\[[a-z0-9]+\] [0-9]+$' | awk '{print $2}' | tail -1)
log "cache log lines for $PKG (I = fetched into the cache, O = delivered to a client):"
run_on "$CACHE" "sudo grep '/${PKG}_' $LOG" | grep '|' | sed 's/^/    /'
assert_equals "$PKG was fetched from the mirror once" "1" "${IN:-?}"
assert_equals "$PKG was delivered to both clients" "2" "${OUTN:-?}"

OUT=$(run_on "$CACHE" "sudo find /var/cache/apt-cacher-ng -name '${PKG}_*.deb' | head -n 1")
assert_contains "the package file is stored in the cache directory" "${PKG}_" "$OUT"

# ---- fallback when the cache is down ---------------------------------------

log "stopping the cache to test the fallback"
run_on "$CACHE" "sudo systemctl stop apt-cacher-ng" >/dev/null
CACHE_STOPPED=1

OUT=$(run_on "$CLIENT_A" "/usr/local/sbin/orphera-apt-proxy")
assert_contains "auto-detect says DIRECT when the cache is down" "DIRECT" "$OUT"

OUT=$(run_on "$CLIENT_A" "sudo apt-get update >/tmp/apt-fallback.log 2>&1; echo rc=\$?; tail -n 3 /tmp/apt-fallback.log")
assert_contains "apt update still works with the cache down" "rc=0" "$OUT"

run_on "$CACHE" "sudo systemctl start apt-cacher-ng" >/dev/null
CACHE_STOPPED=""
sleep 3
OUT=$(run_on "$CLIENT_A" "/usr/local/sbin/orphera-apt-proxy")
assert_contains "auto-detect picks the cache again when it is back" "http://$CACHE_FQDN:$PORT" "$OUT"

# ---- idempotence -----------------------------------------------------------

log "re-apply (idempotence)"
if orphera cluster-playbook manifests/apt_cache.scala && orphera cluster-playbook manifests/apt_cache_clients.scala; then
  log "PASS: second run completed"; PASS=$((PASS + 1))
else
  log "FAIL: second run failed"; FAIL=$((FAIL + 1))
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
