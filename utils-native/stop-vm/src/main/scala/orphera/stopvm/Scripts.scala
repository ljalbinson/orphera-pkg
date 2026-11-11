// SPDX-License-Identifier: Apache-2.0

package orphera.stopvm

/** The script stop-vm runs on the hypervisor, word for word that of
  * utils/stop-vm.sh (`make check-vm-scripts` compares what the two send);
  * the placeholders @VM@, @WAIT@ and @FORCE@ stand for the domain, --wait and --force (0 or 1).
  */
object Scripts:

  val stop: String =
    """V="virsh --connect qemu:///system"
state=$($V domstate '@VM@' 2>/dev/null | head -1)
[ -n "$state" ] || { echo "RESULT|error|domain '@VM@' is not defined on this hypervisor"; exit 0; }
[ "$state" != "shut off" ] || { echo "RESULT|ok|already shut off"; exit 0; }
out=$($V shutdown '@VM@' 2>&1) || { echo "RESULT|error|$out"; exit 0; }
i=0
while [ $i -lt @WAIT@ ]; do
  [ "$($V domstate '@VM@' 2>/dev/null | head -1)" = "shut off" ] && { echo "RESULT|ok|shut down after ${i}s"; exit 0; }
  sleep 2; i=$((i + 2))
done
if [ @FORCE@ -eq 1 ]; then
  out=$($V destroy '@VM@' 2>&1) && echo "RESULT|ok|did not shut down in @WAIT@s; powered off" || echo "RESULT|error|$out"
else
  echo "RESULT|error|still running after @WAIT@s (use --force to power it off)"
fi
"""
