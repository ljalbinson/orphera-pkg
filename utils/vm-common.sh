# SPDX-License-Identifier: Apache-2.0
#
# Shared by start-vm.sh, stop-vm.sh and reboot-vm.sh; sourced, not run.
# Finds a VM's hypervisor and libvirt domain name in its config file (found by
# orphera-sources.sh: $ORPHERA_CONFIG_DIR, default config) and runs a
# script on the hypervisor through `orphera run`.

. "$(dirname "${BASH_SOURCE[0]}")/orphera-sources.sh"

# vm_lookup <name | number | config.yaml>  sets cfg, hv and vm. Returns 1, after
# saying why on stderr, if they cannot be found.
vm_lookup() {
  local dir
  case "$1" in
    *.yaml) cfg=$1 ;;
    *)
      dir=$(config_dir) || return 1
      case "$1" in
        *[!0-9]*) cfg="$dir/$1.yaml" ;;
        *) cfg="$dir/tst$1.yaml" ;;
      esac ;;
  esac
  if [ ! -f "$cfg" ]; then
    echo "$1: no config file $cfg" >&2
    return 1
  fi
  hv=$(config_value "$cfg" hypervisor)
  vm=$(config_value "$cfg" hostname)
  if [ -z "$hv" ] || [ -z "$vm" ]; then
    echo "$1: $cfg has no hypervisor: or hostname:" >&2
    return 1
  fi
  # Both end up inside a remote shell command, so only plain names are accepted.
  case "$hv$vm" in
    *[!A-Za-z0-9._-]*)
      echo "$1: unusual characters in hypervisor or hostname in $cfg; not running" >&2
      return 1 ;;
  esac
}

# vm_run <hypervisor> <script> [timeout-seconds]  runs the script there, as the
# agent (root). The script must end by printing RESULT|ok|<text> or
# RESULT|error|<text>; sets result to what follows "RESULT|" (empty if the
# hypervisor never answered) and out to everything that came back.
vm_run() {
  out=$(orphera run --nodes "$1" --quiet --timeout "${3:-60}" -- sh -c "$2" 2>&1)
  vm_result
}

# vm_result  sets result from out, as vm_run does.
vm_result() {
  result=$(printf '%s\n' "$out" | sed -n 's/^\[[^]]*\] *RESULT|//p; s/^RESULT|//p' | head -1)
}

# vm_report <vm> <hypervisor>  prints the outcome of the last vm_run; returns 1
# when it was not a success.
vm_report() {
  case "$result" in
    ok\|*)
      printf '%-8s on %-4s %s\n' "$1" "$2" "${result#ok|}" ;;
    error\|*)
      printf '%-8s on %-4s FAILED: %s\n' "$1" "$2" "${result#error|}" >&2
      return 1 ;;
    *)
      printf '%-8s on %-4s FAILED: no answer from %s\n' "$1" "$2" "$2" >&2
      printf '%s\n' "$out" | tail -3 >&2
      return 1 ;;
  esac
}

# --node-groups and --exclude-nodes for start-vm.sh, stop-vm.sh and reboot-vm.sh, as in vmctl.sh. The option
# parser calls vm_select_option for each of the two (and vm_select_name for each VM argument, to keep the
# command-line order); vm_select then sets SELECTED to the VMs: the groups and VM names in the order given, each
# group as its members (`orphera nodes --groups`), a bare number N as tstN and a config file as its hostname:,
# then checked against the inventory, without repeats and less --exclude-nodes (`orphera nodes`). It prints
# "VMs: ..." and exits 2 on a usage error or if `orphera nodes` fails.
SEL_GROUPS="" SEL_EXCLUDE=""
SEL_ORDER=()   # "g:<g1,g2>" and "n:<vm>", in command-line order

# vm_select_option <option> <value>
vm_select_option() {
  case "$1" in
    --node-groups)
      [ -n "${2:-}" ] || { echo "--node-groups needs a group name" >&2; exit 2; }
      SEL_GROUPS="${SEL_GROUPS:+$SEL_GROUPS,}$2"; SEL_ORDER+=("g:$2") ;;
    --exclude-nodes)
      [ -n "${2:-}" ] || { echo "--exclude-nodes needs a VM name" >&2; exit 2; }
      SEL_EXCLUDE="${SEL_EXCLUDE:+$SEL_EXCLUDE,}$2" ;;
  esac
}

vm_select_name() { SEL_ORDER+=("n:$1"); }

# True if --node-groups or --exclude-nodes was given.
vm_select_wanted() { [ -n "$SEL_GROUPS$SEL_EXCLUDE" ]; }

vm_select() {
  local listing line item f n g gl wanted=() sel=() expanded names=0
  local -A members=()
  for item in "${SEL_ORDER[@]}"; do case "$item" in n:*) names=1 ;; esac; done
  [ -n "$SEL_GROUPS" ] || [ "$names" = 1 ] || {
    echo "--exclude-nodes takes VMs out of a selection: give --node-groups or VM names as well" >&2; exit 2; }
  if [ -n "$SEL_GROUPS" ]; then
    listing=$(orphera nodes --groups) || exit 2
    while IFS= read -r line; do
      [ -n "$line" ] && members[${line%%:*}]=${line#*: }
    done <<<"$listing"
  fi
  for item in "${SEL_ORDER[@]}"; do
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
  sel=(--nodes "$(IFS=,; printf '%s' "${wanted[*]}")")
  [ -z "$SEL_EXCLUDE" ] || sel+=(--exclude-nodes "$SEL_EXCLUDE")
  expanded=$(orphera nodes "${sel[@]}") || exit 2
  # shellcheck disable=SC2206  # node names are plain (inventory schema), one per line
  SELECTED=($expanded)
  [ "${#SELECTED[@]}" -gt 0 ] || { echo "the selection has no VMs in it" >&2; exit 2; }
  echo "VMs: ${SELECTED[*]}"
}

# vm_parallel_option <value>  checks the value of --parallel (a number of VMs, 1 or more).
vm_parallel_option() {
  case "${1:-}" in ''|*[!0-9]*) echo "--parallel needs a number of VMs (1 or more)" >&2; exit 2 ;; esac
  [ "$1" -ge 1 ] || { echo "--parallel needs a number of VMs (1 or more)" >&2; exit 2; }
}

# vm_run_all <parallel> <timeout> <script function> <vm>...  looks each VM up, runs the script the function
# prints for its domain name on its hypervisor and reports, adding the VMs that failed to $failed. With
# <parallel> 1 one VM after another; with more, up to that many at a time: the VMs are all looked up first,
# then each report is printed in command-line order as soon as that VM and those before it have finished.
vm_run_all() {
  local par=$1 timeout=$2 fn=$3 arg tmpd k n=0 launched=0
  shift 3
  failed=""
  if [ "$par" -le 1 ]; then
    for arg in "$@"; do
      vm_lookup "$arg" || { failed="$failed $arg"; continue; }
      vm_run "$hv" "$("$fn" "$vm")" "$timeout"
      vm_report "$vm" "$hv" || failed="$failed $vm"
    done
    return 0
  fi
  local -a a_arg=() a_vm=() a_hv=() a_ok=() pids=()
  for arg in "$@"; do
    a_arg[n]=$arg a_ok[n]=0
    if vm_lookup "$arg"; then a_vm[n]=$vm a_hv[n]=$hv a_ok[n]=1; fi
    n=$((n + 1))
  done
  tmpd=$(mktemp -d) || exit 1
  trap 'rm -rf "$tmpd"' EXIT
  for ((k = 0; k < n; k++)); do
    while [ "$launched" -lt "$n" ] && [ "$launched" -lt $((k + par)) ]; do
      if [ "${a_ok[launched]}" = 1 ]; then
        ( vm_run "${a_hv[launched]}" "$("$fn" "${a_vm[launched]}")" "$timeout"; printf '%s' "$out" >"$tmpd/$launched" ) &
        pids[launched]=$!
      fi
      launched=$((launched + 1))
    done
    if [ "${a_ok[k]}" = 1 ]; then
      wait "${pids[k]}"
      out=$(cat "$tmpd/$k" 2>/dev/null)
      vm_result
      vm_report "${a_vm[k]}" "${a_hv[k]}" || failed="$failed ${a_vm[k]}"
    else
      failed="$failed ${a_arg[k]}"
    fi
  done
  rm -rf "$tmpd"
  trap - EXIT
}
