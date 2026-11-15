// SPDX-License-Identifier: Apache-2.0

package orphera.startvm

/** The script start-vm runs on the hypervisor, word for word that of
  * utils/start-vm.sh (`make check-vm-scripts` compares what the two send);
  * the placeholders @VM@ and @RETRIES@ stand for the libvirt domain and the
  * number of retries.
  */
object Scripts:

  val start: String =
    """V="virsh --connect qemu:///system"
pruned=""
# Open vSwitch ports named vnetN whose device is gone: libvirt leaves them when a VM stops uncleanly,
# reuses the name, and the start then fails with "vnetN is actually attached to bridge ...".
prune_ovs() {
  command -v ovs-vsctl >/dev/null 2>&1 || return 0
  for b in $(ovs-vsctl list-br 2>/dev/null); do
    for p in $(ovs-vsctl list-ports "$b" 2>/dev/null); do
      case "$p" in vnet[0-9]*) ;; *) continue ;; esac
      [ -e "/sys/class/net/$p" ] && continue
      ovs-vsctl --if-exists del-port "$b" "$p" || continue
      case " $pruned " in *" $p "*) ;; *) pruned="$pruned $p" ;; esac
    done
  done
}
# 0: done, 1: failed (worth another try), 2: failed (another try cannot help).
attempt() {
  state=$($V domstate '@VM@' 2>/dev/null | head -1)
  case "$state" in
    "")         msg="domain '@VM@' is not defined on this hypervisor"; return 2 ;;
    running)    msg="already running"; return 0 ;;
    paused)     $V resume '@VM@' >/dev/null 2>&1 && { msg="resumed"; return 0; }; msg="virsh resume failed"; return 1 ;;
    "shut off") prune_ovs; out=$($V start '@VM@' 2>&1) && { msg="started"; return 0; }; msg=$(printf '%s' "$out" | tr '\n' ' '); return 1 ;;
    *)          msg="domain is in state '$state'; not touching it"; return 2 ;;
  esac
}
n=0
while :; do
  attempt; rc=$?
  [ "$rc" -eq 1 ] && [ "$n" -lt @RETRIES@ ] || break
  n=$((n + 1))
  sleep 5
done
[ "$n" -eq 0 ] || msg="$msg (attempt $((n + 1)) of $((@RETRIES@ + 1)))"
[ -z "$pruned" ] || msg="$msg; removed stale Open vSwitch port(s)$pruned"
if [ "$rc" -eq 0 ]; then echo "RESULT|ok|$msg"; else echo "RESULT|error|$msg"; fi"""
