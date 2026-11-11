#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the Scala Native start-vm, stop-vm, reboot-vm and list-vms (utils/<name>, built by `make native-utils`
# from utils-native/) do what their shell versions do: for each case both are run with the same arguments, in the
# same working directory, and must print the same, exit the same and send the same scripts to the same hosts with
# the same timeouts. A stand-in `orphera` on PATH records each `orphera run` and answers it; the VM configs are
# made in a temporary directory. No lab, no agent and no real `orphera`: nothing is started or stopped.
#
#   make check-vm-scripts                                 # after make native-utils
#   NATIVE_SUFFIX=.sh utils/test-vm-scripts.sh            # the shell versions against themselves: checks this test
#
# Exit code 1 if any case differs.
set -u
cd "$(dirname "$0")/.." || exit 1
repo=$(pwd)
suffix=${NATIVE_SUFFIX:-}
for t in start-vm stop-vm reboot-vm list-vms; do
  [ -x "utils/$t$suffix" ] || { echo "utils/$t$suffix not found: build it with 'make native-utils'" >&2; exit 2; }
done

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/orphera" <<'EOF'
#!/usr/bin/env bash
# Stand-in for `orphera nodes --groups` and `orphera run --nodes N [--quiet] [--timeout T] -- sh -c SCRIPT`.
if [ "${1:-}" = nodes ]; then printf 'k8s: tst30 tst31\nhypervisors: gs1, gs2\n'; exit 0; fi
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

case "$script" in
  *'echo "HV|up"'*)   # list-vms
    echo "warning: an untagged line"
    IFS=, read -r -a hs <<<"$nodes"
    for h in "${hs[@]}"; do
      [ "$h" = gsdown ] && continue
      echo "[$h] HV|up"
      echo "[$h] VM|tst1|running|2|4096|enable"
      echo "[$h] VM|tst2-long-name-for-the-column|shut off|4|8192|disable"$'\r'
      echo "[$h] VM|tst3|running |1.5|2048.7|"
      echo "[$h] VM|odd|paused|||something"
      echo "[$h] not a VM line"
    done
    [ "$nodes" = gsdown ] && echo "Error: gsdown: connection refused"
    exit 0 ;;
esac
vm=$(printf '%s' "$script" | grep -o "domstate '[^']*'" | head -1 | sed "s/domstate '//; s/'\$//")
case "$vm" in
  bad*)    echo "[$nodes] RESULT|error|domain '$vm' is not defined on this hypervisor" ;;
  silent*) echo "Running on $nodes"; echo "Error: no agent answered on $nodes"; echo "connection refused" ;;
  *)
    case "$script" in
      *'already running'*)   echo "[$nodes] RESULT|ok|started" ;;
      *'shut down after'*)   echo "[$nodes] RESULT|ok|shut down after 4s" ;;
      *'reboot sent'*)       echo "[$nodes] RESULT|ok|reboot sent" ;;
      *'how="shut down"'*)   echo "[$nodes] RESULT|ok|shut down, started" ;;
      *)                     echo "[$nodes] RESULT|error|stand-in does not know this script" ;;
    esac ;;
esac
EOF
chmod 755 "$tmp/bin/orphera"

setup() {  # <dir>
  mkdir -p "$1/config" "$1/stub"
  for i in 1 2 3; do printf 'params:\n  hostname: tst%s\n  hypervisor: gs%s\n' "$i" "$(( (i % 2) + 1 ))" > "$1/config/tst$i.yaml"; done
  printf 'params:\n  hostname: tst-a-very-long-name\n  hypervisor: gs10\n' > "$1/config/long.yaml"
  printf 'params:\n  hostname: badvm\n  hypervisor: gs1\n' > "$1/config/badvm.yaml"
  printf 'params:\n  hostname: silentvm\n  hypervisor: gs2\n' > "$1/config/silentvm.yaml"
}

one() {  # <side> <program> <args...>
  local side=$1 prog=$2; shift 2
  local d="$tmp/$side"
  rm -rf "$d"; setup "$d"
  (cd "$d" && PATH="$tmp/bin:$PATH" STUB_DIR="$d/stub" ORPHERA_CONFIG_DIR= HYPERVISORS= \
    "$prog" "$@" >"$d/out" 2>"$d/err" </dev/null)
  echo "$?" > "$d/rc"
  { sort "$d/stub/calls" 2>/dev/null
    for f in $(ls "$d/stub/scripts" 2>/dev/null); do echo "== $f"; cat "$d/stub/scripts/$f"; echo; done; } > "$d/state"
}

cases=0 diffs=0
t() {  # <tool> <args...>
  local tool=$1; shift
  cases=$((cases + 1))
  one a "$repo/utils/$tool.sh" "$@"
  one b "$repo/utils/$tool$suffix" "$@"
  local same=1 f
  for f in rc out err state; do cmp -s "$tmp/a/$f" "$tmp/b/$f" || same=0; done
  if [ "$same" -eq 1 ]; then
    printf '  ok    %s %s (exit %s)\n' "$tool" "$*" "$(cat "$tmp/a/rc")"
  else
    diffs=$((diffs + 1))
    printf '  DIFF  %s %s: exit %s (shell) / %s (native)\n' "$tool" "$*" "$(cat "$tmp/a/rc")" "$(cat "$tmp/b/rc")"
    for f in out err state; do
      diff -u --label "shell $f" --label "native $f" "$tmp/a/$f" "$tmp/b/$f" | sed 's/^/        /'
    done
  fi
}

t start-vm
t start-vm tst1
t start-vm 1 2 config/tst3.yaml
t start-vm long
t start-vm badvm
t start-vm silentvm
t start-vm nosuch
t start-vm -h
t start-vm tst1 badvm nosuch silentvm 2

t stop-vm
t stop-vm --help
t stop-vm --wait
t stop-vm --wait x 1
t stop-vm --wait '' 1
t stop-vm --force
t stop-vm -z 1
t stop-vm 1 2
t stop-vm --wait 300 --force 1 badvm
t stop-vm --wait 010 1
t stop-vm 1 --force

t reboot-vm
t reboot-vm --help
t reboot-vm -x 1
t reboot-vm 1
t reboot-vm --soft 1 silentvm
t reboot-vm --hard 1
t reboot-vm --hard --wait 60 --force 1 badvm
t reboot-vm --hard --soft 2
t reboot-vm --wait 015 --hard 3
t reboot-vm --wait

t list-vms
t list-vms gs1
t list-vms gs1 gsdown gs2
t list-vms 'gs1  gs2'
t list-vms gsdown

echo "$cases cases, $diffs differ"
[ "$diffs" -eq 0 ]
