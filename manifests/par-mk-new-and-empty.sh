#!/bin/bash
#
# Create new, empty virtual machines, then bring each one up to a usable base:
#   1. provision the VM on its hypervisor     (kvm_vm_provision.scala)
#   2. install the orphera agent over SSH     (orphera bootstrap)
#   3. set DNS                                (gen_set_dns.scala)
#   4. dist-upgrade                           (orphera dist-upgrade)
#   5. install the config's package lists     (gen_packages.scala)
#
# Usage: manifests/par-mk-new-and-empty.sh [-f agent.deb] [-j jobs] [-q] [-L] [node ...]
#   node   a name (tst3) or a bare number (3); default is tst0..tst7
#   -f     agent package to install; default is the newest
#          orphera-agent_*_amd64.deb in the current directory
#   -j     nodes built at the same time (default 4; -j 1 is strictly one after
#          another, with plain unprefixed output)
#   -q     quiet: the terminal shows only step banners and the summary; the
#          full output of every node is in its log file
#   -L     no per-hypervisor lock: provision VMs on the same hypervisor at the
#          same time. The lock is a precaution against load (two image copies,
#          zfs creates and boots on one host), not a known conflict; use -L to
#          find out whether it is needed. The known_hosts lock stays.
#
# Run from the repository root (config/ and manifests/ are relative paths).
#
# Each node is an independent pipeline. A failed step stops that node only; the
# others carry on, and the script lists the failures at the end and exits
# non-zero. Full per-node output goes to .orphera-build-logs/<time>/<node>.log.
#
# Two steps are serialized on purpose, everything else runs in parallel:
#   - provisioning (unless -L): one VM at a time per hypervisor (config
#     `hypervisor:`), so tst0 and tst1 on gs1 queue while tst2 on gs2 proceeds
#   - bootstrap: one at a time, because it edits ~/.ssh/known_hosts
# Do not combine with `--resume`: checkpoint state is per playbook name, so
# parallel runs of the same playbook would overwrite each other's.
# Ctrl-C stops every running node (a VM caught mid-provision is left half-built).

set -uo pipefail

jobs_max=4
deb=""
quiet=0
hv_lock=1
while getopts "f:j:qLh" opt; do
    case $opt in
        f) deb=$OPTARG ;;
        j) jobs_max=$OPTARG ;;
        q) quiet=1 ;;
        L) hv_lock=0 ;;
        *) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
    esac
done
shift $((OPTIND - 1))

case $jobs_max in
    ''|*[!0-9]*|0) echo "-j needs a positive number" >&2; exit 2 ;;
esac

if [ $# -gt 0 ]; then
    nodes=()
    for n in "$@"; do
        case $n in
            tst*) nodes+=("$n") ;;
            *) nodes+=("tst$n") ;;
        esac
    done
else
    nodes=(tst{0..7})
fi
[ "$jobs_max" -gt "${#nodes[@]}" ] && jobs_max=${#nodes[@]}

# Banner colour: green, only when stdout is a terminal that can show it
# (honours the NO_COLOR convention and TERM=dumb). Decided once here, because
# inside a job stdout is a pipe.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
    green=$'\033[1;32m' reset=$'\033[0m'
else
    green="" reset=""
fi
banner() { printf '%s%s%s\n' "$green" "$*" "$reset"; }

command -v orphera >/dev/null || { echo "orphera not found in PATH" >&2; exit 1; }
if [ "$jobs_max" -gt 1 ]; then
    command -v flock >/dev/null || { echo "flock (util-linux) is needed for -j > 1" >&2; exit 1; }
    [ "${BASH_VERSINFO[0]}${BASH_VERSINFO[1]}" -ge 43 ] || { echo "bash >= 4.3 is needed for -j > 1" >&2; exit 1; }
fi

if [ -z "$deb" ]; then
    # newest build wins; a bare glob would pass the literal pattern (or several
    # files) to --file when there is no match (or more than one).
    # shellcheck disable=SC2012
    deb=$(ls -t orphera-agent_*_amd64.deb 2>/dev/null | head -n 1)
fi
[ -f "$deb" ] || { echo "no agent .deb found (use -f <file>)" >&2; exit 1; }
banner "Agent package: $deb"

# All config files must exist before anything is created, so a typo in the
# node list fails now rather than after seven VMs have been built.
for node in "${nodes[@]}"; do
    [ -f "config/$node.yaml" ] || { echo "missing config/$node.yaml" >&2; exit 1; }
done

logdir=".orphera-build-logs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$logdir/rc"
lockdir=$(mktemp -d)
trap 'rm -rf "$lockdir"' EXIT
banner "Building ${#nodes[@]} node(s), $jobs_max at a time — logs in $logdir"

# with_lock <name> <command...>: run the command holding an exclusive lock.
with_lock() {
    local name=$1; shift
    if [ "$jobs_max" -le 1 ]; then "$@"; return; fi
    (
        if ! flock -n 9; then
            echo "waiting for lock '$name' (another node is using it)"
            flock 9
        fi
        "$@"
    ) 9>"$lockdir/$name"
}

hypervisor_of() {
    local hv
    hv=$(awk -F: '/^[[:space:]]*hypervisor:/ {gsub(/[[:space:]"'\'']/, "", $2); print $2; exit}' "config/$1.yaml")
    echo "${hv:-unknown-$1}"
}

build_node() {
    local node=$1 cfg="config/$1.yaml" hv
    hv=$(hypervisor_of "$node")
    step() { echo; banner "=== $(date '+%F %T') $node: $* ==="; }

    step "provision VM (hypervisor $hv)"
    if [ "$hv_lock" -eq 1 ]; then
        with_lock "hv-$hv" orphera cluster-playbook manifests/kvm_vm_provision.scala --config "$cfg" || return 1
    else
        orphera cluster-playbook manifests/kvm_vm_provision.scala --config "$cfg" || return 1
    fi

    # --forget-host-key: a rebuilt VM has a new SSH host key under the same name.
    step "install agent"
    with_lock known_hosts orphera bootstrap --file "$deb" --ssh-user localadmin \
        --nodes "$node" --forget-host-key || return 1

    step "set DNS"
    orphera cluster-playbook manifests/gen_set_dns.scala --config "$cfg" || return 1

    step "dist-upgrade"
    orphera dist-upgrade --nodes "$node" || return 1

    step "install packages"
    orphera cluster-playbook manifests/gen_packages.scala --config "$cfg" || return 1
}

# One node, run as a background pipeline: its output is prefixed (when several
# nodes share the terminal), saved whole to the node's log, and shown on the
# terminal in full or, with -q, banners only. The outcome goes to rc/<node>.
pfx() { if [ "$jobs_max" -gt 1 ]; then sed -u "s/^/[$1] /"; else cat; fi; }
show() {
    if [ "$quiet" -eq 1 ]; then grep --line-buffered -E '(===|!!!) |waiting for lock'
    else cat; fi
}
launch() {
    local node=$1
    {
        start=$SECONDS
        build_node "$node"
        echo "$? $((SECONDS - start))" > "$logdir/rc/$node"
    } </dev/null 2>&1 | pfx "$node" | tee "$logdir/$node.log" | show &
}

stop_all() {
    trap - INT TERM
    echo; echo "interrupted — stopping all nodes" >&2
    for p in $(jobs -p); do kill -TERM -- "-$p" 2>/dev/null; done
    wait
    exit 130
}
set -m
trap stop_all INT TERM

running=0
for node in "${nodes[@]}"; do
    if [ "$running" -ge "$jobs_max" ]; then wait -n; running=$((running - 1)); fi
    launch "$node"
    running=$((running + 1))
    # Run logs are named by playbook + second; starts a second apart keep two
    # nodes' logs of the same playbook from landing in one file.
    [ "$jobs_max" -gt 1 ] && sleep 1.1
done
wait

# Summary
echo
banner "=== Summary ==="
failed=()
for node in "${nodes[@]}"; do
    read -r rc secs < "$logdir/rc/$node" 2>/dev/null || { rc=?; secs=0; }
    t=$(printf '%dm%02ds' $((secs / 60)) $((secs % 60)))
    if [ "$rc" = 0 ]; then
        printf '  %-8s %sOK%s      %s\n' "$node" "$green" "$reset" "$t"
    else
        printf '  %-8s FAILED  %s   (%s)\n' "$node" "$t" "$logdir/$node.log"
        failed+=("$node")
    fi
done

if [ ${#failed[@]} -gt 0 ]; then
    for node in "${failed[@]}"; do
        echo; echo "--- last lines of $node ---" >&2
        tail -n 8 "$logdir/$node.log" >&2
    done
    echo; echo "Failed: ${failed[*]}" >&2
    exit 1
fi
banner "All done: ${nodes[*]}"
