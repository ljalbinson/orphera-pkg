#!/bin/bash
#
# Create new, empty virtual machines, then bring each one up to a usable base:
#   1. provision the VM on its hypervisor     (kvm_vm_provision.scala)
#   2. install the orphera agent over SSH     (orphera bootstrap)
#   3. set DNS                                (gen_set_dns.scala)
#   4. dist-upgrade                           (orphera dist-upgrade)
#   5. install the config's package lists     (gen_packages.scala)
#
# Usage: manifests/mk-new-and-empty.sh [-f agent.deb] [node ...]
#   node   a name (tst3) or a bare number (3); default is tst0..tst7
#   -f     agent package to install; default is the newest
#          orphera-agent_*_amd64.deb in the current directory
#
# Run from the repository root (config/ and manifests/ are relative paths).
# A failed step stops that node and moves on to the next; the script exits
# non-zero if any node failed, and lists which ones.

set -uo pipefail

deb=""
while getopts "f:h" opt; do
    case $opt in
        f) deb=$OPTARG ;;
        *) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
    esac
done
shift $((OPTIND - 1))

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

# Banner colour: green, only when stdout is a terminal that can show it
# (honours the NO_COLOR convention and TERM=dumb).
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
    green=$'\033[1;32m' reset=$'\033[0m'
else
    green="" reset=""
fi
banner() { printf '%s%s%s\n' "$green" "$*" "$reset"; }

command -v orphera >/dev/null || { echo "orphera not found in PATH" >&2; exit 1; }

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

build_node() {
    local node=$1 cfg="config/$1.yaml"
    step() { echo; banner "=== $(date '+%F %T') $node: $* ==="; }

    step "provision VM"
    orphera cluster-playbook manifests/kvm_vm_provision.scala --config "$cfg" || return 1

    # --forget-host-key: a rebuilt VM has a new SSH host key under the same name.
    step "install agent"
    orphera bootstrap --file "$deb" --ssh-user localadmin --nodes "$node" \
        --forget-host-key || return 1

    step "set DNS"
    orphera cluster-playbook manifests/gen_set_dns.scala --config "$cfg" || return 1

    step "dist-upgrade"
    orphera dist-upgrade --nodes "$node" || return 1

    step "install packages"
    orphera cluster-playbook manifests/gen_packages.scala --config "$cfg" || return 1
}

failed=()
for node in "${nodes[@]}"; do
    if ! build_node "$node"; then
        echo "!!! $node FAILED — continuing with the next node" >&2
        failed+=("$node")
    fi
done

echo
if [ ${#failed[@]} -gt 0 ]; then
    echo "Failed: ${failed[*]}" >&2
    exit 1
fi
banner "All done: ${nodes[*]}"
