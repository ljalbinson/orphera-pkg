#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the Ubuntu Lustre client (lustre_client.scala) on tst49,
# against the file system lustre_cluster.scala built on tst43-tst48.
#
# Runs lustre_client.scala, then independent checks over `orphera run`:
#   - tst49 runs Ubuntu, its LNet NID is its cluster_ip on tcp, and its kernel
#     metapackages are held;
#   - it sees 2 MDTs and 8 OSTs;
#   - a file written on tst49 (striped over every OST) reads back identically
#     on tst43, and a file written on tst43 reads back identically on tst49;
#   - a directory tst49 makes on MDT1 keeps its files' metadata there;
#   - lustre_client.scala run again does not rebuild and leaves the mount.
# The servers are not torn down; the test directory is removed at the end.
#
# Prerequisites: the servers are built (lustre_cluster.scala or
# test_lustre.sh), tst49 exists with an agent (Ubuntu 24.04) and reaches
# downloads.whamcloud.com. The first run builds the client: allow a few minutes.
#
# Run from scala0, in orphera-pkg:   manifests/test_lustre_client.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

FS="lfs0"
CLIENT="tst49"
OTHER="tst43"    # the client on the server side
WANT_MDT=2
WANT_OST=8
DIR="/mnt/$FS/orphera-client-test"

PASS=0
FAIL=0

LOG="/tmp/test_lustre_client.$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# run_on <node> <command>: --quiet, so only the command's own output comes back.
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
  [ "$FAIL" -eq 0 ] || log "failures: grep -A15 FAIL $LOG"
  [ "$FAIL" -eq 0 ]
  exit $?
}

log "build and mount the client"
if ! orphera cluster-playbook lustre_client.scala; then
  log "FAIL: lustre_client.scala"
  FAIL=$((FAIL + 1))
  finish
fi

log "client node"
ip=$(grep -A3 "name: $CLIENT\$" inventory.yaml | grep -o 'cluster_ip: "[^"]*"' | cut -d'"' -f2)
out=$(run_on "$CLIENT" '. /etc/os-release; echo "OS=$ID"; echo "NIDS=$(lctl list_nids | tr "\n" ",")"; echo "HELD=$(apt-mark showhold | grep -c "^linux-")"')
assert_equals "$CLIENT runs Ubuntu" "ubuntu" "$(value_of OS "$out")" "$out"
assert_equals "$CLIENT NID is $ip@tcp" "$ip@tcp," "$(value_of NIDS "$out")" "$out"
held=$(value_of HELD "$out")
assert_equals "$CLIENT kernel metapackages are held" "yes" "$([ "${held:-0}" -gt 0 ] && echo yes || echo no)" "$out"

log "targets seen by $CLIENT"
out=$(run_on "$CLIENT" "echo MDT=\$(lfs df /mnt/$FS | grep -c '\[MDT:') OST=\$(lfs df /mnt/$FS | grep -c '\[OST:')")
assert_equals "$CLIENT sees $WANT_MDT MDTs" "$WANT_MDT" "$(value_of MDT "$out")" "$out"
assert_equals "$CLIENT sees $WANT_OST OSTs" "$WANT_OST" "$(value_of OST "$out")" "$out"

log "data between $CLIENT and $OTHER"
out=$(run_on "$CLIENT" "set -e
rm -rf $DIR; mkdir -p $DIR
lfs setstripe -c -1 $DIR
dd if=/dev/urandom of=$DIR/from-$CLIENT bs=1M count=128 status=none
sync
echo SUM=\$(md5sum < $DIR/from-$CLIENT | cut -d' ' -f1)")
sum=$(value_of SUM "$out")
other=$(run_on "$OTHER" "echo SUM=\$(md5sum < $DIR/from-$CLIENT | cut -d' ' -f1)")
assert_equals "file written on $CLIENT reads back on $OTHER" "$sum" "$(value_of SUM "$other")" "$out$other"

out=$(run_on "$OTHER" "set -e
dd if=/dev/urandom of=$DIR/from-$OTHER bs=1M count=128 status=none
sync
echo SUM=\$(md5sum < $DIR/from-$OTHER | cut -d' ' -f1)")
sum=$(value_of SUM "$out")
other=$(run_on "$CLIENT" "echo SUM=\$(md5sum < $DIR/from-$OTHER | cut -d' ' -f1)")
assert_equals "file written on $OTHER reads back on $CLIENT" "$sum" "$(value_of SUM "$other")" "$out$other"

log "a directory on MDT1 from $CLIENT"
out=$(run_on "$CLIENT" "set -e
lfs mkdir -i 1 $DIR/on-mdt1
touch $DIR/on-mdt1/f
echo ON1=\$(lfs getstripe -m $DIR/on-mdt1/f)")
assert_equals "file in a directory on MDT1 has its metadata on MDT1" "1" "$(value_of ON1 "$out")" "$out"

log "re-run (idempotence)"
rerun=$(orphera cluster-playbook lustre_client.scala 2>&1)
rc=$?
assert_equals "lustre_client.scala re-run succeeds" "0" "$rc" "$(printf '%s\n' "$rerun" | tail -20)"
# The build's own report line names the kernel; the printed script text has $KV there.
built=$(printf '%s\n' "$rerun" | grep -c 'already built: Lustre [0-9.]* client for [0-9]')
assert_equals "re-run does not rebuild" "1" "$built" "$(printf '%s\n' "$rerun" | grep -E 'built|FAILED')"
mounted=$(printf '%s\n' "$rerun" | grep -c "/mnt/$FS already mounted\$")
assert_equals "re-run leaves the mount" "1" "$mounted" "$(printf '%s\n' "$rerun" | grep -E 'mount|FAILED')"

run_on "$CLIENT" "rm -rf $DIR" >/dev/null
finish
