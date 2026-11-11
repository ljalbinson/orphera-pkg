// SPDX-License-Identifier: Apache-2.0

package orphera.listvms

/** The script list-vms runs on each hypervisor, word for word that of
  * utils/list-vms.sh: one line per VM, VM|name|state|vCPUs|MiB|autostart.
  */
object Scripts:

  val remote: String =
    """V="virsh --connect qemu:///system"; [ "$(id -u)" -eq 0 ] || V="sudo -n $V"; echo "HV|up"; for v in $($V list --all --name 2>/dev/null); do st=$($V domstate "$v" 2>/dev/null | head -1); info=$($V dominfo "$v" 2>/dev/null); cpu=$(printf "%s\n" "$info" | awk "/^CPU\\(s\\)/{print \$2}"); mem=$(printf "%s\n" "$info" | awk "/^Max memory/{printf \"%d\", \$3/1024}"); auto=$(printf "%s\n" "$info" | awk "/^Autostart/{print \$2}"); echo "VM|$v|$st|$cpu|$mem|$auto"; done"""
