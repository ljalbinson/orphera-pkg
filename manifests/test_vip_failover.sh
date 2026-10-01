#!/usr/bin/env bash
#
# Chaos/failover regression test for mariadb_haproxy_keepalived.scala.
#
# Every earlier confirmation of that file only ever proved the VIP works
# while nothing is broken (tst0 holding it, haproxy healthy everywhere).
# This is the gap explicitly flagged — but never built — in this
# project's own CHANGELOG ("not yet exercised: an actual failover").
#
# What it does, against the real tst0/tst1/tst2 cluster (no teardown/
# rebuild — this runs against whatever's already up):
#   1. Finds which node currently holds the VIP (whichever it is — doesn't
#      assume tst0, even though that's the expected steady state given
#      keepalived's priorities).
#   2. Confirms the VIP is reachable BEFORE touching anything, so a
#      failure later can't be confused with a pre-existing problem this
#      test didn't cause.
#   3. Stops haproxy (not keepalived) on the holder — the realistic
#      failure this setup is actually designed to survive:
#      keepalived.conf's own `vrrp_script chk_haproxy` (`pgrep -x
#      haproxy`, weight 4) is what's supposed to notice and dock that
#      node's priority below the others, not a simulated full node
#      outage.
#   4. Polls (bounded, not a blind sleep) until a DIFFERENT node holds the
#      VIP, and confirms the ORIGINAL holder genuinely let go of it —
#      catching a split-brain (both nodes holding it at once) as its own
#      distinct failure, not just "did a healthy node show up".
#   5. Confirms the VIP is still reachable DURING the new state — proving
#      clients kept working through the handover, not just that an IP
#      address moved.
#   6. Restarts haproxy on the original holder and polls for failback.
#      keepalived.conf sets no `nopreempt`, so this is a firm, deterministic
#      assertion, not a maybe: priorities are fixed at tst0=101 > tst1=100
#      > tst2=99, so once every node's haproxy is healthy again the VIP
#      MUST return to tst0 specifically, whichever node was stopped.
#
# Honest limitation, inherited from mariadb_haproxy_keepalived.scala's own
# HealthCheck.Quorum confirm stage: this never checks for split-brain by
# counting VIP holders in one atomic pass across all three nodes — step 4's
# "did the original holder let go" check narrows it, but a slower,
# independent race where two nodes briefly both believe they hold it is
# still possible in principle and wouldn't necessarily be caught by polling
# each node's local view one at a time.
#
# Run from scala0, in orphera-pkg, once mariadb_galera_cluster.scala AND
# mariadb_haproxy_keepalived.scala are already applied and healthy:
#   ./test_vip_failover.sh
#
# Exit code: 0 if every assertion passes, 1 on the first failure (with a
# diagnostic printed before exiting). Leaves the cluster in its original
# state either way (haproxy on the stopped node is always restarted before
# the script exits, even on a failed assertion) — see the trap below.

set -uo pipefail

# ---- Configuration -----------------------------------------------------
NODES=(tst0 tst1 tst2)
SSH_USER="localadmin"       # the real login on these nodes — confirmed
                             # from an actual session against them; NOT
                             # "ubuntu" (test_mariadb_galera.sh's own
                             # SSH_USER is wrong for the same reason and
                             # worth fixing there too, see CHANGELOG).
VIP="10.10.5.100"
CLUSTERCHECK_USER="clustercheck"
CLUSTERCHECK_PASSWORD="orphera-test-clustercheck-password"   # must match
                             # mariadb_haproxy_keepalived.scala's own val.

PASS=0
FAIL=0
STOPPED_HOST=""   # set once haproxy is actually stopped somewhere, so the
                  # cleanup trap knows whether there's anything to restart.

# ---- Helpers ------------------------------------------------------------

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

assert_true() {
  local desc="$1" ok="$2"
  if [ "$ok" = "true" ]; then
    log "PASS: $desc"
    PASS=$((PASS + 1))
  else
    log "FAIL: $desc"
    FAIL=$((FAIL + 1))
  fi
}

ssh_cmd() {
  local host="$1" cmd="$2"
  ssh "${SSH_USER}@${host}" "$cmd" 2>&1
}

# True/false (as the strings "true"/"false") for whether $1 currently
# holds the VIP on its own interface — never guessed from haproxy/keepalived
# status output, always the real interface state.
holds_vip() {
  local host="$1"
  if ssh "${SSH_USER}@${host}" "ip -4 addr show | grep -q '$VIP/'" 2>/dev/null; then
    echo "true"
  else
    echo "false"
  fi
}

current_vip_holder() {
  local host
  for host in "${NODES[@]}"; do
    if [ "$(holds_vip "$host")" = "true" ]; then
      echo "$host"
      return 0
    fi
  done
  echo ""
}

# Proves the client-facing path, not just that an address exists somewhere
# — same reasoning as mariadb_haproxy_keepalived.scala's own
# vipConnectivityScript, reusing the clustercheck user rather than adding a
# fourth test credential to the project. Run from tst5 when available
# (a genuinely external client, not one of the three backend nodes), since
# that's the configuration this whole exercise is meant to serve — but
# falls back to running from whichever of tst0/tst1/tst2 isn't the current
# VIP holder if tst5 isn't reachable, so this test doesn't hard-depend on
# wordpress_site.scala having been applied.
vip_reachable() {
  local from_host="tst5"
  if ! ssh "${SSH_USER}@${from_host}" true 2>/dev/null; then
    from_host=$(current_vip_holder)
    for host in "${NODES[@]}"; do
      [ "$host" != "$from_host" ] && { from_host="$host"; break; }
    done
  fi
  local result
  result=$(ssh "${SSH_USER}@${from_host}" \
    "mariadb -h $VIP -P 3306 -u $CLUSTERCHECK_USER -p'$CLUSTERCHECK_PASSWORD' -N -e 'SELECT 1'" 2>&1)
  [ "$result" = "1" ] && echo "true" || echo "false"
}

# Bounded retry, not a blind sleep — same "retry the real thing" principle
# as etcd_grow_cluster.scala's registerScript and
# test_mariadb_galera.sh's poll_for_marker. 2s between attempts, 30
# attempts (60s total): keepalived's own advert_int is 1s and
# vrrp_script's interval is 2s, so real convergence should land well
# inside this, with headroom for a slow run.
poll_until() {
  local description="$1" check_fn="$2"
  local attempt=0 max_attempts=30
  while [ "$attempt" -lt "$max_attempts" ]; do
    if [ "$("$check_fn")" = "true" ]; then
      log "  $description — settled after ${attempt}x2s"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  log "  $description — never settled after ${max_attempts}x2s"
  return 1
}

# Always restores haproxy on whatever node this script stopped it on,
# whether the run passed, failed, or was interrupted — a chaos test that
# leaves the cluster broken on exit is worse than not running it.
cleanup() {
  if [ -n "$STOPPED_HOST" ]; then
    log "Cleanup: restarting haproxy on $STOPPED_HOST ..."
    ssh_cmd "$STOPPED_HOST" "sudo systemctl start haproxy" > /dev/null
  fi
}
trap cleanup EXIT

# ---- Drill ----------------------------------------------------------------

log "=== VIP failover drill starting ==="

ORIGINAL_HOLDER=$(current_vip_holder)
if [ -z "$ORIGINAL_HOLDER" ]; then
  log "FATAL: no node currently holds $VIP — is mariadb_haproxy_keepalived.scala actually applied and healthy? Aborting."
  exit 1
fi
log "VIP currently held by $ORIGINAL_HOLDER"

log "Confirming VIP is reachable before touching anything ..."
assert_true "VIP reachable before failover" "$(vip_reachable)"
if [ "$FAIL" -gt 0 ]; then
  log "FATAL: VIP wasn't reachable even before this test touched anything — not a failover bug, something is already broken. Aborting."
  exit 1
fi

log "Stopping haproxy on $ORIGINAL_HOLDER (the holder) to force a failover ..."
ssh_cmd "$ORIGINAL_HOLDER" "sudo systemctl stop haproxy" > /dev/null
STOPPED_HOST="$ORIGINAL_HOLDER"

log "Waiting for keepalived to move the VIP off $ORIGINAL_HOLDER (up to 60s) ..."
check_moved_on() { [ "$(holds_vip "$ORIGINAL_HOLDER")" = "false" ] && echo "true" || echo "false"; }
poll_until "VIP released by $ORIGINAL_HOLDER" check_moved_on
assert_true "$ORIGINAL_HOLDER released the VIP" "$([ "$(holds_vip "$ORIGINAL_HOLDER")" = "false" ] && echo true || echo false)"

NEW_HOLDER=$(current_vip_holder)
assert_true "a different node now holds the VIP (got '${NEW_HOLDER:-none}')" "$([ -n "$NEW_HOLDER" ] && [ "$NEW_HOLDER" != "$ORIGINAL_HOLDER" ] && echo true || echo false)"

if [ -n "$NEW_HOLDER" ]; then
  log "VIP now held by $NEW_HOLDER — confirming it's still reachable ..."
  assert_true "VIP reachable after failover (now via $NEW_HOLDER)" "$(vip_reachable)"
fi

log "Restoring haproxy on $ORIGINAL_HOLDER ..."
ssh_cmd "$ORIGINAL_HOLDER" "sudo systemctl start haproxy" > /dev/null
STOPPED_HOST=""   # restored explicitly here; nothing left for the trap to do

if [ "$ORIGINAL_HOLDER" = "tst0" ]; then
  log "Waiting for tst0 (highest keepalived priority, no nopreempt set) to reclaim the VIP (up to 60s) ..."
  check_reclaimed() { [ "$(holds_vip "tst0")" = "true" ] && echo "true" || echo "false"; }
  poll_until "tst0 reclaimed the VIP" check_reclaimed
  assert_true "tst0 reclaimed the VIP after recovering" "$(holds_vip "tst0")"
else
  log "$ORIGINAL_HOLDER wasn't tst0, so tst0's own haproxy was never touched — waiting for tst0 to reclaim via preemption anyway (up to 60s) ..."
  check_reclaimed() { [ "$(holds_vip "tst0")" = "true" ] && echo "true" || echo "false"; }
  poll_until "tst0 reclaimed the VIP" check_reclaimed
  assert_true "tst0 holds the VIP at rest (priority 101, highest)" "$(holds_vip "tst0")"
fi

log "Confirming VIP is reachable after failback ..."
assert_true "VIP reachable after failback" "$(vip_reachable)"

# ---- Summary --------------------------------------------------------------

log "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
