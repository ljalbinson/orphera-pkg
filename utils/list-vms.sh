#!/usr/bin/env bash
#
# Lists every VM on each hypervisor: name, state, vCPUs and memory, with a total
# per hypervisor for the running VMs.
#
#   utils/list-vms.sh                 # gs1 gs2 gs3 st0
#   utils/list-vms.sh gs1 st0         # just those hypervisors
#   HYPERVISORS="gs1 gs2" utils/list-vms.sh
#
# Run from scala0, in orphera-pkg (needs `orphera` and an agent on each
# hypervisor). Read-only. Exit code 1 if a hypervisor did not answer.

set -uo pipefail

if [ $# -gt 0 ]; then
  HV="$*"
else
  HV="${HYPERVISORS:-gs1 gs2 gs3 st0}"
fi

# Runs on each hypervisor; one machine-readable line per VM.
REMOTE='V="virsh --connect qemu:///system"; [ "$(id -u)" -eq 0 ] || V="sudo -n $V"; echo "HV|up"; for v in $($V list --all --name 2>/dev/null); do st=$($V domstate "$v" 2>/dev/null | head -1); info=$($V dominfo "$v" 2>/dev/null); cpu=$(printf "%s\n" "$info" | awk "/^CPU\\(s\\)/{print \$2}"); mem=$(printf "%s\n" "$info" | awk "/^Max memory/{printf \"%d\", \$3/1024}"); echo "VM|$v|$st|$cpu|$mem"; done'

NODES=$(printf '%s' "$HV" | tr -s ' ' ',')
RAW=$(orphera run --nodes "$NODES" --quiet -- sh -c "$REMOTE" 2>&1)

printf '%s\n' "$RAW" | awk -v want="$HV" '
function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
BEGIN { n = split(want, order, " ") }
{
  line = $0
  if (match(line, /^\[[^]]+\] /)) {
    node = substr(line, 2, RLENGTH - 3)
    rest = substr(line, RLENGTH + 1)
  } else { next }
  if (rest ~ /^HV\|up/) { up[node] = 1; next }
  if (rest ~ /^VM\|/) {
    split(rest, f, "|")
    cnt[node]++
    row[node, cnt[node]] = sprintf("  %-22s %-12s %5s %9s", f[2], trim(f[3]), f[4], f[5] " MiB")
    if (trim(f[3]) == "running") { rc[node]++; cpu[node] += f[4]; mem[node] += f[5] }
  }
}
END {
  bad = 0
  for (i = 1; i <= n; i++) {
    h = order[i]
    if (!(h in up)) { printf "%s: no answer (agent down or host unreachable)\n\n", h; bad = 1; continue }
    printf "%s  (%d VMs, %d running: %d vCPU, %d MiB)\n", h, cnt[h] + 0, rc[h] + 0, cpu[h] + 0, mem[h] + 0
    printf "  %-22s %-12s %5s %13s\n", "VM", "STATE", "VCPU", "MEMORY"
    # names are sorted by virsh already; keep its order
    for (j = 1; j <= cnt[h]; j++) print row[h, j]
    print ""
  }
  exit bad
}'
