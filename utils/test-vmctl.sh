#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the Scala Native vmctl (utils/vmctl, built by `make vmctl` from utils-native/) does what utils/vmctl.sh
# does: for each case, both are run with the same arguments and their standard output, standard error and exit
# code must be the same. A stand-in `orphera` on PATH answers `nodes` and `cluster-playbook` (it prints the
# ORPHERA_* variables it was given as steps, so those are compared too), and the VM configs are made in a
# temporary ORPHERA_CONFIG_DIR. No lab, no agent and no real `orphera`; the playbooks are not run.
#
#   make check-vmctl                          # after make vmctl
#   VMCTL_NATIVE=path/to/vmctl utils/test-vmctl.sh
#   VMCTL_NATIVE=utils/vmctl.sh utils/test-vmctl.sh   # the shell against itself: checks this test
#
# `list` is not compared (it runs utils/list-vms.sh in both). Exit code 1 if any case differs.
set -u
cd "$(dirname "$0")/.." || exit 1
repo=$(pwd)
native=${VMCTL_NATIVE:-utils/vmctl}
case "$native" in /*) ;; *) native="$repo/$native" ;; esac
[ -x "$native" ] || { echo "$native not found: build it with 'make vmctl'" >&2; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/config" "$tmp/a" "$tmp/b"

cat > "$tmp/bin/orphera" <<'EOF'
#!/usr/bin/env bash
# Stand-in for orphera: nodes and cluster-playbook only.
case "${1:-}" in
  nodes)
    shift
    if [ "${1:-}" = --groups ]; then
      printf 'ceph: tst0 tst1 tst2\nk8s-workers: tst33 tst34\nk8s-dns: tst39\nhypervisors: gs1, gs2\n'
      exit 0
    fi
    nodes="" excl=""
    while [ $# -gt 0 ]; do
      case "$1" in --nodes) nodes=$2; shift 2 ;; --exclude-nodes) excl=$2; shift 2 ;; *) shift ;; esac
    done
    seen=","
    IFS=, read -r -a ns <<<"$nodes"
    for n in "${ns[@]}"; do
      case "$n" in tst*|bad*|weird) ;; *) echo "Error: Unknown node(s): $n" >&2; exit 1 ;; esac
      case ",$excl," in *",$n,"*) continue ;; esac
      case "$seen" in *",$n,"*) continue ;; esac
      seen="$seen$n,"
      echo "$n"
    done ;;
  cluster-playbook)
    pb=$2; shift 2
    cfg=""
    [ "${1:-}" = --config ] && cfg=$2
    name=$(basename "$pb" .scala | tr _ -)
    echo "echoed script text"
    echo "[$name] Starting $pb"
    echo "[$name] host: Running: virsh start x"
    # Only the variables vmctl sets for the playbooks (never ORPHERA_TOKEN or other secrets of the caller).
    env | grep -E '^ORPHERA_(AUTOSTART|HYPERVISORS|VM_[A-Z_]+)=' | LC_ALL=C sort | sed "s/^/[$name] env /"
    [ -z "$cfg" ] || echo "[$name] config $(basename "$cfg")"
    case "$cfg${ORPHERA_HYPERVISORS:-}" in *bad*) echo "[$name] the step FAILED"; exit 1 ;; esac
    case "${ORPHERA_VM_NAMES:-}" in *bad*) echo "Cluster playbook kvm-autostart failed"; exit 0 ;; esac
    echo "[$name] host: done" ;;
  *) echo "stand-in orphera: $*" >&2; exit 1 ;;
esac
EOF
chmod 755 "$tmp/bin/orphera"

vm() {  # <file> <hypervisor line> <hostname line>
  printf 'params:\n%s\n%s\n  other: x\n' "$2" "$3" > "$tmp/config/$1.yaml"
}
for i in 0 1 2 33 34 39 49; do vm "tst$i" "  hypervisor: gs1" "  hostname: tst$i"; done
vm tst5 "  hypervisor: 'gs2'   # quoted, with a comment" '  hostname: "tst5"'
vm tst7 "  memory: 4096" "  hostname: tst7"
vm badvm "  hypervisor: gs2" "  hostname: badvm"
vm weird "  hypervisor: gs1" "  hostname: we;ird"
cfg5="$tmp/config/tst5.yaml"

cases=0 diffs=0
t() {
  cases=$((cases + 1))
  local rc_a rc_b
  (PATH="$tmp/bin:$PATH" ORPHERA_CONFIG_DIR="$tmp/config" HYPERVISORS="" \
    "$repo/utils/vmctl.sh" "$@" >"$tmp/a/out" 2>"$tmp/a/err" </dev/null); rc_a=$?
  (PATH="$tmp/bin:$PATH" ORPHERA_CONFIG_DIR="$tmp/config" HYPERVISORS="" \
    "$native" "$@" >"$tmp/b/out" 2>"$tmp/b/err" </dev/null); rc_b=$?
  if [ "$rc_a" = "$rc_b" ] && cmp -s "$tmp/a/out" "$tmp/b/out" && cmp -s "$tmp/a/err" "$tmp/b/err"; then
    printf '  ok    %s (exit %s)\n' "$*" "$rc_a"
  else
    diffs=$((diffs + 1))
    printf '  DIFF  %s: exit %s (shell) / %s (native)\n' "$*" "$rc_a" "$rc_b"
    diff -u --label shell --label native "$tmp/a/out" "$tmp/b/out" | sed 's/^/        /'
    diff -u --label shell-stderr --label native-stderr "$tmp/a/err" "$tmp/b/err" | sed 's/^/        /'
  fi
}

# usage and argument errors
t
t --help
t bogus
t autostart
t autostart frob
t start
t start -h
t start -x 1
t start -
t start --retries
t start --retries x 0
t start --wait 3 1
t start --hard 1
t start --yes 1
t start --dry-run 1
t start --all 1
t start --hypervisor gs1 1
t stop --retries 2 1
t stop --except tst0 1
t shutdown-all --retries 1 gs1
t start-all --wait 5 gs1

# start, stop, reboot
t start 0
t start tst1
t start 0 1 2
t start --retries 5 0
t start 1 --retries 0
t stop --wait 300 --force 1
t reboot --hard --force 2
t reboot --soft --retries 4 2
t start "$cfg5"
t start 5
t start 7
t start nosuch
t start weird
t start badvm 1 nosuch

# node groups
t start --node-groups ceph
t stop --node-groups k8s-workers,k8s-dns --exclude-nodes tst39
t start --node-groups ceph 49 0
t start 49 --node-groups ceph --node-groups k8s-dns
t start --node-groups ceph "$cfg5"
t start --node-groups nogroup
t start --node-groups ceph,,k8s-dns
t start --node-groups
t start --exclude-nodes tst1
t start --exclude-nodes tst1 0 1 2
t start --node-groups ceph --exclude-nodes tst0,tst1,tst2
t start --node-groups ceph --all
t start --node-groups ceph unknownvm
t shutdown-all --node-groups ceph gs1

# shutdown-all, start-all
t shutdown-all gs1 gs2
t shutdown-all --yes --force --wait 60 --except tst0,tst1 gs2
t shutdown-all bad
t start-all gs1 st0
t start-all --dry-run --all --retries 2 --except tst0 gs1

# autostart
t autostart add 0 1 5
t autostart remove --hypervisor gs1 --hypervisor gs2
t autostart add --all
t autostart remove --all 1
t autostart add --hypervisor gs1 1
t autostart add --node-groups ceph --exclude-nodes tst2
t autostart add --retries 1 0
t autostart add
t autostart add badvm 0
t autostart add 5 0 badvm 1
t autostart add nosuch 0

echo "$cases cases, $diffs differ"
[ "$diffs" -eq 0 ]
