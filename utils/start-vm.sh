#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Starts one or more VMs on their hypervisors. The hypervisor and the libvirt
# domain name come from each VM's config file (the same config/<vm>.yaml the
# provisioning playbooks read: `hypervisor:` and `hostname:`).
#
#   utils/start-vm.sh tst7                      # config/tst7.yaml
#   utils/start-vm.sh 7                         # a bare number means tst7
#   utils/start-vm.sh tst3 tst4 tst5            # several, one after another
#   utils/start-vm.sh config/tst7.yaml          # an explicit config file
#   utils/start-vm.sh --node-groups ceph        # every VM in an inventory group
#   utils/start-vm.sh --node-groups k8s --exclude-nodes tst39 50   # groups and VMs, less some
#
# A VM that is shut off is started, a paused one is resumed, and one that is
# already running is left alone. The script reports that the hypervisor has
# started the VM; it does not wait for the guest to answer. To wait for SSH, or
# to reset a guest that is "running" but halted, use
#   orphera cluster-playbook manifests/kvm_vm_start.scala --config config/<vm>.yaml
# See also stop-vm.sh and reboot-vm.sh.
#
# Run from scala0, in orphera-pkg (config/ is a relative path; needs `orphera`
# and an agent on the hypervisor). Exit code 1 if any VM could not be started.
#
# --node-groups G1,G2 takes every VM in those inventory groups and --exclude-nodes
# V1,V2 leaves those VMs out. The groups and VM names are taken in the order
# given, each group as its members (`orphera nodes --groups` lists them),
# checked against the inventory and without repeats (`orphera nodes`), less
# the VMs excluded; the VMs are printed first. --exclude-nodes needs
# --node-groups or VM names as well. Options may come anywhere among the names.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }
[ $# -gt 0 ] || { usage; exit 2; }
pos=()
while [ $# -gt 0 ]; do
  case "$1" in
    --node-groups|--exclude-nodes) vm_select_option "$1" "${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) pos+=("$1"); vm_select_name "$1"; shift ;;
  esac
done
if vm_select_wanted; then
  vm_select
  set -- "${SELECTED[@]}"
else
  set -- "${pos[@]+"${pos[@]}"}"
fi
[ $# -gt 0 ] || { usage; exit 2; }

# Runs on the hypervisor, as the agent (root): start, resume or leave alone.
remote_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
case "\$state" in
  "")           echo "RESULT|error|domain '$1' is not defined on this hypervisor" ;;
  running)      echo "RESULT|ok|already running" ;;
  paused)       \$V resume '$1' >/dev/null 2>&1 && echo "RESULT|ok|resumed" || echo "RESULT|error|virsh resume failed" ;;
  "shut off")   out=\$(\$V start '$1' 2>&1) && echo "RESULT|ok|started" || echo "RESULT|error|\$out" ;;
  *)            echo "RESULT|error|domain is in state '\$state'; not touching it" ;;
esac
SH
}

failed=""
for arg in "$@"; do
  vm_lookup "$arg" || { failed="$failed $arg"; continue; }
  vm_run "$hv" "$(remote_script "$vm")"
  vm_report "$vm" "$hv" || failed="$failed $vm"
done

if [ -n "$failed" ]; then
  echo "Not started:$failed" >&2
  exit 1
fi
