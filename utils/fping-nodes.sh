#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Pings every node of one or more inventory groups at once with fping and prints one line per node, in the order
# the nodes were selected, with the node's name first.
#
#   utils/fping-nodes.sh --node-groups k8s
#   utils/fping-nodes.sh --node-groups ceph,openstack --exclude-nodes tst2
#   utils/fping-nodes.sh --node-groups k8s --count 5        # packet loss and round-trip times
#   utils/fping-nodes.sh --node-groups k8s --unreachable    # only the nodes that do not answer
#   utils/fping-nodes.sh --cluster-ip --node-groups ceph    # the cluster_ip address instead of the host name
#   utils/fping-nodes.sh tst0 tst1 12                       # VM names and numbers work as well
#
#   --node-groups G1,G2     every node in those inventory groups
#   --exclude-nodes V1,V2   leave those nodes out of the selection
#   --count N               send N pings to each node (default 1). With 1 each line says alive or unreachable;
#                           with more, it gives sent/received/loss and the minimum, average and maximum time
#   --timeout MS            how long to wait for each reply, in milliseconds (default 500)
#   --alive                 print only the nodes that answered (with --count 1)
#   --unreachable           print only the nodes that did not answer (with --count 1)
#   --cluster-ip            ping the node's `cluster_ip` variable instead of its `host:` name
#
# The groups and node names are selected as in stop-vm.sh (`orphera nodes --groups` lists the groups): in the
# order given, each group as its members, without repeats, less the nodes excluded. --exclude-nodes needs
# --node-groups or node names as well. The address of each node is the `host:` of its entry in inventory.yaml
# (a node with no entry is pinged by its name).
#
# Needs fping (apt install fping). Run from scala0, in orphera-pkg. Read-only.
# Exit code: 0 if every node answered, 1 if some did not, 2 if a name could not be resolved or the arguments
# are wrong, as fping's own.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

count=1
timeout_ms=500
show=all
field=host
pos=()
while [ $# -gt 0 ]; do
  case "$1" in
    --node-groups|--exclude-nodes) vm_select_option "$1" "${2:-}"; shift 2 ;;
    --count)
      case "${2:-}" in ''|*[!0-9]*|0) echo "--count needs a number of pings (1 or more)" >&2; exit 2 ;; esac
      count=$2; shift 2 ;;
    --timeout)
      case "${2:-}" in ''|*[!0-9]*|0) echo "--timeout needs a number of milliseconds (1 or more)" >&2; exit 2 ;; esac
      timeout_ms=$2; shift 2 ;;
    --alive) show=alive; shift ;;
    --unreachable) show=unreachable; shift ;;
    --cluster-ip) field=cluster_ip; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) pos+=("$1"); vm_select_name "$1"; shift ;;
  esac
done
if [ "$show" != all ] && [ "$count" -gt 1 ]; then
  echo "--alive and --unreachable go with --count 1" >&2; exit 2
fi
command -v fping >/dev/null 2>&1 || { echo "fping is not installed (apt install fping)" >&2; exit 2; }

if vm_select_wanted; then
  vm_select
  set -- "${SELECTED[@]}"
else
  # Names given directly: a bare number N is tstN.
  sel=()
  for n in "${pos[@]+"${pos[@]}"}"; do
    case "$n" in *[!0-9]*) sel+=("$n") ;; *) sel+=("tst$n") ;; esac
  done
  set -- "${sel[@]+"${sel[@]}"}"
fi
[ $# -gt 0 ] || { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

inv=$(inventory_file) || exit 2
[ -f "$inv" ] || { echo "$inv: no such file" >&2; exit 2; }

# name|host|cluster_ip for every node in the inventory.
table=$(awk '
  /^nodes:/ { n = 1; next }
  /^[^ #-]/ { n = 0 }
  !n { next }
  /^  - name:/ { if (name != "") print name "|" host "|" cip; name = $NF; host = ""; cip = ""; next }
  /^    host:/ { host = $2; next }
  /^      cluster_ip:/ { cip = $2; gsub(/"/, "", cip); next }
  END { if (name != "") print name "|" host "|" cip }
' "$inv")

map=$(mktemp); trap 'rm -f "$map"' EXIT
targets=()
status=0
idx=0
for n in "$@"; do
  idx=$((idx + 1))
  row=$(printf '%s\n' "$table" | awk -F'|' -v n="$n" '$1 == n { print; exit }')
  if [ -z "$row" ]; then
    addr=$n   # no inventory entry: the name itself
  elif [ "$field" = cluster_ip ]; then
    addr=$(printf '%s' "$row" | cut -d'|' -f3)
  else
    addr=$(printf '%s' "$row" | cut -d'|' -f2)
    [ -n "$addr" ] || addr=$n
  fi
  if [ -z "$addr" ]; then
    echo "$n: no cluster_ip in $inv" >&2
    status=2
    continue
  fi
  targets+=("$addr")
  printf '%s %s %s\n' "$addr" "$n" "$idx" >> "$map"
done
[ "${#targets[@]}" -gt 0 ] || exit 2

if [ "$count" -eq 1 ]; then
  fping_args=(-t "$timeout_ms")
  case "$show" in alive) fping_args+=(-a) ;; unreachable) fping_args+=(-u) ;; esac
else
  fping_args=(-q -c "$count" -t "$timeout_ms")
fi

# fping prints the results in the order they arrive (the summary of -c on stderr); the lines are renamed to the
# node's name and put back in selection order. Anything else fping says (resolver errors) follows.
raw=$(fping "${fping_args[@]}" "${targets[@]}" 2>&1)
rc=$?
printf '%s\n' "$raw" | awk -v map="$map" '
  BEGIN { while ((getline line < map) > 0) { split(line, f, " "); name[f[1]] = f[2]; idx[f[1]] = f[3] } w = 0 }
  {
    t = $1
    if (t in name) {
      rest = $0; sub(/^[^ ]+[ ]*/, "", rest)
      if (w < length(name[t])) w = length(name[t])
      lines[idx[t]] = name[t] "\t" rest
      if (idx[t] > max) max = idx[t]
    } else if ($0 != "") other = other $0 "\n"
  }
  END {
    for (i = 1; i <= max; i++) if (i in lines) {
      split(lines[i], p, "\t")
      printf "%-*s  %s\n", w, p[1], p[2]
    }
    printf "%s", other
  }'
[ "$rc" -ne 0 ] && exit "$rc"
exit "$status"
