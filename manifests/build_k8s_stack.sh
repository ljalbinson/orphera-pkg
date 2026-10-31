#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Master script: builds the whole Kubernetes cluster (tst30-tst39, with DNS on tst40 and tst41)
# and everything on it, in dependency order, and ends with the cluster test:
#
#   vms        (--with-vms)  provisions tst30-tst39 and tst40-tst41 (manifests/par-mk-new-and-empty.sh);
#                            they must NOT exist yet
#   dns        tst40,tst41   k8s_dns: BIND for k8s.ljalbinson.com (primary, secondary); first, because the
#                            cluster is reached as api.k8s.ljalbinson.com and the apps as *.k8s.ljalbinson.com
#   mirrors    tst9          k8s_registry_mirrors: registry.k8s.io, Docker Hub and ghcr.io caches
#   aptclients tst30-tst39   apt_cache_clients: use the apt cache on tst11 when it is up
#   nodeprep   tst30-tst39   k8s_node_prep: swap, sysctls, containerd, kubeadm packages
#   vip        tst30-tst32   k8s_api_vip: haproxy + keepalived, 10.10.3.20 (api.k8s.ljalbinson.com:8443)
#   cluster    tst30-tst39   k8s_cluster: kubeadm init and join, etcd on its own disk
#   cilium                   k8s_cilium: the network (nodes become Ready), Gateway API, L2 announcements
#   certmanager              k8s_cert_manager: ClusterIssuer orphera-ca (sub-CA signed on tst6)
#   cephcsi                  k8s_ceph_csi: StorageClass ceph-rbd (the Ceph cluster on tst0-tst2)
#   metrics                  k8s_metrics_server
#   oidc                     k8s_oidc: Keycloak sign-in (tst26), group roles, Secret encryption (restarts the API servers)
#   gateway                  k8s_gateway: Gateway `public` on 10.10.3.100, wildcard certificate
#   etcdbackup               k8s_etcd_backup: hourly snapshots to the object store on tst0
#   monitoring               k8s_monitoring: Prometheus, Alertmanager, Grafana
#   logging                  k8s_logging: Loki and Alloy
#   kubeadmconfig            k8s_kubeadm_config: kubeadm's records match the hand changes, control-plane metrics
#   externaldns              k8s_external_dns: route hostnames appear in the zone
#   argocd                   k8s_argocd: https://argocd.k8s.ljalbinson.com
#   podsecurity              k8s_pod_security: baseline enforced cluster-wide (restarts the API servers)
#   netpolicy                k8s_network_policy: the orphera-netpol helper, tested on throw-away namespaces
#   lockdown                 k8s_network_lockdown: default deny for external-dns, argocd, logging
#   lockdownmon              k8s_network_lockdown_monitoring: default deny for monitoring
#   test                     manifests/test_k8s.sh (13 checks), without applying anything
#
# It only APPLIES playbooks: nothing is torn down, so it is safe to re-run on a half-built or
# fully built cluster (every playbook is idempotent). The first failure stops the run and says
# how to resume.
#
# Prerequisites (this script cannot do them):
#   - the parent zone ljalbinson.com has A records for tst30-tst41, and the delegation
#       k8s IN NS tst40.ljalbinson.com.   k8s IN NS tst41.ljalbinson.com.
#     with no `api.k8s` or `*.k8s` records of its own; any lab resolver that uses `forwarders`
#     (dns2, dns3) has a forward zone for k8s.ljalbinson.com to 10.10.3.40 and 10.10.3.41
#     (see the comment in manifests/k8s_dns.scala). The delegation is checked after the dns step.
#   - the Ceph cluster (tst0-tst2) with its object gateway, Keycloak (tst26) and the test CA
#     (tst6) exist: built by build_openstack_stack.sh (ceph, rgw, keystone, keycloak), and the
#     Kolla cache and apt cache hosts tst9 and tst11 for the mirrors and caches.
#   - without --with-vms, the VMs exist with an agent each.
#
# Run from scala0, in orphera-pkg:
#
#   manifests/build_k8s_stack.sh [options]
#
#   --with-vms      provision the 12 VMs first (they must not exist yet)
#   --from STEP     start at STEP (vms dns mirrors aptclients nodeprep vip cluster cilium certmanager
#                   cephcsi metrics oidc gateway etcdbackup monitoring logging kubeadmconfig externaldns
#                   argocd podsecurity netpolicy lockdown lockdownmon test), for resuming after a failure
#   --only STEP     run just that one step
#   --list          print the plan and exit
#   -h, --help      this text
#
# Exit code: 0 if every step passed, 1 otherwise. A log of the whole run is kept in
# /tmp/build_k8s_stack.<timestamp>.log.
#
# Not run where this was written; the first run is the first test.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
. utils/orphera-sources.sh

ALL_STEPS=(vms dns mirrors aptclients nodeprep vip cluster cilium certmanager cephcsi metrics oidc gateway etcdbackup monitoring logging kubeadmconfig externaldns argocd podsecurity netpolicy lockdown lockdownmon test)
K8S_NODES="tst30 tst31 tst32 tst33 tst34 tst35 tst36 tst37 tst38 tst39"
DNS_NODES="tst40 tst41"
WITH_VMS=""
FROM=""
ONLY=""
LIST=""

while [ $# -gt 0 ]; do
  case "$1" in
    --with-vms) WITH_VMS=1 ;;
    --from) FROM="${2:-}"; shift ;;
    --only) ONLY="${2:-}"; shift ;;
    --list) LIST=1 ;;
    -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

valid_step() { local s; for s in "${ALL_STEPS[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }
[ -n "$FROM" ] && ! valid_step "$FROM" && { echo "unknown step for --from: $FROM" >&2; exit 2; }
[ -n "$ONLY" ] && ! valid_step "$ONLY" && { echo "unknown step for --only: $ONLY" >&2; exit 2; }
[ -n "$FROM" ] && [ -n "$ONLY" ] && { echo "use --from or --only, not both" >&2; exit 2; }

STEPS=()
started=""
for s in "${ALL_STEPS[@]}"; do
  if [ -n "$ONLY" ]; then [ "$s" = "$ONLY" ] && STEPS+=("$s"); continue; fi
  if [ -z "$FROM" ] || [ "$s" = "$FROM" ]; then started=1; fi
  [ -n "$started" ] || continue
  if [ "$s" = "vms" ] && [ -z "$WITH_VMS" ] && [ "$FROM" != "vms" ] && [ "$ONLY" != "vms" ]; then continue; fi
  STEPS+=("$s")
done

if [ -n "$LIST" ]; then
  echo "plan: ${STEPS[*]}"
  exit 0
fi

LOG="/tmp/build_k8s_stack.$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

PASS=0
FAIL=0
T0=$(date +%s)
KC="sudo kubectl --kubeconfig /etc/kubernetes/admin.conf"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$2" --nodes "$1" 2>&1; }

check() {  # check <description> <command...>   (command succeeds => PASS)
  local desc="$1"; shift
  if "$@"; then log "PASS: $desc"; PASS=$((PASS + 1)); else log "FAIL: $desc"; FAIL=$((FAIL + 1)); return 1; fi
}

contains() { printf '%s' "$2" | grep -qF -- "$1"; }

die() {
  log "STOPPED: $1"
  log "fix the problem, then resume with:  $0 --from $CURRENT_STEP"
  log "log: $LOG"
  exit 1
}

# playbook <file>: applies it; fails the run on a non-zero exit OR on the runner's failure markers.
playbook() {
  local f="$1" out rc
  log "applying $1"
  shift
  out=$(mktemp)
  orphera cluster-playbook "$f" "$@" 2>&1 | tee "$out"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ] || grep -qE "aborting remaining stages|Cluster playbook .* failed" "$out"; then
    rm -f "$out"
    die "$(basename "$f") did not complete"
  fi
  rm -f "$out"
}

# agents_answer <nodes...>: every node answers through its agent.
agents_answer() {
  local n out bad=""
  for n in "$@"; do
    out=$(orphera run hostname --nodes "$n" --quiet 2>&1)
    if contains "$n" "$out"; then log "  $n ok"; else log "  $n NOT answering: $out"; bad="$bad $n"; fi
  done
  [ -z "$bad" ] || die "no agent answer from:$bad (inventory entry, VM up, agent running?)"
}

preflight() {
  local nodes="tst0 tst6 tst9 tst11 tst26"
  case " ${STEPS[*]} " in *" vms "*) ;; *) nodes="$nodes $K8S_NODES $DNS_NODES" ;; esac
  log "preflight: every node this build depends on must answer through its agent"
  agents_answer $nodes
  log "preflight: tst26 (Keycloak) and tst6 (the test CA) are only needed from the steps oidc and certmanager"
}

# ---- steps ----------------------------------------------------------------

step_vms() {
  manifests/par-mk-new-and-empty.sh -q -j 5 $K8S_NODES || die "provisioning tst30-tst39 failed"
  manifests/par-mk-new-and-empty.sh -q -j 2 $DNS_NODES || die "provisioning tst40-tst41 failed"
  agents_answer $K8S_NODES $DNS_NODES
}

step_dns() {
  playbook k8s_dns.scala
  local a b
  a=$(dig +short +time=3 +tries=1 @192.168.1.70 fresh-build-check.k8s.ljalbinson.com 2>/dev/null)
  b=$(dig +short +time=3 +tries=1 @192.168.1.71 api.k8s.ljalbinson.com 2>/dev/null)
  if [ "$a" != "10.10.3.100" ] || [ "$b" != "10.10.3.20" ]; then
    die "the lab resolvers do not answer for k8s.ljalbinson.com (192.168.1.70 gave '$a', 192.168.1.71 gave '$b'): add the delegation (k NS tst40/tst41 and A records for them) to ljalbinson.com, remove api.k8s and *.k8s from it, and give resolvers that use forwarders a forward zone for k8s.ljalbinson.com to 10.10.3.40 and 10.10.3.41"
  fi
  log "  the lab resolvers answer for k8s.ljalbinson.com through the delegation"
}

step_mirrors()    { playbook k8s_registry_mirrors.scala; }
step_aptclients() { playbook apt_cache_clients.scala; }
step_nodeprep()   { playbook k8s_node_prep.scala; }
step_vip()        { playbook k8s_api_vip.scala; }

step_cluster() {
  playbook k8s_cluster.scala
  local out n
  out=$(run_on tst30 "$KC get nodes --no-headers")
  n=$(printf '%s\n' "$out" | grep -c '^tst')
  check "10 nodes have joined" test "$n" -eq 10
}

step_cilium() {
  playbook k8s_cilium.scala
  local out n
  out=$(run_on tst30 "$KC get nodes --no-headers")
  n=$(printf '%s\n' "$out" | grep -c ' Ready')
  check "10 nodes are Ready" test "$n" -eq 10
}

step_certmanager()   { playbook k8s_cert_manager.scala; }
step_cephcsi()       { playbook k8s_ceph_csi.scala; }
step_metrics()       { playbook k8s_metrics_server.scala; }
step_oidc()          { playbook k8s_oidc.scala; }
step_gateway()       { playbook k8s_gateway.scala; }
step_etcdbackup()    { playbook k8s_etcd_backup.scala; }
step_monitoring()    { playbook k8s_monitoring.scala; }
step_logging()       { playbook k8s_logging.scala; }
step_kubeadmconfig() { playbook k8s_kubeadm_config.scala; }
step_externaldns()   { playbook k8s_external_dns.scala; }
step_argocd()        { playbook k8s_argocd.scala; }
step_podsecurity()   { playbook k8s_pod_security.scala; }
step_netpolicy()     { playbook k8s_network_policy.scala; }
step_lockdown()      { playbook k8s_network_lockdown.scala; }
step_lockdownmon()   { playbook k8s_network_lockdown_monitoring.scala; }

step_test() {
  NO_APPLY=1 manifests/test_k8s.sh
  local rc=$?
  check "manifests/test_k8s.sh passes" test "$rc" -eq 0
}

# ---- run ------------------------------------------------------------------

log "=== Kubernetes stack build: ${STEPS[*]} ==="
log "log file: $LOG"
CURRENT_STEP="${STEPS[0]:-dns}"
[ "${#STEPS[@]}" -gt 0 ] || { echo "nothing to do"; exit 0; }
preflight

for CURRENT_STEP in "${STEPS[@]}"; do
  S0=$(date +%s)
  log "--- step: $CURRENT_STEP ---"
  "step_$CURRENT_STEP"
  [ "$FAIL" -eq 0 ] || die "a check failed in step $CURRENT_STEP"
  log "step $CURRENT_STEP done in $(( $(date +%s) - S0 ))s"
done

log "=== finished in $(( $(date +%s) - T0 ))s: passed=$PASS failed=$FAIL ==="
if [ "$FAIL" -eq 0 ]; then
  log "done. Argo CD: https://argocd.k8s.ljalbinson.com  Grafana: https://grafana.k8s.ljalbinson.com  (sign in with Keycloak, realm k8s; kubectl: ssh localadmin@tst30, sudo kubectl --kubeconfig /etc/kubernetes/admin.conf ...)"
fi
[ "$FAIL" -eq 0 ]
