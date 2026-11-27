#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Starts one or more VMs on their hypervisors. The hypervisor and the libvirt
# domain name come from each VM's config file (the same config/<vm>.yaml the
# provisioning playbooks read: `hypervisor:` and `hostname:`).
#
#   utils/start-vm.sh --nodes tst7              # config/tst7.yaml
#   utils/start-vm.sh --nodes 7                 # a bare number means tst7
#   utils/start-vm.sh --nodes tst3,tst4,tst5    # several, one after another
#   utils/start-vm.sh --nodes config/tst7.yaml  # an explicit config file
#   utils/start-vm.sh --node-groups ceph        # every VM in an inventory group
#   utils/start-vm.sh --node-groups k8s --exclude-nodes tst39 --nodes 50   # groups and VMs, less some
#   utils/start-vm.sh --parallel 8 --node-groups k8s   # up to 8 at a time
#   utils/start-vm.sh --retries 5 --nodes tst7  # try a failed start up to 5 more times (default 3, 0 = never)
#
# A VM that is shut off is started, a paused one is resumed, and one that is
# already running is left alone. A failed start or resume is tried again after
# 5 s, up to --retries more times (default 3); an undefined domain is not. Before
# each start, Open vSwitch ports named vnetN whose device no longer exists are
# removed from the hypervisor's bridges (libvirt leaves them behind when a VM
# stops uncleanly, and a later start fails with "vnetN is actually attached to
# bridge ..."); the report names any it removed. The script reports that the hypervisor has
# started the VM; it does not wait for the guest to answer. To wait for SSH, or
# to reset a guest that is "running" but halted, use
#   orphera cluster-playbook manifests/kvm_vm_start.scala --config config/<vm>.yaml
# See also stop-vm.sh and reboot-vm.sh.
#
# Run from scala0, in orphera-pkg (config/ is a relative path; needs `orphera`
# and an agent on the hypervisor). Exit code 1 if any VM could not be started.
#
# --nodes V1,V2 names VMs (a name, a bare number or a config file each). --node-groups G1,G2 takes every VM in
# those inventory groups and --exclude-nodes
# V1,V2 leaves those VMs out. The groups and VM names are taken in the order
# given, each group as its members (`orphera nodes --groups` lists them),
# checked against the inventory and without repeats (`orphera nodes`), less
# the VMs excluded; the VMs are printed first. --exclude-nodes needs
# --node-groups or --nodes as well. VM names are only taken after --nodes (a comma-separated list; a bare
# argument is refused). Options may come in any order.
# --parallel N starts up to N VMs at a time (default 1, one after another);
# the results are still printed in command-line order.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }
[ $# -gt 0 ] || { usage; exit 2; }
pos=()
par=1
retries=3
while [ $# -gt 0 ]; do
  case "$1" in
    --parallel) vm_parallel_option "${2:-}"; par=$2; shift 2 ;;
    --retries)
      case "${2:-}" in ''|*[!0-9]*) echo "--retries needs a number (0 or more)" >&2; exit 2 ;; esac
      retries=$((10#$2)); shift 2 ;;
    --node-groups|--exclude-nodes) vm_select_option "$1" "${2:-}"; shift 2 ;;
    --nodes) nodes_option "${2:-}"; for n in "${NODE_ARGS[@]}"; do pos+=("$n"); vm_select_name "$n"; done; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) unexpected_arg "$1" ;;
  esac
done
if vm_select_wanted; then
  vm_select
  set -- "${SELECTED[@]}"
else
  set -- "${pos[@]+"${pos[@]}"}"
fi
[ $# -gt 0 ] || { usage; exit 2; }

# Runs on the hypervisor, as the agent (root): start, resume or leave alone, with
# retries and the Open vSwitch clean-up. The same text as utils-native/start-vm's
# Scripts.start (make check-vm-scripts compares what the two send).
remote_script() {
  sed -e "s/@VM@/$1/g" -e "s/@RETRIES@/$retries/g" <<'SH'
V="virsh --connect qemu:///system"
pruned=""
# Open vSwitch ports named vnetN whose device is gone: libvirt leaves them when a VM stops uncleanly,
# reuses the name, and the start then fails with "vnetN is actually attached to bridge ...".
prune_ovs() {
  command -v ovs-vsctl >/dev/null 2>&1 || return 0
  for b in $(ovs-vsctl list-br 2>/dev/null); do
    for p in $(ovs-vsctl list-ports "$b" 2>/dev/null); do
      case "$p" in vnet[0-9]*) ;; *) continue ;; esac
      [ -e "/sys/class/net/$p" ] && continue
      ovs-vsctl --if-exists del-port "$b" "$p" || continue
      case " $pruned " in *" $p "*) ;; *) pruned="$pruned $p" ;; esac
    done
  done
}
# 0: done, 1: failed (worth another try), 2: failed (another try cannot help).
attempt() {
  state=$($V domstate '@VM@' 2>/dev/null | head -1)
  case "$state" in
    "")         msg="domain '@VM@' is not defined on this hypervisor"; return 2 ;;
    running)    msg="already running"; return 0 ;;
    paused)     $V resume '@VM@' >/dev/null 2>&1 && { msg="resumed"; return 0; }; msg="virsh resume failed"; return 1 ;;
    "shut off") prune_ovs; out=$($V start '@VM@' 2>&1) && { msg="started"; return 0; }; msg=$(printf '%s' "$out" | tr '\n' ' '); return 1 ;;
    *)          msg="domain is in state '$state'; not touching it"; return 2 ;;
  esac
}
n=0
while :; do
  attempt; rc=$?
  [ "$rc" -eq 1 ] && [ "$n" -lt @RETRIES@ ] || break
  n=$((n + 1))
  sleep 5
done
[ "$n" -eq 0 ] || msg="$msg (attempt $((n + 1)) of $((@RETRIES@ + 1)))"
[ -z "$pruned" ] || msg="$msg; removed stale Open vSwitch port(s)$pruned"
if [ "$rc" -eq 0 ]; then echo "RESULT|ok|$msg"; else echo "RESULT|error|$msg"; fi
SH
}

# Each attempt may take up to a minute, with 5 s between them.
vm_run_all "$par" "$((60 * (retries + 1) + 5 * retries))" remote_script "$@"

if [ -n "$failed" ]; then
  echo "Not started:$failed" >&2
  exit 1
fi
