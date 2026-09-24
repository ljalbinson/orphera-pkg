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

_orphera_completions() {
  local cur prev words cword
  _init_completion || return

  local verbs="install remove autoremove copy network-apply reboot uptime run
    playbook cluster-playbook deploy-agent bootstrap teardown fetch facts
    version help"

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
      case "$prev" in
        "$verb") COMPREPLY=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "$cur")) ;;
        *) COMPREPLY=($(compgen -f -X '!*.@(yaml|yml|scala)' -- "$cur")) ;;
      esac
      ;;

    deploy-agent)
      case "$prev" in
        deploy-agent) COMPREPLY=($(compgen -f -X '!*.deb' -- "$cur")) ;;
        --remote-path) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--remote-path" -- "$cur")) ;;
      esac
      ;;

    bootstrap)
      case "$prev" in
        bootstrap) COMPREPLY=($(compgen -f -X '!*.deb' -- "$cur")) ;;
        --nodes) COMPREPLY=() ;;
        --ssh-user) COMPREPLY=() ;;
        --ssh-key) COMPREPLY=($(compgen -f -- "$cur")) ;;
        --remote-path) COMPREPLY=() ;;
        *) COMPREPLY=($(compgen -W "--nodes --ssh-user --ssh-key --remote-path" -- "$cur")) ;;
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

    *)
      COMPREPLY=()
      ;;
  esac
}

complete -F _orphera_completions orphera
