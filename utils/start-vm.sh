#!/usr/bin/env bash
#
# Starts one or more VMs on their hypervisors. The hypervisor and the libvirt
# domain name come from each VM's config file (the same config/<vm>.yaml the
# provisioning playbooks read: `hypervisor:` and `hostname:`).
#
#   utils/start-vm.sh tst7                      # config/tst7.yaml
#   utils/start-vm.sh 7                         # a bare number means tst7
#   utils/start-vm.sh tst3 tst4 tst5            # several, one after another
#   utils/start-vm.sh config/tst7.yaml          # an explicit config file
#
# A VM that is shut off is started, a paused one is resumed, and one that is
# already running is left alone. The script reports that the hypervisor has
# started the VM; it does not wait for the guest to answer. To wait for SSH, or
# to reset a guest that is "running" but halted, use
#   orphera cluster-playbook manifests/kvm_vm_start.scala --config config/<vm>.yaml
#
# Run from scala0, in orphera-pkg (config/ is a relative path; needs `orphera`
# and an agent on the hypervisor). Exit code 1 if any VM could not be started.

set -uo pipefail

if [ $# -eq 0 ]; then
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

# Value of a top-level `key:` under `params:` in a config file, without quotes.
config_value() {
  awk -v key="$2" '
    $0 ~ "^  " key ":" {
      v = $0
      sub("^  " key ":[ \t]*", "", v)
      sub(/[ \t]*#.*$/, "", v)
      gsub(/^["\x27]|["\x27]$/, "", v)
      print v
      exit
    }' "$1"
}

# Runs on the hypervisor, as the agent (root): start, resume or leave alone.
remote_script() {
  cat <<SH
V="virsh --connect qemu:///system"
state=\$(\$V domstate '$1' 2>/dev/null | head -1)
case "\$state" in
  "")           echo "RESULT|error|domain '$1' is not defined on this hypervisor" ;;
  running)      echo "RESULT|ok|already running" ;;
  paused)       \$V resume '$1' >/dev/null 2>&1 && echo "RESULT|ok|resumed" || echo "RESULT|error|virsh resume failed" ;;
  "shut off")   out=\$(\$V start '$1' 2>&1) && echo "RESULT|ok|started" || echo "RESULT|error|\$out" ;;
  *)            echo "RESULT|error|domain is in state '\$state'; not touching it" ;;
esac
SH
}

failed=""
for arg in "$@"; do
  case "$arg" in
    *.yaml) cfg=$arg ;;
    *[!0-9]*) cfg="config/$arg.yaml" ;;
    *) cfg="config/tst$arg.yaml" ;;
  esac

  if [ ! -f "$cfg" ]; then
    echo "$arg: no config file $cfg" >&2
    failed="$failed $arg"
    continue
  fi
  hv=$(config_value "$cfg" hypervisor)
  vm=$(config_value "$cfg" hostname)
  if [ -z "$hv" ] || [ -z "$vm" ]; then
    echo "$arg: $cfg has no hypervisor: or hostname:" >&2
    failed="$failed $arg"
    continue
  fi
  # Both end up inside a remote shell command, so only plain names are accepted.
  case "$hv$vm" in
    *[!A-Za-z0-9._-]*)
      echo "$arg: unusual characters in hypervisor or hostname in $cfg; not running" >&2
      failed="$failed $arg"
      continue ;;
  esac

  out=$(orphera run --nodes "$hv" --quiet -- sh -c "$(remote_script "$vm")" 2>&1)
  result=$(printf '%s\n' "$out" | sed -n 's/^\[[^]]*\] *RESULT|//p; s/^RESULT|//p' | head -1)
  case "$result" in
    ok\|*)    printf '%-8s on %-4s %s\n' "$vm" "$hv" "${result#ok|}" ;;
    error\|*) printf '%-8s on %-4s FAILED: %s\n' "$vm" "$hv" "${result#error|}" >&2; failed="$failed $vm" ;;
    *)        printf '%-8s on %-4s FAILED: no answer from %s\n' "$vm" "$hv" "$hv" >&2
              printf '%s\n' "$out" | tail -3 >&2
              failed="$failed $vm" ;;
  esac
done

if [ -n "$failed" ]; then
  echo "Not started:$failed" >&2
  exit 1
fi
