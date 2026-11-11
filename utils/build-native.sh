#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds Scala Native executables from utils-native/ in one sbt run and installs each in utils/, beside its
# shell version. Each argument is <sbt project>:<executable>; `make native-utils` gives all of them.
#
#   utils/build-native.sh vmctl:vmctl moveVm:move-vm
#
# Needs sbt and clang. Exit code 1 if sbt failed or an executable is missing from its output.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
[ $# -gt 0 ] || { echo "usage: utils/build-native.sh <project>:<executable>..." >&2; exit 2; }

cmds=()
for pair in "$@"; do cmds+=("print ${pair%%:*}/nativeLink"); done
# `print <project>/nativeLink` links it and prints where the executable is.
out=$(cd utils-native && sbt -batch -Dsbt.supershell=false -Dsbt.color=false "${cmds[@]}" | tee /dev/stderr) || {
  echo "build-native: sbt failed" >&2
  exit 1
}
rc=0
for pair in "$@"; do
  exe=${pair#*:}
  path=$(printf '%s\n' "$out" | grep -E "^/.*/$exe\$" | tail -1)
  if [ -n "$path" ] && install -m 755 "$path" "utils/$exe"; then
    echo "Installed utils/$exe"
  else
    echo "build-native: no executable for $exe in sbt's output" >&2
    rc=1
  fi
done
exit $rc
