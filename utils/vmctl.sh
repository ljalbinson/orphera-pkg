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
#   utils/vmctl.sh start --node-groups k8s       # every VM in that inventory group (orphera nodes --groups lists them)
#   utils/vmctl.sh stop --node-groups k8s-workers,k8s-dns --exclude-nodes tst39   # groups, less some VMs
#   utils/vmctl.sh start --node-groups lustre-servers 49 50   # groups and VM names together, started in that order
#   utils/vmctl.sh shutdown-all <hypervisor>...  # list the running VMs on those hypervisors (nothing is shut down)
#   utils/vmctl.sh shutdown-all --yes gs2 st0    # shut them all down, gracefully, and wait
#   utils/vmctl.sh shutdown-all --yes --force --except <vm> gs2   # power off any that do not stop; leave <vm> running
#   utils/vmctl.sh start-all gs2 st0             # start the VMs that shutdown-all shut down there (--dry-run to only list)
#   utils/vmctl.sh start-all --all gs2           # start every shut-off VM on gs2, recorded or not
#   utils/vmctl.sh list                          # every VM on every hypervisor: state, vCPUs, memory, autostart
#   utils/vmctl.sh list gs2 st0                  # just those hypervisors
#   utils/vmctl.sh autostart add <vm>...         # libvirt starts these VMs when their hypervisor boots
#   utils/vmctl.sh autostart remove <vm>...      # ... no longer does
#   utils/vmctl.sh autostart add --hypervisor gs2 [--hypervisor st0]   # every VM on those hypervisors
#   utils/vmctl.sh autostart remove --all        # every VM on every hypervisor
#   utils/vmctl.sh autostart add --node-groups ceph,galera   # every VM in those groups
#
# Options map to the playbooks' environment variables: --retries to ORPHERA_VM_RETRIES
# (start and reboot --hard), --wait to ORPHERA_VM_WAIT and --force to ORPHERA_VM_FORCE=1 (stop, reboot --hard),
# --hard / --soft to ORPHERA_VM_MODE (reboot). shutdown-all takes hypervisor names (gs1, gs2, ...), not VMs: it
# runs manifests/kvm_shutdown_all.scala, which lists the running VMs unless --yes is given, always leaves running the
# VM named like the machine you run it on, and takes --wait, --force and --except <vm>[,<vm>] (ORPHERA_VM_EXCLUDE).
# start-all runs manifests/kvm_start_all.scala: it starts exactly the VMs that shutdown-all recorded on each hypervisor
# (--all: every shut-off VM there), with --retries (default 3), --except and --dry-run. It does not wait for the guests.
# autostart add|remove runs manifests/kvm_autostart.scala to turn libvirt's autostart on or off for the named VMs, for
# every VM on the --hypervisor(s) given, or for every VM on every hypervisor with --all (the running state is not changed;
# `list` shows the AUTOSTART column).
# --node-groups g1,g2 and --exclude-nodes a,b (start, stop, reboot, autostart) work as they do for the orphera commands:
# the groups come from inventory.yaml (`orphera nodes --groups`), and they and any VM names are handled in the order they
# were given on the command line (a VM named twice keeps its first place); --exclude-nodes takes VMs out of that, and
# `orphera nodes` checks the names and the exclusions as it does for every command. Options may come before or after
# the VM names. --exclude-nodes needs
# --node-groups or VM names as well: it never means every VM. Groups are for VMs; a member with no config/<vm>.yaml
# (a hypervisor, say) fails like any other unknown VM.
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
# list: utils/list-vms.sh, for the named hypervisors or, with none, for all of them
if [ "$action" = list ]; then
  exec utils/list-vms.sh "$@"
fi
sub=""
if [ "$action" = autostart ]; then
  sub=${1:-}
  case "$sub" in add|remove) shift ;; *) echo "autostart needs add or remove" >&2; exit 2 ;; esac
fi
case "$action" in
  start|stop|reboot|shutdown-all|start-all|autostart) ;;
  *) echo "unknown action '$action' (start, stop, reboot, shutdown-all, start-all, autostart or list)" >&2; exit 2 ;;
esac

retries="" wait_s="" force="" mode="" yes="" except="" all="" dry="" ngroups="" nexclude=""
hvs=()
pos=()     # the positional arguments (VM names, hypervisors), in order
order=()   # VM names and --node-groups values as given, "n:<vm>" or "g:<g1,g2>", to keep the command-line order
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
    --hypervisor) hvs+=("${2:-}"); shift 2 || { echo "--hypervisor needs a name" >&2; exit 2; } ;;
    --except) except=${2:-}; shift 2 || { echo "--except needs a VM name" >&2; exit 2; } ;;
    --node-groups)
      [ -n "${2:-}" ] || { echo "--node-groups needs a group name" >&2; exit 2; }
      ngroups="${ngroups:+$ngroups,}$2"; order+=("g:$2"); shift 2 ;;
    --exclude-nodes)
      [ -n "${2:-}" ] || { echo "--exclude-nodes needs a VM name" >&2; exit 2; }
      nexclude="${nexclude:+$nexclude,}$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) pos+=("$1"); order+=("n:$1"); shift ;;
  esac
done
set -- ${pos[@]+"${pos[@]}"}
for v in "$retries" "$wait_s"; do
  case "$v" in *[!0-9]*) echo "--retries and --wait need a number" >&2; exit 2 ;; esac
done
[ -z "$retries" ] || [ "$action" != stop ] || { echo "--retries applies to start and reboot --hard only" >&2; exit 2; }
[ -z "$yes" ] || [ "$action" = shutdown-all ] || { echo "--yes applies to shutdown-all only" >&2; exit 2; }
[ -z "$except" ] || [ "$action" = shutdown-all ] || [ "$action" = start-all ] || { echo "--except applies to shutdown-all and start-all only" >&2; exit 2; }
[ -z "$dry" ] || [ "$action" = start-all ] || { echo "--dry-run applies to start-all only" >&2; exit 2; }
[ -z "$all" ] || [ "$action" = start-all ] || [ "$action" = autostart ] || { echo "--all applies to start-all and autostart only" >&2; exit 2; }
[ "${#hvs[@]}" -eq 0 ] || [ "$action" = autostart ] || { echo "--hypervisor applies to autostart only" >&2; exit 2; }
[ -z "$wait_s$force" ] || [ "$action" != start ] || { echo "--wait and --force apply to stop and reboot only" >&2; exit 2; }
[ -z "$mode" ] || [ "$action" = reboot ] || { echo "--hard and --soft apply to reboot only" >&2; exit 2; }
if [ -n "$ngroups$nexclude" ]; then
  case "$action" in
    start|stop|reboot|autostart) ;;
    *) echo "--node-groups and --exclude-nodes apply to start, stop, reboot and autostart only" >&2; exit 2 ;;
  esac
  [ -z "$all" ] && [ "${#hvs[@]}" -eq 0 ] || { echo "--node-groups and --exclude-nodes do not go with --all or --hypervisor" >&2; exit 2; }
  [ -n "$ngroups" ] || [ $# -gt 0 ] || { echo "--exclude-nodes takes VMs out of a selection: give --node-groups or VM names as well" >&2; exit 2; }
  # Groups and VM names in the order they were given, each group as its members (from `orphera nodes --groups`),
  # a bare number N as tstN and a config file as its hostname:. Duplicates keep their first place.
  declare -A members=()
  if [ -n "$ngroups" ]; then
    listing=$(orphera nodes --groups) || exit 2
    while IFS= read -r line; do
      [ -n "$line" ] && members[${line%%:*}]=${line#*: }
    done <<<"$listing"
  fi
  wanted=()
  for item in "${order[@]}"; do
    case "$item" in
      g:*)
        IFS=, read -r -a gl <<<"${item#g:}"
        for g in "${gl[@]}"; do
          [ -n "$g" ] || continue
          if [ -z "${members[$g]+x}" ]; then
            echo "Error: Unknown node group(s): $g. Defined in inventory: $(printf '%s\n' "${!members[@]}" | sort | paste -sd, - | sed 's/,/, /g')" >&2
            exit 2
          fi
          # shellcheck disable=SC2206  # member names are plain (inventory schema)
          wanted+=(${members[$g]})
        done ;;
      n:*.yaml)
        f=${item#n:}
        [ -f "$f" ] || { echo "$f: no such config file" >&2; exit 2; }
        n=$(config_value "$f" hostname)
        [ -n "$n" ] || { echo "$f has no hostname:" >&2; exit 2; }
        wanted+=("$n") ;;
      n:*[!0-9]*) wanted+=("${item#n:}") ;;
      n:*) wanted+=("tst${item#n:}") ;;
    esac
  done
  [ "${#wanted[@]}" -gt 0 ] || { echo "the selection has no VMs in it" >&2; exit 2; }
  # orphera nodes checks every name is in the inventory, drops repeats (keeping the order) and applies --exclude-nodes.
  sel=(--nodes "$(IFS=,; printf '%s' "${wanted[*]}")")
  [ -z "$nexclude" ] || sel+=(--exclude-nodes "$nexclude")
  expanded=$(orphera nodes "${sel[@]}") || exit 2
  # shellcheck disable=SC2086  # node names are plain (inventory schema), one per line
  set -- $expanded
  [ $# -gt 0 ] || { echo "the selection has no VMs in it" >&2; exit 2; }
  echo "VMs: $*"
fi
[ $# -gt 0 ] || [ "$action" = autostart ] || { usage; exit 2; }

if [ "$action" = autostart ]; then
  [ -z "$retries$wait_s$force$mode$yes$except$dry" ] || { echo "autostart takes only VM names, --hypervisor and --all" >&2; exit 2; }
  playbook="manifests/kvm_autostart.scala"
  [ -f "$playbook" ] || { echo "$playbook not found" >&2; exit 1; }
  setting=on
  [ "$sub" != remove ] || setting=off
  run_autostart() {  # <hypervisors, space separated> <VM names, space separated, or empty for all>
    local log="/tmp/vmctl.autostart.$(printf '%s' "$1" | tr -c 'A-Za-z0-9\n' '_').log" rc
    ORPHERA_AUTOSTART=$setting ORPHERA_HYPERVISORS=$1 ORPHERA_VM_NAMES=$2 \
      orphera cluster-playbook "$playbook" >"$log" 2>&1
    rc=$?
    grep -E '^\[[A-Za-z0-9_-]+\] ' "$log" | grep -v ': Running: ' | grep -v '^\[kvm-autostart\] Starting' | sed 's/^/  /'
    if [ "$rc" -ne 0 ] || grep -qE "aborting remaining stages|Cluster playbook .* failed|FAILED" "$log"; then
      echo "autostart $sub FAILED on $1 (full output: $log)" >&2
      return 1
    fi
  }
  fail=0
  if [ -n "$all" ]; then
    [ $# -eq 0 ] && [ "${#hvs[@]}" -eq 0 ] || { echo "--all takes no VM names and no --hypervisor" >&2; exit 2; }
    run_autostart "${HYPERVISORS:-gs1 gs2 gs3 st0}" "" || fail=1
  elif [ "${#hvs[@]}" -gt 0 ]; then
    [ $# -eq 0 ] || { echo "give VM names or --hypervisor, not both" >&2; exit 2; }
    run_autostart "${hvs[*]}" "" || fail=1
  else
    [ $# -gt 0 ] || { usage; exit 2; }
    declare -A groups=()
    for arg in "$@"; do
      vm_lookup "$arg" || { fail=1; continue; }
      groups[$hv]+=" $vm"
    done
    for h in "${!groups[@]}"; do
      run_autostart "$h" "${groups[$h]}" || fail=1
    done
  fi
  [ "$fail" -eq 0 ] || exit 1
  echo "autostart $sub done"
  exit 0
fi

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
