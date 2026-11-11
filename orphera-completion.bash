# SPDX-License-Identifier: Apache-2.0
#
# Bash completion for the orphera CLI.
#
# `make orpheracli` installs this file as /etc/bash_completion.d/orphera.
# To install it by hand:
#   sudo cp orphera-completion.bash /etc/bash_completion.d/orphera
# or, per-user:
#   mkdir -p ~/.local/share/bash-completion/completions
#   cp orphera-completion.bash ~/.local/share/bash-completion/completions/orphera
# then start a new shell (or `source` the file directly).

# Completes a playbook path argument (for `playbook`/`cluster-playbook`),
# preferring manifests/ without making the user type that prefix first.
#
# Ordinary `compgen -f` completion only matches names relative to the
# current directory, so with every real playbook in this project living
# under manifests/, completion previously needed "manifests/" typed out by
# hand before it found anything — and manifests/ itself couldn't even be
# tab-completed into, since it's a directory, not a .yaml/.yml/.scala file,
# so the extension filter (-X) excluded it too. Fixed by ALSO searching
# manifests/ directly for anything matching the current prefix, and adding
# those results (already "manifests/<name>" — compgen -f prefixes matches
# with whatever path you searched under) alongside the ordinary
# cwd-relative matches. So `orphera playbook etcd<TAB>` now finds
# `manifests/etcd_cluster.scala` etc. directly, while a path that already
# names a directory (contains a "/" — including "manifests/" itself, once
# the user has navigated into it) is left to ordinary completion, which
# already handles that correctly on its own.
# Group names from inventory.yaml (the `groups:` section), for --node-groups.
_orphera_groups() {
  local inv="${_ORPHERA_INV:-${ORPHERA_INVENTORY:-inventory.yaml}}"
  inv="${inv#file://}"
  # Only a file can be read here; another kind of location has no groups to offer.
  case "$inv" in *://*) return 0 ;; esac
  [ -r "$inv" ] || return 0
  awk '/^groups:/ {g=1; next} /^[^ #-]/ {g=0} g && /^[ ]*- name:/ {print $NF}' "$inv"
}

# Node names from inventory.yaml (the `nodes:` section), for --nodes and --exclude-nodes.
_orphera_nodes() {
  local inv="${_ORPHERA_INV:-${ORPHERA_INVENTORY:-inventory.yaml}}"
  inv="${inv#file://}"
  # Only a file can be read here; another kind of location has no names to offer.
  case "$inv" in *://*) return 0 ;; esac
  [ -r "$inv" ] || return 0
  awk '/^nodes:/ {n=1; next} /^[^ #-]/ {n=0} n && /^[ ]*- name:/ {print $NF}' "$inv"
}

# The value of --nodes / --exclude-nodes: a comma-separated list of node names.
# Completes the part after the last comma and keeps what came before it.
_orphera_complete_nodelist() {
  local cur="$1" prefix="" last="$1"
  if [[ "$cur" == *,* ]]; then
    prefix="${cur%,*},"
    last="${cur##*,}"
  fi
  # Leave out the names already in the list.
  local chosen=",$prefix" names="" n
  for n in $(_orphera_nodes); do
    case "$chosen" in *",$n,"*) ;; *) names="$names $n" ;; esac
  done
  COMPREPLY=($(compgen -P "$prefix" -W "$names" -- "$last"))
  # No space after a name, so that a comma can follow it.
  [[ ${#COMPREPLY[@]} -gt 0 ]] && compopt -o nospace 2>/dev/null
  return 0
}

_orphera_playbook_path_completions() {
  local cur="$1"
  local -a matches
  # The manifests directory: -m, ORPHERA_MANIFESTS, else manifests.
  local man="${_ORPHERA_MAN:-${ORPHERA_MANIFESTS:-manifests}}"
  man="${man#file://}"

  matches+=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "$cur"))

  if [[ -d "$man" && "$cur" != */* ]]; then
    if [[ "$man" == manifests ]]; then
      matches+=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "manifests/$cur"))
    else
      # Another directory: offer the bare file names, which orphera looks up there.
      matches+=($(cd "$man" 2>/dev/null && compgen -f -X '!*.@(yaml|yml|scala)' -- "$cur"))
    fi
  fi

  # De-duplicate (a bare cur of "" would otherwise repeat nothing here,
  # but a cur like "manifests" with no trailing slash — before the user
  # has committed to descending into it — could plausibly match both
  # branches on some setups) and hand back to the caller.
  COMPREPLY=($(printf '%s\n' "${matches[@]}" | sort -u))
}

_orphera_completions() {
  local cur prev words cword
  _init_completion || return

  local verbs="install remove autoremove dist-upgrade copy write-file network-apply reboot shutdown uptime thread-info run
    playbook cluster-playbook deploy-agent bootstrap issue-certs teardown fetch facts
    version cert-info log-summary audit-log pipeline secrets show-config nodes init-ca os10 ilo help"

  # Flags common to most verbs that take --nodes
  local nodes_flag="--nodes"

  # The global options (-I/--inventory <uri>, -c/--config-dir <uri>, -m/--manifests
  # <dir>) come before the verb. Take each finished pair out of `words`, so everything below sees
  # the verb at words[1] as usual, and keep the inventory for --node-groups.
  local _ORPHERA_INV="" _ORPHERA_CFG="" _ORPHERA_MAN=""
  while [[ $cword -gt 2 ]]; do
    case "${words[1]}" in
      -I|--inventory)
        _ORPHERA_INV="${words[2]}"
        words=("${words[0]}" "${words[@]:3}"); cword=$((cword - 2)) ;;
      -c|--config-dir)
        _ORPHERA_CFG="${words[2]}"
        words=("${words[0]}" "${words[@]:3}"); cword=$((cword - 2)) ;;
      -m|--manifests)
        _ORPHERA_MAN="${words[2]}"
        words=("${words[0]}" "${words[@]:3}"); cword=$((cword - 2)) ;;
      *) break ;;
    esac
  done
  # The value of one of those options: a path or a URI.
  if [[ $cword -eq 2 ]]; then
    case "${words[1]}" in
      -I|--inventory|-c|--config-dir) _filedir; return ;;
      -m|--manifests) _filedir -d; return ;;
    esac
  fi

  # First word after "orphera" itself — complete the verb.
  if [[ $cword -eq 1 ]]; then
    # `--help` is accepted as the first argument too, but only offered once
    # a dash has been typed, so it doesn't clutter the plain verb list.
    if [[ "$cur" == -* ]]; then
      COMPREPLY=($(compgen -W "--help -I --inventory -c --config-dir -m --manifests" -- "$cur"))
    else
      COMPREPLY=($(compgen -W "$verbs" -- "$cur"))
    fi
    return
  fi

  local verb="${words[1]}"

  # Whatever the verb, the value of --nodes / --exclude-nodes is a list of node names.
  case "$prev" in
    --nodes|--exclude-nodes) _orphera_complete_nodelist "$cur"; return 0 ;;
  esac

  case "$verb" in
    install)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --update-cache" -- "$cur")) ;;
      esac
      ;;

    remove)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --purge" -- "$cur")) ;;
      esac
      ;;

    autoremove)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --purge" -- "$cur")) ;;
      esac
      ;;

    dist-upgrade)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --no-update-cache" -- "$cur")) ;;
      esac
      ;;

    copy)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --owner|--group|--mode) COMPREPLY=() ;;
        copy) COMPREPLY=($(compgen -f -- "$cur")) ;; # local path
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --owner --group --mode" -- "$cur")) ;;
      esac
      ;;

    write-file)
      # Unlike copy's first positional (a local path), write-file's
      # first positional is the REMOTE dest path — nothing locally to
      # complete against, so only --content-file's value gets -f.
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --owner|--group|--mode) COMPREPLY=() ;;
        --content) COMPREPLY=() ;;
        --content-file) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--content --content-file --nodes --node-groups --exclude-nodes --owner --group --mode" -- "$cur")) ;;
      esac
      ;;

    network-apply)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --timeout) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --timeout" -- "$cur")) ;;
      esac
      ;;

    pipeline)
      case "$prev" in
        --nodes|--parallel) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --parallel --quiet" -- "$cur")) ;;
      esac
      ;;

    shutdown)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --delay) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --delay --yes" -- "$cur")) ;;
      esac
      ;;

    reboot)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --delay|--wait-timeout) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --delay --wait --wait-timeout" -- "$cur")) ;;
      esac
      ;;

    uptime)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes" -- "$cur")) ;;
      esac
      ;;

    nodes)
      case "$prev" in
        --nodes|--exclude-nodes) COMPREPLY=($(compgen -W "$(_orphera_nodes)" -- "$cur")) ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --groups" -- "$cur")) ;;
      esac
      ;;

    init-ca)
      COMPREPLY=($(compgen -W "--days --name" -- "$cur"))
      ;;

    show-config)
      # Names of the files in the config directory (-c, ORPHERA_CONFIG_DIR, else config);
      # a database location has no files to list.
      local cfg="${_ORPHERA_CFG:-${ORPHERA_CONFIG_DIR:-config}}" names
      cfg="${cfg#file://}"
      case "$cfg" in
        *://*) COMPREPLY=() ;;
        *)
          names=$(cd "$cfg" 2>/dev/null && for f in *.yaml; do [ -e "$f" ] && echo "${f%.yaml}"; done)
          COMPREPLY=($(compgen -W "$names" -- "$cur")) ;;
      esac
      ;;

    ilo)
      if [ "$cword" -eq 2 ]; then
        COMPREPLY=($(compgen -W "status on off shutdown reset soft-reset" -- "$cur"))
      elif [[ "$cur" == -* ]]; then
        COMPREPLY=($(compgen -W "--user --password-file --secret --insecure --cacert --yes --dry-run --no-wait --timeout" -- "$cur"))
      fi
      ;;

    secrets)
      if [ "$cword" -eq 2 ]; then
        COMPREPLY=($(compgen -W "init list generate set get rm" -- "$cur"))
      elif [ "$cword" -eq 3 ]; then
        case "${words[2]}" in
          get|set|rm|generate)
            # The names only (never the values), from `orphera secrets list`; the
            # timeout keeps a missing or locked store from hanging the prompt.
            local names
            names=$(timeout 5 orphera secrets list 2>/dev/null)
            COMPREPLY=($(compgen -W "$names" -- "$cur")) ;;
          *) COMPREPLY=() ;;
        esac
      else
        COMPREPLY=()
      fi
      ;;

    os10)
      # orphera os10 <command> <switch> ...: the commands, then the options
      if [ "$cword" -eq 2 ]; then
        COMPREPLY=($(compgen -W "interfaces vlans vlan port get operations schema backup set-description set-mtu set-speed set-mode vlan-create vlan-delete vlan-add vlan-remove save help" -- "$cur"))
      else
        case "$prev" in
          -u|--user|--secret) COMPREPLY=() ;;
          --password-file|--cacert) COMPREPLY=($(compgen -f -- "$cur")) ;;
          *) COMPREPLY=($(compgen -W "--user --password-file --secret --insecure --cacert --json --yes --dry-run --untagged" -- "$cur")) ;;
        esac
      fi
      ;;

    thread-info)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --detail --reset-peak" -- "$cur")) ;;
      esac
      ;;

    run)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --timeout) COMPREPLY=() ;;
        --) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --timeout --quiet --" -- "$cur")) ;;
      esac
      ;;

    playbook|cluster-playbook)
      # prev doesn't actually distinguish anything here — every non-flag
      # position for either verb is a playbook path (cluster-playbook
      # takes one or more, in a row, before --resume) — so both branches
      # do the same thing; kept as a case for readability/symmetry with
      # every other verb below, and in case a verb-specific flag needs its
      # own branch here later (--resume already falls through to no
      # completion via the flag literal below).
      # cluster-playbook's --config takes several files in a row (up to the
      # next --flag), so the last flag on the line decides whether a bare
      # word is a config file or a playbook path.
      local last_flag="" w
      for w in "${words[@]:2:cword-2}"; do
        [[ "$w" == --* ]] && last_flag="$w"
      done
      case "$prev" in
        --resume|--quiet|--forget-host-key) COMPREPLY=() ;;
        --parallel|--ssh-user|--ssh-key) COMPREPLY=() ;;
        --agent|--file) COMPREPLY=($(compgen -f -X '!*.@(deb|rpm)' -- "$cur")) ;;
        *)
          if [[ "$cur" == --* ]]; then
            if [[ "$verb" == cluster-playbook ]]; then
              COMPREPLY=($(compgen -W "--resume --config --parallel --quiet --ssh-user --ssh-key --agent --forget-host-key" -- "$cur"))
            else
              COMPREPLY=($(compgen -W "--resume --config" -- "$cur"))
            fi
          elif [[ "$last_flag" == --config ]]; then
            COMPREPLY=($(compgen -f -- "$cur"))
          else
            _orphera_playbook_path_completions "$cur"
          fi
          ;;
      esac
      ;;

    deploy-agent)
      # No positional args — the .deb is --agent (auto-discovered if
      # omitted), not a bare path like playbook/bootstrap take.
      case "$prev" in
        --agent|--file) COMPREPLY=($(compgen -f -X '!*.@(deb|rpm)' -- "$cur")) ;;
        --remote-path|--parallel) COMPREPLY=() ;;
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--agent --scala --force --parallel --remote-path --nodes --node-groups --exclude-nodes" -- "$cur")) ;;
      esac
      ;;

    bootstrap)
      # No positional args here either — same as deploy-agent, the
      # package is --agent (.deb or .rpm; auto-discovered if omitted).
      case "$prev" in
        --agent|--file) COMPREPLY=($(compgen -f -X '!*.@(deb|rpm)' -- "$cur")) ;;
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        --remote-path) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--agent --nodes --node-groups --exclude-nodes --ssh-user --ssh-key --remote-path --forget-host-key" -- "$cur")) ;;
      esac
      ;;

    issue-certs)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --ssh-user --ssh-key" -- "$cur")) ;;
      esac
      ;;

    teardown)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes --ssh-user --ssh-key --purge --yes" -- "$cur")) ;;
      esac
      ;;

    fetch)
      case "$prev" in
        --out) COMPREPLY=($(compgen -d -- "$cur")) ;;
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--out --nodes --node-groups --exclude-nodes" -- "$cur")) ;;
      esac
      ;;

    facts|version|cert-info)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --node-groups) COMPREPLY=($(compgen -W "$(_orphera_groups)" -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --node-groups --exclude-nodes" -- "$cur")) ;;
      esac
      ;;

    log-summary)
      # <playbook-name | file.jsonl> — no flags, so only offer .jsonl
      # paths; a bare playbook name has nothing to complete against.
      case "$prev" in
        log-summary) COMPREPLY=($(compgen -f -X '!*.jsonl' -- "$cur")) ;;
        *) COMPREPLY=() ;;
      esac
      ;;

    audit-log)
      case "$prev" in
        --limit) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--limit --full" -- "$cur")) ;;
      esac
      ;;

    *)
      COMPREPLY=()
      ;;
  esac
}

complete -F _orphera_completions orphera

# --- utils/vmctl.sh and the other VM scripts ----------------------------------------------------
# Completes the actions, options, VM names and hypervisor names of utils/vmctl.sh, and of start-vm.sh,
# stop-vm.sh, reboot-vm.sh, list-vms.sh, move-vm.sh, scan-vm-sizes.sh and galera-cold-start.sh; each also
# under the name of its Scala Native version (utils/vmctl, utils/start-vm, ...). VM names are the
# config files in the config directory (ORPHERA_CONFIG_DIR, a path or a file: URI, else config/);
# hypervisor names are the members of the inventory group `hypervisors` (read from the inventory file,
# ORPHERA_INVENTORY, else inventory.yaml), else $HYPERVISORS, else gs1 gs2 gs3 st0.

_vmctl_config_dir() {
  local d="${ORPHERA_CONFIG_DIR:-config}"
  d="${d#file://}"
  # Only a directory can be read here; another kind of location has no names to offer.
  case "$d" in *://*) return 1 ;; esac
  [ -d "$d" ] || return 1
  printf '%s\n' "${d%/}"
}

_vmctl_vms() {
  local d f
  d=$(_vmctl_config_dir) || return 0
  for f in "$d"/*.yaml; do
    [ -e "$f" ] || continue
    grep -q '^  is_hypervisor:[[:space:]]*true' "$f" && continue  # a hypervisor's config, not a VM's
    f=${f##*/}
    printf '%s\n' "${f%.yaml}"
  done
}

_vmctl_hypervisors() {
  local inv="${ORPHERA_INVENTORY:-inventory.yaml}" out=""
  inv="${inv#file://}"
  # Only a file can be read here (not a database); there the group is not looked for, and the next source is used.
  case "$inv" in
    *://*) ;;
    *) [ -f "$inv" ] && out=$(awk '
         /^[[:space:]]*-[[:space:]]*name:/ { ingroup = ($0 ~ /name:[[:space:]]*["\x27]?hypervisors["\x27]?[[:space:]]*(#.*)?$/); inlist = 0; next }
         ingroup && /^[[:space:]]*members:/ {
           v = $0; sub(/^[[:space:]]*members:[[:space:]]*/, "", v)
           if (v ~ /^\[/) { gsub(/[\[\],"\x27]/, " ", v); print v; exit }
           inlist = 1; next }
         ingroup && inlist && /^[[:space:]]*-[[:space:]]/ { v = $0; sub(/^[[:space:]]*-[[:space:]]*/, "", v); gsub(/["\x27]/, "", v); printf "%s ", v; next }
         ingroup && inlist && /^[[:space:]]*[A-Za-z]/ { inlist = 0 }
       ' "$inv" 2>/dev/null) ;;
  esac
  [ -n "${out//[[:space:]]/}" ] || out="${HYPERVISORS:-gs1 gs2 gs3 st0}"
  printf '%s\n' $out
}

# _vm_complete_list <cur> <names...>  completes a comma-separated list (--node-groups g1,g2, --exclude-nodes a,b):
# the part after the last comma, keeping what came before it and leaving out the names already in it.
_vm_complete_list() {
  local cur="$1" prefix="" last="$1" names="" n
  shift
  if [[ "$cur" == *,* ]]; then
    prefix="${cur%,*},"
    last="${cur##*,}"
  fi
  for n in "$@"; do
    case ",$prefix" in *",$n,"*) ;; *) names="$names $n" ;; esac
  done
  COMPREPLY=($(compgen -P "$prefix" -W "$names" -- "$last"))
  [[ ${#COMPREPLY[@]} -gt 0 ]] && compopt -o nospace 2>/dev/null
  return 0
}

_vmctl_completions() {
  local cur prev words cword
  _init_completion || return

  local actions="start stop reboot shutdown-all start-all autostart list"
  if [[ $cword -eq 1 ]]; then
    COMPREPLY=($(compgen -W "$actions" -- "$cur"))
    return 0
  fi

  local action="${words[1]}"
  if [[ "$action" == autostart && $cword -eq 2 ]]; then
    COMPREPLY=($(compgen -W "add remove" -- "$cur"))
    return 0
  fi

  # Values of the options that take one.
  case "$prev" in
    --retries|--wait|--parallel) COMPREPLY=(); return 0 ;;
    --hypervisor) COMPREPLY=($(compgen -W "$(_vmctl_hypervisors)" -- "$cur")); return 0 ;;
    --except|--exclude-nodes) _vm_complete_list "$cur" $(_vmctl_vms); return 0 ;;
    --node-groups) _vm_complete_list "$cur" $(_orphera_groups); return 0 ;;
  esac

  local opts=""
  case "$action" in
    start)        opts="--retries --parallel --node-groups --exclude-nodes" ;;
    stop)         opts="--wait --force --parallel --node-groups --exclude-nodes" ;;
    reboot)       opts="--hard --soft --wait --force --retries --parallel --node-groups --exclude-nodes" ;;
    shutdown-all) opts="--yes --wait --force --except" ;;
    start-all)    opts="--all --dry-run --retries --except" ;;
    autostart)    opts="--all --hypervisor --node-groups --exclude-nodes" ;;
  esac

  if [[ "$cur" == -* ]]; then
    COMPREPLY=($(compgen -W "$opts" -- "$cur"))
    return 0
  fi

  # Positional arguments: hypervisors for these, VMs for the rest.
  case "$action" in
    shutdown-all|start-all|list) COMPREPLY=($(compgen -W "$(_vmctl_hypervisors)" -- "$cur")) ;;
    start|stop|reboot|autostart) COMPREPLY=($(compgen -W "$(_vmctl_vms)" -- "$cur")) ;;
    *) COMPREPLY=() ;;
  esac
}

# _vm_names <script>  the names a script of utils/ may be typed as: with and without .sh (the native version),
# bare, after ./, after utils/ and after ./utils/.
_vm_names() {
  local n
  for n in "$1.sh" "$1"; do printf '%s ./%s utils/%s ./utils/%s ' "$n" "$n" "$n" "$n"; done
}

complete -F _vmctl_completions $(_vm_names vmctl)

# start-vm, stop-vm, reboot-vm: VM names; --node-groups, --exclude-nodes, --parallel, and their own options.
_vm_script_completions() {
  local cur prev words cword
  _init_completion || return
  local cmd="${words[0]##*/}"
  cmd="${cmd%.sh}"
  case "$prev" in
    --wait|--parallel) COMPREPLY=(); return 0 ;;
    --exclude-nodes) _vm_complete_list "$cur" $(_vmctl_vms); return 0 ;;
    --node-groups) _vm_complete_list "$cur" $(_orphera_groups); return 0 ;;
  esac
  local opts="--parallel --node-groups --exclude-nodes --help"
  case "$cmd" in
    stop-vm)   opts="--wait --force $opts" ;;
    reboot-vm) opts="--soft --hard --wait --force $opts" ;;
  esac
  if [[ "$cur" == -* ]]; then
    COMPREPLY=($(compgen -W "$opts" -- "$cur"))
  else
    COMPREPLY=($(compgen -W "$(_vmctl_vms)" -- "$cur"))
  fi
}
complete -F _vm_script_completions $(_vm_names start-vm) $(_vm_names stop-vm) $(_vm_names reboot-vm)

# list-vms: hypervisor names.
_list_vms_completions() {
  local cur prev words cword
  _init_completion || return
  COMPREPLY=($(compgen -W "$(_vmctl_hypervisors)" -- "$cur"))
}
complete -F _list_vms_completions $(_vm_names list-vms)

# scan-vm-sizes: VM names.
_scan_vm_sizes_completions() {
  local cur prev words cword
  _init_completion || return
  COMPREPLY=($(compgen -W "$(_vmctl_vms)" -- "$cur"))
}
complete -F _scan_vm_sizes_completions $(_vm_names scan-vm-sizes)

# move-vm [options] <vm> <hypervisor>: the options, then a VM, then a hypervisor.
_move_vm_completions() {
  local cur prev words cword
  _init_completion || return
  case "$prev" in
    --stop-wait|--boot-wait|--transfer-timeout|--port) COMPREPLY=(); return 0 ;;
  esac
  if [[ "$cur" == -* ]]; then
    COMPREPLY=($(compgen -W "--dry-run --yes --delete-source --no-start --force-stop --stop-wait --boot-wait --transfer-timeout --port --help" -- "$cur"))
    return 0
  fi
  # The arguments before this one that are not options or option values: none so far means the VM, one the hypervisor.
  local i n=0
  for ((i = 1; i < cword; i++)); do
    case "${words[i]}" in
      --stop-wait|--boot-wait|--transfer-timeout|--port) i=$((i + 1)) ;;
      -*) ;;
      *) n=$((n + 1)) ;;
    esac
  done
  case "$n" in
    0) COMPREPLY=($(compgen -W "$(_vmctl_vms)" -- "$cur")) ;;
    1) COMPREPLY=($(compgen -W "$(_vmctl_hypervisors)" -- "$cur")) ;;
    *) COMPREPLY=() ;;
  esac
}
complete -F _move_vm_completions $(_vm_names move-vm)

# galera-cold-start: --check, --bootstrap <tst3|tst4|tst5>.
_galera_cold_start_completions() {
  local cur prev words cword
  _init_completion || return
  if [[ "$prev" == --bootstrap ]]; then
    COMPREPLY=($(compgen -W "tst3 tst4 tst5" -- "$cur"))
  else
    COMPREPLY=($(compgen -W "--check --bootstrap --help" -- "$cur"))
  fi
}
complete -F _galera_cold_start_completions $(_vm_names galera-cold-start)
