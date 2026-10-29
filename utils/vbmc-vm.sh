#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# IPMI control of VMs through VirtualBMC (installed on the hypervisors by manifests/virtualbmc.scala). Each VM gets
# its own IPMI endpoint on its hypervisor: the hypervisor's address, UDP port 6230 plus the VM's number (tst17 is
# 6247), user admin, the password in the secret `vbmc/password`. The hypervisor and the libvirt domain name come
# from the VM's config/<vm>.yaml, as for start-vm.sh and stop-vm.sh.
#
#   utils/vbmc-vm.sh add tst17 tst18          # make the endpoint and start it (repeatable)
#   utils/vbmc-vm.sh add --all                # every VM that has a config file with hypervisor: and hostname:
#   utils/vbmc-vm.sh remove tst17
#   utils/vbmc-vm.sh start tst17              # start / stop the endpoint (not the VM)
#   utils/vbmc-vm.sh stop tst17
#   utils/vbmc-vm.sh list [hypervisor...]     # the endpoints on each hypervisor (default: all in the configs)
#   utils/vbmc-vm.sh power tst17 status       # on | off | soft | cycle | reset | status, with ipmitool over IPMI
#   utils/vbmc-vm.sh bootdev tst17 pxe        # pxe | disk | cdrom
#
# `power` and `bootdev` run ipmitool here (apt install ipmitool) against the endpoint, which is the same path
# OpenStack Ironic's ipmi driver takes: ipmitool -I lanplus -H <hypervisor> -p <port> -U admin -P <password>.
# The password is given to ipmitool in the environment (IPMI_PASSWORD), not on its command line.
# `bootdev` changes the boot order of the whole libvirt domain: `bootdev disk` puts it back.
#
# Run from scala0, in orphera-pkg. Exit code 1 if any VM failed.

set -uo pipefail
. "$(dirname "$0")/vm-common.sh"

BASE=6230
USER_NAME=admin
VBMC=/opt/virtualbmc/bin/vbmc

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

# port_of <vm name>  (tstN -> 6230+N)
port_of() {
  case "$1" in
    tst[0-9]*) n=${1#tst}; case "$n" in *[!0-9]*) return 1 ;; esac; echo $((BASE + n)) ;;
    *) return 1 ;;
  esac
}

# the host name of an inventory node
node_host() {
  awk -v n="$1" '$1 == "-" && $2 == "name:" { hit = ($3 == n) } hit && $1 == "host:" { gsub(/"/, "", $2); print $2; exit }' "$(inventory_file)" 2>/dev/null
}

remote_script() {  # remote_script <action> <domain> <port>
  cat <<SH
export HOME=/root
ACTION='$1'; DOM='$2'; PORT='$3'
B=$VBMC
fail() { echo "RESULT|error|\$1"; exit 0; }
[ -x \$B ] || fail "VirtualBMC is not installed here: apply manifests/virtualbmc.scala"
[ -r /etc/orphera-vbmc.conf ] || fail "/etc/orphera-vbmc.conf is missing: apply manifests/virtualbmc.scala"
. /etc/orphera-vbmc.conf
prop() { \$B show "\$DOM" -f json 2>/dev/null | /opt/virtualbmc/bin/python -c "import sys, json; d = {i['Property']: i['Value'] for i in json.load(sys.stdin)}; print(d.get('\$1', ''))"; }
case "\$ACTION" in
  add)
    virsh --connect qemu:///system dominfo "\$DOM" >/dev/null 2>&1 || fail "domain \$DOM is not defined on this hypervisor"
    if \$B show "\$DOM" >/dev/null 2>&1 && [ "\$(prop port)" != "\$PORT" ]; then
      \$B stop "\$DOM" >/dev/null 2>&1 || true
      \$B delete "\$DOM" >/dev/null 2>&1 || fail "could not replace the endpoint of \$DOM"
    fi
    if ! \$B show "\$DOM" >/dev/null 2>&1; then
      out=\$(\$B add "\$DOM" --port "\$PORT" --username "\$VBMC_USER" --password "\$VBMC_PASSWORD" --address 0.0.0.0 2>&1) || fail "vbmc add: \$out"
    fi
    [ "\$(prop status)" = "running" ] || \$B start "\$DOM" >/dev/null 2>&1 || true
    sleep 1
    [ "\$(prop status)" = "running" ] || fail "the endpoint of \$DOM is not running"
    echo "RESULT|ok|IPMI endpoint on port \$PORT (running)" ;;
  remove)
    \$B show "\$DOM" >/dev/null 2>&1 || { echo "RESULT|ok|no endpoint"; exit 0; }
    \$B stop "\$DOM" >/dev/null 2>&1 || true
    out=\$(\$B delete "\$DOM" 2>&1) || fail "vbmc delete: \$out"
    echo "RESULT|ok|endpoint removed" ;;
  start|stop)
    \$B show "\$DOM" >/dev/null 2>&1 || fail "no endpoint for \$DOM: run add first"
    \$B \$ACTION "\$DOM" >/dev/null 2>&1 || true
    sleep 1
    echo "RESULT|ok|endpoint is \$(prop status)" ;;
  *) fail "unknown action \$ACTION" ;;
esac
SH
}

# ipmi <vm> <ipmitool args...>
ipmi() {
  local name=$1 host port pw
  shift
  vm_lookup "$name" || return 1
  command -v ipmitool >/dev/null 2>&1 || { echo "ipmitool is not installed here (apt install ipmitool)" >&2; return 1; }
  port=$(port_of "$vm") || { echo "$vm: no port rule for this name" >&2; return 1; }
  host=$(node_host "$hv"); host=${host:-$hv}
  pw=$(orphera secrets get vbmc/password 2>/dev/null) || { echo "cannot read the secret vbmc/password (apply manifests/virtualbmc.scala)" >&2; return 1; }
  pw=${pw:0:16}   # IPMI 2.0 allows 20 bytes at most; the playbook uses the first 16 characters of the secret
  IPMI_PASSWORD=$pw ipmitool -I lanplus -H "$host" -p "$port" -U "$USER_NAME" -E "$@"
}

action=${1:-}
[ -n "$action" ] || { usage; exit 2; }
shift
failed=""

case "$action" in
  -h|--help) usage; exit 0 ;;

  add|remove|start|stop)
    names=("$@")
    if [ "${1:-}" = "--all" ]; then
      names=()
      dir=$(config_dir) || exit 1
      for c in "$dir"/*.yaml; do
        [ -f "$c" ] || continue
        [ -n "$(config_value "$c" hypervisor)" ] && [ -n "$(config_value "$c" hostname)" ] && names+=("$c")
      done
    fi
    [ ${#names[@]} -gt 0 ] || { usage; exit 2; }
    for arg in "${names[@]}"; do
      vm_lookup "$arg" || { failed="$failed $arg"; continue; }
      port=$(port_of "$vm") || { echo "$vm: no port rule for this name (tstN only)" >&2; failed="$failed $vm"; continue; }
      vm_run "$hv" "$(remote_script "$action" "$vm" "$port")" 90
      vm_report "$vm" "$hv" || failed="$failed $vm"
    done ;;

  list)
    hvs=("$@")
    if [ ${#hvs[@]} -eq 0 ]; then
      dir=$(config_dir) || exit 1
      mapfile -t hvs < <(for c in "$dir"/*.yaml; do [ -f "$c" ] && config_value "$c" hypervisor; done | sort -u | grep -v '^$')
    fi
    for h in "${hvs[@]}"; do
      case "$h" in *[!A-Za-z0-9._-]*) echo "$h: not a plain name" >&2; failed="$failed $h"; continue ;; esac
      echo "== $h"
      orphera run --nodes "$h" --quiet --timeout 60 -- sh -c "HOME=/root $VBMC list 2>&1" || failed="$failed $h"
    done ;;

  power)
    [ $# -eq 2 ] || { usage; exit 2; }
    case "$2" in
      on|off|soft|cycle|reset|status) ipmi "$1" chassis power "$2" || failed="$1" ;;
      *) echo "power: on | off | soft | cycle | reset | status" >&2; exit 2 ;;
    esac ;;

  bootdev)
    [ $# -eq 2 ] || { usage; exit 2; }
    case "$2" in
      pxe|disk|cdrom) ipmi "$1" chassis bootdev "$2" || failed="$1" ;;
      *) echo "bootdev: pxe | disk | cdrom" >&2; exit 2 ;;
    esac ;;

  *) echo "unknown command $action" >&2; usage; exit 2 ;;
esac

if [ -n "$failed" ]; then
  echo "Failed:$failed" >&2
  exit 1
fi
