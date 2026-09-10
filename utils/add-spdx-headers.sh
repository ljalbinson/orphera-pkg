#!/bin/sh
# Adds an SPDX-License-Identifier header to every .scala file under the
# current directory (excluding build output and sbt's own state), unless
# the file already has one somewhere in its first few lines.
#
# Usage:
#   ./add-spdx-headers.sh              # apply with default Apache-2.0
#   ./add-spdx-headers.sh MIT          # apply with a different identifier
#   ./add-spdx-headers.sh --dry-run    # list files that would change, no writes

set -eu

LICENSE_ID="Apache-2.0"
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) LICENSE_ID="$arg" ;;
  esac
done

HEADER="// SPDX-License-Identifier: ${LICENSE_ID}"

FILES=$(find . \
  -name "*.scala" \
  -not -path "*/target/*" \
  -not -path "*/project/target/*" \
  -not -path "*/project/project/*" \
  -not -path "*/.bloop/*" \
  -not -path "*/.metals/*" \
  -not -path "*/.bsp/*")

COUNT=0
SKIPPED=0

for f in $FILES; do
  # Skip files that already have an SPDX tag anywhere in the first 5 lines.
  if head -n 5 "$f" | grep -q "SPDX-License-Identifier"; then
    SKIPPED=$((SKIPPED + 1))
    continue
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "would add header: $f"
  else
    tmp=$(mktemp)
    {
      echo "$HEADER"
      echo
      cat "$f"
    } > "$tmp"
    mv "$tmp" "$f"
    echo "added header: $f"
  fi

  COUNT=$((COUNT + 1))
done

if [ "$DRY_RUN" -eq 1 ]; then
  echo "---"
  echo "$COUNT file(s) would be updated, $SKIPPED already had a header."
else
  echo "---"
  echo "$COUNT file(s) updated, $SKIPPED already had a header."
fi
