// SPDX-License-Identifier: Apache-2.0

package orphera.rebootvm

/** The scripts reboot-vm runs on the hypervisor (--soft, --hard), word for word
  * those of utils/reboot-vm.sh (`make check-vm-scripts` compares what the two
  * send); @VM@, @WAIT@ and @FORCE@ stand for the domain, --wait and --force.
  */
object Scripts:

  val soft: String =
    """V="virsh --connect qemu:///system"
state=$($V domstate '@VM@' 2>/dev/null | head -1)
case "$state" in
  "")        echo "RESULT|error|domain '@VM@' is not defined on this hypervisor" ;;
  running)   out=$($V reboot '@VM@' 2>&1) && echo "RESULT|ok|reboot sent" || echo "RESULT|error|$out" ;;
  *)         echo "RESULT|error|domain is $state, not running" ;;
esac"""

  val hard: String =
    """V="virsh --connect qemu:///system"
state=$($V domstate '@VM@' 2>/dev/null | head -1)
[ -n "$state" ] || { echo "RESULT|error|domain '@VM@' is not defined on this hypervisor"; exit 0; }
how="shut down"
if [ "$state" != "shut off" ]; then
  out=$($V shutdown '@VM@' 2>&1) || { echo "RESULT|error|$out"; exit 0; }
  i=0
  while [ $i -lt @WAIT@ ]; do
    [ "$($V domstate '@VM@' 2>/dev/null | head -1)" = "shut off" ] && break
    sleep 2; i=$((i + 2))
  done
  if [ "$($V domstate '@VM@' 2>/dev/null | head -1)" != "shut off" ]; then
    if [ @FORCE@ -eq 1 ]; then
      out=$($V destroy '@VM@' 2>&1) || { echo "RESULT|error|$out"; exit 0; }
      how="powered off after @WAIT@s"
    else
      echo "RESULT|error|still running after @WAIT@s (use --force to power it off); not restarted"; exit 0
    fi
  fi
else
  how="was already shut off"
fi
out=$($V start '@VM@' 2>&1) && echo "RESULT|ok|$how, started" || echo "RESULT|error|$how, but start failed: $out""""
