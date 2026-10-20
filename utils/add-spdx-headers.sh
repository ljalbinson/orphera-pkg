#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Kept for the old name: adds an SPDX-License-Identifier header to the tracked source
# files that lack one (Scala, shell, YAML playbooks, sbt, proto, Makefile, ...).
#
#   utils/add-spdx-headers.sh              # apply with Apache-2.0
#   utils/add-spdx-headers.sh MIT          # another identifier
#   utils/add-spdx-headers.sh --dry-run    # list what would change
#
# The work is done by utils/spdx.py, which also has --check. Files that cannot carry a
# comment are listed in utils/spdx-exempt.txt.
set -eu
here=$(dirname "$0")
case "${1:-}" in
  --dry-run) exec python3 "$here/spdx.py" --dry-run ;;
  *) exec python3 "$here/spdx.py" --apply "$@" ;;
esac
