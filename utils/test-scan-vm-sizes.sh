#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the Scala Native scan-vm-sizes (utils/scan-vm-sizes, built by `make scan-vm-sizes` from
# utils-native/) prints and decides what utils/scan-vm-sizes.sh does: for each case (a set of VM configs, what
# libvirt reports on each hypervisor and what each guest sees) both are run with the same arguments and must print
# the same, exit the same and send the same commands to the same hosts. A stand-in `orphera` on PATH answers from
# fixture files: $FIX/hv-<hypervisor> (lines VM|name|state|cpu|MiB; no file: the hypervisor does not answer) and
# $FIX/guest-<vm> (nproc and MemTotal in kB; no file: the guest does not answer). No lab and no real `orphera`.
#
#   make check-scan-vm-sizes                                     # after make scan-vm-sizes
#   SCAN_NATIVE=utils/scan-vm-sizes.sh utils/test-scan-vm-sizes.sh   # the shell against itself: checks this test
#   SHOW=1 utils/test-scan-vm-sizes.sh                           # also print the shell version's output
#
# Exit code 1 if any case differs.
set -u
cd "$(dirname "$0")/.." || exit 1
repo=$(pwd)
native=${SCAN_NATIVE:-utils/scan-vm-sizes}
case "$native" in /*) ;; *) native="$repo/$native" ;; esac
[ -x "$native" ] || { echo "$native not found: build it with 'make scan-vm-sizes'" >&2; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/orphera" <<'EOF'
#!/usr/bin/env bash
# Stand-in for `orphera run --nodes A,B --quiet --timeout T -- sh -c CMD`, answering from $FIX.
[ "${1:-}" = run ] || { echo "stand-in orphera: $*" >&2; exit 1; }
shift
nodes="" timeout="-"
while [ $# -gt 0 ]; do
  case "$1" in --nodes) nodes=$2; shift 2 ;; --timeout) timeout=$2; shift 2 ;; --quiet) shift ;; --) shift; break ;; *) shift ;; esac
done
script=$3
sum=$(printf '%s' "$script" | sha256sum | cut -c1-16)
mkdir -p "$STUB_DIR/scripts"
printf '%s' "$script" > "$STUB_DIR/scripts/$sum"
echo "run $nodes $timeout $sum" >> "$STUB_DIR/calls"
IFS=, read -r -a ns <<<"$nodes"
case "$script" in
  *'echo "HV|up"'*)
    for n in "${ns[@]}"; do
      if [ -f "$FIX/hv-$n" ]; then echo "[$n] HV|up"; sed "s/^/[$n] /" "$FIX/hv-$n"
      else echo "Error: $n: connection refused"; fi
    done ;;
  *'G|$(nproc)'*)
    for n in "${ns[@]}"; do
      if [ -f "$FIX/guest-$n" ]; then echo "[$n] G|$(cat "$FIX/guest-$n")"; else echo "Error: $n: no agent"; fi
    done ;;
  *) echo "stand-in orphera does not know this command" ;;
esac
EOF
chmod 755 "$tmp/bin/orphera"

FIX="$tmp/fix"
cfg() {  # <file> <hostname> <hypervisor> [cpu] [memory]
  { echo "params:"; echo "  hostname: $2"; echo "  hypervisor: $3"
    [ -z "${4:-}" ] || echo "  cpu: $4"; [ -z "${5:-}" ] || echo "  memory: $5"; } > "$FIX/config/$1.yaml"
}
fresh() { rm -rf "$FIX"; mkdir -p "$FIX/config"; }

# The lab as it should be: everything agrees, plus a VM on gs2 that has no config file.
good() {
  fresh
  cfg tst1 tst1 gs1 2 4096
  cfg tst2 tst2 gs1 1 2048
  cfg tst10 tst10 gs2 4 8192
  cfg tst9 tst9 gs2 2 4096
  cfg scala0 scala0 gs2
  printf 'VM|tst1|running|2|4096\nVM|tst2|shut off|1|2048\n' > "$FIX/hv-gs1"
  printf 'VM|tst10|running|4|8192\nVM|tst9|running |2|4096\nVM|scala0|running|8|16384\nVM|orphan|paused|1|512\nVM|old-vm2|shut off|2|1024\n' > "$FIX/hv-gs2"
  echo "2|3900000" > "$FIX/guest-tst1"
  echo "4|8000000" > "$FIX/guest-tst10"
  echo "2|4012345 " > "$FIX/guest-tst9"
  echo "8|16000000" > "$FIX/guest-scala0"
}

one() {  # <side> <program> <args...>
  local side=$1 prog=$2; shift 2
  local d="$tmp/$side"
  rm -rf "$d"; mkdir -p "$d/stub"; cp -r "$FIX/config" "$d/config"
  (cd "$d" && PATH="$tmp/bin:$PATH" STUB_DIR="$d/stub" FIX="$FIX" "$prog" "$@" >"$d/out" 2>"$d/err" </dev/null)
  echo "$?" > "$d/rc"
  { sort "$d/stub/calls" 2>/dev/null
    for f in $(ls "$d/stub/scripts" 2>/dev/null); do echo "== $f"; cat "$d/stub/scripts/$f"; echo; done; } > "$d/state"
}

cases=0 diffs=0
t() {  # <label> <args...>
  local label=$1; shift
  cases=$((cases + 1))
  one a "$repo/utils/scan-vm-sizes.sh" "$@"
  one b "$native" "$@"
  local same=1 f
  for f in rc out err state; do cmp -s "$tmp/a/$f" "$tmp/b/$f" || same=0; done
  [ -z "${SHOW:-}" ] || { sed 's/^/        | /' "$tmp/a/out"; sed 's/^/        ! /' "$tmp/a/err"; }
  if [ "$same" -eq 1 ]; then
    printf '  ok    %s: %s (exit %s)\n' "$label" "$*" "$(cat "$tmp/a/rc")"
  else
    diffs=$((diffs + 1))
    printf '  DIFF  %s: %s: exit %s (shell) / %s (native)\n' "$label" "$*" "$(cat "$tmp/a/rc")" "$(cat "$tmp/b/rc")"
    for f in out err state; do
      diff -u --label "shell $f" --label "native $f" "$tmp/a/$f" "$tmp/b/$f" | sed 's/^/        /'
    done
  fi
}

good; t "all agree"
good; t "all agree, some" tst10 1 scala0
good; t "none matches" nosuch
good; GUEST_MEM_MIN_PCT=96 t "stricter guest memory"
good; GUEST_MEM_MIN_PCT=9x t "bad GUEST_MEM_MIN_PCT"
good; GUEST_MEM_MIN_PCT= t "empty GUEST_MEM_MIN_PCT"

good
printf 'VM|tst1|running|4|4096\nVM|tst2|shut off|1|3072\n' > "$FIX/hv-gs1"
echo "2|5000000" > "$FIX/guest-tst10"
echo "1|3000000" > "$FIX/guest-tst9"
rm "$FIX/guest-scala0"
t "differences"

good
cfg tst20 tst20 gs3 2 4096
cfg tst21 tst21 gs1 2 4096
t "a hypervisor down, a VM not defined"

good
cfg weird "we;ird" gs1 2 4096
cfg dup tst1 gs1 3 4096
cfg nohv tst30 "" 2 4096
mkdir "$FIX/config/sub.yaml"
t "odd configs"

good
cfg tst1 tst1 gs1 "" 4096
cfg tst10 tst10 gs2 4 ""
t "no cpu or memory in a config"

good; rm "$FIX/hv-gs1" "$FIX/hv-gs2"; t "no hypervisor answers"
good; ORPHERA_CONFIG_DIR=https://example.test/config t "config dir is a URL"
good; ORPHERA_CONFIG_DIR="file://$FIX/config/" t "config dir as a file URI"

echo "$cases cases, $diffs differ"
[ "$diffs" -eq 0 ]
