#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Short front end to the VM playbooks (manifests/kvm_vm_start.scala, kvm_vm_stop.scala,
# kvm_vm_reboot.scala): no playbook path, no --config, and none of the echoed
# script text in the output.
#
#   utils/vmctl.sh start <vm>                    # config/<vm>.yaml; a bare number N means tstN
#   utils/vmctl.sh start <vm1> <vm2> <vm3>       # several, one after another
#   utils/vmctl.sh start --retries 5 <vm>        # retry a failed start up to 5 times (default 3, 0 = never)
#   utils/vmctl.sh stop --wait 300 <vm>          # wait up to 300 s for the shutdown (default 120)
#   utils/vmctl.sh stop --force <vm>             # power it off if it has not shut down in time
#   utils/vmctl.sh reboot <vm>                   # soft: ask the guest to reboot
#   utils/vmctl.sh reboot --hard --force <vm>    # full power cycle
#   utils/vmctl.sh start config/<vm>.yaml        # an explicit config file
#
# Options map to the playbooks' environment variables: --retries to ORPHERA_VM_RETRIES
# (start and reboot --hard), --wait to ORPHERA_VM_WAIT and --force to ORPHERA_VM_FORCE=1 (stop, reboot --hard),
# --hard / --soft to ORPHERA_VM_MODE (reboot).
#
# Unlike start-vm.sh, stop-vm.sh and reboot-vm.sh (which only ask the hypervisor), these run
# the playbooks, so `start` also waits until the guest answers SSH. Prints one line per step;
# when a VM fails, the whole playbook output is kept in /tmp/vmctl.<vm>.log and named.
# Run from scala0 (any directory). Exit code 1 if any VM failed.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
. utils/vm-common.sh

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

[ $# -gt 0 ] || { usage; exit 2; }
case "$1" in -h|--help) usage; exit 0 ;; esac
action=$1
shift
case "$action" in
  start|stop|reboot) ;;
  *) echo "unknown action '$action' (start, stop or reboot)" >&2; exit 2 ;;
esac

retries="" wait_s="" force="" mode=""
while [ $# -gt 0 ]; do
  case "$1" in
    --retries) retries=${2:-}; shift 2 || { echo "--retries needs a number" >&2; exit 2; } ;;
    --wait) wait_s=${2:-}; shift 2 || { echo "--wait needs a number of seconds" >&2; exit 2; } ;;
    --force) force=1; shift ;;
    --hard) mode=hard; shift ;;
    --soft) mode=soft; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
for v in "$retries" "$wait_s"; do
  case "$v" in *[!0-9]*) echo "--retries and --wait need a number" >&2; exit 2 ;; esac
done
[ -z "$retries" ] || [ "$action" != stop ] || { echo "--retries applies to start and reboot --hard only" >&2; exit 2; }
[ -z "$wait_s$force" ] || [ "$action" != start ] || { echo "--wait and --force apply to stop and reboot only" >&2; exit 2; }
[ -z "$mode" ] || [ "$action" = reboot ] || { echo "--hard and --soft apply to reboot only" >&2; exit 2; }
[ $# -gt 0 ] || { usage; exit 2; }

playbook="manifests/kvm_vm_$action.scala"
[ -f "$playbook" ] || { echo "$playbook not found" >&2; exit 1; }

failed=""
for arg in "$@"; do
  vm_lookup "$arg" || { failed="$failed $arg"; continue; }
  log="/tmp/vmctl.$vm.log"
  env_args=()
  [ -z "$retries" ] || env_args+=("ORPHERA_VM_RETRIES=$retries")
  [ -z "$wait_s" ] || env_args+=("ORPHERA_VM_WAIT=$wait_s")
  [ -z "$force" ] || env_args+=("ORPHERA_VM_FORCE=1")
  [ -z "$mode" ] || env_args+=("ORPHERA_VM_MODE=$mode")
  env "${env_args[@]}" orphera cluster-playbook "$playbook" --config "$cfg" >"$log" 2>&1
  rc=$?
  # One line per step: drop the echoed script text and the "Running: ..." lines.
  grep '^\[' "$log" | grep -v ': Running: ' | grep -v '^\[kvm-vm-[a-z]*\] Starting' | sed 's/^/  /'
  if [ "$rc" -ne 0 ] || grep -qE "aborting remaining stages|Cluster playbook .* failed|FAILED" "$log"; then
    echo "$vm: $action FAILED (full output: $log)" >&2
    failed="$failed $vm"
  else
    echo "$vm: $action done"
  fi
done

if [ -n "$failed" ]; then
  echo "Failed:$failed" >&2
  exit 1
fi
