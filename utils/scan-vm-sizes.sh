#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Compares the CPU and memory of every VM three ways:
#   config      what config/<vm>.yaml says (cpu: and memory:, in MiB)
#   hypervisor  what libvirt has defined (virsh dominfo: CPU(s) and Max memory) and the VM's state
#   guest       what the running guest sees (nproc, and MemTotal from /proc/meminfo)
# and flags a difference. The guest's MemTotal is always a little below the memory given to the VM (the
# kernel keeps some), so it is accepted from GUEST_MEM_MIN_PCT (default 88) up to 100 percent of the config
# value; the config and the hypervisor must agree exactly. VMs that are not running are shown without guest
# figures and are not counted as a difference.
#
#   utils/scan-vm-sizes.sh              # every VM that has a config file with hypervisor:, hostname:
#   utils/scan-vm-sizes.sh tst17 23     # just those (a name or a number)
#   GUEST_MEM_MIN_PCT=90 utils/scan-vm-sizes.sh
#
# Run from scala0, in orphera-pkg (needs `orphera`, an agent on each hypervisor and on each running guest).
# The config files are found as for the other scripts here (ORPHERA_CONFIG_DIR, default config).
# Read-only. Exit code 1 if any size differs or a hypervisor did not answer, else 0.

set -uo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/orphera-sources.sh"

MIN_PCT="${GUEST_MEM_MIN_PCT:-88}"
case "$MIN_PCT" in '' | *[!0-9]*) echo "GUEST_MEM_MIN_PCT must be a whole number" >&2; exit 1 ;; esac

dir=$(config_dir) || exit 1

# ---- static: the config files -------------------------------------------------------------------------------

declare -A C_HV C_CPU C_MEM
VMS=()

want() {
  local v="$1" a
  shift
  [ $# -eq 0 ] && return 0
  for a in "$@"; do
    case "$a" in
      *[!0-9]*) [ "$a" = "$v" ] && return 0 ;;
      *) [ "tst$a" = "$v" ] && return 0 ;;
    esac
  done
  return 1
}

for cfg in "$dir"/*.yaml; do
  [ -f "$cfg" ] || continue
  hv=$(config_value "$cfg" hypervisor)
  vm=$(config_value "$cfg" hostname)
  [ -n "$hv" ] && [ -n "$vm" ] || continue
  case "$hv$vm" in *[!A-Za-z0-9._-]*) echo "$cfg: unusual characters in hypervisor or hostname; skipped" >&2; continue ;; esac
  want "$vm" "$@" || continue
  C_HV[$vm]=$hv
  C_CPU[$vm]=$(config_value "$cfg" cpu)
  C_MEM[$vm]=$(config_value "$cfg" memory)
  VMS+=("$vm")
done

if [ ${#VMS[@]} -eq 0 ]; then
  echo "no VM config with hypervisor: and hostname: found in $dir" >&2
  exit 1
fi
mapfile -t VMS < <(printf '%s\n' "${VMS[@]}" | sort -V)
HVS=$(for v in "${VMS[@]}"; do echo "${C_HV[$v]}"; done | sort -u | tr '\n' ' ')

# ---- dynamic: the hypervisors (libvirt) ---------------------------------------------------------------------

# Runs on each hypervisor; one machine-readable line per VM.
HV_CMD='V="virsh --connect qemu:///system"; [ "$(id -u)" -eq 0 ] || V="sudo -n $V"; echo "HV|up"; for v in $($V list --all --name 2>/dev/null); do st=$($V domstate "$v" 2>/dev/null | head -1); info=$($V dominfo "$v" 2>/dev/null); cpu=$(printf "%s\n" "$info" | awk "/^CPU\\(s\\)/{print \$2}"); mem=$(printf "%s\n" "$info" | awk "/^Max memory/{printf \"%d\", \$3/1024}"); echo "VM|$v|$st|$cpu|$mem"; done'

declare -A H_UP H_STATE H_CPU H_MEM SEEN
HV_NODES=$(printf '%s' "$HVS" | sed 's/ *$//' | tr -s ' ' ',')
RAW=$(orphera run --nodes "$HV_NODES" --quiet --timeout 60 -- sh -c "$HV_CMD" 2>&1)
while IFS= read -r line; do
  [[ "$line" =~ ^\[([^]]+)\]\ (.*)$ ]] || continue
  node=${BASH_REMATCH[1]}
  rest=${BASH_REMATCH[2]}
  case "$rest" in
    "HV|up") H_UP[$node]=1 ;;
    VM\|*)
      IFS='|' read -r _ name state cpu mem <<<"$rest"
      state=${state%"${state##*[![:space:]]}"}
      H_STATE["$node/$name"]=$state
      H_CPU["$node/$name"]=$cpu
      H_MEM["$node/$name"]=$mem
      SEEN["$node/$name"]=1
      ;;
  esac
done <<<"$RAW"

# ---- dynamic: the running guests ----------------------------------------------------------------------------

GUEST_CMD='echo "G|$(nproc)|$(grep ^MemTotal /proc/meminfo | tr -s " " | cut -d" " -f2)"'

RUNNING=()
for v in "${VMS[@]}"; do
  [ "${H_STATE[${C_HV[$v]}/$v]:-}" = "running" ] && RUNNING+=("$v")
done

declare -A G_CPU G_MEM
if [ ${#RUNNING[@]} -gt 0 ]; then
  G_NODES=$(printf '%s,' "${RUNNING[@]}")
  G_NODES=${G_NODES%,}
  RAW=$(orphera run --nodes "$G_NODES" --quiet --timeout 60 -- sh -c "$GUEST_CMD" 2>&1)
  while IFS= read -r line; do
    case "$line" in "["*"] G|"*) ;; *) continue ;; esac
    gnode=${line%%]*}
    gnode=${gnode#[}
    grest=${line#*\] G|}
    gcpu=${grest%%|*}
    gkb=${grest#*|}
    gkb=${gkb//[[:space:]]/}
    case "$gcpu$gkb" in '' | *[!0-9]*) continue ;; esac
    G_CPU[$gnode]=$gcpu
    G_MEM[$gnode]=$((gkb / 1024))
  done <<<"$RAW"
fi

# ---- compare and print --------------------------------------------------------------------------------------

DIFFS=0
BAD_HV=0
printf '%-8s %-5s | %-13s | %-24s | %-13s | %s\n' VM HV "CONFIG" "HYPERVISOR" "GUEST" CHECK
printf '%-8s %-5s | %5s %7s | %5s %7s %-9s | %5s %7s | %s\n' "" "" cpu MiB cpu MiB state cpu MiB ""
T_CC=0 T_CM=0 T_HC=0 T_HM=0 T_GC=0 T_GM=0

for v in "${VMS[@]}"; do
  hv=${C_HV[$v]}
  cc=${C_CPU[$v]:-}
  cm=${C_MEM[$v]:-}
  key="$hv/$v"
  hc=${H_CPU[$key]:-}
  hm=${H_MEM[$key]:-}
  hs=${H_STATE[$key]:-}
  gc=${G_CPU[$v]:-}
  gm=${G_MEM[$v]:-}
  notes=()
  d0=$DIFFS

  if [ -z "${H_UP[$hv]:-}" ]; then
    notes+=("hypervisor $hv did not answer")
    BAD_HV=1
  elif [ -z "$hs" ]; then
    notes+=("not defined on $hv")
    DIFFS=$((DIFFS + 1))
  else
    [ -n "$cc" ] && [ "$cc" != "$hc" ] && notes+=("cpu: config $cc, libvirt $hc") && DIFFS=$((DIFFS + 1))
    [ -n "$cm" ] && [ "$cm" != "$hm" ] && notes+=("memory: config $cm, libvirt $hm") && DIFFS=$((DIFFS + 1))
  fi

  if [ "$hs" = "running" ]; then
    if [ -z "$gc" ]; then
      notes+=("guest did not answer")
    else
      [ -n "$cc" ] && [ "$cc" != "$gc" ] && notes+=("cpu: config $cc, guest $gc") && DIFFS=$((DIFFS + 1))
      if [ -n "$cm" ] && [ "$cm" -gt 0 ] 2>/dev/null; then
        if [ $((gm * 100)) -lt $((cm * MIN_PCT)) ] || [ "$gm" -gt "$cm" ]; then
          notes+=("memory: config $cm, guest sees $gm")
          DIFFS=$((DIFFS + 1))
        fi
      fi
    fi
    T_GC=$((T_GC + ${gc:-0}))
    T_GM=$((T_GM + ${gm:-0}))
  fi

  T_CC=$((T_CC + ${cc:-0}))
  T_CM=$((T_CM + ${cm:-0}))
  [ -n "$hs" ] && [ "$hs" = "running" ] && T_HC=$((T_HC + ${hc:-0})) && T_HM=$((T_HM + ${hm:-0}))

  if [ ${#notes[@]} -eq 0 ]; then
    verdict=ok
    [ "$hs" != "running" ] && [ -n "$hs" ] && verdict="ok ($hs)"
  else
    label=NOTE
    [ "$DIFFS" -gt "$d0" ] && label=DIFF
    verdict="$label"
    sep=" "
    for n in "${notes[@]}"; do
      verdict="$verdict$sep$n"
      sep="; "
    done
  fi
  printf '%-8s %-5s | %5s %7s | %5s %7s %-9s | %5s %7s | %s\n' "$v" "$hv" "${cc:--}" "${cm:--}" "${hc:--}" "${hm:--}" "${hs:--}" "${gc:--}" "${gm:--}" "$verdict"
done

printf '%-8s %-5s | %5s %7s | %5s %7s %-9s | %5s %7s |\n' "total" "" "$T_CC" "$T_CM" "$T_HC" "$T_HM" "(running)" "$T_GC" "$T_GM"

# VMs libvirt knows that have no config file (only when the whole fleet was scanned).
if [ $# -eq 0 ]; then
  EXTRA=""
  for key in "${!SEEN[@]}"; do
    name=${key#*/}
    [ -n "${C_HV[$name]:-}" ] || EXTRA="$EXTRA ${key}"
  done
  if [ -n "$EXTRA" ]; then
    echo
    echo "on a hypervisor but with no config file here:"
    for e in $(printf '%s\n' $EXTRA | sort -V); do
      printf '  %-14s %-9s %3s cpu %7s MiB\n' "$e" "${H_STATE[$e]}" "${H_CPU[$e]}" "${H_MEM[$e]}"
    done
  fi
fi

echo
if [ "$DIFFS" -gt 0 ] || [ "$BAD_HV" -gt 0 ]; then
  echo "differences: $DIFFS; hypervisors not answering: $BAD_HV"
  exit 1
fi
echo "every size agrees"
