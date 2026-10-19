# SPDX-License-Identifier: Apache-2.0
#
# Shared by start-vm.sh, stop-vm.sh and reboot-vm.sh; sourced, not run.
# Finds a VM's hypervisor and libvirt domain name in its config file and runs a
# script on the hypervisor through `orphera run`.

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

# vm_lookup <name | number | config.yaml>  sets cfg, hv and vm. Returns 1, after
# saying why on stderr, if they cannot be found.
vm_lookup() {
  case "$1" in
    *.yaml) cfg=$1 ;;
    *[!0-9]*) cfg="config/$1.yaml" ;;
    *) cfg="config/tst$1.yaml" ;;
  esac
  if [ ! -f "$cfg" ]; then
    echo "$1: no config file $cfg" >&2
    return 1
  fi
  hv=$(config_value "$cfg" hypervisor)
  vm=$(config_value "$cfg" hostname)
  if [ -z "$hv" ] || [ -z "$vm" ]; then
    echo "$1: $cfg has no hypervisor: or hostname:" >&2
    return 1
  fi
  # Both end up inside a remote shell command, so only plain names are accepted.
  case "$hv$vm" in
    *[!A-Za-z0-9._-]*)
      echo "$1: unusual characters in hypervisor or hostname in $cfg; not running" >&2
      return 1 ;;
  esac
}

# vm_run <hypervisor> <script> [timeout-seconds]  runs the script there, as the
# agent (root). The script must end by printing RESULT|ok|<text> or
# RESULT|error|<text>; sets result to what follows "RESULT|" (empty if the
# hypervisor never answered) and out to everything that came back.
vm_run() {
  out=$(orphera run --nodes "$1" --quiet --timeout "${3:-60}" -- sh -c "$2" 2>&1)
  result=$(printf '%s\n' "$out" | sed -n 's/^\[[^]]*\] *RESULT|//p; s/^RESULT|//p' | head -1)
}

# vm_report <vm> <hypervisor>  prints the outcome of the last vm_run; returns 1
# when it was not a success.
vm_report() {
  case "$result" in
    ok\|*)
      printf '%-8s on %-4s %s\n' "$1" "$2" "${result#ok|}" ;;
    error\|*)
      printf '%-8s on %-4s FAILED: %s\n' "$1" "$2" "${result#error|}" >&2
      return 1 ;;
    *)
      printf '%-8s on %-4s FAILED: no answer from %s\n' "$1" "$2" "$2" >&2
      printf '%s\n' "$out" | tail -3 >&2
      return 1 ;;
  esac
}
