#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Fails if a tracked file looks like it holds a credential: a lab password in the
# old published style, a quoted cephx key, or a PEM private key. Secrets belong in
# the store (`orphera secrets ...`), never in the repository, which is public.
#
#   utils/check-no-secrets.sh        (also: make check-secrets)
#
# Prose that discusses these patterns lives in the files excluded below.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

EXCLUDE=(':!CHANGELOG.md' ':!README.md' ':!docs/TESTS.md' ':!docs/ROTATING-LAB-SECRETS.md' ':!utils/check-no-secrets.sh')
fail=0
scan() {   # scan DESCRIPTION REGEX
  local out
  out=$(git grep -nE "$2" -- . "${EXCLUDE[@]}" | cut -c1-110)
  if [ -n "$out" ]; then
    echo "FOUND: $1" >&2
    echo "$out" | sed 's/^/  /' >&2
    fail=1
  fi
}

scan "a lab password in the old published style (orphera-test-...-password)" 'orphera-test-[a-z-]*-(password|pass|key)'
scan "a quoted cephx key" "['\"]AQ[A-Za-z0-9+/]{30,}={0,2}"
scan "a PEM private key" 'BEGIN (RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY'

[ "$fail" -eq 0 ] && echo "no credentials found in tracked files"
exit "$fail"
