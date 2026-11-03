#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the Lustre file system (lustre_cluster.scala,
# lustre_teardown.scala) on tst43-tst47.
#
# Lifecycle: lustre_teardown -> lustre_cluster, then independent checks run
# over `orphera run`:
#   - every server's LNet NID is its cluster_ip on tcp;
#   - the client (tst43) sees 1 MDT and 8 OSTs;
#   - a file striped over every OST is written, read back after the client's
#     cache is dropped, and has objects on all 8 OSTs;
#   - lustre_cluster.scala run again changes nothing: the targets are reported
#     as already formatted and the file is still there, unchanged.
# The file system is left built; the test file is removed at the end.
#
# Prerequisites: tst43-tst47 exist with an agent each (Rocky Linux 9), and
# reach downloads.whamcloud.com. THE TEARDOWN WIPES THE TARGET DISKS.
#
# Run from scala0, in orphera-pkg:   manifests/test_lustre.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

FS="lfs0"
CLIENT="tst43"
SERVERS="tst43 tst44 tst45 tst46 tst47"
WANT_MDT=1
WANT_OST=8
DIR="/mnt/$FS/orphera-test"

PASS=0
FAIL=0

LOG="/tmp/test_lustre.$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# run_on <node> <command>: --quiet, so only the command's own output comes back
# (not the "Running: <command>" line, whose text could match a KEY= below).
run_on() { orphera run sh -c "$2" --nodes "$1" --quiet 2>&1; }

# value_of <KEY> <text>: the value printed as KEY=value in the output.
value_of() { printf '%s\n' "$2" | grep -o "$1=[^ ]*" | tail -1 | cut -d= -f2; }

# assert_equals <description> <want> <got> [output to show on failure]
assert_equals() {
  local desc="$1" want="$2" got="$3" context="${4:-}"
  if [ "$want" = "$got" ]; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected '$want', got '$got')"
    [ -n "$context" ] && printf '%s\n' "$context" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  fi
}

finish() {
  log "passed: $PASS, failed: $FAIL"
  [ "$FAIL" -eq 0 ] || log "failures: grep FAIL $LOG"
  [ "$FAIL" -eq 0 ]
  exit $?
}

log "teardown (wipes the target disks)"
orphera cluster-playbook manifests/lustre_teardown.scala || true

log "build"
if ! orphera cluster-playbook manifests/lustre_cluster.scala; then
  log "FAIL: lustre_cluster.scala"
  FAIL=$((FAIL + 1))
  finish
fi

log "LNet NIDs"
for n in $SERVERS; do
  out=$(run_on "$n" 'echo "NIDS=$(lctl list_nids | tr "\n" ",")"')
  ip=$(grep -A3 "name: $n\$" inventory.yaml | grep -o 'cluster_ip: "[^"]*"' | cut -d'"' -f2)
  assert_equals "$n NID is $ip@tcp" "$ip@tcp," "$(value_of NIDS "$out")" "$out"
done

log "targets seen by the client"
out=$(run_on "$CLIENT" "lfs df /mnt/$FS; echo MDT=\$(lfs df /mnt/$FS | grep -c '\[MDT:') OST=\$(lfs df /mnt/$FS | grep -c '\[OST:')")
assert_equals "client sees $WANT_MDT MDT" "$WANT_MDT" "$(value_of MDT "$out")" "$out"
assert_equals "client sees $WANT_OST OSTs" "$WANT_OST" "$(value_of OST "$out")" "$out"

log "striped write and read"
out=$(run_on "$CLIENT" "set -e
rm -rf $DIR; mkdir -p $DIR
lfs setstripe -c -1 $DIR
dd if=/dev/urandom of=$DIR/data bs=1M count=256 status=none
echo WRITE=\$(md5sum < $DIR/data | cut -d' ' -f1)
sync
lctl set_param 'ldlm.namespaces.*osc*.lru_size=clear' >/dev/null
echo 3 > /proc/sys/vm/drop_caches
echo READ=\$(md5sum < $DIR/data | cut -d' ' -f1)
echo STRIPES=\$(lfs getstripe -c $DIR/data)
echo OSTS=\$(lfs getstripe $DIR/data | awk '/obdidx/ { f = 1; next } f && NF { print \$1 }' | sort -u | wc -l)")
written=$(value_of WRITE "$out")
assert_equals "data read back after dropping the cache matches" "$written" "$(value_of READ "$out")" "$out"
assert_equals "file striped over $WANT_OST OSTs" "$WANT_OST" "$(value_of STRIPES "$out")" "$out"
assert_equals "file has objects on $WANT_OST distinct OSTs" "$WANT_OST" "$(value_of OSTS "$out")" "$out"

log "re-run (idempotence)"
rerun=$(orphera cluster-playbook manifests/lustre_cluster.scala 2>&1)
rc=$?
assert_equals "lustre_cluster.scala re-run succeeds" "0" "$rc" "$(printf '%s\n' "$rerun" | tail -20)"
# Count the formatter's own report lines ("<role> on <disk>: already formatted"),
# not the script text the playbook runner prints, which contains the phrase too.
skipped=$(printf '%s\n' "$rerun" | grep -c 'on /dev/.*: already formatted (')
assert_equals "re-run leaves all $((WANT_OST + WANT_MDT + 1)) targets alone" "$((WANT_OST + WANT_MDT + 1))" "$skipped" "$(printf '%s\n' "$rerun" | grep -E 'mkfs.lustre|formatted|FAILED')"
out=$(run_on "$CLIENT" "echo AFTER=\$(md5sum < $DIR/data | cut -d' ' -f1)")
assert_equals "test file unchanged after the re-run" "$written" "$(value_of AFTER "$out")" "$out"

run_on "$CLIENT" "rm -rf $DIR" >/dev/null
finish
