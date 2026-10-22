#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Run on a node that has Java (any node with the Scala agent installed):
# compares the network interfaces and disks the Go agent reports with what
# the JVM reports. The orchestrator templates ip_<name> and ip_secondary from
# the interface list, so its order and address format must match.
#
#   compare/compare-facts.sh /path/to/orphera-agent-go
#
# Free-space figures can differ by a little if the disk is in use; anything
# else that differs is a difference between the agents.
set -e
here=$(dirname "$0")
agent=${1:?usage: $0 /path/to/orphera-agent-go}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
java "$here/Facts.java" > "$tmp/java"
"$agent" --print-facts > "$tmp/go"
# Compare everything except the disk sizes, which move while the disk is used.
sed -E 's/^(DISK [^ ]+ [^ ]+) .*/\1/' "$tmp/java" > "$tmp/java.s"
sed -E 's/^(DISK [^ ]+ [^ ]+) .*/\1/' "$tmp/go" > "$tmp/go.s"
if diff -u "$tmp/java.s" "$tmp/go.s"; then
  echo "Interfaces and disks match (names, types, order, addresses, MACs)."
else
  echo "DIFFERENCES above (- JVM, + Go)."
  exit 1
fi
