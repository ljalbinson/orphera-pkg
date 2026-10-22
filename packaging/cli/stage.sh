#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Stages the orphera command-line package: the tree that `make cli-deb`, `make cli-rpm` and
# `make cli-tar` turn into a .deb, an .rpm and a .tar.gz.
#
#   packaging/cli/stage.sh STAGE_DIR VERSION ORCHESTRATOR_JAR SCRIPTING_JAR
#
#   STAGE_DIR/opt/orphera/   bin/orphera, lib/ (the two jars and orphera-env.sh), manifests/ (playbooks
#                            and their scripts), utils/, catalogues/, schemas/, files/, docs/, examples/
#   STAGE_DIR/etc/orphera/   empty config/ and certs/: the site's own data goes here
#   STAGE_DIR/usr/bin/orphera, STAGE_DIR/etc/bash_completion.d/orphera, STAGE_DIR/DEBIAN/control
#
# It takes the jars as arguments, so a test can stage with stand-ins and no build.
set -euo pipefail

die() { echo "stage.sh: $1" >&2; exit 1; }
[ $# -eq 4 ] || die "usage: stage.sh STAGE_DIR VERSION ORCHESTRATOR_JAR SCRIPTING_JAR"
stage=$1 version=$2 orch=$3 scripting=$4
[ -f "$orch" ] || die "no such file: $orch (build it: sbt orchestrator/assembly)"
[ -f "$scripting" ] || die "no such file: $scripting (build it: sbt scripting/assembly)"

root=$(cd "$(dirname "$0")/../.." && pwd)
p="$stage/opt/orphera"

rm -rf "$stage"
mkdir -p "$stage/DEBIAN" "$p/bin" "$p/lib" "$stage/etc/orphera/config" "$stage/etc/orphera/certs" \
  "$stage/usr/bin" "$stage/etc/bash_completion.d" "$p/examples"

install -m 755 "$root/packaging/cli/orphera" "$p/bin/orphera"
install -m 644 "$root/packaging/cli/orphera-env.sh" "$p/lib/orphera-env.sh"
install -m 644 "$orch" "$p/lib/orphera-orchestrator.jar"
install -m 644 "$scripting" "$p/lib/orphera-scripting.jar"

for d in manifests utils catalogues schemas files docs; do
  cp -a "$root/$d" "$p/$d"
done
# The checkout's launcher is not part of the package, and neither is a Python cache.
rm -f "$p/utils/orphera"
find "$p" -name __pycache__ -prune -exec rm -rf {} +

# The lab's own inventory and configs are examples of the format, not defaults.
cp "$root/inventory.yaml" "$p/examples/inventory.yaml"
cp -a "$root/config" "$p/examples/config"
cp "$root/README.md" "$root/CHANGELOG.md" "$root/LICENSE" "$p/"
printf '%s\n' "$version" > "$p/VERSION"

install -m 644 "$root/orphera-completion.bash" "$stage/etc/bash_completion.d/orphera"
ln -s /opt/orphera/bin/orphera "$stage/usr/bin/orphera"
sed "s/@VERSION@/$version/" "$root/packaging/cli/control.template" > "$stage/DEBIAN/control"

echo "Staged orphera $version in $stage"
