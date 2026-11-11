// SPDX-License-Identifier: Apache-2.0

package orphera.scanvmsizes

/** The scripts scan-vm-sizes runs on the hypervisors and on the running guests,
  * word for word those of utils/scan-vm-sizes.sh.
  */
object Scripts:

  val hypervisor: String =
    """V="virsh --connect qemu:///system"; [ "$(id -u)" -eq 0 ] || V="sudo -n $V"; echo "HV|up"; for v in $($V list --all --name 2>/dev/null); do st=$($V domstate "$v" 2>/dev/null | head -1); info=$($V dominfo "$v" 2>/dev/null); cpu=$(printf "%s\n" "$info" | awk "/^CPU\\(s\\)/{print \$2}"); mem=$(printf "%s\n" "$info" | awk "/^Max memory/{printf \"%d\", \$3/1024}"); echo "VM|$v|$st|$cpu|$mem"; done"""

  val guest: String =
    """echo "G|$(nproc)|$(grep ^MemTotal /proc/meminfo | tr -s " " | cut -d" " -f2)""""
