# SPDX-License-Identifier: Apache-2.0
#
# Bash completion for the orphera CLI.
#
# Install:
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
_orphera_playbook_path_completions() {
  local cur="$1"
  local -a matches

  matches+=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "$cur"))

  if [[ -d manifests && "$cur" != */* ]]; then
    matches+=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "manifests/$cur"))
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

  local verbs="install remove autoremove copy write-file network-apply reboot uptime run
    playbook cluster-playbook deploy-agent bootstrap teardown fetch facts
    version log-summary audit-log help"

  # Flags common to most verbs that take --nodes
  local nodes_flag="--nodes"

  # First word after "orphera" itself — complete the verb.
  if [[ $cword -eq 1 ]]; then
    COMPREPLY=($(compgen -W "$verbs" -- "$cur"))
    return
  fi

  local verb="${words[1]}"

  case "$verb" in
    install)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --update-cache" -- "$cur")) ;;
      esac
      ;;

    remove)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --purge" -- "$cur")) ;;
      esac
      ;;

    autoremove)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --purge" -- "$cur")) ;;
      esac
      ;;

    copy)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --owner|--group|--mode) COMPREPLY=() ;;
        copy) COMPREPLY=($(compgen -f -- "$cur")) ;; # local path
        *) COMPREPLY=($(compgen -W "--nodes --owner --group --mode" -- "$cur")) ;;
      esac
      ;;

    write-file)
      # Unlike copy's first positional (a local path), write-file's
      # first positional is the REMOTE dest path — nothing locally to
      # complete against, so only --content-file's value gets -f.
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --owner|--group|--mode) COMPREPLY=() ;;
        --content) COMPREPLY=() ;;
        --content-file) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--content --content-file --nodes --owner --group --mode" -- "$cur")) ;;
      esac
      ;;

    network-apply)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --timeout) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --timeout" -- "$cur")) ;;
      esac
      ;;

    reboot)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --delay|--wait-timeout) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --delay --wait --wait-timeout" -- "$cur")) ;;
      esac
      ;;

    uptime)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes" -- "$cur")) ;;
      esac
      ;;

    run)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --timeout) COMPREPLY=() ;;
        --) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --timeout --" -- "$cur")) ;;
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
      case "$prev" in
        --resume) COMPREPLY=() ;;
        --config) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *)
          if [[ "$cur" == --* ]]; then
            COMPREPLY=($(compgen -W "--resume --config" -- "$cur"))
          else
            _orphera_playbook_path_completions "$cur"
          fi
          ;;
      esac
      ;;

    deploy-agent)
      # No positional args — the .deb is --file (auto-discovered if
      # omitted), not a bare path like playbook/bootstrap take.
      case "$prev" in
        --file) COMPREPLY=($(compgen -f -X '!*.deb' -- "$cur")) ;;
        --remote-path) COMPREPLY=() ;;
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--file --remote-path --nodes" -- "$cur")) ;;
      esac
      ;;

    bootstrap)
      # No positional args here either — same as deploy-agent, the
      # .deb is --file (auto-discovered if omitted).
      case "$prev" in
        --file) COMPREPLY=($(compgen -f -X '!*.deb' -- "$cur")) ;;
        --nodes) COMPREPLY=() ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        --remote-path) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--file --nodes --ssh-user --ssh-key --remote-path" -- "$cur")) ;;
      esac
      ;;

    teardown)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        *) COMPREPLY=($(compgen -W "--nodes --ssh-user --ssh-key --purge --yes" -- "$cur")) ;;
      esac
      ;;

    fetch)
      case "$prev" in
        --out) COMPREPLY=($(compgen -d -- "$cur")) ;;
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--out --nodes" -- "$cur")) ;;
      esac
      ;;

    facts|version)
      case "$prev" in
        --nodes) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes" -- "$cur")) ;;
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
        *) COMPREPLY=($(compgen -W "--limit" -- "$cur")) ;;
      esac
      ;;

    *)
      COMPREPLY=()
      ;;
  esac
}

complete -F _orphera_completions orphera
