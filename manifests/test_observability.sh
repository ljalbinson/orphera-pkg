#!/usr/bin/env bash
#
# Regression test for RunLog.scala / the .orphera-logs/*.jsonl structured
# event log (run_start, task_start, task_end, task_skipped, run_end).
#
# Reuses restartability_test.yaml's proven 3-run cycle (see
# test_restartability.sh) — no --resume / no --resume / --resume — and,
# on top of the console-output assertions that script already makes,
# also parses each run's own .orphera-logs/*.jsonl file and checks the
# structured events line up with what actually happened:
#   Run 1 (no --resume, marker absent): task1 succeeds, task2 fails,
#     task3 is never reached — no task_skipped events at all yet.
#   Run 2 (no --resume, marker still absent): task1 runs again (not
#     skipped) — default behavior is unchanged when logged, not just
#     on the console.
#   Run 3 (--resume, marker created): task1 is task_skipped with
#     reason=resume_checkpoint (and never task_start'd), task2 and
#     task3 both actually run and succeed, run_end success=true.
#
# restartability_test.yaml never exercises a `when:`-gated task, so a
# second, smaller fixture (observability_condition_test.scala, using
# the real PlaybookDsl — not a guessed YAML `when:` schema) covers the
# other skip reason:
#   Run 4: task1 runs, task2 is task_skipped with
#     reason=condition_not_met (its condition can never be true) and
#     is never task_start'd, task3 still runs afterwards, and the run
#     as a whole still succeeds — a condition skip is not a failure.
#
# Run from scala0, in orphera-pkg (tst0 needs a running agent):
#   ./test_observability.sh

set -uo pipefail

MANIFESTS_DIR="manifests"
MANIFEST="restartability_test.yaml"
CONDITION_MANIFEST="observability_condition_test.scala"
CHECKPOINT_FILE=".orphera-state/playbook-restartability-test.txt"
CONDITION_CHECKPOINT_FILE=".orphera-state/playbook-observability-condition-test.txt"
MARKER_PATH="/tmp/orphera-resume-test-marker"
LOG_GLOB=".orphera-logs/playbook-restartability-test-*.jsonl"
CONDITION_LOG_GLOB=".orphera-logs/playbook-observability-condition-test-*.jsonl"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

pass() { log "PASS: $1"; PASS=$((PASS + 1)); }
fail() { log "FAIL: $1"; FAIL=$((FAIL + 1)); }

# Reads PASS|<desc> / FAIL|<desc> lines from stdin (as printed by the
# python assertion blocks below) and feeds them through the same
# pass()/fail() counters as every other check in this script.
apply_verdicts() {
  local verdict desc
  while IFS='|' read -r verdict desc; do
    [ -z "$verdict" ] && continue
    case "$verdict" in
      PASS) pass "$desc" ;;
      FAIL) fail "$desc" ;;
      *) fail "unrecognized verdict line from log-checking script: $verdict|$desc" ;;
    esac
  done
}

cleanup() {
  rm -f "$CHECKPOINT_FILE" "$CONDITION_CHECKPOINT_FILE"
  orphera run --nodes tst0 -- rm -f "$MARKER_PATH" >/dev/null 2>&1
}

# Prints the JSON lines of the LAST run's segment (from its own
# run_start to end of file) found in whichever file matching $1
# (a glob) was most recently modified. RunLog filenames only have
# second resolution, so two runs launched inside the same second land
# in the same file and get appended to it — segmenting from the last
# run_start onward (rather than trusting "one file == one run") is
# robust either way.
latest_run_segment() {
  local glob_pattern="$1"
  python3 -c "
import glob, json, os, sys
files = glob.glob('$glob_pattern')
if not files:
    sys.exit(1)
files.sort(key=os.path.getmtime)
lines = open(files[-1]).read().splitlines()
last_start = None
for i, line in enumerate(lines):
    line = line.strip()
    if not line:
        continue
    try:
        d = json.loads(line)
    except Exception:
        continue
    if d.get('event') == 'run_start':
        last_start = i
if last_start is None:
    sys.exit(1)
for line in lines[last_start:]:
    line = line.strip()
    if line:
        print(line)
"
}

log "=== Observability regression test starting ==="
cleanup

# ---------------------------------------------------------------------
# Run 1: no --resume, marker absent. task1 succeeds, task2 fails, task3
# never runs.
# ---------------------------------------------------------------------
log "Run 1: expect task1 to succeed, task2 to fail, exit nonzero"
output1=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" 2>&1)
rc1=$?
echo "$output1"

if [ "$rc1" -ne 0 ]; then
  pass "run 1 exits nonzero (task2 genuinely failed)"
else
  fail "run 1 exited 0 — expected task2 to fail"
fi

seg1=$(latest_run_segment "$LOG_GLOB")
verdicts1=$(SEGMENT="$seg1" python3 <<'PYEOF'
import json, os

lines = [l for l in os.environ.get("SEGMENT", "").splitlines() if l.strip()]
events, bad_json = [], 0
for l in lines:
    try:
        events.append(json.loads(l))
    except Exception:
        bad_json += 1

if bad_json:
    print(f"FAIL|run 1: {bad_json} log line(s) failed to parse as JSON")
else:
    print("PASS|run 1: every log line is valid JSON")

missing_ts = [e for e in events if "ts" not in e]
if missing_ts:
    print(f"FAIL|run 1: {len(missing_ts)} event(s) missing a 'ts' field")
else:
    print("PASS|run 1: every event has a 'ts' field")


def find(event, **filters):
    return [e for e in events if e.get("event") == event and all(e.get(k) == v for k, v in filters.items())]


run_starts = find("run_start")
if len(run_starts) == 1 and run_starts[0].get("playbook") == "restartability-test" and run_starts[0].get("target_count") == 1:
    print("PASS|run 1: exactly one run_start with playbook=restartability-test, target_count=1")
else:
    print(f"FAIL|run 1: run_start events were {run_starts}")

t1_start = find("task_start", node="tst0", task="task1 always succeeds")
t1_end = find("task_end", node="tst0", task="task1 always succeeds")
if len(t1_start) == 1 and len(t1_end) == 1 and t1_end[0].get("success") is True \
        and isinstance(t1_end[0].get("duration_ms"), int) and "error" not in t1_end[0]:
    print("PASS|run 1: task1 logged as task_start + task_end(success=true, no error)")
else:
    print(f"FAIL|run 1: task1 events were start={t1_start} end={t1_end}")

t2_start = find("task_start", node="tst0", task="task2 fails until marker exists")
t2_end = find("task_end", node="tst0", task="task2 fails until marker exists")
if len(t2_start) == 1 and len(t2_end) == 1 and t2_end[0].get("success") is False and "error" in t2_end[0]:
    print("PASS|run 1: task2 logged as task_start + task_end(success=false, error present)")
else:
    print(f"FAIL|run 1: task2 events were start={t2_start} end={t2_end}")

t3_any = (
    find("task_start", task="task3 always succeeds")
    + find("task_end", task="task3 always succeeds")
    + find("task_skipped", task="task3 always succeeds")
)
if not t3_any:
    print("PASS|run 1: task3 never appears in the log (never reached — task2 failed first)")
else:
    print(f"FAIL|run 1: task3 unexpectedly appears in the log: {t3_any}")

skipped = find("task_skipped")
if not skipped:
    print("PASS|run 1: no task_skipped events (no checkpoint exists yet)")
else:
    print(f"FAIL|run 1: unexpected task_skipped events: {skipped}")

run_ends = find("run_end")
if len(run_ends) == 1 and run_ends[0].get("playbook") == "restartability-test" \
        and run_ends[0].get("success") is False and isinstance(run_ends[0].get("duration_ms"), int):
    print("PASS|run 1: exactly one run_end with success=false and a numeric duration_ms")
else:
    print(f"FAIL|run 1: run_end events were {run_ends}")
PYEOF
)
apply_verdicts <<< "$verdicts1"

# ---------------------------------------------------------------------
# Run 2: no --resume, marker still absent. Default behavior must be
# unchanged in the LOG too — task1 must appear as task_start again, not
# task_skipped.
# ---------------------------------------------------------------------
log "Run 2 (no --resume): expect task1 to run again, not be skipped"
output2=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" 2>&1)
rc2=$?
echo "$output2"

if [ "$rc2" -ne 0 ]; then
  pass "run 2 (no --resume) exits nonzero again"
else
  fail "run 2 (no --resume) exited 0 — expected task2 to fail again"
fi

seg2=$(latest_run_segment "$LOG_GLOB")
verdicts2=$(SEGMENT="$seg2" python3 <<'PYEOF'
import json, os

lines = [l for l in os.environ.get("SEGMENT", "").splitlines() if l.strip()]
events, bad_json = [], 0
for l in lines:
    try:
        events.append(json.loads(l))
    except Exception:
        bad_json += 1

if bad_json:
    print(f"FAIL|run 2: {bad_json} log line(s) failed to parse as JSON")
else:
    print("PASS|run 2: every log line is valid JSON")


def find(event, **filters):
    return [e for e in events if e.get("event") == event and all(e.get(k) == v for k, v in filters.items())]


t1_start = find("task_start", node="tst0", task="task1 always succeeds")
t1_skipped = find("task_skipped", node="tst0", task="task1 always succeeds")
if len(t1_start) == 1 and not t1_skipped:
    print("PASS|run 2: task1 logged as task_start again (ran, not skipped) — default behavior unchanged")
else:
    print(f"FAIL|run 2: task1 start={t1_start} skipped={t1_skipped}")

run_ends = find("run_end")
if len(run_ends) == 1 and run_ends[0].get("success") is False:
    print("PASS|run 2: run_end success=false again")
else:
    print(f"FAIL|run 2: run_end events were {run_ends}")
PYEOF
)
apply_verdicts <<< "$verdicts2"

# ---------------------------------------------------------------------
# Run 3: fix the condition that failed task2, then --resume. task1 must
# be task_skipped(reason=resume_checkpoint) and never task_start'd;
# task2 and task3 must actually run and succeed this time.
# ---------------------------------------------------------------------
log "Creating marker on tst0 so task2 can succeed, then running --resume"
orphera run --nodes tst0 -- sh -c "touch $MARKER_PATH" >/dev/null 2>&1

output3=$(orphera playbook "$MANIFESTS_DIR/$MANIFEST" --resume 2>&1)
rc3=$?
echo "$output3"

if [ "$rc3" -eq 0 ]; then
  pass "run 3 (--resume) exits 0"
else
  fail "run 3 (--resume) exited nonzero — expected full success this time"
fi

seg3=$(latest_run_segment "$LOG_GLOB")
verdicts3=$(SEGMENT="$seg3" python3 <<'PYEOF'
import json, os

lines = [l for l in os.environ.get("SEGMENT", "").splitlines() if l.strip()]
events, bad_json = [], 0
for l in lines:
    try:
        events.append(json.loads(l))
    except Exception:
        bad_json += 1

if bad_json:
    print(f"FAIL|run 3: {bad_json} log line(s) failed to parse as JSON")
else:
    print("PASS|run 3: every log line is valid JSON")


def find(event, **filters):
    return [e for e in events if e.get("event") == event and all(e.get(k) == v for k, v in filters.items())]


t1_skipped = find("task_skipped", node="tst0", task="task1 always succeeds", reason="resume_checkpoint")
t1_start = find("task_start", node="tst0", task="task1 always succeeds")
if len(t1_skipped) == 1 and not t1_start:
    print("PASS|run 3: task1 logged as task_skipped(reason=resume_checkpoint), never task_start'd")
else:
    print(f"FAIL|run 3: task1 skipped={t1_skipped} start={t1_start}")

t2_end = find("task_end", node="tst0", task="task2 fails until marker exists")
if len(t2_end) == 1 and t2_end[0].get("success") is True and "error" not in t2_end[0]:
    print("PASS|run 3: task2 logged as task_end(success=true, no error) — marker fixed it")
else:
    print(f"FAIL|run 3: task2 task_end events were {t2_end}")

t3_end = find("task_end", node="tst0", task="task3 always succeeds")
if len(t3_end) == 1 and t3_end[0].get("success") is True:
    print("PASS|run 3: task3 logged as task_end(success=true) — reached this time")
else:
    print(f"FAIL|run 3: task3 task_end events were {t3_end}")

run_ends = find("run_end")
if len(run_ends) == 1 and run_ends[0].get("success") is True:
    print("PASS|run 3: run_end success=true")
else:
    print(f"FAIL|run 3: run_end events were {run_ends}")
PYEOF
)
apply_verdicts <<< "$verdicts3"

cleanup

# ---------------------------------------------------------------------
# Run 4: the condition_not_met path, which restartability_test.yaml
# never exercises. task2's condition is guaranteed never true, so it
# must be task_skipped(reason=condition_not_met) and never task_start'd
# — and task3 must still run afterwards, with the run as a whole still
# succeeding (a condition skip is not a failure).
# ---------------------------------------------------------------------
log "Run 4: task2 gated on an impossible condition — expect task_skipped(reason=condition_not_met)"
output4=$(orphera playbook "$MANIFESTS_DIR/$CONDITION_MANIFEST" 2>&1)
rc4=$?
echo "$output4"

if [ "$rc4" -eq 0 ]; then
  pass "run 4 exits 0 (a condition skip is not a failure)"
else
  fail "run 4 exited nonzero — expected task1/task3 to succeed and task2 to be skipped, not failed"
fi

if printf '%s' "$output4" | grep -q "task2 gated on an impossible condition: skipped (condition not met)"; then
  pass "run 4 console output shows task2 skipped for condition, not run"
else
  fail "run 4 console output does not show task2 being skipped for its condition"
fi

seg4=$(latest_run_segment "$CONDITION_LOG_GLOB")
verdicts4=$(SEGMENT="$seg4" python3 <<'PYEOF'
import json, os

lines = [l for l in os.environ.get("SEGMENT", "").splitlines() if l.strip()]
events, bad_json = [], 0
for l in lines:
    try:
        events.append(json.loads(l))
    except Exception:
        bad_json += 1

if bad_json:
    print(f"FAIL|run 4: {bad_json} log line(s) failed to parse as JSON")
else:
    print("PASS|run 4: every log line is valid JSON")


def find(event, **filters):
    return [e for e in events if e.get("event") == event and all(e.get(k) == v for k, v in filters.items())]


t1_end = find("task_end", node="tst0", task="task1 always runs")
if len(t1_end) == 1 and t1_end[0].get("success") is True:
    print("PASS|run 4: task1 ran and succeeded")
else:
    print(f"FAIL|run 4: task1 task_end events were {t1_end}")

gated_skipped = find("task_skipped", node="tst0", task="task2 gated on an impossible condition", reason="condition_not_met")
gated_start = find("task_start", node="tst0", task="task2 gated on an impossible condition")
if len(gated_skipped) == 1 and not gated_start:
    print("PASS|run 4: gated task logged as task_skipped(reason=condition_not_met), never task_start'd")
else:
    print(f"FAIL|run 4: gated task skipped={gated_skipped} start={gated_start}")

t3_end = find("task_end", node="tst0", task="task3 always runs")
if len(t3_end) == 1 and t3_end[0].get("success") is True:
    print("PASS|run 4: task3 ran and succeeded after the skip (execution carried on)")
else:
    print(f"FAIL|run 4: task3 task_end events were {t3_end}")

run_ends = find("run_end")
if len(run_ends) == 1 and run_ends[0].get("success") is True:
    print("PASS|run 4: run_end success=true (a condition skip is not a failure)")
else:
    print(f"FAIL|run 4: run_end events were {run_ends}")
PYEOF
)
apply_verdicts <<< "$verdicts4"

cleanup

log "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
