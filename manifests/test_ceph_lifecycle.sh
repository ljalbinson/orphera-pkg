#!/usr/bin/env bash
#
# End-to-end regression test for the Orphera cephadm Ceph playbooks.
#
# Runs the full lifecycle (cephadm-aware teardown -> install -> add-mons ->
# add-osds)
# through `orphera cluster-playbook`, then independently verifies the
# final cluster state over SSH — deliberately NOT by trusting Orphera's
# own health-check output. This is the check that would have caught the
# confirm-osds bug (a copy-pasted mon-quorum grep standing in for an OSD
# check) automatically: that bug made the *playbook* time out, but a
# test that only asked "did the playbook report success?" would have
# been equally fooled by a health check that quietly checked the wrong
# thing and happened to pass.
#
# Run from scala0, in orphera-pkg:
#   ./test_ceph_lifecycle.sh
#
# Exit code: 0 if every assertion passes, 1 on the first failure (with
# a diagnostic printed before exiting).

set -uo pipefail

# ---- Configuration -----------------------------------------------------
ADMIN_HOST="tst0"                      # host to SSH into for `ceph -s`
ADMIN_SSH_USER="localadmin"             # the real login on these nodes — was
                                         # "ubuntu" (a guess, never confirmed
                                         # against real infra, including the
                                         # specific cloud-init denial message
                                         # this comment used to quote).
                                         # Corrected after a real session
                                         # against these nodes this session
                                         # showed `localadmin@tst0:~$` prompts
                                         # throughout. Root SSH login is
                                         # still blocked by cloud-init on
                                        # these images. cephadm still needs root, so commands below go
                                        # through `sudo` instead of `ssh root@...`.
EXPECTED_MON_COUNT=3                   # tst0, tst1, tst2
MANIFESTS_DIR="manifests"

# EXPECTED_OSD_COUNT is NOT a hand-maintained constant, and not derived
# by scanning any source/config file either — both were tried and both
# went stale (see CHANGELOG for the full history: a hand-set "3" that
# silently stopped matching reality, then a source-grep that miscounted
# a comment line as a device, then broke again the moment device
# selection moved out of the .scala file and into manifests/inventory.yaml
# as comma-joined per-node vars, which a line-counting grep can't see
# into correctly either). Any static analysis of "whichever file device
# selection happens to live in today" is fragile in the same way: it's
# a second, independent restatement of what cephadm_add_osds.scala
# itself already computes and asserts on every run.
#
# So this reads it out of the playbook's own run output instead — its
# confirm-osds stage prints exactly "All N specified OSD device(s) are
# up and in.", where N is osdDevices.values.map(_.size).sum, computed
# live from whatever cephadm_add_osds.scala actually resolved for this
# run. That's the one true source: not a copy of the config, the
# playbook's own real computed answer.
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
# Also stashes the run's output in LAST_PLAYBOOK_OUTPUT so a caller can
# pull information out of it afterward (used below to read the real
# OSD count out of cephadm_add_osds.scala's own confirm-osds message,
# rather than maintaining a second copy of that number here).
#
# NOTE: `orphera cluster-playbook` has been observed to exit 0 even when
# a stage failed and it printed "Stage '...' failed or did not become
# healthy — aborting remaining stages." — so the process exit code
# cannot be trusted here. Until that's fixed upstream, this greps the
# actual output for the failure marker instead. (Worth fixing in
# Orphera itself — any script relying on $?, which is the normal thing
# to do, gets silently lied to otherwise.)
LAST_PLAYBOOK_OUTPUT=""
run_playbook() {
  local manifest="$1"
  local rc
  log "Running $manifest ..."
  LAST_PLAYBOOK_OUTPUT=$(orphera cluster-playbook "$MANIFESTS_DIR/$manifest" 2>&1)
  rc=$?
  echo "$LAST_PLAYBOOK_OUTPUT"
  if [ "$rc" -ne 0 ] || printf '%s' "$LAST_PLAYBOOK_OUTPUT" | grep -q "aborting remaining stages"; then
    log "FATAL: $manifest failed (non-zero exit, or a stage reported failure) — aborting test run"
    exit 1
  fi
}

# Runs `ceph -s -f json` on $ADMIN_HOST (as root — cephadm refuses
# anything else) and extracts one field via a short inline python
# (avoids a jq dependency on the admin host). Deliberately does NOT
# swallow stderr: a real failure here (auth, cephadm erroring out,
# malformed JSON) should be visible, not silently coerced into "ERROR"
# the way it was before — that's what let a fetch failure sail through
# the health-check assertion as a false PASS.
ceph_field() {
  local jq_expr="$1"
  local raw
  if ! raw=$(ssh "${ADMIN_SSH_USER}@${ADMIN_HOST}" "sudo cephadm shell -- ceph -s -f json" 2>&1); then
    log "  ceph_field: ssh/cephadm command failed — raw output was:"
    log "  $raw"
    echo "ERROR"
    return 1
  fi
  if ! result=$(printf '%s' "$raw" | python3 -c "
import json, sys
raw = sys.stdin.read()
# cephadm shell prints its own status chatter ('Inferring fsid...',
# 'Using ceph image...') onto the same stream as the command's real
# output, ahead of it — so parse from the first '{' onward rather than
# assuming stdin is pure JSON.
start = raw.find('{')
if start == -1:
    print('no JSON object found in output', file=sys.stderr)
    sys.exit(1)
d = json.loads(raw[start:])
$jq_expr
" 2>&1); then
    log "  ceph_field: JSON parse/field extraction failed — raw output was:"
    log "  $raw"
    echo "ERROR"
    return 1
  fi
  echo "$result"
}

# ---- Lifecycle -----------------------------------------------------------

log "=== Ceph lifecycle regression test starting ==="

run_playbook "cephadm_teardown.scala"
run_playbook "cephadm_install.scala"
run_playbook "cephadm_add_mons.scala"
run_playbook "cephadm_add_osds.scala"

# Pull the real OSD count out of cephadm_add_osds.scala's own
# confirm-osds message ("All N specified OSD device(s) are up and in.")
# rather than maintaining any second copy of it in this script — see the
# EXPECTED_OSD_COUNT comment near the top for why.
EXPECTED_OSD_COUNT=$(printf '%s' "$LAST_PLAYBOOK_OUTPUT" | grep -oE 'All [0-9]+ specified OSD device' | grep -oE '[0-9]+')
if [ -z "$EXPECTED_OSD_COUNT" ]; then
  log "FATAL: could not find cephadm_add_osds.scala's 'All N specified OSD device(s)' line in its output — can't derive EXPECTED_OSD_COUNT"
  exit 1
fi
log "cephadm_add_osds.scala reported $EXPECTED_OSD_COUNT specified OSD device(s)"

log "Playbooks completed — independently verifying final cluster state via SSH ..."

# Any field that came back "ERROR" means the fetch itself failed — that's
# a hard stop, not a value to compare against expectations. Silently
# comparing "ERROR" against an expected value is exactly what produced
# the false PASS on the health check before.
require_fetched() {
  local desc="$1" value="$2"
  if [ "$value" = "ERROR" ]; then
    log "FAIL: $desc — could not be fetched at all (see ceph_field diagnostics above)"
    FAIL=$((FAIL + 1))
    return 1
  fi
  return 0
}

# --- Mon quorum: exact member count, not just "some number of mons" ---
mon_count=$(ceph_field "print(len(d['quorum_names']))")
if require_fetched "mon quorum member count" "$mon_count"; then
  assert_eq "mon quorum member count" "$EXPECTED_MON_COUNT" "$mon_count"
fi

# --- Mon quorum: the expected named hosts, not just the right count ---
# (catches the case where N mons are in quorum but they're the wrong N)
expected_mons_sorted="tst0 tst1 tst2"
actual_mons_sorted=$(ceph_field "print(' '.join(sorted(d['quorum_names'])))")
if require_fetched "mon quorum membership" "$actual_mons_sorted"; then
  assert_eq "mon quorum membership" "$expected_mons_sorted" "$actual_mons_sorted"
fi

# --- OSD counts: total registered, up, and in must all match ---
osd_total=$(ceph_field "print(d['osdmap']['num_osds'])")
osd_up=$(ceph_field "print(d['osdmap']['num_up_osds'])")
osd_in=$(ceph_field "print(d['osdmap']['num_in_osds'])")
if require_fetched "total OSD count" "$osd_total"; then
  assert_eq "total OSD count" "$EXPECTED_OSD_COUNT" "$osd_total"
fi
if require_fetched "OSDs up" "$osd_up"; then
  assert_eq "OSDs up" "$EXPECTED_OSD_COUNT" "$osd_up"
fi
if require_fetched "OSDs in" "$osd_in"; then
  assert_eq "OSDs in" "$EXPECTED_OSD_COUNT" "$osd_in"
fi

# --- Overall health must not be HEALTH_ERR (HEALTH_WARN is tolerated —
#     e.g. the harmless "pool has no application enabled" notice) ---
health=$(ceph_field "print(d['health']['status'])")
if require_fetched "cluster health" "$health"; then
  if [ "$health" = "HEALTH_ERR" ]; then
    log "FAIL: cluster health is HEALTH_ERR"
    FAIL=$((FAIL + 1))
  else
    log "PASS: cluster health is not HEALTH_ERR (got $health)"
    PASS=$((PASS + 1))
  fi
fi

# ---- Summary --------------------------------------------------------------

log "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
