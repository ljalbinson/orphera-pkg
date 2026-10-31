// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Brings the Galera cluster on tst3, tst4 and tst5 back after every node was
// stopped: the playbook form of utils/galera-cold-start.sh (which stays).
//
//   orphera cluster-playbook manifests/galera_cold_start.scala
//   ORPHERA_GALERA_CHECK=1 orphera cluster-playbook manifests/galera_cold_start.scala
//   ORPHERA_GALERA_BOOTSTRAP=tst4 orphera cluster-playbook manifests/galera_cold_start.scala
//
// What it does, per case:
//   - no Galera on the nodes yet (fresh build)      -> nothing; mariadb_galera_cluster.scala builds it
//   - all three healthy (size 3)                    -> nothing
//   - some nodes up                                 -> start MariaDB on the others; they rejoin
//   - all three down (the cold start)               -> read each node's last committed position,
//       bootstrap from the node with the highest seqno, start the other two one after the other,
//       wait for size 3, Primary, ready on every node.
//
// Why not "bootstrap tst3 and force safe_to_bootstrap": after a stop of the whole cluster the node
// that left last holds the newest data; bootstrapping an older node silently discards the
// transactions only the newer one has. A node's position is the seqno in grastate.dat or, when that
// is -1 (unclean stop), what `mariadbd --wsrep-recover` reports. The playbook refuses to guess: if no
// node reports a position, or the nodes report different cluster UUIDs, it stops and says so.
// ORPHERA_GALERA_BOOTSTRAP=<node> is the deliberate override. ORPHERA_GALERA_CHECK=1 only reports the plan:
// nothing is started, and a MariaDB that is stuck restarting is left alone (so its position is not read
// and the plan may say "no position").
//
// This needs `register` on Task.RunCommand: stage 1 stores each node's state as a fact, the
// decision stage reads all three through {{{nodes.<node>.galera_state}}} (triple braces, so that
// "=" is not HTML-escaped), and the later stages act on the stored plan.
//
// Not run where this was written; the first run on the lab is the first test.
object galera_cold_start extends OrpheraClusterPlaybook:

  private val nodes = List("tst3", "tst4", "tst5")

  private val checkOnly: Boolean =
    sys.env.get("ORPHERA_GALERA_CHECK").map(_.trim).contains("1")

  private val forced: String =
    sys.env.get("ORPHERA_GALERA_BOOTSTRAP").map(_.trim).getOrElse("")

  // One line: GALERA-STATE none | up size=N | down seqno=N safe=0|1 uuid=U
  // @@STOP@@ is 1 when a MariaDB that is stuck restarting may be stopped to read its position.
  private val stateScript: String =
    """if [ ! -f /etc/mysql/mariadb.conf.d/60-galera.cnf ] || ! command -v mariadbd >/dev/null 2>&1; then echo "GALERA-STATE none"; exit 0; fi
      |STOP_LOOPS=@@STOP@@
      |# Stop mariadb without waiting for it: a unit that is still looking for its peers ignores SIGTERM
      |# and mariadb.service allows 900 s to stop. Ask, give it 10 s, then kill the main process; it is
      |# only trying to start, serves nothing, and this is no worse than the crash that put it there.
      |gone() { s=$(systemctl is-active mariadb); [ "$s" = "inactive" ] || [ "$s" = "failed" ]; }
      |stop_bounded() {
      |  systemctl stop --no-block mariadb 2>/dev/null
      |  for i in 1 2 3 4 5 6 7 8 9 10; do gone && break; sleep 1; done
      |  if ! gone; then systemctl kill --kill-whom=main -s KILL mariadb 2>/dev/null; fi
      |  for i in 1 2 3 4 5 6 7 8 9 10; do gone && break; sleep 1; done
      |  systemctl reset-failed mariadb 2>/dev/null
      |  gone
      |}
      |st=$(systemctl is-active mariadb)
      |if [ "$st" = "active" ]; then
      |  size=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_cluster_size'" 2>/dev/null | awk '{print $2}')
      |  comment=$(mariadb -N -e "SHOW STATUS LIKE 'wsrep_local_state_comment'" 2>/dev/null | awk '{print $2}')
      |  wsrep_on=$(mariadb -N -e "SELECT @@global.wsrep_on" 2>/dev/null)
      |  if [ "${size:-0}" = "0" ] && [ "$wsrep_on" = "1" ] && { [ "$comment" = "Initialized" ] || [ "$comment" = "Disconnected" ]; }; then
      |    # A failed join (for example an IST error after the SST) leaves mariadbd running with the
      |    # Galera layer closed: active, but in no cluster. It needs a restart, so treat it as down.
      |    if [ "$STOP_LOOPS" = "1" ]; then stop_bounded || { echo "could not stop mariadb" >&2; exit 1; }; st=stopped
      |    else echo "GALERA-STATE down seqno=-1 safe=0 uuid=none"; exit 0; fi
      |  else
      |    echo "GALERA-STATE up size=${size:-0}"; exit 0
      |  fi
      |fi
      |if [ "$st" = "activating" ] || [ "$st" = "deactivating" ] || [ "$st" = "failed" ]; then
      |  # A node that cannot find its peers is started by systemd over and over (Restart=on-abort).
      |  # Reading its position or bootstrapping while that goes on races with the restarts.
      |  if [ "$STOP_LOOPS" = "1" ]; then stop_bounded || { echo "could not stop mariadb" >&2; exit 1; }
      |  else echo "GALERA-STATE down seqno=-1 safe=0 uuid=none"; exit 0; fi
      |fi
      |f=/var/lib/mysql/grastate.dat
      |uuid=none; seq=-1; safe=0
      |if [ -f $f ]; then
      |  uuid=$(sed -n 's/^uuid: *//p' $f); seq=$(sed -n 's/^seqno: *//p' $f); safe=$(sed -n 's/^safe_to_bootstrap: *//p' $f)
      |fi
      |if [ "${seq:--1}" = "-1" ]; then
      |  rm -f /tmp/orphera-wsrep-recover.log
      |  mariadbd --user=mysql --wsrep-recover --log-error=/tmp/orphera-wsrep-recover.log >/dev/null 2>&1
      |  rec=$(sed -n 's/.*Recovered position: *//p' /tmp/orphera-wsrep-recover.log 2>/dev/null | tail -n 1)
      |  rm -f /tmp/orphera-wsrep-recover.log
      |  if [ -n "$rec" ]; then uuid=${rec%:*}; seq=${rec##*:}; fi
      |fi
      |echo "GALERA-STATE down seqno=${seq:--1} safe=${safe:-0} uuid=${uuid:-none}"
      |""".stripMargin

  // Writes the state script to a file, runs it, and prints only its GALERA-STATE line, so that the
  // value stored by `register` is exactly that line whatever else the commands wrote.
  private val inspectScript: String =
    "cat > /tmp/orphera-galera-state.sh <<'GSEOF'\n" +
      stateScript.replace("@@STOP@@", if checkOnly then "0" else "1") +
      "GSEOF\n" +
      """OUT=$(sh /tmp/orphera-galera-state.sh 2>&1)
        |LINE=$(printf '%s\n' "$OUT" | grep '^GALERA-STATE ' | tail -n 1)
        |rm -f /tmp/orphera-galera-state.sh
        |if [ -z "$LINE" ]; then printf '%s\n' "$OUT" >&2; echo "no GALERA-STATE line from this node" >&2; exit 1; fi
        |echo "$LINE"
        |""".stripMargin

  // Runs on tst3 only: all three states in, one line out:
  //   PLAN verb=nothing|join|bootstrap|abort best=<node>|none why=<token>
  private val planScript: String =
    """S_tst3='{{{nodes.tst3.galera_state}}}'
      |S_tst4='{{{nodes.tst4.galera_state}}}'
      |S_tst5='{{{nodes.tst5.galera_state}}}'
      |FORCE='@@FORCE@@'
      |NODES="tst3 tst4 tst5"
      |plan() { echo "PLAN verb=$1 best=$2 why=$3"; exit 0; }
      |kind() { set -- $1; echo "$2"; }
      |field() { for w in $1; do case "$w" in "$2="*) echo "${w#*=}"; return;; esac; done; }
      |none=0; standalone=0; up=0; down=0; sizes=""
      |for n in $NODES; do
      |  eval "st=\$S_$n"
      |  case "$(kind "$st")" in
      |    none) none=$((none + 1)) ;;
      |    up) sz=$(field "$st" size)
      |       if [ "${sz:-0}" = "0" ]; then standalone=$((standalone + 1)); else up=$((up + 1)); sizes="$sizes $sz"; fi ;;
      |    down) down=$((down + 1)) ;;
      |    *) plan abort none no-state ;;
      |  esac
      |done
      |if [ $none -gt 0 ] || [ $standalone -gt 0 ]; then plan nothing none not-built; fi
      |if [ $up -eq 3 ] && [ "$sizes" = " 3 3 3" ]; then plan nothing none healthy; fi
      |if [ $up -ge 2 ] && [ "$(printf '%s\n' $sizes | sort -u | wc -l)" -gt 1 ]; then plan abort none split-brain; fi
      |if [ $up -eq 3 ]; then plan abort none sizes-differ; fi
      |if [ $up -gt 0 ]; then plan join none partial; fi
      |best=""
      |for n in $NODES; do
      |  if [ -z "$best" ]; then best=$n; continue; fi
      |  eval "st=\$S_$n; bst=\$S_$best"
      |  sq=$(field "$st" seqno); bsq=$(field "$bst" seqno); sf=$(field "$st" safe); bsf=$(field "$bst" safe)
      |  if [ "${sq:--1}" -gt "${bsq:--1}" ] || { [ "${sq:--1}" -eq "${bsq:--1}" ] && [ "$sf" = "1" ] && [ "$bsf" != "1" ]; }; then best=$n; fi
      |done
      |if [ -n "$FORCE" ]; then
      |  case " $NODES " in *" $FORCE "*) plan bootstrap "$FORCE" forced ;; *) plan abort none bad-force ;; esac
      |fi
      |eval "bst=\$S_$best"
      |bsq=$(field "$bst" seqno)
      |if [ "${bsq:--1}" -lt 0 ]; then plan abort none no-position; fi
      |uu=$(for n in $NODES; do eval "st=\$S_$n"; sq=$(field "$st" seqno); if [ "${sq:--1}" -ge 0 ]; then field "$st" uuid; fi; done | sort -u | wc -l)
      |if [ "$uu" -gt 1 ]; then plan abort none diverged; fi
      |plan bootstrap "$best" highest-seqno
      |""".stripMargin
      .replace("@@FORCE@@", forced)

  // Runs on tst3 after the plan: stops the playbook, with the explanation, when the plan is abort.
  private val abortScript: String =
    """PLAN='{{{nodes.tst3.galera_plan}}}'
      |WHY=$(printf '%s\n' "$PLAN" | sed -n 's/.*why=\([a-z-]*\).*/\1/p')
      |VERB=$(printf '%s\n' "$PLAN" | sed -n 's/.*verb=\([a-z]*\).*/\1/p')
      |[ "$VERB" = "abort" ] || exit 0
      |case "$WHY" in
      |  split-brain) echo "SPLIT BRAIN: the running nodes disagree about the cluster size, so they are in separate components." >&2
      |    echo "Not touching it. Compare 'SHOW STATUS LIKE wsrep_last_committed' on each side, keep the side with the newer data," >&2
      |    echo "then on each node of the other side: systemctl stop mariadb; rm /var/lib/mysql/grastate.dat; systemctl start mariadb" >&2 ;;
      |  sizes-differ) echo "all three nodes are running but report different sizes; check 'SHOW STATUS LIKE wsrep_%'" >&2 ;;
      |  no-position) echo "no node could report a committed position (seqno -1 everywhere). Not guessing which has the newest data." >&2
      |    echo "If you know (or the data is disposable), run again with ORPHERA_GALERA_BOOTSTRAP=<node>" >&2 ;;
      |  diverged) echo "the nodes report different cluster UUIDs; their histories have diverged. Not choosing for you." >&2
      |    echo "Decide which node to keep, then run again with ORPHERA_GALERA_BOOTSTRAP=<node>" >&2 ;;
      |  bad-force) echo "ORPHERA_GALERA_BOOTSTRAP must be one of: tst3 tst4 tst5" >&2 ;;
      |  *) echo "no usable state from a node (agent not answering?): $PLAN" >&2 ;;
      |esac
      |exit 1
      |""".stripMargin

  private def verbOf(): String =
    """PLAN='{{{nodes.tst3.galera_plan}}}'
      |VERB=$(printf '%s\n' "$PLAN" | sed -n 's/.*verb=\([a-z]*\).*/\1/p')
      |BEST=$(printf '%s\n' "$PLAN" | sed -n 's/.*best=\([a-z0-9]*\).*/\1/p')
      |CHECK=@@CHECK@@
      |if [ "$CHECK" = "1" ]; then echo "check only: nothing is started ($PLAN)"; exit 0; fi
      |""".stripMargin.replace("@@CHECK@@", if checkOnly then "1" else "0")

  private def bootstrapScript(self: String): String =
    verbOf() +
      s"""if [ "$$VERB" != "bootstrap" ] || [ "$$BEST" != "$self" ]; then echo "$self: not the bootstrap node"; exit 0; fi
         |sed -i 's/safe_to_bootstrap: 0/safe_to_bootstrap: 1/' /var/lib/mysql/grastate.dat 2>/dev/null
         |galera_new_cluster || { echo 'galera_new_cluster failed' >&2; journalctl -xeu mariadb --no-pager -n 30 >&2; exit 1; }
         |echo "$self: bootstrapped a new cluster"
         |""".stripMargin

  private def joinScript(self: String): String =
    verbOf() +
      s"""S='{{{nodes.$self.galera_state}}}'
         |case "$$S" in "GALERA-STATE down"*) DOWN=1 ;; *) DOWN=0 ;; esac
         |if [ "$$VERB" = "bootstrap" ] && [ "$$BEST" != "$self" ]; then DOWN=1; elif [ "$$VERB" != "join" ]; then DOWN=0; fi
         |if [ "$$DOWN" != "1" ]; then echo "$self: nothing to start"; exit 0; fi
         |systemctl start mariadb || { echo 'starting mariadb failed' >&2; journalctl -xeu mariadb --no-pager -n 15 >&2; exit 1; }
         |echo "$self: started"
         |""".stripMargin

  private val verifyScript: String =
    verbOf() +
      """if [ "$VERB" != "bootstrap" ] && [ "$VERB" != "join" ]; then exit 0; fi
        |q() { mariadb -N -e "SHOW STATUS LIKE '$1'" 2>/dev/null | awk '{print $2}'; }
        |for i in $(seq 1 36); do
        |  if [ "$(q wsrep_cluster_size)" = "3" ] && [ "$(q wsrep_cluster_status)" = "Primary" ] && [ "$(q wsrep_ready)" = "ON" ]; then
        |    echo "healthy: size 3, Primary, ready"; exit 0
        |  fi
        |  sleep 5
        |done
        |echo "not healthy after 3 minutes: unit $(systemctl is-active mariadb) size=$(q wsrep_cluster_size) status=$(q wsrep_cluster_status) ready=$(q wsrep_ready)" >&2
        |exit 1
        |""".stripMargin

  private def inspectStage(host: String) =
    stage(s"inspect-$host", host)
      .task(s"read the Galera state of $host")(
        Task.RunCommand(
          List("sh", "-c", inspectScript),
          timeoutSeconds = 120,
          register = "galera_state"
        )
      )
      .build

  val playbook: ClusterPlaybook =
    clusterPlaybook("galera-cold-start")(
      (nodes.map(inspectStage) ++ List(
        stage("plan", "tst3")
          .task("decide what to do")(
            Task.RunCommand(
              List("sh", "-c", planScript),
              timeoutSeconds = 30,
              register = "galera_plan"
            )
          )
          .task("show the plan")(Task.Debug("{{{galera_plan}}}"))
          .task("stop if the plan is to abort")(
            Task.RunCommand(List("sh", "-c", abortScript), timeoutSeconds = 30)
          )
          .build
      ) ++ nodes.map(n =>
        stage(s"bootstrap-$n", n)
          .task(s"bootstrap on $n if chosen")(
            Task.RunCommand(
              List("sh", "-c", bootstrapScript(n)),
              timeoutSeconds = 300
            )
          )
          .build
      ) ++ nodes.map(n =>
        stage(s"join-$n", n)
          .task(s"start mariadb on $n if it is down")(
            Task.RunCommand(
              List("sh", "-c", joinScript(n)),
              timeoutSeconds = 600
            )
          )
          .build
      ) ++ nodes.map(n =>
        stage(s"verify-$n", n)
          .task(s"wait for $n to be healthy")(
            Task.RunCommand(
              List("sh", "-c", verifyScript),
              timeoutSeconds = 240
            )
          )
          .build
      ))*
    )
