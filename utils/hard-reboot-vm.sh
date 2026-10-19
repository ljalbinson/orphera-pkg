#!/usr/bin/env bash
#
# Reboots one or more VMs from the hypervisor (virsh reboot), without going
# through the guest's own agent: for a VM whose Orphera agent or SSH is not
# answering. The hypervisor and domain name come from each VM's config/<vm>.yaml.
#
#   utils/hard-reboot-vm.sh tst7
#   utils/hard-reboot-vm.sh --reset tst7 tst8
#
#   --reset   virsh reset instead: the VM is restarted at once without asking the
#             guest, like pressing the reset button, for a guest that ignores
#             virsh reboot (one that has hung or halted)
#
# virsh reboot sends the guest an ACPI request, so a guest that is not running
# its OS ignores it. Only a running VM is rebooted; one that is shut off is
# reported (use start-vm.sh). The script does not wait for the guest to come
# back; `orphera uptime --nodes <vm>` shows when it has.
#
# Run from scala0, in orphera-pkg. Exit code 1 if any VM could not be rebooted.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

verb=reboot
while [ $# -gt 0 ]; do
  case "$1" in
    --reset) verb=reset; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

remote_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
case "\$state" in
  "")        echo "RESULT|error|domain '$1' is not defined on this hypervisor" ;;
  running)   out=\$(\$V $verb '$1' 2>&1) && echo "RESULT|ok|$verb sent" || echo "RESULT|error|\$out" ;;
  *)         echo "RESULT|error|domain is \$state, not running" ;;
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
  echo "Not rebooted:$failed" >&2
  exit 1
fi
