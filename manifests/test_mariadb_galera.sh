#!/usr/bin/env bash
#
# End-to-end regression test for the Orphera MariaDB Galera playbooks.
#
# Runs the full lifecycle (mariadb_galera_teardown -> mariadb_galera_cluster)
# through `orphera cluster-playbook`, then independently verifies the final
# cluster state over SSH — deliberately NOT by trusting Orphera's own
# confirm-cluster-healthy HealthCheck output. Same reasoning as
# test_ceph_lifecycle.sh: a check that only asks "did the playbook report
# success?" is exactly as trustworthy as whatever the playbook's own health
# check actually looked at, which is precisely the kind of thing that's
# worth confirming independently rather than assuming.
#
# Goes one step further than a status-flags check, too: after confirming
# wsrep_cluster_size/wsrep_cluster_status/wsrep_ready independently on all
# three nodes, it writes a uniquely-marked row on tst0 and reads it back
# from tst1 and tst2 — proving actual synchronous replication happened,
# not just that every node's status flags happen to say the right words.
# A cluster could plausibly report wsrep_ready=ON on all three nodes while
# actually being split into two Primary components of size 3 that never
# talk to each other in a way status flags alone wouldn't necessarily
# catch (an unlikely failure mode for a 3-node cluster specifically, but
# an inexpensive extra check that catches a wider class of "looks healthy,
# isn't" bugs than trusting wsrep_ready alone).
#
# Run from scala0, in orphera-pkg:
#   ./test_mariadb_galera.sh
#
# Exit code: 0 if every assertion passes, 1 on the first failure (with a
# diagnostic printed before exiting).

set -uo pipefail

# ---- Configuration -----------------------------------------------------
NODES=(tst0 tst1 tst2)
SSH_USER="ubuntu"          # root SSH login is blocked by cloud-init on these
                            # images, same as the Ceph test hits — commands
                            # below go through `sudo` on the target instead.
MANIFESTS_DIR="manifests"
TEST_DB="orphera_galera_test"
TEST_MARKER="orphera-test-$(date +%s)-$$"   # unique per run, so a stale row
                                             # from a previous run can never
                                             # produce a false PASS.

PASS=0
FAIL=0

# ---- Helpers ------------------------------------------------------------

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    log "PASS: $desc (got $actual)"
    PASS=$((PASS + 1))
  else
    log "FAIL: $desc — expected $expected, got $actual"
    FAIL=$((FAIL + 1))
  fi
}

# Runs a playbook and hard-fails the whole script on any sign of failure.
# Trusts the process exit code (now correct — see CHANGELOG's "orphera
# playbook/cluster-playbook always exiting 0" fix), but also still greps
# for the failure marker as belt-and-suspenders, matching
# test_ceph_lifecycle.sh's own convention.
run_playbook() {
  local manifest="$1"
  local rc output
  log "Running $manifest ..."
  output=$(orphera cluster-playbook "$MANIFESTS_DIR/$manifest" 2>&1)
  rc=$?
  echo "$output"
  if [ "$rc" -ne 0 ] || printf '%s' "$output" | grep -q "aborting remaining stages"; then
    log "FATAL: $manifest failed (non-zero exit, or a stage reported failure) — aborting test run"
    exit 1
  fi
}

# Runs a single `mariadb -N -e "..."` statement on $1 over SSH (as root,
# via sudo — same unix_socket-auth assumption mariadb_galera_cluster.scala
# itself documents and flags as worth confirming on a first real run; this
# test IS that confirmation, for every node, not just tst0). Returns "ERROR"
# on any SSH/command failure rather than silently coercing a failure into
# an empty string that might accidentally satisfy a later comparison.
mariadb_query() {
  local host="$1" sql="$2"
  local raw
  if ! raw=$(ssh "${SSH_USER}@${host}" "sudo mariadb -N -e \"$sql\"" 2>&1); then
    log "  mariadb_query($host): ssh/mariadb command failed — raw output was:"
    log "  $raw"
    echo "ERROR"
    return 1
  fi
  echo "$raw"
}

wsrep_status_field() {
  local host="$1" name="$2"
  mariadb_query "$host" "SHOW STATUS LIKE '$name'" | awk '{print $2}'
}

require_fetched() {
  local desc="$1" value="$2"
  if [ "$value" = "ERROR" ] || [ -z "$value" ]; then
    log "FAIL: $desc — could not be fetched at all (see diagnostics above)"
    FAIL=$((FAIL + 1))
    return 1
  fi
  return 0
}

# ---- Lifecycle -----------------------------------------------------------

log "=== MariaDB Galera lifecycle regression test starting ==="

run_playbook "mariadb_galera_teardown.scala"
run_playbook "mariadb_galera_cluster.scala"

log "Playbooks completed — independently verifying final cluster state via SSH ..."

# --- Per-node wsrep status, checked independently on EACH node — same
#     per-node convention the playbook's own HealthCheck.Quorum uses, but
#     queried directly here rather than trusting Orphera's report of it ---
for host in "${NODES[@]}"; do
  size=$(wsrep_status_field "$host" "wsrep_cluster_size")
  if require_fetched "$host: wsrep_cluster_size fetched" "$size"; then
    assert_eq "$host: wsrep_cluster_size" "3" "$size"
  fi

  status=$(wsrep_status_field "$host" "wsrep_cluster_status")
  if require_fetched "$host: wsrep_cluster_status fetched" "$status"; then
    assert_eq "$host: wsrep_cluster_status" "Primary" "$status"
  fi

  ready=$(wsrep_status_field "$host" "wsrep_ready")
  if require_fetched "$host: wsrep_ready fetched" "$ready"; then
    assert_eq "$host: wsrep_ready" "ON" "$ready"
  fi
done

# --- Real replication proof: write on tst0, read back on tst1 and tst2 ---
# A status-flags-only check can't distinguish "actually replicating" from
# "three nodes that each independently believe they're in a healthy
# Primary component" — this proves data actually moved between nodes.
log "Writing marker row on tst0 ($TEST_MARKER) ..."
write_result=$(mariadb_query "tst0" "
  CREATE DATABASE IF NOT EXISTS $TEST_DB;
  CREATE TABLE IF NOT EXISTS $TEST_DB.replication_probe (marker VARCHAR(128) PRIMARY KEY);
  INSERT INTO $TEST_DB.replication_probe (marker) VALUES ('$TEST_MARKER');
")
if [ "$write_result" = "ERROR" ]; then
  log "FATAL: could not write the replication marker row on tst0 — aborting test run"
  exit 1
fi

# Galera replication is virtually synchronous (certification-based) but
# not instantaneous — a short, bounded poll rather than a single
# immediate read, same "retry the real thing rather than guess a sleep"
# principle used in etcd_grow_cluster.scala's registerScript.
poll_for_marker() {
  local host="$1"
  local attempt=0 max_attempts=10 found
  while [ "$attempt" -lt "$max_attempts" ]; do
    found=$(mariadb_query "$host" "SELECT marker FROM $TEST_DB.replication_probe WHERE marker = '$TEST_MARKER'")
    if [ "$found" = "$TEST_MARKER" ]; then
      echo "$found"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  echo "$found"   # last attempt's value (possibly empty or ERROR), for diagnostics
}

for host in tst1 tst2; do
  log "Polling for the marker row on $host (up to 20s) ..."
  seen=$(poll_for_marker "$host")
  if require_fetched "$host: replicated marker row fetched" "$seen"; then
    assert_eq "$host: replicated marker row matches" "$TEST_MARKER" "$seen"
  fi
done

# Cleanup: drop the test database on tst0 — Galera replicates DDL too, so
# this removes it cluster-wide, not just on tst0. Best-effort; a leftover
# test database from a failed run doesn't affect a later run's correctness
# (CREATE DATABASE/TABLE IF NOT EXISTS above tolerate it), so this isn't
# wrapped in its own pass/fail assertion.
log "Cleaning up test database ..."
mariadb_query "tst0" "DROP DATABASE IF EXISTS $TEST_DB;" > /dev/null

# ---- Summary --------------------------------------------------------------

log "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
