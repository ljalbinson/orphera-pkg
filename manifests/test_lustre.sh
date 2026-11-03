#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the Lustre file system (lustre_cluster.scala,
# lustre_teardown.scala) on tst43-tst48.
#
# Lifecycle: lustre_teardown -> lustre_cluster, then independent checks run
# over `orphera run`:
#   - every server's LNet NID is its cluster_ip on tcp;
#   - the client (tst43) sees 2 MDTs and 8 OSTs;
#   - a file striped over every OST is written, read back after the client's
#     cache is dropped, and has objects on all 8 OSTs;
#   - DNE: a directory placed on MDT1 (tst48) keeps its files' metadata there,
#     and a directory striped over both MDTs spreads its files over both;
#   - the Rocky client tst50 (no targets): EL9, its NID, the Lustre client
#     packages version-locked, 2 MDTs and 8 OSTs, and files written on tst43
#     and tst50 read back identically on the other;
#   - lustre_cluster.scala run again changes nothing: the targets are reported
#     as already formatted and the file is still there, unchanged.
# The file system is left built; the test file is removed at the end.
#
# Prerequisites: tst43-tst50 exist (tst49, the Ubuntu client, is unmounted by
# the teardown; run lustre_client.scala afterwards to remount it) with an agent each (Rocky Linux 9), and
# reach downloads.whamcloud.com. THE TEARDOWN WIPES THE TARGET DISKS.
#
# Run from scala0, in orphera-pkg:   manifests/test_lustre.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

FS="lfs0"
CLIENT="tst43"
ROCKY_CLIENT="tst50"
SERVERS="tst43 tst44 tst45 tst46 tst47 tst48"
WANT_MDT=2
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
assert_equals "client sees $WANT_MDT MDTs" "$WANT_MDT" "$(value_of MDT "$out")" "$out"
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

log "Rocky client $ROCKY_CLIENT"
ip=$(grep -A3 "name: $ROCKY_CLIENT\$" inventory.yaml | grep -o 'cluster_ip: "[^"]*"' | cut -d'"' -f2)
out=$(run_on "$ROCKY_CLIENT" '. /etc/os-release; echo "EL=${VERSION_ID%%.*}"
echo "NIDS=$(lctl list_nids | tr "\n" ",")"
echo "LOCKED=$(dnf -q versionlock list 2>/dev/null | grep -c kmod-lustre-client)"
echo "MDT=$(lfs df /mnt/'"$FS"' | grep -c "\[MDT:") OST=$(lfs df /mnt/'"$FS"' | grep -c "\[OST:")"')
assert_equals "$ROCKY_CLIENT runs EL9" "9" "$(value_of EL "$out")" "$out"
assert_equals "$ROCKY_CLIENT NID is $ip@tcp" "$ip@tcp," "$(value_of NIDS "$out")" "$out"
assert_equals "$ROCKY_CLIENT has kmod-lustre-client version-locked" "1" "$(value_of LOCKED "$out")" "$out"
assert_equals "$ROCKY_CLIENT sees $WANT_MDT MDTs" "$WANT_MDT" "$(value_of MDT "$out")" "$out"
assert_equals "$ROCKY_CLIENT sees $WANT_OST OSTs" "$WANT_OST" "$(value_of OST "$out")" "$out"
out=$(run_on "$ROCKY_CLIENT" "echo SUM=\$(md5sum < $DIR/data | cut -d' ' -f1)")
assert_equals "file written on $CLIENT reads back on $ROCKY_CLIENT" "$written" "$(value_of SUM "$out")" "$out"
out=$(run_on "$ROCKY_CLIENT" "set -e
dd if=/dev/urandom of=$DIR/from-$ROCKY_CLIENT bs=1M count=64 status=none
sync
echo SUM=\$(md5sum < $DIR/from-$ROCKY_CLIENT | cut -d' ' -f1)")
rsum=$(value_of SUM "$out")
other=$(run_on "$CLIENT" "echo SUM=\$(md5sum < $DIR/from-$ROCKY_CLIENT | cut -d' ' -f1)")
assert_equals "file written on $ROCKY_CLIENT reads back on $CLIENT" "$rsum" "$(value_of SUM "$other")" "$out$other"

log "metadata on both MDTs (DNE)"
out=$(run_on "$CLIENT" "set -e
lfs mkdir -i 1 $DIR/on-mdt1
touch $DIR/on-mdt1/f
echo ON1=\$(lfs getstripe -m $DIR/on-mdt1/f)
lfs mkdir -c $WANT_MDT $DIR/striped
for i in \$(seq 1 64); do touch $DIR/striped/f\$i; done
echo SPREAD=\$(for i in \$(seq 1 64); do lfs getstripe -m $DIR/striped/f\$i; done | sort -u | wc -l)")
assert_equals "file in a directory on MDT1 has its metadata on MDT1" "1" "$(value_of ON1 "$out")" "$out"
assert_equals "files in a directory striped over $WANT_MDT MDTs use all $WANT_MDT" "$WANT_MDT" "$(value_of SPREAD "$out")" "$out"

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
