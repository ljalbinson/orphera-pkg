#!/usr/bin/env bash
#
# Regression test for --resume / Checkpoint.scala. Doesn't just check
# that a resumed run doesn't crash — proves the actual mechanism:
#   1. A run that partially fails records exactly the completed task(s)
#      in .orphera-state/, and nothing else.
#   2. Re-running WITHOUT --resume re-executes everything (default
#      behavior is unchanged) — task1 runs again, not skipped.
#   3. Re-running WITH --resume skips task1 (checkpoint hit) and
#      actually executes task2/task3, succeeding once the condition
#      that failed it originally is fixed.
#
# restartability_test.yaml: task1 always succeeds, task2 fails until
# /tmp/orphera-resume-test-marker exists on tst0, task3 always
# succeeds. This lets the test force a real partial failure
# deterministically rather than hoping a task happens to fail.
#
# Run from scala0, in orphera-pkg (tst0 needs a running agent):
#   ./test_restartability.sh

set -uo pipefail

MANIFESTS_DIR="manifests"
MANIFEST="restartability_test.yaml"
CHECKPOINT_FILE=".orphera-state/playbook-restartability-test.txt"
MARKER_PATH="/tmp/orphera-resume-test-marker"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

pass() { log "PASS: $1"; PASS=$((PASS + 1)); }
fail() { log "FAIL: $1"; FAIL=$((FAIL + 1)); }

cleanup() {
  rm -f "$CHECKPOINT_FILE"
  orphera run --nodes tst0 -- rm -f "$MARKER_PATH" >/dev/null 2>&1
}

log "=== Restartability regression test starting ==="
cleanup

# --- Run 1: no --resume, marker absent. task1 succeeds, task2 fails,
#     task3 never runs. Playbook must exit nonzero. ---
log "Run 1: expect task1 to succeed, task2 to fail, exit nonzero"
output1=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" 2>&1)
rc1=$?
echo "$output1"

if [ "$rc1" -ne 0 ]; then
  pass "run 1 exits nonzero (task2 genuinely failed)"
else
  fail "run 1 exited 0 — expected task2 to fail"
fi

# --- Checkpoint file must record task1 only ---
if [ -f "$CHECKPOINT_FILE" ]; then
  content=$(cat "$CHECKPOINT_FILE")
  if printf '%s\n' "$content" | grep -qF $'tst0\ttask1 always succeeds'; then
    pass "checkpoint file recorded task1 as completed"
  else
    fail "checkpoint file does not contain task1 — content was: $content"
  fi
  if printf '%s\n' "$content" | grep -qF $'tst0\ttask2 fails until marker exists'; then
    fail "checkpoint file recorded task2 as completed — it should have failed, not succeeded"
  else
    pass "checkpoint file correctly does NOT record task2 (it failed)"
  fi
else
  fail "checkpoint file $CHECKPOINT_FILE does not exist after run 1"
fi

# --- Run 2: no --resume, marker still absent. Default behavior must be
#     unchanged — task1 must run again (NOT be skipped), task2 fails
#     again, exit nonzero again. ---
log "Run 2 (no --resume): expect task1 to run again, not be skipped"
output2=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" 2>&1)
rc2=$?
echo "$output2"

if printf '%s' "$output2" | grep -q "task1 always succeeds: skipped (already completed"; then
  fail "run 2 (no --resume) skipped task1 — default behavior should be unchanged"
else
  pass "run 2 (no --resume) did not skip task1 — default behavior unchanged"
fi

if [ "$rc2" -ne 0 ]; then
  pass "run 2 (no --resume) exits nonzero again"
else
  fail "run 2 (no --resume) exited 0 — expected task2 to fail again"
fi

# --- Fix the condition that failed task2, then resume ---
log "Creating marker on tst0 so task2 can succeed, then running --resume"
orphera run --nodes tst0 -- sh -c "touch $MARKER_PATH" >/dev/null 2>&1

output3=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" --resume 2>&1)
rc3=$?
echo "$output3"

if printf '%s' "$output3" | grep -q "task1 always succeeds: skipped (already completed"; then
  pass "run 3 (--resume) skipped task1 (checkpoint hit)"
else
  fail "run 3 (--resume) did not skip task1 — resume is not working"
fi

if printf '%s' "$output3" | grep -q "task2 fails until marker exists: exit=0"; then
  pass "run 3 (--resume) actually executed task2 (not skipped) and it succeeded"
else
  fail "run 3 (--resume) did not show task2 actually running and succeeding"
fi

if [ "$rc3" -eq 0 ]; then
  pass "run 3 (--resume) exits 0"
else
  fail "run 3 (--resume) exited nonzero — expected full success this time"
fi

cleanup

log "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
