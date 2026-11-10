#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the Scala Native move-vm (utils/move-vm, built by `make move-vm` from utils-native/) does what
# utils/move-vm.sh does: for each case both are run with the same arguments and must print the same (standard
# output and error, with the clock times and the snapshot's time stamp masked), exit the same, send the same
# scripts to the same hosts with the same timeouts, and leave the same config file and saved XML. A stand-in
# `orphera` on PATH answers `orphera run` from the script it is given (STUB_FAIL picks a step to fail); the VM
# config and the inventory are made in a temporary directory, the working directory of both runs. No lab, no
# agent and no real `orphera`: nothing is moved.
#
#   make check-move-vm                              # after make move-vm
#   MOVEVM_NATIVE=path/to/move-vm utils/test-move-vm.sh
#   MOVEVM_NATIVE=utils/move-vm.sh utils/test-move-vm.sh   # the shell against itself: checks this test
#
# Exit code 1 if any case differs. It takes about a minute: some cases include the waits of the real thing.
set -u
cd "$(dirname "$0")/.." || exit 1
repo=$(pwd)
native=${MOVEVM_NATIVE:-utils/move-vm}
case "$native" in /*) ;; *) native="$repo/$native" ;; esac
[ -x "$native" ] || { echo "$native not found: build it with 'make move-vm'" >&2; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"

cat > "$tmp/bin/orphera" <<'EOF'
#!/usr/bin/env bash
# Stand-in for `orphera run --nodes N --quiet --timeout T -- <command>`: records the call (the script's time stamp
# masked) and answers as the script would on a hypervisor that has the VM.
[ "${1:-}" = run ] || { echo "stand-in orphera: $*" >&2; exit 1; }
shift
node="" timeout=""
while [ $# -gt 0 ]; do
  case "$1" in --nodes) node=$2; shift 2 ;; --timeout) timeout=$2; shift 2 ;; --quiet) shift ;; --) shift; break ;; *) shift ;; esac
done
if [ "${1:-}" = sh ]; then script=$3; else script="$*"; fi
masked=$(printf '%s' "$script" | sed -E 's/move-[0-9]{8}-[0-9]{6}/move-STAMP/g')
sum=$(printf '%s' "$masked" | sha256sum | cut -c1-16)
mkdir -p "$STUB_DIR/scripts"
printf '%s' "$masked" > "$STUB_DIR/scripts/$sum"
echo "run $node $timeout $sum" >> "$STUB_DIR/calls"
fail() { [ "${STUB_FAIL:-}" = "$1" ]; }
say() { echo "[$node] $1"; }
has() { case "$script" in *"$1"*) return 0 ;; *) return 1 ;; esac; }

fail noanswer && [ "$node" = gs3 ] && { echo "Error: no agent answered on gs3: connection refused"; exit 1; }
if [ "${1:-}" = hostname ]; then fail boot || say "$node"; exit 0; fi
if has 'look movable'; then
  fail srccheck && { say "RESULT|error|domain tst17 is not defined on this hypervisor"; exit 0; }
  say "DATA|state|${STUB_STATE:-running}"
  say "DATA|used|${STUB_USED:-2147483648}"
  say "DATA|mem|4096"
  say "DATA|autostart|enable"
  say "DATA|vendor|GenuineIntel"
  say "DATA|bridges|br-net105 br-net107 "
  say "RESULT|ok|domain tst17 and dataset look movable"
elif has 'destination is ready'; then
  say "DATA|avail|${STUB_AVAIL:-107374182400}"
  say "DATA|memavail|${STUB_MEMAVAIL:-65536}"
  say "DATA|vendor|${STUB_DST_VENDOR:-GenuineIntel}"
  say "RESULT|ok|destination is ready"
elif has 'does not resolve on this host'; then
  say "RESULT|ok|resolved"
elif has 'RESULT|ok|already shut off'; then
  say "RESULT|ok|shut down after 4s"
elif has 'RESULT|ok|xml read'; then
  say "DATA|xmlsave|$(printf '<domain>source</domain>\n' | base64 -w0)"
  say "DATA|xml|$(printf '<domain>destination</domain>\n' | base64 -w0)"
  say "RESULT|ok|xml read"
elif has 'zfs snapshot'; then
  say "RESULT|ok|snapshot"
elif has 'manifest taken'; then
  if fail manifest && [ "$node" = gs3 ]; then say "DATA|manifest|ffff"; else say "DATA|manifest|abc123"; fi
  say "DATA|files|3"
  say "RESULT|ok|manifest taken"
elif has 'zfs receive -u'; then
  if fail listen; then sleep 1; echo "receiver log line"; say "RESULT|error|nc | zfs receive failed"; exit 0; fi
  for _ in $(seq 1 50); do [ -e "$STUB_DIR/sent" ] && break; sleep 0.2; done
  if fail receive; then echo "zfs receive: bad stream"; say "RESULT|error|nc | zfs receive failed"; exit 1; fi
  say "RESULT|ok|received into tank/kvm/gs3/tst17, mounted at /exports/kvm/gs3/tst17"
elif has 'not listening yet'; then
  if fail listen; then say "RESULT|error|not listening yet"; else say "RESULT|ok|listening"; fi
elif has 'stream sent'; then
  fail send && { say "RESULT|error|zfs send | nc 10.0.0.3 29500 failed: broken pipe"; exit 0; }
  touch "$STUB_DIR/sent"
  say "RESULT|ok|stream sent"
elif has 'defined (autostart'; then
  fail define && { say "RESULT|error|virsh define: error: bad XML"; exit 0; }
  [ -z "${STUB_LEFTOVER:-}" ] || say "DATA|leftover|$STUB_LEFTOVER"
  say "RESULT|ok|defined (autostart: enable)"
elif has 'undefined on'; then
  fail undefine && { say "RESULT|error|virsh undefine: domain is busy"; exit 0; }
  say "RESULT|ok|undefined on gs1"
elif has 'RESULT|ok|started'; then
  if fail start && [ "$node" = gs3 ]; then say "RESULT|error|Requested operation is not valid"; else say "RESULT|ok|started"; fi
elif has 'destination cleaned'; then
  say "RESULT|ok|destination cleaned"
elif has 'snapshot removed'; then
  say "RESULT|ok|snapshot removed"
elif has 'source dataset destroyed'; then
  fail destroy && { say "RESULT|error|zfs destroy failed (is something still using the dataset?)"; exit 0; }
  say "RESULT|ok|source dataset destroyed"
else
  say "RESULT|error|stand-in orphera does not know this script"
fi
EOF
chmod 755 "$tmp/bin/orphera"

setup() {  # <dir>: a working directory with the VM config and an inventory
  mkdir -p "$1/config" "$1/stub" "$1/tmp"
  printf 'params:\n  hostname: tst17\n  hypervisor:   gs1   # where it runs\n  memory: 4096\n' > "$1/config/tst17.yaml"
  printf 'params:\n  hostname: tst18\n  hypervisor: gs1\n' > "$1/config/tst18.yaml"
  cat > "$1/inventory.yaml" <<'YAML'
nodes:
  - name: gs1
    host: gs1.lab.test
  - name: gs3
    host: "gs3.lab.test"
  - name: tst17
    host: tst17.lab.test
YAML
}

mask() { sed -E 's/^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\]/[TIME]/; s/move-[0-9]{8}-[0-9]{6}/move-STAMP/g' "$1"; }

one() {  # <side> <program> <args...>: runs it in a fresh working directory, then keeps what to compare
  local side=$1 prog=$2; shift 2
  local d="$tmp/$side"
  rm -rf "$d"; setup "$d"
  (cd "$d" && PATH="$tmp/bin:$PATH" STUB_DIR="$d/stub" TMPDIR="$tmp/shared-tmp" HOME="$d" \
    ORPHERA_CONFIG_DIR= ORPHERA_INVENTORY= "$prog" "$@" >"$d/out" 2>"$d/err" <"${STDIN:-/dev/null}")
  echo "$?" > "$d/rc"
  mask "$d/out" > "$d/cmp.out"; mask "$d/err" > "$d/cmp.err"
  { sort "$d/stub/calls" 2>/dev/null; for f in $(ls "$d/stub/scripts" 2>/dev/null); do echo "== $f"; cat "$d/stub/scripts/$f"; echo; done
    echo "== config"; cat "$d/config/tst17.yaml"
    echo "== saved XML"; cat "$tmp/shared-tmp/move-vm-tst17-gs1.xml" 2>/dev/null
    echo "== receive log"; cat "$tmp/shared-tmp/move-vm-tst17-receive.log" 2>/dev/null; } > "$d/cmp.state"
  rm -rf "$tmp/shared-tmp"; mkdir -p "$tmp/shared-tmp"
}

cases=0 diffs=0
t() {
  cases=$((cases + 1))
  one a "$repo/utils/move-vm.sh" "$@"
  one b "$native" "$@"
  local label="$(env | grep '^STUB_[A-Z_]*=' | grep -v '^STUB_DIR=' | sort | tr '\n' ' ')$*"
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
mkdir -p "$tmp/shared-tmp"
P="--port 29500"

# usage and argument errors
t
t --help
t --bogus tst17 gs3
t --port x tst17 gs3
t --stop-wait
t tst17
t tst17 gs3 extra
t tst17 --dry-run gs3
t nosuch gs3
t tst17 'g s'
t tst17 gs1
t 17 gs3 --dry-run

# checks and the plan
t --dry-run $P tst17 gs3
t --dry-run $P --delete-source --no-start 17 gs3
t --dry-run $P config/tst17.yaml gs2
STUB_STATE="shut off" t --dry-run $P tst17 gs3
STUB_FAIL=srccheck t --dry-run $P tst17 gs3
STUB_FAIL=noanswer t --dry-run $P tst17 gs3
STUB_DST_VENDOR=AuthenticAMD t --dry-run $P tst17 gs3
STUB_AVAIL=1000 t --dry-run $P tst17 gs3
STUB_MEMAVAIL=1024 t --dry-run $P tst17 gs3
t $P tst17 gs3

# moves
t --yes $P tst17 gs3
t --yes $P --delete-source --force-stop --stop-wait 60 --boot-wait 30 --transfer-timeout 600 tst17 gs3
t --yes $P --no-start tst17 gs3
STUB_STATE="shut off" t --yes $P tst17 gs3
STUB_LEFTOVER="12: <source bridge='gs1'/>" t --yes $P tst17 gs3
STUB_FAIL=destroy t --yes $P --delete-source tst17 gs3

# failures and rollback
STUB_FAIL=send t --yes $P tst17 gs3
STUB_FAIL=listen t --yes $P tst17 gs3
STUB_FAIL=receive t --yes $P tst17 gs3
STUB_FAIL=manifest t --yes $P tst17 gs3
STUB_FAIL=define t --yes $P tst17 gs3
STUB_FAIL=undefine t --yes $P tst17 gs3
STUB_FAIL=start t --yes $P tst17 gs3
STUB_FAIL=boot t --yes $P --boot-wait 5 tst17 gs3

echo "$cases cases, $diffs differ"
[ "$diffs" -eq 0 ]
