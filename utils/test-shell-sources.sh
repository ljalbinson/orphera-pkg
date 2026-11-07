#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the shell scripts read the inventory and the VM configs from where ORPHERA_INVENTORY and
# ORPHERA_CONFIG_DIR say, as `orphera` does, and not from a fixed config/ in the working directory.
# Needs no lab, no agent and no `orphera`. Run from the repository root: make check-scripts
set -u
cd "$(dirname "$0")/.." || exit 1
fail=0
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }
eq()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$3', got '$2'"; fi; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/alt/config" "$tmp/work"
cat > "$tmp/alt/config/vmx.yaml" <<'YAML'
params:
  hypervisor: hvz
  hostname: vmx-domain
YAML
cat > "$tmp/alt/inventory.yaml" <<'YAML'
nodes:
  - name: vmx
    host: vmx.example.test
YAML

. utils/orphera-sources.sh

eq "config_dir default"             "$(unset ORPHERA_CONFIG_DIR; config_dir)"                       "config"
eq "config_dir plain path"          "$(ORPHERA_CONFIG_DIR=/a/b/ config_dir)"                        "/a/b"
eq "config_dir file: URI"           "$(ORPHERA_CONFIG_DIR=file:///a/b config_dir)"                  "/a/b"
if ORPHERA_CONFIG_DIR=mariadb://h/db config_dir >/dev/null 2>&1; then bad "config_dir refuses mariadb://"; else ok "config_dir refuses mariadb://"; fi
eq "inventory_file default"         "$(unset ORPHERA_INVENTORY; inventory_file)"                    "inventory.yaml"
eq "inventory_file file: URI"       "$(ORPHERA_INVENTORY=file:///x/inv.yaml inventory_file)"        "/x/inv.yaml"
if ORPHERA_INVENTORY=https://h/inv.yaml inventory_file >/dev/null 2>&1; then bad "inventory_file refuses https://"; else ok "inventory_file refuses https://"; fi
eq "config_value hypervisor"        "$(config_value "$tmp/alt/config/vmx.yaml" hypervisor)"         "hvz"

# vm_lookup, the part of start/stop/reboot-vm.sh that finds a VM, from a directory that is not config/.
. utils/vm-common.sh
(cd "$tmp/work" && ORPHERA_CONFIG_DIR="$tmp/alt/config" vm_lookup vmx && echo "$hv $vm") > "$tmp/out" 2>&1
eq "vm_lookup reads ORPHERA_CONFIG_DIR" "$(tail -1 "$tmp/out")" "hvz vmx-domain"
(cd "$tmp/work" && vm_lookup vmx) > "$tmp/out" 2>&1
case "$(cat "$tmp/out")" in *"no config file config/vmx.yaml"*) ok "vm_lookup default is config/";; *) bad "vm_lookup default is config/: $(cat "$tmp/out")";; esac

# hypervisor_list: the inventory group `hypervisors`, else $HYPERVISORS, else gs1 gs2 gs3 st0. A stand-in `orphera`
# prints the groups `nodes --groups` would.
mkdir -p "$tmp/bin"
fake_orphera() {  # <what `nodes --groups` prints, or "fail">
  if [ "$1" = fail ]; then printf '#!/bin/sh\nexit 1\n' > "$tmp/bin/orphera"
  else printf '#!/bin/sh\n[ "$1 $2" = "nodes --groups" ] || exit 2\nprintf "%%s\\n" "$GROUPS_OUT"\n' > "$tmp/bin/orphera"; GROUPS_OUT=$1; export GROUPS_OUT; fi
  chmod +x "$tmp/bin/orphera"
}
hl() { (PATH="$tmp/bin:/usr/bin:/bin"; . utils/orphera-sources.sh; hypervisor_list); }
fake_orphera $'k8s: tst30 tst31\nhypervisors: gs1 gs2 gs9\nceph: tst0'
eq "hypervisor_list: the inventory group"                 "$(unset HYPERVISORS; hl)"                       "gs1 gs2 gs9"
eq "hypervisor_list: the group beats HYPERVISORS"         "$(HYPERVISORS="a b" hl)"                        "gs1 gs2 gs9"
fake_orphera $'k8s: tst30 tst31\nceph: tst0'
eq "hypervisor_list: no group, then HYPERVISORS"          "$(HYPERVISORS="a b" hl)"                        "a b"
eq "hypervisor_list: no group, no variable: static list"  "$(unset HYPERVISORS; hl)"                       "gs1 gs2 gs3 st0"
fake_orphera $'hypervisors: '
eq "hypervisor_list: an empty group counts as none"       "$(HYPERVISORS="a b" hl)"                        "a b"
fake_orphera fail
eq "hypervisor_list: orphera failing counts as no group"  "$(HYPERVISORS="a b" hl)"                        "a b"
rm -f "$tmp/bin/orphera"
eq "hypervisor_list: no orphera at all: static list"      "$(unset HYPERVISORS; hl)"                       "gs1 gs2 gs3 st0"

# The same order in tab completion, which reads the inventory file itself (no JVM per keypress).
printf 'groups:\n  - name: other\n    members: [x1, x2]\n  - name: hypervisors\n    members: [gs1, "gs2", gs9]\n' > "$tmp/inv-inline.yaml"
printf 'groups:\n  - name: hypervisors\n    members:\n      - gs4\n      - gs5\n  - name: other\n    members: [x1]\n' > "$tmp/inv-block.yaml"
printf 'groups:\n  - name: other\n    members: [x1]\n' > "$tmp/inv-none.yaml"
vh() { (. ./orphera-completion.bash; _vmctl_hypervisors | tr '\n' ' ' | sed 's/ $//'); }
eq "completion: the group, members in a list"             "$(ORPHERA_INVENTORY=$tmp/inv-inline.yaml HYPERVISORS="a b" vh)" "gs1 gs2 gs9"
eq "completion: the group, members one per line"          "$(ORPHERA_INVENTORY=$tmp/inv-block.yaml vh)"    "gs4 gs5"
eq "completion: no group, then HYPERVISORS"               "$(ORPHERA_INVENTORY=$tmp/inv-none.yaml HYPERVISORS="a b" vh)" "a b"
eq "completion: no group, no variable: static list"       "$(unset HYPERVISORS; ORPHERA_INVENTORY=$tmp/inv-none.yaml vh)" "gs1 gs2 gs3 st0"
eq "completion: a database inventory: the variable"       "$(ORPHERA_INVENTORY=mariadb://h/db HYPERVISORS="a b" vh)" "a b"
eq "completion: the real inventory has the group"         "$(unset HYPERVISORS ORPHERA_INVENTORY; vh)"      "$(awk '/name: hypervisors/{f=1} f&&/members:/{gsub(/[^A-Za-z0-9 ]/," ",$0); sub(/members/,""); print; exit}' inventory.yaml | xargs)"

# No script may name config/ or inventory.yaml as a path to open; it goes through CONF_DIR / INV_FILE.
before=$fail
for f in manifests/*.sh utils/*.sh; do
  [ "$f" = utils/test-shell-sources.sh ] && continue
  hits=$(grep -n -E '"config/|"\$\{?[a-z]*\}?config/|[ ]inventory\.yaml[ \x27"]*$' "$f" | grep -v -E '^[0-9]+:[ ]*#' || true)
  [ -z "$hits" ] || bad "$f opens a fixed path: $hits"
done
[ "$fail" -ne "$before" ] || ok "no script opens a fixed config/ or inventory.yaml path"

# Playbooks are named bare (etcd_cluster.scala): orphera looks them up in -m / ORPHERA_MANIFESTS, so no script
# may name them as manifests/<file>.
before=$fail
for f in manifests/*.sh utils/*.sh; do
  [ "$f" = utils/test-shell-sources.sh ] && continue
  hits=$(grep -n -E 'orphera +(cluster-playbook|playbook) +"?manifests/|MANIFESTS_DIR' "$f" | grep -v -E '^[0-9]+:[ ]*#' || true)
  [ -z "$hits" ] || bad "$f names a playbook by its manifests/ path: $hits"
done
[ "$fail" -ne "$before" ] || ok "no script names a playbook by its manifests/ path"

if [ "$fail" -eq 0 ]; then echo "test-shell-sources: all checks passed"; else echo "test-shell-sources: $fail failed"; exit 1; fi
