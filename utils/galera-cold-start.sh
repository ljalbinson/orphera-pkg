#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Bring the Galera cluster on tst3, tst4 and tst5 back after every node was stopped.
#
# Usage:  utils/galera-cold-start.sh [--bootstrap <node>] [--check]
#
# What it does, per case:
#   - no Galera on the nodes yet (fresh build)      -> nothing; the galera step builds it
#   - all three healthy (size 3, Primary, ready)    -> nothing
#   - some nodes up                                 -> start MariaDB on the others; they rejoin
#   - all three down (the cold start)               -> read each node's last committed position,
#       bootstrap from the node with the highest seqno, start the other two, wait for size 3.
#
# Why not "bootstrap tst3 and force safe_to_bootstrap": after a stop of the whole cluster the
# node that left last holds the newest data. Bootstrapping an older node silently discards
# the transactions only the newer one has. A node's position is the seqno in grastate.dat, or,
# when that is -1 (unclean stop), what `mariadbd --wsrep-recover` reports from the InnoDB log.
# The script refuses to guess: if no node reports a position, or the nodes report different
# cluster UUIDs, it stops and says so. `--bootstrap <node>` is the deliberate override.
# `--check` only reports what it would do.
#
# Not a playbook because a playbook cannot pass one node's output to a decision about another
# (Task.SetFact takes literals, there is no capture of command output); see the README.
set -uo pipefail
NODES=(tst3 tst4 tst5)
FORCE="" CHECK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --bootstrap) FORCE="${2:?--bootstrap needs a node name}"; shift 2 ;;
    --check) CHECK=1; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[%s] galera: %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$2" --nodes "$1" --timeout "${3:-120}" 2>&1; }

# One line per node: none | up size=N | down seqno=N safe=0|1 uuid=U
read -r -d '' STATE_SCRIPT <<'EOF'
if [ ! -f /etc/mysql/mariadb.conf.d/60-galera.cnf ] || ! command -v mariadbd >/dev/null 2>&1; then echo "GALERA-STATE none"; exit 0; fi
# Stop mariadb without waiting for it: a unit that is still looking for its peers ignores SIGTERM
# and mariadb.service allows 900 s to stop. Ask, give it 10 s, then kill the main process; it is
# only trying to start, serves nothing, and this is no worse than the crash that put it there.
gone() { s=$(systemctl is-active mariadb); [ "$s" = "inactive" ] || [ "$s" = "failed" ]; }
stop_bounded() {
  systemctl stop --no-block mariadb 2>/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do gone && break; sleep 1; done
  if ! gone; then systemctl kill --kill-whom=main -s KILL mariadb 2>/dev/null; fi
  for i in 1 2 3 4 5 6 7 8 9 10; do gone && break; sleep 1; done
  systemctl reset-failed mariadb 2>/dev/null
  gone
}
st=$(systemctl is-active mariadb)
if [ "$st" = "active" ]; then
  size=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'" 2>/dev/null | awk '{print $2}')
  comment=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_local_state_comment'" 2>/dev/null | awk '{print $2}')
  wsrep_on=$(mariadb -N -e "SELECT @@global.wsrep_on" 2>/dev/null)
  if [ "${size:-0}" = "0" ] && [ "$wsrep_on" = "1" ] && { [ "$comment" = "Initialized" ] || [ "$comment" = "Disconnected" ]; }; then
    # A failed join (for example an IST error after the SST) leaves mariadbd running with the
    # Galera layer closed: active, but in no cluster. It needs a restart, so treat it as down.
    if [ "$STOP_LOOPS" = "1" ]; then stop_bounded || { echo "could not stop mariadb" >&2; exit 1; }; st=stopped
    else echo "GALERA-STATE down seqno=-1 safe=0 uuid=none stuck=$comment"; exit 0; fi
  else
    echo "GALERA-STATE up size=${size:-0}"; exit 0
  fi
fi
if [ "$st" = "activating" ] || [ "$st" = "deactivating" ] || [ "$st" = "failed" ]; then
  # A node that cannot find its peers is started by systemd over and over (Restart=on-abort).
  # Reading its position or bootstrapping while that goes on races with the restarts.
  if [ "$STOP_LOOPS" = "1" ]; then stop_bounded || { echo "could not stop mariadb" >&2; exit 1; }
  else echo "GALERA-STATE down seqno=-1 safe=0 uuid=none restarting=$st"; exit 0; fi
fi
f=/var/lib/mysql/grastate.dat
uuid=none; seq=-1; safe=0
if [ -f $f ]; then
  uuid=$(sed -n 's/^uuid: *//p' $f); seq=$(sed -n 's/^seqno: *//p' $f); safe=$(sed -n 's/^safe_to_bootstrap: *//p' $f)
fi
if [ "${seq:--1}" = "-1" ]; then
  rm -f /tmp/orphera-wsrep-recover.log
  mariadbd --user=mysql --wsrep-recover --log-error=/tmp/orphera-wsrep-recover.log >/dev/null 2>&1
  rec=$(sed -n 's/.*Recovered position: *//p' /tmp/orphera-wsrep-recover.log 2>/dev/null | tail -n 1)
  rm -f /tmp/orphera-wsrep-recover.log
  if [ -n "$rec" ]; then uuid=${rec%:*}; seq=${rec##*:}; fi
fi
echo "GALERA-STATE down seqno=${seq:--1} safe=${safe:-0} uuid=${uuid:-none}"
EOF

read -r -d '' HEALTH_SCRIPT <<'EOF'
q() { mariadb -N -e "SHOW STATUS LIKE '$1'" 2>/dev/null | awk '{print $2}'; }
if [ "$(q wsrep_cluster_size)" = "3" ] && [ "$(q wsrep_cluster_status)" = "Primary" ] && [ "$(q wsrep_ready)" = "ON" ]; then echo "GALERA-HEALTH ok"; else echo "GALERA-HEALTH no"; fi
EOF

declare -A KIND SIZE SEQ SAFE UUID
STOP_LOOPS=1; [ -n "$CHECK" ] && STOP_LOOPS=0
for n in "${NODES[@]}"; do
  out=$(run_on "$n" "STOP_LOOPS=$STOP_LOOPS
$STATE_SCRIPT")
  line=$(grep -E "^\[$n\] GALERA-STATE " <<<"$out" | tail -n 1 | sed "s/^\[$n\] GALERA-STATE //")
  if [ -z "$line" ]; then
    log "no state from $n (agent not answering, or MariaDB could not be stopped). Last output:"
    grep -v '^\[.*\] Running:' <<<"$out" | tail -n 6 | sed 's/^/    /'
    exit 1
  fi
  KIND[$n]=${line%% *}
  SIZE[$n]=$(sed -n 's/.*size=\([0-9]*\).*/\1/p' <<<"$line")
  SEQ[$n]=$(sed -n 's/.*seqno=\(-\{0,1\}[0-9]*\).*/\1/p' <<<"$line")
  SAFE[$n]=$(sed -n 's/.*safe=\([01]\).*/\1/p' <<<"$line")
  UUID[$n]=$(sed -n 's/.*uuid=\([0-9a-f-]*\).*/\1/p' <<<"$line")
  log "$n: $line"
done

healthy() { run_on "$1" "$HEALTH_SCRIPT" | grep -q "^\[$1\] GALERA-HEALTH ok"; }
wait_healthy() {
  local i n ok bad
  log "waiting for size 3, Primary, ready on every node (up to 3 minutes)"
  for i in $(seq 1 36); do
    ok=1; bad=""
    for n in "${NODES[@]}"; do healthy "$n" || { ok=0; bad="$bad $n"; }; done
    [ "$ok" = 1 ] && return 0
    [ $((i % 6)) -eq 0 ] && log "  still waiting ($((i * 5))s): not yet healthy:$bad"
    sleep 5
  done
  log "not healthy after 3 minutes; wsrep state per node:"
  for n in "${NODES[@]}"; do
    run_on "$n" "echo \"unit: \$(systemctl is-active mariadb)\"; mariadb -N -e \"SHOW STATUS WHERE Variable_name IN ('wsrep_cluster_size','wsrep_cluster_status','wsrep_ready','wsrep_local_state_comment')\" 2>&1 | tr '\\t' '='" | sed 's/^/    /'
  done
  return 1
}

none=0 up=0 down=0 standalone=0
for n in "${NODES[@]}"; do
  case "${KIND[$n]}" in
    none) none=$((none + 1)) ;;
    up) if [ "${SIZE[$n]:-0}" = "0" ]; then standalone=$((standalone + 1)); else up=$((up + 1)); fi ;;
    down) down=$((down + 1)) ;;
  esac
done

if [ "$none" -gt 0 ] || [ "$standalone" -gt 0 ]; then
  log "Galera is not (fully) built on these nodes yet; leaving it to the galera step"; exit 0
fi
if [ "$up" -eq 3 ] && [ "${SIZE[tst3]}" = 3 ] && [ "${SIZE[tst4]}" = 3 ] && [ "${SIZE[tst5]}" = 3 ]; then
  log "all three nodes are in the cluster (size 3); nothing to do"; exit 0
fi

if [ "$up" -ge 2 ] && [ "$(for n in "${NODES[@]}"; do [ "${KIND[$n]}" = up ] && echo "${SIZE[$n]}"; done | sort -u | wc -l)" -gt 1 ]; then
  log "SPLIT BRAIN: the running nodes disagree about the cluster size, so they are in separate components:"
  for n in "${NODES[@]}"; do [ "${KIND[$n]}" = up ] && log "  $n sees $(printf '%s' "${SIZE[$n]}") member(s)"; done
  log "Not touching it. Compare 'SHOW STATUS LIKE wsrep_last_committed' on each side, keep the side with the newer data,"
  log "then on each node of the other side: systemctl stop mariadb; rm /var/lib/mysql/grastate.dat; systemctl start mariadb"
  exit 1
fi
if [ "$up" -eq 3 ]; then
  log "all three nodes are running but report different sizes; check 'SHOW STATUS LIKE wsrep_%'"; exit 1
fi

if [ "$up" -gt 0 ]; then
  log "$up node(s) are in a running cluster; starting MariaDB on the others so they rejoin"
  [ -n "$CHECK" ] && exit 0
  for n in "${NODES[@]}"; do
    [ "${KIND[$n]}" = down ] || continue
    out=$(run_on "$n" "systemctl start mariadb" 600)
    grep -q 'success=true' <<<"$out" || { log "starting MariaDB on $n failed:"; sed 's/^/    /' <<<"$out" | tail -n 15; exit 1; }
  done
  wait_healthy && { log "cluster healthy: size 3 on every node"; exit 0; }
  log "the cluster did not reach size 3 within 3 minutes"; exit 1
fi

# All three are down: choose where to bootstrap.
best=""
for n in "${NODES[@]}"; do
  [ -n "$best" ] || { best=$n; continue; }
  if [ "${SEQ[$n]}" -gt "${SEQ[$best]}" ] || { [ "${SEQ[$n]}" -eq "${SEQ[$best]}" ] && [ "${SAFE[$n]}" = 1 ] && [ "${SAFE[$best]}" != 1 ]; }; then best=$n; fi
done
uuids=$(for n in "${NODES[@]}"; do [ "${SEQ[$n]}" -ge 0 ] && echo "${UUID[$n]}"; done | sort -u | wc -l)

if [ -n "$FORCE" ]; then
  case " ${NODES[*]} " in *" $FORCE "*) ;; *) log "--bootstrap must be one of: ${NODES[*]}"; exit 2 ;; esac
  log "bootstrapping from $FORCE as instructed (positions above are not used to choose)"
  best=$FORCE
else
  if [ "${SEQ[$best]}" -lt 0 ]; then
    log "no node could report a committed position (seqno -1 everywhere). Not guessing which has the newest data."
    log "If you know (or the data is disposable), run again with:  $0 --bootstrap <node>"; exit 1
  fi
  if [ "$uuids" -gt 1 ]; then
    log "the nodes report different cluster UUIDs; their histories have diverged. Not choosing for you."
    log "Decide which node to keep, then:  $0 --bootstrap <node>"; exit 1
  fi
  log "highest committed position: $best (seqno ${SEQ[$best]})"
fi
[ -n "$CHECK" ] && { log "would bootstrap from $best, then start the other two"; exit 0; }

out=$(run_on "$best" "sed -i 's/safe_to_bootstrap: 0/safe_to_bootstrap: 1/' /var/lib/mysql/grastate.dat 2>/dev/null; galera_new_cluster || { echo 'galera_new_cluster failed' >&2; journalctl -xeu mariadb --no-pager -n 30 >&2; exit 1; }" 300)
grep -q 'success=true' <<<"$out" || { log "bootstrap on $best failed:"; sed 's/^/    /' <<<"$out" | tail -n 25; exit 1; }
log "bootstrapped a new cluster on $best"
for n in "${NODES[@]}"; do
  [ "$n" = "$best" ] && continue
  out=$(run_on "$n" "systemctl start mariadb" 600)
  grep -q 'success=true' <<<"$out" || { log "starting MariaDB on $n failed:"; sed 's/^/    /' <<<"$out" | tail -n 15; exit 1; }
  log "$n started and joined"
done
if wait_healthy; then log "cluster healthy: size 3, Primary, ready on every node"; exit 0; fi
log "the cluster did not reach size 3 within 3 minutes"; exit 1
