#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Gives the agents on the named nodes the shared token kept in the secret store
# (`orphera/agent-token`), and restarts them. The orchestrator sends the same token
# (see AgentToken.scala). Until this is done an agent accepts the built-in development
# token, which is in the source code.
#
#   orphera secrets generate orphera/agent-token     # once
#   utils/set-agent-token.sh tst0 tst1 tst2 ...      # SSH_USER defaults to localadmin
#
# The token travels on ssh's standard input, never on a command line, and ends up in
# /etc/orphera-agent/agent.env (root only, mode 600). Other lines in that file stay.
# Run it for every node BEFORE `orphera bootstrap` replaces the agent package:
# the package keeps a changed agent.env.
set -uo pipefail

[ $# -gt 0 ] || { sed -n '2,/^set /p' "$0" | sed 's/^# \{0,1\}//' | head -n -1; exit 2; }

orphera secrets get orphera/agent-token >/dev/null 2>&1 || {
  echo "there is no agent token yet: run  orphera secrets generate orphera/agent-token" >&2
  exit 1
}

user=${SSH_USER:-localadmin}
REMOTE='umask 077; IFS= read -r t; f=${AGENT_ENV:-/etc/orphera-agent/agent.env}; { grep -v "^#*ORPHERA_TOKEN=" "$f" 2>/dev/null || true; printf "ORPHERA_TOKEN=%s\n" "$t"; } > "$f.new" && mv "$f.new" "$f" && systemctl restart orphera-agent'

failed=()
for node in "$@"; do
  if orphera secrets get orphera/agent-token | ssh "$user@$node" "sudo sh -c '$REMOTE'"; then
    echo "ok    $node"
  else
    echo "FAIL  $node"
    failed+=("$node")
  fi
done
[ ${#failed[@]} -eq 0 ] || { echo "failed: ${failed[*]}" >&2; exit 1; }
