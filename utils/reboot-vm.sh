#!/usr/bin/env bash
#
# Reboots one or more VMs from the hypervisor, without going through the
# guest's own agent: for a VM whose Orphera agent or SSH is not answering. The
# hypervisor and domain name come from each VM's config/<vm>.yaml.
#
#   utils/reboot-vm.sh tst7                  # same as --soft
#   utils/reboot-vm.sh --soft tst7 tst8
#   utils/reboot-vm.sh --hard tst7
#   utils/reboot-vm.sh --hard --force --wait 60 tst7
#
#   --soft         virsh reboot: asks the guest to reboot (an ACPI request), so
#                  the guest shuts down cleanly and comes straight back; a guest
#                  that is not running its OS ignores it. The default.
#   --hard         shuts the VM down (virsh shutdown), waits until it is off, then
#                  starts it again: a full power cycle, as stop-vm.sh followed by
#                  start-vm.sh
#   --wait SECONDS with --hard, how long to wait for the shutdown (default 120)
#   --force        with --hard, power the VM off at once (virsh destroy) if it has
#                  not shut down in that time; without it the VM is reported as
#                  failed and left running
#
# Only a running VM is rebooted with --soft; a VM that is shut off is reported
# (use start-vm.sh), while --hard starts it. The script does not wait for the guest
# to come back: `orphera uptime --nodes <vm>` shows when it has.
#
# Run from scala0, in orphera-pkg. Exit code 1 if any VM could not be rebooted.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

mode=soft
wait_s=120
force=0
while [ $# -gt 0 ]; do
  case "$1" in
    --soft) mode=soft; shift ;;
    --hard) mode=hard; shift ;;
    --force) force=1; shift ;;
    --wait) wait_s=${2:-}; shift 2 || { echo "--wait needs a number of seconds" >&2; exit 2; } ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
case "$wait_s" in ''|*[!0-9]*) echo "--wait needs a number of seconds" >&2; exit 2 ;; esac
[ $# -gt 0 ] || { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

soft_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
case "\$state" in
  "")        echo "RESULT|error|domain '$1' is not defined on this hypervisor" ;;
  running)   out=\$(\$V reboot '$1' 2>&1) && echo "RESULT|ok|reboot sent" || echo "RESULT|error|\$out" ;;
  *)         echo "RESULT|error|domain is \$state, not running" ;;
esac
SH
}

hard_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
[ -n "\$state" ] || { echo "RESULT|error|domain '$1' is not defined on this hypervisor"; exit 0; }
how="shut down"
if [ "\$state" != "shut off" ]; then
  out=\$(\$V shutdown '$1' 2>&1) || { echo "RESULT|error|\$out"; exit 0; }
  i=0
  while [ \$i -lt $wait_s ]; do
    [ "\$(\$V domstate '$1' 2>/dev/null | head -1)" = "shut off" ] && break
    sleep 2; i=\$((i + 2))
  done
  if [ "\$(\$V domstate '$1' 2>/dev/null | head -1)" != "shut off" ]; then
    if [ $force -eq 1 ]; then
      out=\$(\$V destroy '$1' 2>&1) || { echo "RESULT|error|\$out"; exit 0; }
      how="powered off after ${wait_s}s"
    else
      echo "RESULT|error|still running after ${wait_s}s (use --force to power it off); not restarted"; exit 0
    fi
  fi
else
  how="was already shut off"
fi
out=\$(\$V start '$1' 2>&1) && echo "RESULT|ok|\$how, started" || echo "RESULT|error|\$how, but start failed: \$out"
SH
}

failed=""
for arg in "$@"; do
  vm_lookup "$arg" || { failed="$failed $arg"; continue; }
  if [ "$mode" = hard ]; then
    vm_run "$hv" "$(hard_script "$vm")" $((wait_s + 60))
  else
    vm_run "$hv" "$(soft_script "$vm")"
  fi
  vm_report "$vm" "$hv" || failed="$failed $vm"
done

if [ -n "$failed" ]; then
  echo "Not rebooted:$failed" >&2
  exit 1
fi
