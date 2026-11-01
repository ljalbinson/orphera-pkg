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
#   utils/vmctl.sh shutdown-all <hypervisor>...  # list the running VMs on those hypervisors (nothing is shut down)
#   utils/vmctl.sh shutdown-all --yes gs2 st0    # shut them all down, gracefully, and wait
#   utils/vmctl.sh shutdown-all --yes --force --except <vm> gs2   # power off any that do not stop; leave <vm> running
#   utils/vmctl.sh start-all gs2 st0             # start the VMs that shutdown-all shut down there (--dry-run to only list)
#   utils/vmctl.sh start-all --all gs2           # start every shut-off VM on gs2, recorded or not
#
# Options map to the playbooks' environment variables: --retries to ORPHERA_VM_RETRIES
# (start and reboot --hard), --wait to ORPHERA_VM_WAIT and --force to ORPHERA_VM_FORCE=1 (stop, reboot --hard),
# --hard / --soft to ORPHERA_VM_MODE (reboot). shutdown-all takes hypervisor names (gs1, gs2, ...), not VMs: it
# runs manifests/kvm_shutdown_all.scala, which lists the running VMs unless --yes is given, always leaves running the
# VM named like the machine you run it on, and takes --wait, --force and --except <vm>[,<vm>] (ORPHERA_VM_EXCLUDE).
# start-all runs manifests/kvm_start_all.scala: it starts exactly the VMs that shutdown-all recorded on each hypervisor
# (--all: every shut-off VM there), with --retries (default 3), --except and --dry-run. It does not wait for the guests.
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
  start|stop|reboot|shutdown-all|start-all) ;;
  *) echo "unknown action '$action' (start, stop, reboot, shutdown-all or start-all)" >&2; exit 2 ;;
esac

retries="" wait_s="" force="" mode="" yes="" except="" all="" dry=""
while [ $# -gt 0 ]; do
  case "$1" in
    --retries) retries=${2:-}; shift 2 || { echo "--retries needs a number" >&2; exit 2; } ;;
    --wait) wait_s=${2:-}; shift 2 || { echo "--wait needs a number of seconds" >&2; exit 2; } ;;
    --force) force=1; shift ;;
    --hard) mode=hard; shift ;;
    --soft) mode=soft; shift ;;
    --yes) yes=1; shift ;;
    --all) all=1; shift ;;
    --dry-run) dry=1; shift ;;
    --except) except=${2:-}; shift 2 || { echo "--except needs a VM name" >&2; exit 2; } ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
for v in "$retries" "$wait_s"; do
  case "$v" in *[!0-9]*) echo "--retries and --wait need a number" >&2; exit 2 ;; esac
done
[ -z "$retries" ] || [ "$action" != stop ] || { echo "--retries applies to start and reboot --hard only" >&2; exit 2; }
[ -z "$yes" ] || [ "$action" = shutdown-all ] || { echo "--yes applies to shutdown-all only" >&2; exit 2; }
[ -z "$except" ] || [ "$action" = shutdown-all ] || [ "$action" = start-all ] || { echo "--except applies to shutdown-all and start-all only" >&2; exit 2; }
[ -z "$all$dry" ] || [ "$action" = start-all ] || { echo "--all and --dry-run apply to start-all only" >&2; exit 2; }
[ -z "$wait_s$force" ] || [ "$action" != start ] || { echo "--wait and --force apply to stop and reboot only" >&2; exit 2; }
[ -z "$mode" ] || [ "$action" = reboot ] || { echo "--hard and --soft apply to reboot only" >&2; exit 2; }
[ $# -gt 0 ] || { usage; exit 2; }

if [ "$action" = shutdown-all ] || [ "$action" = start-all ]; then
  if [ "$action" = shutdown-all ]; then
    [ -z "$retries$mode" ] || { echo "--retries, --hard and --soft do not apply to shutdown-all" >&2; exit 2; }
    playbook="manifests/kvm_shutdown_all.scala"
  else
    [ -z "$wait_s$force$mode" ] || { echo "--wait, --force, --hard and --soft do not apply to start-all" >&2; exit 2; }
    playbook="manifests/kvm_start_all.scala"
  fi
  [ -f "$playbook" ] || { echo "$playbook not found" >&2; exit 1; }
  log="/tmp/vmctl.$action.log"
  env_args=("ORPHERA_HYPERVISORS=$*")
  [ -z "$yes" ] || env_args+=("ORPHERA_VM_CONFIRM=1")
  [ -z "$wait_s" ] || env_args+=("ORPHERA_VM_WAIT=$wait_s")
  [ -z "$force" ] || env_args+=("ORPHERA_VM_FORCE=1")
  [ -z "$except" ] || env_args+=("ORPHERA_VM_EXCLUDE=$except")
  [ -z "$retries" ] || env_args+=("ORPHERA_VM_RETRIES=$retries")
  [ -z "$all" ] || env_args+=("ORPHERA_VM_ALL=1")
  [ -z "$dry" ] || env_args+=("ORPHERA_VM_DRY_RUN=1")
  env "${env_args[@]}" orphera cluster-playbook "$playbook" >"$log" 2>&1
  rc=$?
  grep -E '^\[[A-Za-z0-9_-]+\] ' "$log" | grep -v ': Running: ' | grep -v '^\[kvm-[a-z-]*\] Starting' | sed 's/^/  /'
  if [ "$rc" -ne 0 ] || grep -qE "aborting remaining stages|Cluster playbook .* failed|FAILED" "$log"; then
    echo "$action FAILED (full output: $log)" >&2
    exit 1
  fi
  if [ "$action" = shutdown-all ] && [ -z "$yes" ]; then
    echo "shutdown-all: nothing was shut down; add --yes to do it"
  else
    echo "$action done"
  fi
  exit 0
fi

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
  grep -E '^\[[A-Za-z0-9_-]+\] ' "$log" | grep -v ': Running: ' | grep -v '^\[kvm-vm-[a-z]*\] Starting' | sed 's/^/  /'
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
