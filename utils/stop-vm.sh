#!/usr/bin/env bash
#
# Stops one or more VMs by asking the guest to shut down (virsh shutdown, an
# ACPI power-button press), then waits until libvirt reports it shut off. The
# hypervisor and domain name come from each VM's config/<vm>.yaml.
#
#   utils/stop-vm.sh tst7                  # waits up to 120 s
#   utils/stop-vm.sh --wait 300 tst7 tst8
#   utils/stop-vm.sh --force tst7          # power it off if it has not shut down in time
#
#   --wait SECONDS   how long to wait for each VM (default 120)
#   --force          after that time, power the VM off at once (virsh destroy),
#                    like pulling the plug; without it a VM that has not shut
#                    down is reported as failed and left running
#
# A VM that is already shut off is left alone. Run from scala0, in orphera-pkg.
# Exit code 1 if any VM is not shut off at the end. To start it again:
# utils/start-vm.sh.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

wait_s=120
force=0
while [ $# -gt 0 ]; do
  case "$1" in
    --wait) wait_s=${2:-}; shift 2 || { echo "--wait needs a number of seconds" >&2; exit 2; } ;;
    --force) force=1; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
case "$wait_s" in ''|*[!0-9]*) echo "--wait needs a number of seconds" >&2; exit 2 ;; esac
[ $# -gt 0 ] || { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

remote_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
[ -n "\$state" ] || { echo "RESULT|error|domain '$1' is not defined on this hypervisor"; exit 0; }
[ "\$state" != "shut off" ] || { echo "RESULT|ok|already shut off"; exit 0; }
out=\$(\$V shutdown '$1' 2>&1) || { echo "RESULT|error|\$out"; exit 0; }
i=0
while [ \$i -lt $wait_s ]; do
  [ "\$(\$V domstate '$1' 2>/dev/null | head -1)" = "shut off" ] && { echo "RESULT|ok|shut down after \${i}s"; exit 0; }
  sleep 2; i=\$((i + 2))
done
if [ $force -eq 1 ]; then
  out=\$(\$V destroy '$1' 2>&1) && echo "RESULT|ok|did not shut down in ${wait_s}s; powered off" || echo "RESULT|error|\$out"
else
  echo "RESULT|error|still running after ${wait_s}s (use --force to power it off)"
fi
SH
}

failed=""
for arg in "$@"; do
  vm_lookup "$arg" || { failed="$failed $arg"; continue; }
  vm_run "$hv" "$(remote_script "$vm")" $((wait_s + 30))
  vm_report "$vm" "$hv" || failed="$failed $vm"
done

if [ -n "$failed" ]; then
  echo "Not stopped:$failed" >&2
  exit 1
fi
