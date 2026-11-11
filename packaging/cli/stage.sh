#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Stages the orphera command-line package: the tree that `make cli-deb`, `make cli-rpm`, `make cli-tar`
# and `make install` turn into a .deb, an .rpm, a .tar.gz and an installation.
#
#   packaging/cli/stage.sh [--prefix PREFIX] [--no-deb] STAGE_DIR VERSION ORCHESTRATOR_JAR SCRIPTING_JAR
#
# The standard (FHS) layout, under PREFIX (default /usr):
#   PREFIX/bin/orphera                       the launcher
#   PREFIX/bin/vmctl, start-vm, ...          the utility commands (packaging/cli/utilities), each running
#                                            share/orphera/utils/<name>.sh
#   PREFIX/lib/orphera/                      the two jars and orphera-env.sh (the site defaults)
#   PREFIX/share/orphera/                    manifests/ (playbooks and their scripts), utils/, catalogues/,
#                                            schemas/, files/, examples/, VERSION
#   PREFIX/share/doc/orphera/                README.md, CHANGELOG.md, LICENSE, docs/
#   PREFIX/share/bash-completion/completions/orphera
#   /etc/orphera/                            empty config/ and certs/: a shared site's data goes here
#   DEBIAN/control                           unless --no-deb
# The launcher finds the rest from where it is (PREFIX = its bin/..), so the tree also works when it
# is moved, as the tarball is. /etc/orphera is the same whatever the prefix.
#
# It takes the jars as arguments, so a test can stage with stand-ins and no build.
set -euo pipefail

die() { echo "stage.sh: $1" >&2; exit 1; }
prefix=/usr deb=1
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix=${2:-}; shift 2 || die "--prefix needs a directory" ;;
    --no-deb) deb=""; shift ;;
    -*) die "unknown option $1" ;;
    *) break ;;
  esac
done
case "$prefix" in
  /*) prefix=${prefix%/} ;;
  *) die "--prefix must be an absolute path: $prefix" ;;
esac
[ $# -eq 4 ] || die "usage: stage.sh [--prefix PREFIX] [--no-deb] STAGE_DIR VERSION ORCHESTRATOR_JAR SCRIPTING_JAR"
stage=$1 version=$2 orch=$3 scripting=$4
[ -n "$stage" ] && [ "$stage" != / ] || die "refusing to stage into '$stage'"
[ -f "$orch" ] || die "no such file: $orch (build it: sbt orchestrator/assembly)"
[ -f "$scripting" ] || die "no such file: $scripting (build it: sbt scripting/assembly)"

root=$(cd "$(dirname "$0")/../.." && pwd)
p="$stage$prefix"
lib="$p/lib/orphera"
share="$p/share/orphera"
doc="$p/share/doc/orphera"

rm -rf "$stage"
mkdir -p "$p/bin" "$lib" "$share/examples" "$doc" "$p/share/bash-completion/completions" \
  "$stage/etc/orphera/config" "$stage/etc/orphera/certs"

install -m 755 "$root/packaging/cli/orphera" "$p/bin/orphera"
utilities=$(grep -v '^[[:space:]]*#' "$root/packaging/cli/utilities" | grep -v '^[[:space:]]*$')
for u in $utilities; do
  install -m 755 "$root/packaging/cli/utility" "$p/bin/$u"
done
install -m 644 "$root/packaging/cli/orphera-env.sh" "$lib/orphera-env.sh"
install -m 644 "$orch" "$lib/orphera-orchestrator.jar"
install -m 644 "$scripting" "$lib/orphera-scripting.jar"

for d in manifests utils catalogues schemas files; do
  cp -a "$root/$d" "$share/$d"
done
# The checkout's launchers are not part of the package, and neither is a Python cache.
rm -f "$share/utils/orphera" "$share/utils/orphera-utility"
# Nor are the native executables of the utilities (built here for this machine only; the package is for any).
for u in $utilities; do rm -f "$share/utils/$u"; done
find "$share" -name __pycache__ -prune -exec rm -rf {} +

# The lab's own inventory and configs are examples of the format, not defaults.
cp "$root/inventory.yaml" "$share/examples/inventory.yaml"
cp -a "$root/config" "$share/examples/config"
printf '%s\n' "$version" > "$share/VERSION"

cp "$root/README.md" "$root/CHANGELOG.md" "$root/LICENSE" "$doc/"
cp -a "$root/docs" "$doc/docs"

install -m 644 "$root/orphera-completion.bash" "$p/share/bash-completion/completions/orphera"

if [ -n "$deb" ]; then
  mkdir -p "$stage/DEBIAN"
  sed "s/@VERSION@/$version/" "$root/packaging/cli/control.template" > "$stage/DEBIAN/control"
fi

echo "Staged orphera $version in $stage (prefix $prefix)"
