#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the Scala Native galera-cold-start (utils/galera-cold-start, built by `make galera-cold-start` from
# utils-native/) decides and does what utils/galera-cold-start.sh does: for each case (a state of tst3, tst4 and
# tst5) both are run with the same arguments and must print the same (clock times and the script's own path
# masked), exit the same and send the same commands to the same nodes with the same timeouts. A stand-in
# `orphera` on PATH plays the three nodes: ST_tst3, ST_tst4 and ST_tst5 give what each reports after
# "GALERA-STATE " (NOANSWER: nothing), STUB_FAIL_START=<node> fails `systemctl start mariadb` there and
# STUB_FAIL_BOOT=1 fails the bootstrap. Every node is healthy once asked, so the 3-minute wait for health is not
# exercised. No lab and no real `orphera`.
#
#   make check-galera-cold-start                                   # after make galera-cold-start
#   GALERA_NATIVE=utils/galera-cold-start.sh utils/test-galera-cold-start.sh   # the shell against itself
#
# Exit code 1 if any case differs.
set -u
cd "$(dirname "$0")/.." || exit 1
repo=$(pwd)
native=${GALERA_NATIVE:-utils/galera-cold-start}
case "$native" in /*) ;; *) native="$repo/$native" ;; esac
[ -x "$native" ] || { echo "$native not found: build it with 'make galera-cold-start'" >&2; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/orphera" <<'EOF'
#!/usr/bin/env bash
# Stand-in for `orphera run sh -c SCRIPT --nodes N --timeout T`, playing tst3, tst4 and tst5.
[ "${1:-}" = run ] || { echo "stand-in orphera: $*" >&2; exit 1; }
shift
node="" timeout="-" script=""
while [ $# -gt 0 ]; do
  case "$1" in
    sh) script=$3; shift 3 ;;
    --nodes) node=$2; shift 2 ;;
    --timeout) timeout=$2; shift 2 ;;
    *) shift ;;
  esac
done
sum=$(printf '%s' "$script" | sha256sum | cut -c1-16)
mkdir -p "$STUB_DIR/scripts"
printf '%s' "$script" > "$STUB_DIR/scripts/$sum"
echo "run $node $timeout $sum" >> "$STUB_DIR/calls"
echo "[$node] Running: sh -c ..."
case "$script" in
  *'GALERA-STATE none'*)
    var="ST_$node"; st=${!var:-}
    if [ "$st" = NOANSWER ]; then echo "Error: $node: timed out"; echo "[$node] exit=124 success=false"; exit 1; fi
    echo "[$node] GALERA-STATE $st"
    echo "[$node] exit=0 success=true" ;;
  *'GALERA-HEALTH ok'*)
    echo "[$node] GALERA-HEALTH ok" ;;
  *galera_new_cluster*)
    if [ -n "${STUB_FAIL_BOOT:-}" ]; then echo "[$node] galera_new_cluster failed"; echo "[$node] exit=1 success=false"; exit 1; fi
    echo "[$node] exit=0 success=true" ;;
  'systemctl start mariadb')
    if [ "${STUB_FAIL_START:-}" = "$node" ]; then
      echo "[$node] Job for mariadb.service failed because the control process exited with error code."
      echo "[$node] exit=1 success=false"; exit 1
    fi
    echo "[$node] exit=0 success=true" ;;
  *) echo "[$node] stand-in does not know this command" ;;
esac
EOF
chmod 755 "$tmp/bin/orphera"

mask() {
  sed -E 's/^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\]/[TIME]/; s#[^ ]*/galera-cold-start(\.sh)?#GALERA#g; s/^.*(2: --bootstrap needs a node name)$/\1/' "$1"
}

one() {  # <side> <program> <args...>
  local side=$1 prog=$2; shift 2
  local d="$tmp/$side"
  rm -rf "$d"; mkdir -p "$d/stub"
  (cd "$d" && PATH="$tmp/bin:$PATH" STUB_DIR="$d/stub" "$prog" "$@" >"$d/out" 2>"$d/err" </dev/null)
  echo "$?" > "$d/rc"
  mask "$d/out" > "$d/cmp.out"; mask "$d/err" > "$d/cmp.err"
  { sort "$d/stub/calls" 2>/dev/null
    for f in $(ls "$d/stub/scripts" 2>/dev/null); do echo "== $f"; cat "$d/stub/scripts/$f"; echo; done; } > "$d/cmp.state"
}

cases=0 diffs=0
t() {  # ST_tst3 ST_tst4 ST_tst5 [args...]
  export ST_tst3=$1 ST_tst4=$2 ST_tst5=$3; shift 3
  cases=$((cases + 1))
  one a "$repo/utils/galera-cold-start.sh" "$@"
  one b "$native" "$@"
  local s3=${ST_tst3//$U/U} s4=${ST_tst4//$U/U} s5=${ST_tst5//$U/U}
  local label="[${s3//$V/V} / ${s4//$V/V} / ${s5//$V/V}]${STUB_FAIL_START:+ STUB_FAIL_START=$STUB_FAIL_START}${STUB_FAIL_BOOT:+ STUB_FAIL_BOOT=1} $*"
  local same=1 f
  for f in rc cmp.out cmp.err cmp.state; do cmp -s "$tmp/a/$f" "$tmp/b/$f" || same=0; done
  if [ "$same" -eq 1 ]; then
    printf '  ok    %s (exit %s)\n' "$label" "$(cat "$tmp/a/rc")"
  else
    diffs=$((diffs + 1))
    printf '  DIFF  %s: exit %s (shell) / %s (native)\n' "$label" "$(cat "$tmp/a/rc")" "$(cat "$tmp/b/rc")"
    for f in cmp.out cmp.err cmp.state; do
      diff -u --label "shell $f" --label "native $f" "$tmp/a/$f" "$tmp/b/$f" | sed 's/^/        /'
    done
  fi
}

U=a1b2c3d4-0000-1111-2222-333344445555
V=ffff0000-9999-8888-7777-666655554444
D="down seqno=10 safe=0 uuid=$U"

# options
t "$D" "$D" "$D" --help
t "$D" "$D" "$D" -x
t "$D" "$D" "$D" --bootstrap
t "$D" "$D" "$D" --check --bootstrap tst9

# nothing to do, or not ours to do
t "none" "none" "none"
t "up size=3" "none" "down seqno=4 safe=0 uuid=$U"
t "up size=0" "up size=3" "up size=3"
t "up size=3" "up size=3" "up size=3"
t "up size=3" "up size=3" "up size=3" --check

# running nodes that disagree
t "up size=2" "up size=1" "$D"
t "up size=2" "up size=2" "up size=2"

# some up: the others rejoin
t "up size=1" "$D" "$D" --check
t "up size=1" "$D" "$D"
t "up size=2" "up size=2" "down seqno=-1 safe=0 uuid=none restarting=failed"
STUB_FAIL_START=tst4 t "up size=1" "$D" "$D"

# all down: where to bootstrap
t "down seqno=10 safe=0 uuid=$U" "down seqno=12 safe=0 uuid=$U" "down seqno=11 safe=0 uuid=$U" --check
t "down seqno=10 safe=0 uuid=$U" "down seqno=12 safe=0 uuid=$U" "down seqno=11 safe=0 uuid=$U"
t "down seqno=12 safe=0 uuid=$U" "down seqno=12 safe=1 uuid=$U" "down seqno=5 safe=0 uuid=$U" --check
t "down seqno=12 safe=1 uuid=$U" "down seqno=12 safe=0 uuid=$U" "down seqno=12 safe=1 uuid=$U" --check
t "down seqno=-1 safe=0 uuid=none" "down seqno=-1 safe=0 uuid=none" "down seqno=-1 safe=0 uuid=none stuck=Initialized"
t "down seqno=7 safe=0 uuid=$U" "down seqno=9 safe=0 uuid=$V" "down seqno=-1 safe=0 uuid=$V"
t "down seqno=7 safe=0 uuid=$U" "down seqno=-1 safe=0 uuid=$V" "down seqno=-1 safe=0 uuid=none" --check
t "down seqno=-1 safe=0 uuid=none" "down seqno=-1 safe=0 uuid=none" "down seqno=-1 safe=0 uuid=none" --bootstrap tst5
t "$D" "$D" "$D" --bootstrap tst9
STUB_FAIL_BOOT=1 t "$D" "down seqno=11 safe=0 uuid=$U" "$D"
STUB_FAIL_START=tst5 t "$D" "down seqno=11 safe=0 uuid=$U" "$D"

# a node that does not answer
t "$D" "NOANSWER" "$D"
t "" "$D" "$D"

echo "$cases cases, $diffs differ"
[ "$diffs" -eq 0 ]
