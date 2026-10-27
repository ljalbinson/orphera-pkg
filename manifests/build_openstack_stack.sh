#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Master script: builds the whole OpenStack stack (control plane and compute nodes) from
# freshly provisioned VMs that only have the Orphera agent, in dependency order, and ends
# by booting a real instance:
#
#   prep                                turn off unattended-upgrades on the nodes
#                                       (disable_unattended_upgrades.scala)
#   caches   (optional, --with-caches)  apt cache (tst11) + its clients, Kolla
#                                       image cache (tst9): speeds up the rest
#   ceph     tst0-tst2   cephadm_install, cephadm_add_mons, cephadm_add_osds
#   galera   tst3-tst5   mariadb_galera_cluster, mariadb_haproxy_keepalived
#                        (VIP 10.10.5.100)
#   keystone tst6        keystone_single_node
#   cinder   tst10       cinder_single_node (pool `volumes`)
#   glance   tst13       glance_single_node (pool `images`)
#   placement tst13      placement_single_node (port 8780, shares tst13 with Glance)
#   nova     tst14       nova_single_node (API, scheduler, conductor, noVNC proxy; RabbitMQ
#                        vhost on tst10)
#   neutron  tst19       neutron_single_node (server, Open vSwitch + DHCP agents, flat provider
#                        network on tst19's second NIC; its address is removed)
#   compute  tst17,tst18 nova_compute (nova-compute on KVM + Neutron Open vSwitch agent, config drive;
#                        the second NIC becomes the provider port, its address is removed)
#   horizon  tst13       horizon_single_node (the dashboard, http://tst13:8080/)
#   heat     tst20       heat_single_node (orchestration: API, CloudFormation API, engine; RabbitMQ vhost on tst10)
#   barbican tst20       barbican_single_node (key manager: API and worker, on the Heat node; RabbitMQ vhost on tst10)
#   manila   tst22       manila_single_node (shared file systems: API, scheduler, share service on CephFS over NFS;
#                        also makes the CephFS filesystem and the NFS cluster on tst0; RabbitMQ vhost on tst10)
#   rgw      tst0        rgw_single_node (object storage: the Ceph RADOS Gateway with the Swift and S3 APIs,
#                        authenticated by Keystone; a cephadm service, no new VM)
#   octavia  tst19       octavia_single_node (load balancing: API, worker, health manager, housekeeping with the
#                        Amphora provider; makes the amphora network, flavor and test image, a port for the
#                        health manager on br-int, and certificates; RabbitMQ vhost on tst10)
#   designate tst16      designate_single_node (DNS as a service: API, central, worker, producer, mdns and
#                        a BIND9 name server; RabbitMQ vhost on tst10)
#   clients  scala0      utils/openstack-client-setup.sh
#   smoke                a real volume, image, resource provider, flavor and provider network
#                        created and deleted
#   boot     tst17,tst18 a CirrOS instance booted on KVM with a DHCP lease, then deleted
#                        (needs internet on this machine for the image, or CIRROS_IMAGE=/path)
#
# It only APPLIES playbooks: nothing is torn down, so it is safe to re-run on
# a half-built or fully built stack (every playbook is idempotent) and it will
# not wipe a database. Each step is checked independently of the playbook's
# own health check before the next one starts; the first failure stops the run
# and says how to resume.
#
# Prerequisites: the VMs exist with an agent each (kvm_*_vm_provision.scala),
# `orphera` works from here, the inventory has the nodes, and inventory.yaml
# names the OSD disks for tst0-tst2. Run from scala0, in orphera-pkg:
#
#   manifests/build_openstack_stack.sh [options]
#
#   --with-caches   build the apt cache, point the Ubuntu nodes at it, and
#                   build the Kolla image cache first (tst9, tst11 required)
#   --from STEP     start at STEP (prep caches ceph galera keystone cinder glance
#                   placement nova neutron compute horizon designate heat barbican manila rgw octavia clients smoke boot) - for resuming
#                   after a failure
#   --only STEP     run just that one step
#   --list          print the plan and exit
#   -h, --help      this text
#
# Exit code: 0 if every step passed, 1 otherwise. A log of the whole run is
# kept in /tmp/build_openstack_stack.<timestamp>.log.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
. utils/orphera-sources.sh
CONF_DIR=$(config_dir) || exit 1

ALL_STEPS=(prep caches ceph galera keystone cinder glance placement nova neutron compute horizon designate heat barbican manila rgw octavia clients smoke boot)
WITH_CACHES=""
FROM=""
ONLY=""
LIST=""

while [ $# -gt 0 ]; do
  case "$1" in
    --with-caches) WITH_CACHES=1 ;;
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

# The steps this run will execute, in order.
STEPS=()
started=""
for s in "${ALL_STEPS[@]}"; do
  if [ -n "$ONLY" ]; then [ "$s" = "$ONLY" ] && STEPS+=("$s"); continue; fi
  if [ -z "$FROM" ] || [ "$s" = "$FROM" ]; then started=1; fi
  [ -n "$started" ] || continue
  if [ "$s" = "caches" ] && [ -z "$WITH_CACHES" ] && [ "$FROM" != "caches" ] && [ "$ONLY" != "caches" ]; then continue; fi
  STEPS+=("$s")
done

if [ -n "$LIST" ]; then
  echo "plan: ${STEPS[*]}"
  exit 0
fi

LOG="/tmp/build_openstack_stack.$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

PASS=0
FAIL=0
T0=$(date +%s)

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$2" --nodes "$1" 2>&1; }
ceph_sh() { run_on tst0 "sudo cephadm shell -- $1"; }

check() {  # check <description> <command...>   (command succeeds => PASS)
  local desc="$1"; shift
  if "$@"; then log "PASS: $desc"; PASS=$((PASS + 1)); else log "FAIL: $desc"; FAIL=$((FAIL + 1)); return 1; fi
}

contains() {  # contains <needle> <haystack>
  printf '%s' "$2" | grep -qF -- "$1"
}

matches() {  # matches <extended regex> <haystack>
  printf '%s' "$2" | grep -qE -- "$1"
}

# in_quorum <ceph quorum_status json> <node>: node is listed in quorum_names.
in_quorum() {
  printf '%s' "$1" | tr -d ' \n' | grep -o '"quorum_names":\[[^]]*\]' | grep -q "\"$2\""
}

die() {
  log "STOPPED: $1"
  log "fix the problem, then resume with:  $0 --from $CURRENT_STEP${WITH_CACHES:+ --with-caches}"
  log "log: $LOG"
  exit 1
}

# playbook <file>: applies it; fails the run on a non-zero exit OR on the
# runner's failure markers (older builds were seen exiting 0 after a failed
# stage, so both are checked).
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

# ---- preflight ------------------------------------------------------------

preflight() {
  local nodes="tst0 tst1 tst2 tst3 tst4 tst5 tst6 tst10 tst13 tst14 tst16 tst17 tst18 tst19 tst20 tst22" n out bad="" hv
  if [ -n "$WITH_CACHES" ]; then nodes="$nodes tst9 tst11"; fi
  log "preflight: every node must answer through its agent"
  for n in $nodes; do
    out=$(orphera run hostname --nodes "$n" --quiet 2>&1)
    if contains "$n" "$out"; then log "  $n ok"; else log "  $n NOT answering: $out"; bad="$bad $n"; fi
  done
  if [ -n "$bad" ]; then
    for n in $bad; do
      hv=$(awk -F: '/^[[:space:]]*hypervisor:/ {gsub(/[[:space:]"'"'"']/, "", $2); print $2; exit}' "$CONF_DIR/$n.yaml" 2>/dev/null)
      [ -n "$hv" ] && log "  start $n:  orphera run sh -c \"virsh start $n\" --nodes $hv"
    done
    die "no agent answer from:$bad (inventory entry, VM up, agent running?)"
  fi
  # After a power-off of the whole fleet MariaDB is down on all three Galera nodes and every
  # OpenStack service fails with 500s. This restarts the cluster from the node with the newest
  # data; it does nothing when Galera is healthy or not built yet.
  log "preflight: the Galera cluster must be up (recovered here after a full stop)"
  utils/galera-cold-start.sh || die "the Galera cluster is down and could not be recovered automatically (see above; utils/galera-cold-start.sh --help)"
}

# ---- steps ----------------------------------------------------------------

# prep covers only the nodes this build uses, the same list the preflight checks (tst7, tst9,
# tst11 and tst12 are not part of the stack; tst9 and tst11 count only with --with-caches).
step_prep() {
  local nodes="tst0 tst1 tst2 tst3 tst4 tst5 tst6 tst10 tst13 tst14 tst16 tst17 tst18 tst19 tst20 tst22" cfg
  if [ -n "$WITH_CACHES" ]; then nodes="$nodes tst9 tst11"; fi
  cfg=$(mktemp --suffix=.yaml)
  echo "nodes: [$(echo $nodes | sed 's/ /, /g')]" > "$cfg"
  playbook disable_unattended_upgrades.scala --config "$cfg"
  rm -f "$cfg"
}

step_caches() {
  playbook apt_cache.scala
  playbook apt_cache_clients.scala
  playbook kolla_cache.scala
  out=$(run_on tst11 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:3142/acng-report.html")
  check "apt cache answers on tst11" contains "http=200" "$out"
  out=$(run_on tst9 "curl -s http://localhost:5000/v2/")
  check "Kolla registry answers on tst9" contains "{}" "$out"
}

step_ceph() {
  playbook cephadm_install.scala
  playbook cephadm_add_mons.scala
  playbook cephadm_add_osds.scala
  local out
  out=$(ceph_sh "ceph quorum_status -f json")
  check "tst0 is in the monitor quorum" in_quorum "$out" tst0
  check "tst1 is in the monitor quorum" in_quorum "$out" tst1
  check "tst2 is in the monitor quorum" in_quorum "$out" tst2
  out=$(ceph_sh "ceph osd stat")
  log "  $(printf '%s' "$out" | grep -E '[0-9]+ osds:' | head -1)"
  check "every OSD is up and in (and there is at least one)" matches '([1-9][0-9]*) osds: \1 up.* \1 in' "$out"
  out=$(ceph_sh "ceph health")
  log "  ceph health: $(printf '%s' "$out" | grep -E 'HEALTH_' | head -1)"
}

step_galera() {
  playbook mariadb_galera_cluster.scala
  playbook mariadb_haproxy_keepalived.scala
  local n out
  for n in tst3 tst4 tst5; do
    out=$(run_on "$n" "sudo mariadb -N -e \"SHOW STATUS LIKE 'wsrep_cluster_size'\"")
    check "$n sees a 3-node Galera cluster" matches 'wsrep_cluster_size[[:space:]]+3' "$out"
  done
  # Only the port is checked: the VIP serves users with a host grant.
  out=$(run_on tst3 "timeout 5 bash -c '</dev/tcp/10.10.5.100/3306' && echo vip-open")
  check "the VIP 10.10.5.100 accepts connections on 3306" contains "vip-open" "$out"
}

step_keystone() {
  playbook keystone_single_node.scala
  local out
  out=$(run_on tst6 "curl -s --cacert /etc/kolla/keystone/ssl/ca.crt https://tst6.ljalbinson.com:5000/v3")
  check "Keystone answers a version document over TLS" contains '"version"' "$out"
}

step_cinder() {
  playbook cinder_single_node.scala
  local out
  out=$(run_on tst10 "sudo /usr/local/sbin/orphera-openstack-api volume-services")
  check "cinder-scheduler and cinder-volume are up" contains "state=up" "$out"
}

step_glance() {
  playbook glance_single_node.scala
  local out
  out=$(run_on tst13 "sudo /usr/local/sbin/orphera-glance-api image-list")
  check "Glance lists images through Keystone" contains "images:" "$out"
}

step_placement() {
  playbook placement_single_node.scala
  local out
  out=$(run_on tst13 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:8780/")
  check "Placement answers its version document" contains "http=200" "$out"
  out=$(run_on tst13 "sudo /usr/local/sbin/orphera-placement-api rp-list")
  check "Placement lists resource providers with an admin token" contains "resource providers:" "$out"
}

step_nova() {
  playbook nova_single_node.scala
  local out
  out=$(run_on tst14 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:8774/")
  check "the Nova API answers its version document" contains "http=" "$out"
  out=$(run_on tst14 "sudo /usr/local/sbin/orphera-nova-api services-up")
  check "nova-scheduler and nova-conductor are up" contains "are up" "$out"
}

step_neutron() {
  playbook neutron_single_node.scala
  local out
  out=$(run_on tst19 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9696/")
  check "the Neutron API answers its version document" contains "http=" "$out"
  out=$(run_on tst19 "sudo /usr/local/sbin/orphera-neutron-api agents-up")
  check "the Open vSwitch and DHCP agents are alive" contains "are alive" "$out"
}

step_compute() {
  playbook nova_compute.scala
  local out
  out=$(run_on tst17 "sudo /usr/local/sbin/orphera-compute-api compute-up tst17 tst18")
  check "nova-compute is up on tst17 and tst18" contains "nova-compute is up on tst17, tst18" "$out"
  out=$(run_on tst17 "sudo /usr/local/sbin/orphera-compute-api ovs-up tst17 tst18")
  check "the Open vSwitch agents on tst17 and tst18 are alive" contains "alive on tst17, tst18" "$out"
}

step_horizon() {
  playbook horizon_single_node.scala
  local out
  out=$(run_on tst13 "sudo /usr/local/sbin/orphera-horizon-check login")
  check "admin can sign in to the dashboard" contains "login ok as admin" "$out"
}

step_designate() {
  playbook designate_single_node.scala
  local out
  out=$(run_on tst16 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9001/")
  check "the Designate API answers its version document" contains "http=" "$out"
  out=$(run_on tst16 "sudo /usr/local/sbin/orphera-designate-api zone-cycle")
  check "a zone made through the API is served by BIND9 and deleted again" contains "zone cycle ok" "$out"
}

step_heat() {
  playbook heat_single_node.scala
  local out
  out=$(run_on tst20 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:8004/")
  check "the Heat API answers its version document" contains "http=" "$out"
  out=$(run_on tst20 "sudo /usr/local/sbin/orphera-heat-api engines-up")
  check "heat-engine reports in through the API" contains "heat-engine is up" "$out"
  out=$(run_on tst20 "sudo /usr/local/sbin/orphera-heat-api stack-cycle")
  check "a stack that makes a Nova flavor is created and deleted" contains "stack cycle ok" "$out"
}

step_barbican() {
  playbook barbican_single_node.scala
  local out
  out=$(run_on tst20 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9311/")
  check "the Barbican API answers its version document" contains "http=" "$out"
  out=$(run_on tst20 "sudo /usr/local/sbin/orphera-barbican-api secret-cycle")
  check "a secret is stored, read back and deleted" contains "secret cycle ok" "$out"
  out=$(run_on tst20 "sudo /usr/local/sbin/orphera-barbican-api order-cycle")
  check "the worker makes a key from an order" contains "order cycle ok" "$out"
}

step_manila() {
  playbook manila_single_node.scala
  local out
  out=$(run_on tst22 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:8786/")
  check "the Manila API answers its version document" contains "http=" "$out"
  out=$(run_on tst22 "sudo /usr/local/sbin/orphera-manila-api services")
  check "the Manila scheduler and share service are listed" contains "services:" "$out"
  out=$(run_on tst22 "sudo /usr/local/sbin/orphera-manila-api share-cycle")
  check "a share is made, mounted over NFS, written and deleted" contains "share cycle ok" "$out"
}

step_rgw() {
  playbook rgw_single_node.scala
  local out
  out=$(run_on tst0 "curl -s -o /dev/null -w 'http=%{http_code}' http://10.10.5.12:8080/")
  check "RGW answers on 8080" contains "http=" "$out"
  out=$(run_on tst0 "sudo /usr/local/sbin/orphera-rgw-api swift-cycle")
  check "an object is made through the Swift API with a Keystone token" contains "swift cycle ok" "$out"
  out=$(run_on tst0 "sudo /usr/local/sbin/orphera-rgw-api s3-cycle")
  check "an object is made through the S3 API with EC2 credentials" contains "s3 cycle ok" "$out"
}

step_octavia() {
  playbook octavia_single_node.scala
  local out
  out=$(run_on tst19 "curl -s -o /dev/null -w 'http=%{http_code}' http://localhost:9876/")
  check "the Octavia API answers its version document" contains "http=200" "$out"
  out=$(run_on tst19 "sudo /usr/local/sbin/orphera-octavia-api providers")
  check "the amphora provider is listed" contains "providers: amphora" "$out"
  out=$(orphera run sh -c "sudo /usr/local/sbin/orphera-octavia-api lb-cycle" --nodes tst19 --timeout 1800 2>&1)
  check "a load balancer answers an HTTP request through its amphora" contains "lb cycle ok" "$out"
}

step_clients() {
  log "setting up the OpenStack clients on this machine"
  if utils/openstack-client-setup.sh; then log "PASS: clients set up"; PASS=$((PASS + 1)); else die "utils/openstack-client-setup.sh failed"; fi
}

step_smoke() {
  local out id
  out=$(run_on tst10 "sudo /usr/local/sbin/orphera-openstack-api volume-create")
  id=$(printf '%s\n' "$out" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
  if [ -n "$id" ]; then
    out=$(ceph_sh "rbd ls volumes")
    check "a Cinder volume exists as an RBD image" contains "volume-$id" "$out"
    run_on tst10 "sudo /usr/local/sbin/orphera-openstack-api volume-delete $id" >/dev/null
  else
    log "FAIL: volume create returned no id"; FAIL=$((FAIL + 1))
  fi
  out=$(run_on tst13 "sudo /usr/local/sbin/orphera-glance-api image-create")
  id=$(printf '%s\n' "$out" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
  if [ -n "$id" ]; then
    out=$(ceph_sh "rbd ls images")
    check "a Glance image exists as an RBD image" contains "$id" "$out"
    run_on tst13 "sudo /usr/local/sbin/orphera-glance-api image-delete $id" >/dev/null
  else
    log "FAIL: image create returned no id"; FAIL=$((FAIL + 1))
  fi
  out=$(run_on tst13 "sudo /usr/local/sbin/orphera-placement-api rp-create")
  id=$(printf '%s\n' "$out" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
  if [ -n "$id" ]; then
    out=$(run_on tst13 "sudo /usr/local/sbin/orphera-placement-api rp-delete $id")
    check "a Placement resource provider was created and deleted" contains "deleted $id" "$out"
  else
    log "FAIL: resource provider create returned no id"; FAIL=$((FAIL + 1))
  fi
  out=$(run_on tst14 "sudo /usr/local/sbin/orphera-nova-api flavor-create")
  id=$(printf '%s\n' "$out" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|^[0-9]+$' | tail -1)
  if [ -n "$id" ]; then
    out=$(run_on tst14 "sudo /usr/local/sbin/orphera-nova-api flavor-delete $id")
    check "a Nova flavor was created and deleted" contains "deleted $id" "$out"
  else
    log "FAIL: flavor create returned no id"; FAIL=$((FAIL + 1))
  fi
  out=$(run_on tst19 "sudo /usr/local/sbin/orphera-neutron-api net-create")
  id=$(printf '%s\n' "$out" | grep -Eo '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | tail -1)
  if [ -z "$id" ] && contains "already exists" "$out"; then
    log "SKIP: a flat network on the physical network provider already exists (only one is allowed): the network create/delete check is skipped"
  elif [ -n "$id" ]; then
    out=$(run_on tst19 "sudo /usr/local/sbin/orphera-neutron-api net-delete $id")
    check "a Neutron provider network was created and deleted" contains "deleted $id" "$out"
  else
    log "FAIL: network create returned no id: $out"; FAIL=$((FAIL + 1))
  fi
}

# End to end: a real instance. Uploads CirrOS to Glance, boots it on a temporary flat network
# with DHCP (10.10.6.0/24, pool .200-.220), waits for the login prompt and a lease on the
# console, and deletes everything again (the helper on tst17 does the work).
step_boot() {
  local img="${CIRROS_IMAGE:-/tmp/cirros-0.6.2-x86_64-disk.img}" out ready g_ip g_mac g_net chk
  if [ ! -s "$img" ]; then
    log "downloading CirrOS to $img"
    curl -fL -o "$img" "https://download.cirros-cloud.net/0.6.2/cirros-0.6.2-x86_64-disk.img" || { rm -f "$img"; die "could not download CirrOS (set CIRROS_IMAGE=/path/to/cirros.qcow2)"; }
  fi
  out=$(run_on tst19 "sudo /usr/local/sbin/orphera-neutron-api net-list")
  if contains "orphera-boot-test" "$out"; then run_on tst17 "sudo /usr/local/sbin/orphera-compute-api boot-cleanup" >/dev/null; fi
  if ! orphera copy "$img" /root/cirros-boot-test.qcow2 --owner root --group root --mode 0644 --nodes tst17 >/dev/null 2>&1; then
    die "could not copy the CirrOS image to tst17"
  fi
  out=$(orphera run sh -c "sudo /usr/local/sbin/orphera-compute-api boot-test /root/cirros-boot-test.qcow2 --keep" --nodes tst17 --timeout 900 2>&1)
  run_on tst17 "rm -f /root/cirros-boot-test.qcow2" >/dev/null
  if ! contains "instance ready:" "$out"; then
    printf '%s\n' "$out" | tail -n 25 | sed 's/^/    /'
    run_on tst17 "sudo /usr/local/sbin/orphera-compute-api boot-cleanup" >/dev/null
    log "FAIL: the CirrOS boot test (the instance did not boot or its port did not go ACTIVE)"; FAIL=$((FAIL + 1)); return 0
  fi
  ready=$(printf '%s\n' "$out" | grep -o 'instance ready: .*' | head -n 1)
  g_ip=$(printf '%s' "$ready" | sed -n 's/.*ip=\([^ ]*\).*/\1/p')
  g_mac=$(printf '%s' "$ready" | sed -n 's/.*mac=\([^ ]*\).*/\1/p')
  g_net=$(printf '%s' "$ready" | sed -n 's/.*network=\([^ ]*\).*/\1/p')
  chk=$(utils/check-guest-dhcp.sh "$g_ip" "$g_mac" "$g_net")
  run_on tst17 "sudo /usr/local/sbin/orphera-compute-api boot-cleanup" >/dev/null
  if contains "answers ARP" "$chk"; then
    log "PASS: an instance booted on KVM, took DHCP address $g_ip and was deleted"; PASS=$((PASS + 1))
  else
    printf '%s\n' "$chk" | sed 's/^/    /'
    log "FAIL: the CirrOS guest did not take its DHCP address"; FAIL=$((FAIL + 1))
  fi
}

# ---- run ------------------------------------------------------------------

log "=== OpenStack stack build: ${STEPS[*]} ==="
log "log file: $LOG"
CURRENT_STEP="${STEPS[0]:-ceph}"
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
  log "done. Dashboard: http://tst13.ljalbinson.com:8080/ (admin, domain Default). CLI: source ~/.config/openstack/admin-openrc.sh (see README)"
fi
[ "$FAIL" -eq 0 ]
