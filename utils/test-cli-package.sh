#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Checks the orphera command-line package without building anything: stages it with stand-in jars,
# then checks the layout, that the launcher finds its own installation (also through a symlink and
# after the tree is moved), that it sets the site defaults without overriding the environment, and that
# the shell scripts of the package pick the same defaults up. `java` is a stand-in that prints what it was given.
# Run from anywhere: make check-package.
set -u
cd "$(dirname "$0")/.." || exit 1

fail=0
ok() { echo "  ok    $1"; }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }
eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: got [$2], want [$3]"; fi; }
has() { if grep -qF -- "$3" <<<"$2"; then ok "$1"; else bad "$1: [$3] not in: $2"; fi; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unset JAVA_HOME ORPHERA_INVENTORY ORPHERA_CONFIG_DIR ORPHERA_CERTS_DIR ORPHERA_MANIFESTS ORPHERA_CATALOGUES \
  ORPHERA_STATE_DIR ORPHERA_SITE_DIR ORPHERA_ORCHESTRATOR_JAR ORPHERA_SCRIPTING_JAR ORPHERA_PREFIX XDG_STATE_HOME

echo orchestrator >"$tmp/o.jar"
echo scripting >"$tmp/s.jar"
stage="$tmp/stage"
packaging/cli/stage.sh "$stage" 9.9.9 "$tmp/o.jar" "$tmp/s.jar" >/dev/null || { echo "staging failed"; exit 1; }
p="$stage/opt/orphera"

# The layout
[ -x "$p/bin/orphera" ] && ok "bin/orphera is executable" || bad "bin/orphera is executable"
eq "the jars are the ones given" "$(cat "$p/lib/orphera-orchestrator.jar" "$p/lib/orphera-scripting.jar" | tr '\n' ' ')" "orchestrator scripting "
for f in lib/orphera-env.sh manifests/build_openstack_stack.sh manifests/rebuild_openstack_from_scratch.sh \
  utils/orphera-sources.sh utils/sql-to-yaml.py schemas/inventory.schema.json catalogues examples/inventory.yaml \
  examples/config README.md LICENSE; do
  [ -e "$p/$f" ] && ok "has $f" || bad "has $f"
done
[ ! -e "$p/utils/orphera" ] && ok "the checkout's launcher is not in the package" || bad "the checkout's launcher is not in the package"
[ -z "$(find "$p" -name __pycache__)" ] && ok "no Python cache" || bad "no Python cache"
eq "VERSION" "$(cat "$p/VERSION")" "9.9.9"
has "control: version" "$(cat "$stage/DEBIAN/control")" "Version: 9.9.9"
has "control: architecture" "$(cat "$stage/DEBIAN/control")" "Architecture: all"
eq "/usr/bin/orphera points at the installed launcher" "$(readlink "$stage/usr/bin/orphera")" "/opt/orphera/bin/orphera"
eq "the site config directory is empty" "$(ls -A "$stage/etc/orphera/config")" ""
eq "the site certs directory is empty" "$(ls -A "$stage/etc/orphera/certs")" ""
[ -f "$stage/etc/bash_completion.d/orphera" ] && ok "completion is installed" || bad "completion is installed"

# The launcher, with a `java` that prints its arguments and the ORPHERA_ environment
mkdir -p "$tmp/fakebin" "$tmp/home"
cat >"$tmp/fakebin/java" <<'JAVA'
#!/bin/sh
echo "ARGS $*"
env | grep '^ORPHERA_' | sort
JAVA
chmod +x "$tmp/fakebin/java"
run() { env -i PATH="$tmp/fakebin:/usr/bin:/bin" HOME="$tmp/home" "$@"; }

out=$(run "$p/bin/orphera" version --nodes tst0)
has "launcher: runs the orchestrator jar" "$out" "ARGS -cp $p/lib/orphera-orchestrator.jar orphera.orchestrator.Main version --nodes tst0"
has "launcher: inventory default" "$out" "ORPHERA_INVENTORY=/etc/orphera/inventory.yaml"
has "launcher: config default" "$out" "ORPHERA_CONFIG_DIR=/etc/orphera/config"
has "launcher: certs default" "$out" "ORPHERA_CERTS_DIR=/etc/orphera/certs"
has "launcher: manifests default" "$out" "ORPHERA_MANIFESTS=$p/manifests"
has "launcher: root default" "$out" "ORPHERA_ROOT=$p"
has "launcher: catalogues default" "$out" "ORPHERA_CATALOGUES=$p/catalogues"
has "launcher: state under the home" "$out" "ORPHERA_STATE_DIR=$tmp/home/.local/state/orphera"
has "launcher: scripting jar default" "$out" "ORPHERA_SCRIPTING_JAR=$p/lib/orphera-scripting.jar"

out=$(run ORPHERA_INVENTORY=/x/inv.yaml ORPHERA_SITE_DIR=/srv/o XDG_STATE_HOME=/var/st "$p/bin/orphera" help)
has "environment wins over the default" "$out" "ORPHERA_INVENTORY=/x/inv.yaml"
has "ORPHERA_SITE_DIR moves the other site defaults" "$out" "ORPHERA_CONFIG_DIR=/srv/o/config"
has "XDG_STATE_HOME moves the state" "$out" "ORPHERA_STATE_DIR=/var/st/orphera"

mkdir -p "$tmp/usrbin" && ln -s "$p/bin/orphera" "$tmp/usrbin/orphera"
out=$(run "$tmp/usrbin/orphera" help)
has "found through a symlink" "$out" "ARGS -cp $p/lib/orphera-orchestrator.jar"

cp -a "$p" "$tmp/moved"
out=$(run "$tmp/moved/bin/orphera" help)
has "a moved tree finds itself" "$out" "ARGS -cp $tmp/moved/lib/orphera-orchestrator.jar"

out=$(run JAVA_HOME="$tmp/jh" "$p/bin/orphera" help 2>&1); rc=$?
[ "$rc" -ne 0 ] && ok "a JAVA_HOME without java is an error" || bad "a JAVA_HOME without java is an error: $out"

rm "$tmp/moved/lib/orphera-orchestrator.jar"
out=$(run "$tmp/moved/bin/orphera" help 2>&1); rc=$?
if [ "$rc" -ne 0 ] && grep -q "incomplete" <<<"$out"; then ok "a missing jar says the installation is incomplete"; else bad "a missing jar says the installation is incomplete: $out"; fi

# The shell scripts of the package
got=$(cd "$p" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" bash -c '. utils/orphera-sources.sh; config_dir; inventory_file; echo "$PATH"')
eq "scripts: config directory" "$(sed -n 1p <<<"$got")" "/etc/orphera/config"
eq "scripts: inventory" "$(sed -n 2p <<<"$got")" "/etc/orphera/inventory.yaml"
eq "scripts: orphera is on PATH" "$(sed -n 3p <<<"$got")" "$p/bin:/usr/bin:/bin"
got=$(cd "$p" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" ORPHERA_CONFIG_DIR=/y/cfg bash -c '. utils/orphera-sources.sh; config_dir')
eq "scripts: the environment still wins" "$got" "/y/cfg"
got=$(cd "$p/manifests" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" bash -c '. ../utils/orphera-sources.sh; config_dir')
eq "scripts: found from another directory" "$got" "/etc/orphera/config"

# A checkout is unchanged: no lib/orphera-env.sh beside utils/
got=$(env -i PATH=/usr/bin:/bin HOME="$tmp/home" bash -c ". utils/orphera-sources.sh; config_dir; echo \"\$PATH\"")
eq "a checkout: config directory" "$(sed -n 1p <<<"$got")" "config"
eq "a checkout: PATH is untouched" "$(sed -n 2p <<<"$got")" "/usr/bin:/bin"

if [ "$fail" -eq 0 ]; then echo "test-cli-package: all checks passed"; else echo "test-cli-package: $fail failed"; exit 1; fi
