// SPDX-License-Identifier: Apache-2.0

package orphera.galeracoldstart

/** The scripts galera-cold-start runs on the Galera nodes, word for word those of
  * utils/galera-cold-start.sh (`make check-galera-cold-start` compares what the
  * two send): the state of a node (after a STOP_LOOPS= line), and its health.
  */
object Scripts:

  val state: String =
    """if [ ! -f /etc/mysql/mariadb.conf.d/60-galera.cnf ] || ! command -v mariadbd >/dev/null 2>&1; then echo "GALERA-STATE none"; exit 0; fi
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
echo "GALERA-STATE down seqno=${seq:--1} safe=${safe:-0} uuid=${uuid:-none}""""

  val health: String =
    """q() { mariadb -N -e "SHOW STATUS LIKE '$1'" 2>/dev/null | awk '{print $2}'; }
if [ "$(q wsrep_cluster_size)" = "3" ] && [ "$(q wsrep_cluster_status)" = "Primary" ] && [ "$(q wsrep_ready)" = "ON" ]; then echo "GALERA-HEALTH ok"; else echo "GALERA-HEALTH no"; fi"""
