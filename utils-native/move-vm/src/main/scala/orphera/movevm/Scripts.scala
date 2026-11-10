// SPDX-License-Identifier: Apache-2.0

package orphera.movevm

/** The scripts move-vm runs on the hypervisors, word for word those of
  * utils/move-vm.sh (`make check-move-vm` compares what the two send). Each
  * runs under `sh -c` after the variable line of `Move.pre` and ends by
  * printing RESULT|ok|<text> or RESULT|error|<text>, with DATA|<key>|<value>
  * lines before it.
  */
object Scripts:

  val srcCheck: String =
    """V="virsh --connect qemu:///system"
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
echo "RESULT|ok|domain $VM and dataset $FS look movable""""

  val dstCheck: String =
    """V="virsh --connect qemu:///system"
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
echo "RESULT|ok|destination is ready""""

  val snapshot: String =
    """zfs snapshot "$SNAP" || { echo "RESULT|error|zfs snapshot $SNAP failed"; exit 0; }
echo "RESULT|ok|snapshot $SNAP""""

  val listen: String =
    """zfs create -p "tank/kvm/$DST" 2>/dev/null || true
if nc -l "$PORT" | zfs receive -u "tank/kvm/$DST/$VM"; then
  DIR="/exports/kvm/$DST/$VM"
  [ "$(zfs get -H -o value mountpoint "tank/kvm/$DST/$VM")" = "$DIR" ] || zfs set mountpoint="$DIR" "tank/kvm/$DST/$VM"
  zfs mount "tank/kvm/$DST/$VM" 2>/dev/null || true
  mountpoint -q "$DIR" || { echo "RESULT|error|received, but $DIR is not mounted"; exit 0; }
  echo "RESULT|ok|received into tank/kvm/$DST/$VM, mounted at $DIR"
else
  echo "RESULT|error|nc | zfs receive failed"
fi"""

  val listening: String =
    """if ss -ltn 2>/dev/null | grep -q ":$PORT "; then echo "RESULT|ok|listening"; else echo "RESULT|error|not listening yet"; fi"""

  val send: String =
    """NCOPT=""
nc -h 2>&1 | grep -q -e ' -N' && NCOPT="-N"
DSTIP="$DST_ADDR"
E=/tmp/move-vm.send.$$
if zfs send "$SNAP" 2>"$E.zfs" | nc $NCOPT "$DSTIP" "$PORT" 2>"$E.nc"; then
  echo "RESULT|ok|stream sent"
else
  MSG=$(cat "$E.zfs" "$E.nc" 2>/dev/null | tr '\n' ' ' | cut -c1-300)
  echo "RESULT|error|zfs send | nc $NCOPT $DSTIP $PORT failed: ${MSG:-no message}"
fi
rm -f "$E.zfs" "$E.nc""""

  val resolve: String =
    """A=$(getent ahostsv4 "$DST_ADDR" 2>/dev/null | awk 'NR==1{print $1}')
[ -n "$A" ] || A=$(getent hosts "$DST_ADDR" 2>/dev/null | awk 'NR==1{print $1}')
if [ -n "$A" ]; then echo "RESULT|ok|$DST_ADDR is $A"; else echo "RESULT|error|$DST_ADDR does not resolve on this host (DNS or /etc/hosts)"; fi"""

  val manifest: String =
    """[ -d "$DIR" ] || { echo "RESULT|error|$DIR is not mounted"; exit 0; }
echo "DATA|manifest|$(cd "$DIR" && find . -type f -printf '%s %p\n' | sort | sha256sum | cut -c1-24)"
echo "DATA|files|$(cd "$DIR" && find . -type f | wc -l)"
echo "RESULT|ok|manifest taken""""

  val xml: String =
    """virsh --connect qemu:///system dumpxml --inactive "$VM" > /tmp/move-vm.xml || { echo "RESULT|error|dumpxml failed"; exit 0; }
echo "DATA|xmlsave|$(base64 -w0 < /tmp/move-vm.xml)"
sed "s#/exports/kvm/$SRC/$VM#/exports/kvm/$DST/$VM#g" /tmp/move-vm.xml | base64 -w0 > /tmp/move-vm.b64
echo "DATA|xml|$(cat /tmp/move-vm.b64)"
rm -f /tmp/move-vm.xml /tmp/move-vm.b64
echo "RESULT|ok|xml read""""

  val define: String =
    """V="virsh --connect qemu:///system"
echo "$XML_B64" | base64 -d > "/tmp/$VM.xml" || { echo "RESULT|error|bad XML transfer"; exit 0; }
if grep -q "/exports/kvm/$SRC/" "/tmp/$VM.xml"; then echo "RESULT|error|the XML still names the source path"; rm -f "/tmp/$VM.xml"; exit 0; fi
out=$($V define "/tmp/$VM.xml" 2>&1) || { echo "RESULT|error|virsh define: $out"; rm -f "/tmp/$VM.xml"; exit 0; }
LEFT=$(grep -n -w "$SRC" "/tmp/$VM.xml" | head -3 | tr '\n' ' ')
rm -f "/tmp/$VM.xml"
[ -z "$LEFT" ] || echo "DATA|leftover|$LEFT"
if [ "$AUTOSTART" = "enable" ]; then $V autostart "$VM" >/dev/null 2>&1 || true; fi
echo "RESULT|ok|defined (autostart: ${AUTOSTART:-disable})""""

  val undefine: String =
    """V="virsh --connect qemu:///system"
out=$($V undefine "$VM" 2>&1) || { echo "RESULT|error|virsh undefine: $out"; exit 0; }
echo "RESULT|ok|undefined on $SRC""""

  val start: String =
    """out=$(virsh --connect qemu:///system start "$VM" 2>&1) && echo "RESULT|ok|started" || echo "RESULT|error|$out""""

  val cleanupDst: String =
    """V="virsh --connect qemu:///system"
pkill -f "^nc -l $PORT\$" 2>/dev/null || true   # a receiver left listening after a failed send
sleep 1
$V destroy "$VM" >/dev/null 2>&1 || true
$V undefine "$VM" >/dev/null 2>&1 || true
zfs destroy -r "tank/kvm/$DST/$VM" 2>/dev/null || true
echo "RESULT|ok|destination cleaned""""

  val cleanupSrc: String =
    """zfs destroy "$SNAP" 2>/dev/null || true
echo "RESULT|ok|snapshot removed""""

  val destroySrc: String =
    """zfs destroy -r "tank/kvm/$SRC/$VM" || { echo "RESULT|error|zfs destroy failed (is something still using the dataset?)"; exit 0; }
echo "RESULT|ok|source dataset destroyed""""
