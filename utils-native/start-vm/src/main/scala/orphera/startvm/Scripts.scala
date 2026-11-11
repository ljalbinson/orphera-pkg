// SPDX-License-Identifier: Apache-2.0

package orphera.startvm

/** The script start-vm runs on the hypervisor, word for word that of
  * utils/start-vm.sh (`make check-vm-scripts` compares what the two send);
  * the placeholder @VM@ stands for the libvirt domain.
  */
object Scripts:

  val start: String =
    """V="virsh --connect qemu:///system"
state=$($V domstate '@VM@' 2>/dev/null | head -1)
case "$state" in
  "")           echo "RESULT|error|domain '@VM@' is not defined on this hypervisor" ;;
  running)      echo "RESULT|ok|already running" ;;
  paused)       $V resume '@VM@' >/dev/null 2>&1 && echo "RESULT|ok|resumed" || echo "RESULT|error|virsh resume failed" ;;
  "shut off")   out=$($V start '@VM@' 2>&1) && echo "RESULT|ok|started" || echo "RESULT|error|$out" ;;
  *)            echo "RESULT|error|domain is in state '$state'; not touching it" ;;
esac"""
