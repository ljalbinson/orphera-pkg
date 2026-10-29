#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Moves a VM from one hypervisor to another when it was provisioned on ZFS
# (manifests/kvm_zfs_vm_provision.scala): every file of the VM lives in one dataset,
# tank/kvm/<hypervisor>/<vm>, mounted at /exports/kvm/<hypervisor>/<vm>. A cold move:
#
#   1. checks both hypervisors (the domain and dataset, disks only inside the dataset, no snapshots, no
#      passthrough devices, the bridges on the destination, free space and memory, the CPU vendor, a free port)
#   2. shuts the guest down (utils/stop-vm.sh), takes a ZFS snapshot
#   3. sends the snapshot to the destination (zfs send | nc | zfs receive, over a port that is open only
#      while it runs) into tank/kvm/<destination>/<vm>, sets its mountpoint and checks that the file names and
#      sizes match the source
#   4. defines the domain on the destination (the XML of the source with the dataset path changed; same UUID,
#      MACs and addresses), copies the autostart setting, and undefines it on the source
#   5. changes `hypervisor:` in the VM's config file, starts the VM on the destination if it was running, and
#      waits for its Orphera agent to answer
#
#   utils/move-vm.sh tst17 gs3                 # asks before it starts
#   utils/move-vm.sh --dry-run tst17 gs3       # only the checks and the plan
#   utils/move-vm.sh --yes --delete-source tst17 gs3
#
#   --dry-run          run the checks, print the plan, change nothing
#   --yes              do not ask for confirmation (needed when not run from a terminal)
#   --delete-source    after a successful move destroy the source dataset (and its snapshot); without it the
#                      source copy is kept and the command to remove it is printed
#   --no-start         leave the VM shut off on the destination even if it was running
#   --force-stop       power the guest off if it has not shut down in time (stop-vm.sh --force)
#   --stop-wait SEC    how long to wait for the guest to shut down (default 180)
#   --boot-wait SEC    how long to wait for the agent in the guest after the start (default 300)
#   --transfer-timeout SEC   limit for the send and receive (default 7200)
#   --port N           TCP port for the transfer (default: random in 29000-29999)
#
# If a step fails before the source domain is undefined, the script removes what it made on the destination,
# deletes the snapshot and starts the VM on the source again if it had been running. After that point it only
# says what state things are in. The original domain XML is saved in $TMPDIR (default /tmp) as
# move-vm-<vm>-<source>.xml.
#
# Run from scala0, in orphera-pkg. It needs `orphera`, an agent on both hypervisors and on the guest, ZFS
# (pool `tank`) and nc (the OpenBSD netcat, as on Ubuntu) on both hypervisors. The stream is not encrypted: use it on
# the lab network. A running VM is shut down, not live-migrated, so the services on it are down for the time
# it takes (about the time to copy the dataset). Moving a node of a cluster (Ceph, Galera) is your
# decision: nothing here checks the cluster.
#
# Exit code 0 if the VM is on the destination and answered (or was left shut off as asked), else 1.

set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/vm-common.sh"

DRY=0; YES=0; DELETE_SRC=0; NO_START=0; FORCE_STOP=0
STOP_WAIT=180; BOOT_WAIT=300; XFER_TIMEOUT=7200; PORT=""

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }
num() { case "$2" in ''|*[!0-9]*) echo "$1 needs a number" >&2; exit 2 ;; esac; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --yes) YES=1; shift ;;
    --delete-source) DELETE_SRC=1; shift ;;
    --no-start) NO_START=1; shift ;;
    --force-stop) FORCE_STOP=1; shift ;;
    --stop-wait) STOP_WAIT=${2:-}; num --stop-wait "$STOP_WAIT"; shift 2 ;;
    --boot-wait) BOOT_WAIT=${2:-}; num --boot-wait "$BOOT_WAIT"; shift 2 ;;
    --transfer-timeout) XFER_TIMEOUT=${2:-}; num --transfer-timeout "$XFER_TIMEOUT"; shift 2 ;;
    --port) PORT=${2:-}; num --port "$PORT"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ $# -eq 2 ] || { usage; exit 2; }

vm_lookup "$1" || exit 1
src=$hv
dst=$2
case "$dst" in ''|*[!A-Za-z0-9._-]*) echo "$dst: not a plain hypervisor name" >&2; exit 2 ;; esac
[ "$dst" != "$src" ] || { echo "$vm is already on $src" >&2; exit 1; }
[ -z "$PORT" ] && PORT=$((29000 + RANDOM % 1000))

FS_SRC="tank/kvm/$src/$vm"
FS_DST="tank/kvm/$dst/$vm"
DIR_SRC="/exports/kvm/$src/$vm"
DIR_DST="/exports/kvm/$dst/$vm"
STAMP=$(date +%Y%m%d-%H%M%S)
SNAP="$FS_SRC@move-$STAMP"
XML_BACKUP="${TMPDIR:-/tmp}/move-vm-$vm-$src.xml"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# field <key>  value of a DATA|<key>|<value> line in the output of the last vm_run
field() { printf '%s\n' "$out" | sed -n "s/^\[[^]]*\] *DATA|$1|//p; s/^DATA|$1|//p" | head -1; }

# step <hypervisor> <script> [timeout]  runs it; on anything but RESULT|ok prints the failure and returns 1
step() {
  vm_run "$1" "$2" "${3:-120}"
  case "$result" in
    ok\|*) return 0 ;;
    error\|*) echo "  FAILED on $1: ${result#error|}" >&2; return 1 ;;
    *) echo "  FAILED: no answer from $1" >&2; printf '%s\n' "$out" | tail -3 >&2; return 1 ;;
  esac
}

# The variables every remote script reads. All values are plain names, numbers or base64.
pre() {
  printf "VM='%s'; SRC='%s'; DST='%s'; PORT='%s'; SNAP='%s'; BRIDGES='%s'; AUTOSTART='%s'; XML_B64='%s'\n" \
    "$vm" "$src" "$dst" "$PORT" "$SNAP" "${bridges:-}" "${autostart:-}" "${xml_b64:-}"
}

# ---- the remote scripts -------------------------------------------------------------------------------------------

src_check_body() {
  cat <<'SH'
V="virsh --connect qemu:///system"
fail() { echo "RESULT|error|$1"; exit 0; }
FS="tank/kvm/$SRC/$VM"; DIR="/exports/kvm/$SRC/$VM"
$V dominfo "$VM" >/dev/null 2>&1 || fail "domain $VM is not defined on this hypervisor"
zfs list -H -o name "$FS" >/dev/null 2>&1 || fail "no ZFS dataset $FS (was the VM provisioned on ZFS?)"
MP=$(zfs get -H -o value mountpoint "$FS")
[ "$MP" = "$DIR" ] || fail "dataset $FS has mountpoint $MP, expected $DIR"
[ "$(zfs list -H -o name -r -d 1 "$FS" | wc -l)" -eq 1 ] || fail "dataset $FS has child datasets; not handled"
command -v nc >/dev/null 2>&1 || fail "nc is not installed on this hypervisor"
XML=$($V dumpxml --inactive "$VM")
BAD=$(printf '%s\n' "$XML" | grep -o "<source file='[^']*'" | sed "s/<source file='//; s/'\$//" | grep -v "^$DIR/" || true)
[ -z "$BAD" ] || fail "disk files outside $DIR: $BAD"
printf '%s\n' "$XML" | grep -q "<disk type='block'" && fail "the domain has a block-device disk; not handled"
printf '%s\n' "$XML" | grep -q '<hostdev' && fail "the domain has a passthrough device; not handled"
printf '%s\n' "$XML" | grep -q '<nvram' && fail "the domain has an NVRAM file (UEFI); not handled"
$V snapshot-list --name "$VM" 2>/dev/null | grep -q . && fail "the domain has libvirt snapshots; delete them first"
echo "DATA|state|$($V domstate "$VM" | head -1)"
echo "DATA|used|$(zfs get -Hp -o value used "$FS")"
echo "DATA|mem|$($V dominfo "$VM" | awk '/^Max memory/{printf "%d", $3/1024}')"
echo "DATA|autostart|$($V dominfo "$VM" | awk '/^Autostart/{print $2}')"
echo "DATA|vendor|$(awk '/vendor_id/{print $3; exit}' /proc/cpuinfo)"
echo "DATA|bridges|$(printf '%s\n' "$XML" | grep -o "<source bridge='[^']*'" | sed "s/<source bridge='//; s/'\$//" | sort -u | tr '\n' ' ')"
echo "RESULT|ok|domain $VM and dataset $FS look movable"
SH
}

dst_check_body() {
  cat <<'SH'
V="virsh --connect qemu:///system"
fail() { echo "RESULT|error|$1"; exit 0; }
$V list --all --name >/tmp/move-vm.names 2>&1 || fail "libvirt does not answer: $(cat /tmp/move-vm.names)"
grep -qx "$VM" /tmp/move-vm.names && fail "a domain named $VM already exists on this hypervisor"
rm -f /tmp/move-vm.names
zpool list -H tank >/dev/null 2>&1 || fail "no ZFS pool named tank"
zfs list -H -o name "tank/kvm/$DST/$VM" >/dev/null 2>&1 && fail "dataset tank/kvm/$DST/$VM already exists"
command -v nc >/dev/null 2>&1 || fail "nc is not installed on this hypervisor"
ss -ltn 2>/dev/null | grep -q ":$PORT " && fail "port $PORT is in use"
for b in $BRIDGES; do
  ip link show "$b" >/dev/null 2>&1 || ovs-vsctl br-exists "$b" 2>/dev/null || fail "bridge $b does not exist on this hypervisor"
done
echo "DATA|avail|$(zfs get -Hp -o value available tank)"
echo "DATA|memavail|$(awk '/^MemAvailable/{printf "%d", $2/1024}' /proc/meminfo)"
echo "DATA|vendor|$(awk '/vendor_id/{print $3; exit}' /proc/cpuinfo)"
echo "RESULT|ok|destination is ready"
SH
}

snapshot_body() {
  cat <<'SH'
zfs snapshot "$SNAP" || { echo "RESULT|error|zfs snapshot $SNAP failed"; exit 0; }
echo "RESULT|ok|snapshot $SNAP"
SH
}

listen_body() {
  cat <<'SH'
zfs create -p "tank/kvm/$DST" 2>/dev/null || true
if nc -l "$PORT" | zfs receive -u "tank/kvm/$DST/$VM"; then
  DIR="/exports/kvm/$DST/$VM"
  [ "$(zfs get -H -o value mountpoint "tank/kvm/$DST/$VM")" = "$DIR" ] || zfs set mountpoint="$DIR" "tank/kvm/$DST/$VM"
  zfs mount "tank/kvm/$DST/$VM" 2>/dev/null || true
  mountpoint -q "$DIR" || { echo "RESULT|error|received, but $DIR is not mounted"; exit 0; }
  echo "RESULT|ok|received into tank/kvm/$DST/$VM, mounted at $DIR"
else
  echo "RESULT|error|nc | zfs receive failed"
fi
SH
}

listening_body() {
  cat <<'SH'
if ss -ltn 2>/dev/null | grep -q ":$PORT "; then echo "RESULT|ok|listening"; else echo "RESULT|error|not listening yet"; fi
SH
}

send_body() {
  cat <<'SH'
NCOPT=""
nc -h 2>&1 | grep -q -e ' -N' && NCOPT="-N"
DSTIP="$DST_ADDR"
if zfs send "$SNAP" | nc $NCOPT "$DSTIP" "$PORT"; then echo "RESULT|ok|stream sent"; else echo "RESULT|error|zfs send | nc failed"; fi
SH
}

manifest_body() {  # prints DATA|manifest|<hash>  for the directory in $DIR
  cat <<'SH'
[ -d "$DIR" ] || { echo "RESULT|error|$DIR is not mounted"; exit 0; }
echo "DATA|manifest|$(cd "$DIR" && find . -type f -printf '%s %p\n' | sort | sha256sum | cut -c1-24)"
echo "DATA|files|$(cd "$DIR" && find . -type f | wc -l)"
echo "RESULT|ok|manifest taken"
SH
}

xml_body() {
  cat <<'SH'
virsh --connect qemu:///system dumpxml --inactive "$VM" > /tmp/move-vm.xml || { echo "RESULT|error|dumpxml failed"; exit 0; }
echo "DATA|xmlsave|$(base64 -w0 < /tmp/move-vm.xml)"
sed "s#/exports/kvm/$SRC/$VM#/exports/kvm/$DST/$VM#g" /tmp/move-vm.xml | base64 -w0 > /tmp/move-vm.b64
echo "DATA|xml|$(cat /tmp/move-vm.b64)"
rm -f /tmp/move-vm.xml /tmp/move-vm.b64
echo "RESULT|ok|xml read"
SH
}

define_body() {
  cat <<'SH'
V="virsh --connect qemu:///system"
echo "$XML_B64" | base64 -d > "/tmp/$VM.xml" || { echo "RESULT|error|bad XML transfer"; exit 0; }
if grep -q "/exports/kvm/$SRC/" "/tmp/$VM.xml"; then echo "RESULT|error|the XML still names the source path"; rm -f "/tmp/$VM.xml"; exit 0; fi
out=$($V define "/tmp/$VM.xml" 2>&1) || { echo "RESULT|error|virsh define: $out"; rm -f "/tmp/$VM.xml"; exit 0; }
LEFT=$(grep -n -w "$SRC" "/tmp/$VM.xml" | head -3 | tr '\n' ' ')
rm -f "/tmp/$VM.xml"
[ -z "$LEFT" ] || echo "DATA|leftover|$LEFT"
if [ "$AUTOSTART" = "enable" ]; then $V autostart "$VM" >/dev/null 2>&1 || true; fi
echo "RESULT|ok|defined (autostart: ${AUTOSTART:-disable})"
SH
}

undefine_body() {
  cat <<'SH'
V="virsh --connect qemu:///system"
out=$($V undefine "$VM" 2>&1) || { echo "RESULT|error|virsh undefine: $out"; exit 0; }
echo "RESULT|ok|undefined on $SRC"
SH
}

start_body() {
  cat <<'SH'
out=$(virsh --connect qemu:///system start "$VM" 2>&1) && echo "RESULT|ok|started" || echo "RESULT|error|$out"
SH
}

cleanup_dst_body() {
  cat <<'SH'
V="virsh --connect qemu:///system"
$V destroy "$VM" >/dev/null 2>&1 || true
$V undefine "$VM" >/dev/null 2>&1 || true
zfs destroy -r "tank/kvm/$DST/$VM" 2>/dev/null || true
echo "RESULT|ok|destination cleaned"
SH
}

cleanup_src_body() {
  cat <<'SH'
zfs destroy "$SNAP" 2>/dev/null || true
echo "RESULT|ok|snapshot removed"
SH
}

destroy_src_body() {
  cat <<'SH'
zfs destroy -r "tank/kvm/$SRC/$VM" || { echo "RESULT|error|zfs destroy failed (is something still using the dataset?)"; exit 0; }
echo "RESULT|ok|source dataset destroyed"
SH
}

# ---- the run ------------------------------------------------------------------------------------------------------

# Rolling back is only done while the source domain still exists.
stopped=0; was_running=0; snap_made=0; dst_touched=0; src_undefined=0

abort() {
  echo "ABORT: $1" >&2
  if [ "$src_undefined" -eq 0 ]; then
    echo "rolling back" >&2
    if [ "$dst_touched" -eq 1 ]; then step "$dst" "$(pre)"$'\n'"$(cleanup_dst_body)" 120 >/dev/null 2>&1 || echo "  could not clean tank/kvm/$dst/$vm on $dst" >&2; fi
    if [ "$snap_made" -eq 1 ]; then step "$src" "$(pre)"$'\n'"$(cleanup_src_body)" 60 >/dev/null 2>&1 || echo "  could not remove snapshot $SNAP on $src" >&2; fi
    if [ "$stopped" -eq 1 ] && [ "$was_running" -eq 1 ]; then
      step "$src" "$(pre)"$'\n'"$(start_body)" 60 >/dev/null 2>&1 && echo "  $vm started again on $src" >&2 || echo "  could not start $vm on $src: use utils/start-vm.sh $vm" >&2
    fi
  else
    echo "the source domain is already undefined: $vm exists on $dst; the original XML is in $XML_BACKUP; the source dataset $FS_SRC on $src is still there" >&2
  fi
  exit 1
}

log "checking $vm on $src"
step "$src" "$(pre)"$'\n'"$(src_check_body)" 120 || exit 1
state=$(field state); used=$(field used); mem=$(field mem); autostart=$(field autostart)
bridges=$(field bridges); vendor_src=$(field vendor)
bridges=$(printf '%s' "$bridges" | sed 's/[[:space:]]*$//')
case "$bridges$autostart" in *[!A-Za-z0-9._\ -]*) echo "unexpected characters in the domain's bridges or autostart; not continuing" >&2; exit 1 ;; esac

log "checking $dst"
step "$dst" "$(pre)"$'\n'"$(dst_check_body)" 120 || exit 1
avail=$(field avail); memavail=$(field memavail); vendor_dst=$(field vendor)

if [ -n "$vendor_src" ] && [ "$vendor_src" != "$vendor_dst" ]; then
  echo "CPU vendor differs ($src: $vendor_src, $dst: $vendor_dst); a host-passthrough guest may not start. Not moving." >&2
  exit 1
fi
if [ -n "$used" ] && [ -n "$avail" ] && [ $((used + used / 10)) -gt "$avail" ]; then
  echo "not enough space on $dst: dataset uses $((used / 1048576)) MiB, tank has $((avail / 1048576)) MiB free" >&2
  exit 1
fi
if [ -n "$mem" ] && [ -n "$memavail" ] && [ "$mem" -gt "$memavail" ]; then
  echo "WARNING: $vm has $mem MiB and $dst has $memavail MiB available; it may not start there" >&2
fi

# The destination's address for the transfer: its host name in the inventory, else its plain name.
DST_ADDR=$(awk -v n="$dst" '$1 == "-" && $2 == "name:" { hit = ($3 == n) } hit && $1 == "host:" { gsub(/"/, "", $2); print $2; exit }' "$(inventory_file 2>/dev/null)" 2>/dev/null || true)
[ -n "$DST_ADDR" ] || DST_ADDR="$dst"
case "$DST_ADDR" in ''|*[!A-Za-z0-9._-]*) echo "unusual destination address '$DST_ADDR'" >&2; exit 1 ;; esac

cat <<PLAN

  VM            $vm  (state: $state, autostart: ${autostart:-?})
  from          $src  $FS_SRC  ($((used / 1048576)) MiB used)
  to            $dst  $FS_DST
  bridges       ${bridges:-none}
  transfer      zfs send | nc  ->  $DST_ADDR:$PORT  (not encrypted)
  after         source dataset $( [ "$DELETE_SRC" -eq 1 ] && echo "destroyed" || echo "kept" ); config file $cfg: hypervisor: $src -> $dst
  start on $dst $( [ "$NO_START" -eq 1 ] && echo "no" || { [ "$state" = "running" ] && echo "yes (it is running now)" || echo "no (it is $state)"; } )

PLAN
[ "$DRY" -eq 1 ] && { echo "dry run: nothing was changed"; exit 0; }

if [ "$YES" -ne 1 ]; then
  if [ ! -t 0 ]; then echo "not a terminal: add --yes to go ahead" >&2; exit 2; fi
  printf 'Move %s from %s to %s? The VM will be shut down. [y/N] ' "$vm" "$src" "$dst"
  read -r reply
  case "$reply" in y|Y|yes|YES) ;; *) echo "not moved"; exit 1 ;; esac
fi

# 1. shut down
if [ "$state" = "running" ]; then was_running=1; fi
if [ "$state" != "shut off" ]; then
  log "shutting $vm down"
  stop_args=(--wait "$STOP_WAIT"); [ "$FORCE_STOP" -eq 1 ] && stop_args+=(--force)
  "$HERE/stop-vm.sh" "${stop_args[@]}" "$vm" || abort "$vm did not shut down (add --force-stop to power it off)"
  stopped=1
fi

# 2. save the XML, snapshot
log "reading the domain XML"
step "$src" "$(pre)"$'\n'"$(xml_body)" 60 || abort "could not read the domain XML"
xml_b64=$(field xml)
printf '%s' "$(field xmlsave)" | base64 -d > "$XML_BACKUP" 2>/dev/null || true
[ -n "$xml_b64" ] || abort "the domain XML came back empty"
log "snapshot $SNAP"
step "$src" "$(pre)"$'\n'"$(snapshot_body)" 120 || abort "could not take the snapshot"
snap_made=1
step "$src" "DIR='$DIR_SRC'"$'\n'"$(manifest_body)" 300 || abort "could not list the files on $src"
man_src=$(field manifest); files_src=$(field files)

# 3. transfer
log "transfer: $dst listens on $PORT, $src sends ($((used / 1048576)) MiB)"
dst_touched=1
recv_out=$(mktemp)
( orphera run --nodes "$dst" --quiet --timeout "$XFER_TIMEOUT" -- sh -c "$(pre)"$'\n'"$(listen_body)" >"$recv_out" 2>&1 ) &
recv_pid=$!
ready=0
for _ in $(seq 1 30); do
  kill -0 "$recv_pid" 2>/dev/null || break
  vm_run "$dst" "$(pre)"$'\n'"$(listening_body)" 30
  case "$result" in ok\|*) ready=1; break ;; esac
  sleep 2
done
if [ "$ready" -ne 1 ]; then
  kill "$recv_pid" 2>/dev/null; wait "$recv_pid" 2>/dev/null
  sed 's/^/    /' "$recv_out" >&2; rm -f "$recv_out"
  abort "the receiver on $dst did not start listening"
fi
step "$src" "DST_ADDR='$DST_ADDR'"$'\n'"$(pre)"$'\n'"$(send_body)" "$XFER_TIMEOUT" || { kill "$recv_pid" 2>/dev/null; wait "$recv_pid" 2>/dev/null; rm -f "$recv_out"; abort "the send failed"; }
wait "$recv_pid"
recv_result=$(sed -n 's/^\[[^]]*\] *RESULT|//p; s/^RESULT|//p' "$recv_out" | head -1)
rm -f "$recv_out"
case "$recv_result" in
  ok\|*) log "  ${recv_result#ok|}" ;;
  error\|*) abort "receive on $dst: ${recv_result#error|}" ;;
  *) abort "no answer from the receiver on $dst" ;;
esac

# 4. verify
step "$dst" "DIR='$DIR_DST'"$'\n'"$(manifest_body)" 300 || abort "could not list the files on $dst"
man_dst=$(field manifest); files_dst=$(field files)
if [ -z "$man_src" ] || [ "$man_src" != "$man_dst" ]; then
  abort "the files on $dst differ from $src (source: $files_src files, $man_src; destination: $files_dst files, $man_dst)"
fi
log "verified: $files_dst files, names and sizes match"

# 5. define on the destination, undefine on the source
step "$dst" "$(pre)"$'\n'"$(define_body)" 60 || abort "could not define $vm on $dst"
log "defined on $dst"
left=$(field leftover)
[ -z "$left" ] || echo "WARNING: the XML on $dst still contains the name $src: $left" >&2
step "$src" "$(pre)"$'\n'"$(undefine_body)" 60 || { step "$dst" "$(pre)"$'\n'"$(cleanup_dst_body)" 60 >/dev/null 2>&1; abort "could not undefine $vm on $src"; }
src_undefined=1
log "undefined on $src"

# 6. the config file
if [ -w "$cfg" ]; then
  sed -i "s/^\(  hypervisor:[[:space:]]*\).*/\1$dst/" "$cfg"
  now=$(config_value "$cfg" hypervisor)
  if [ "$now" = "$dst" ]; then log "$cfg: hypervisor: $dst"; else echo "WARNING: could not set hypervisor: in $cfg (it says '$now'); edit it by hand" >&2; fi
else
  echo "WARNING: $cfg is not writable; set hypervisor: $dst in it by hand" >&2
fi

# 7. start
rc=0
if [ "$was_running" -eq 1 ] && [ "$NO_START" -eq 0 ]; then
  log "starting $vm on $dst"
  # libvirt can fail the first start when the tap name it picked (vnetN) is still held by a stale Open vSwitch
  # port on another bridge; the next attempt picks another name, so try twice.
  started=0
  for attempt in 1 2; do
    if step "$dst" "$(pre)"$'\n'"$(start_body)" 60; then started=1; break; fi
    [ "$attempt" -eq 1 ] && { log "start failed on $dst; trying once more in 5 s (see 'journalctl -u libvirtd' there for the reason)"; sleep 5; }
  done
  if [ "$started" -eq 1 ]; then
    log "waiting up to ${BOOT_WAIT}s for the agent in $vm"
    up=0; t=0
    while [ "$t" -lt "$BOOT_WAIT" ]; do
      if orphera run --nodes "$vm" --quiet --timeout 10 -- hostname 2>/dev/null | grep -qi "$vm"; then up=1; break; fi
      sleep 5; t=$((t + 5))
    done
    if [ "$up" -eq 1 ]; then log "$vm answers on $dst after about ${t}s"; else echo "$vm was started on $dst but its agent did not answer in ${BOOT_WAIT}s" >&2; rc=1; fi
  else
    echo "$vm is defined on $dst but did not start: check with 'virsh start $vm' there" >&2; rc=1
  fi
else
  log "$vm is shut off on $dst (utils/start-vm.sh $vm starts it)"
fi

# 8. the source copy
if [ "$DELETE_SRC" -eq 1 ] && [ "$rc" -eq 0 ]; then
  step "$src" "$(pre)"$'\n'"$(destroy_src_body)" 300 && log "source dataset $FS_SRC destroyed" || { echo "could not destroy $FS_SRC on $src" >&2; rc=1; }
else
  echo "The source copy is still on $src. Once $vm is confirmed on $dst, remove it there with:"
  echo "    zfs destroy -r $FS_SRC"
fi
echo "Commit the changed config:  git add $cfg && git commit -m \"$vm moved to $dst\""
exit "$rc"
