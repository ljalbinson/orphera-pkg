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
  ORPHERA_STATE_DIR ORPHERA_SITE_DIR ORPHERA_SYSTEM_SITE_DIR ORPHERA_ORCHESTRATOR_JAR ORPHERA_SCRIPTING_JAR ORPHERA_PREFIX XDG_STATE_HOME

echo orchestrator >"$tmp/o.jar"
echo scripting >"$tmp/s.jar"
stage="$tmp/stage"
packaging/cli/stage.sh "$stage" 9.9.9 "$tmp/o.jar" "$tmp/s.jar" >/dev/null || { echo "staging failed"; exit 1; }
p="$stage/usr"            # the prefix
lib="$p/lib/orphera"
share="$p/share/orphera"

# The layout
[ -x "$p/bin/orphera" ] && [ ! -h "$p/bin/orphera" ] && ok "bin/orphera is the launcher itself" || bad "bin/orphera is the launcher itself"
eq "the jars are the ones given" "$(cat "$lib/orphera-orchestrator.jar" "$lib/orphera-scripting.jar" | tr '\n' ' ')" "orchestrator scripting "
for f in lib/orphera/orphera-env.sh share/orphera/manifests/build_openstack_stack.sh \
  share/orphera/manifests/rebuild_openstack_from_scratch.sh share/orphera/utils/orphera-sources.sh \
  share/orphera/utils/sql-to-yaml.py share/orphera/schemas/inventory.schema.json share/orphera/catalogues \
  share/orphera/examples/inventory.yaml share/orphera/examples/config share/doc/orphera/README.md \
  share/doc/orphera/LICENSE share/doc/orphera/docs/GETTING_STARTED.md share/bash-completion/completions/orphera; do
  [ -e "$p/$f" ] && ok "has $f" || bad "has $f"
done
[ -z "$(ls -A "$stage" | grep -vxE 'usr|etc|DEBIAN')" ] && ok "nothing outside usr/ and etc/" || bad "nothing outside usr/ and etc/: $(ls -A "$stage")"
[ -z "$(ls -A "$p/bin" | grep -vx orphera)" ] && ok "bin/ holds only orphera" || bad "bin/ holds only orphera: $(ls -A "$p/bin")"
[ ! -e "$share/utils/orphera" ] && ok "the checkout's launcher is not in the package" || bad "the checkout's launcher is not in the package"
[ -z "$(find "$p" -name __pycache__)" ] && ok "no Python cache" || bad "no Python cache"
eq "VERSION" "$(cat "$share/VERSION")" "9.9.9"
has "control: version" "$(cat "$stage/DEBIAN/control")" "Version: 9.9.9"
has "control: architecture" "$(cat "$stage/DEBIAN/control")" "Architecture: all"
eq "the site config directory is empty" "$(ls -A "$stage/etc/orphera/config")" ""
eq "the site certs directory is empty" "$(ls -A "$stage/etc/orphera/certs")" ""
# Every file the rpm lists is in the tree, and the tree has nothing the rpm leaves out
listed=$(sed -n '/^%files/,$p' packaging/cli/orphera.spec.template | sed 's/^%dir //' | grep '^/' | sort)
missing=$(while read -r f; do [ -e "$stage$f" ] || echo "$f"; done <<<"$listed")
eq "rpm %files are all staged" "$missing" ""
unlisted=$(cd "$stage" && find usr etc -mindepth 2 -maxdepth 3 \( -type f -o -type d \) | sed 's,^,/,' | while read -r f; do
  grep -qxF "$f" <<<"$listed" || grep -q "^${f}/" <<<"$listed" || { f2=$f; while [ "$f2" != / ] && ! grep -qxF "$f2" <<<"$listed"; do f2=$(dirname "$f2"); done; [ "$f2" != / ] || echo "$f"; }; done)
eq "nothing staged is left out of the rpm" "$unlisted" ""

# --prefix and --no-deb, as `make install` uses them
packaging/cli/stage.sh --prefix /usr/local --no-deb "$tmp/st2" 9.9.9 "$tmp/o.jar" "$tmp/s.jar" >/dev/null
[ -x "$tmp/st2/usr/local/bin/orphera" ] && [ -f "$tmp/st2/usr/local/lib/orphera/orphera-env.sh" ] && [ ! -e "$tmp/st2/DEBIAN" ] &&
  ok "--prefix /usr/local --no-deb" || bad "--prefix /usr/local --no-deb"
packaging/cli/stage.sh --prefix usr "$tmp/st3" 9.9.9 "$tmp/o.jar" "$tmp/s.jar" >/dev/null 2>&1 &&
  bad "a relative --prefix is refused" || ok "a relative --prefix is refused"

# The launcher, with a `java` that prints its arguments and the ORPHERA_ environment
mkdir -p "$tmp/fakebin" "$tmp/home"
cat >"$tmp/fakebin/java" <<'JAVA'
#!/bin/sh
if [ "$1" = "-version" ] && [ -n "${FAKE_JAVA_VERSION:-}" ]; then
  echo "openjdk version \"$FAKE_JAVA_VERSION\" 2025-01-01" >&2
  exit 0
fi
echo "ARGS $*"
env | grep '^ORPHERA_' | sort
JAVA
chmod +x "$tmp/fakebin/java"
sys="$tmp/system-site"   # stands in for /etc/orphera, so the host's own does not matter
run() { env -i PATH="$tmp/fakebin:/usr/bin:/bin" HOME="$tmp/home" ORPHERA_SYSTEM_SITE_DIR="$sys" "$@"; }

out=$(run "$p/bin/orphera" version --nodes tst0)
has "launcher: runs the orchestrator jar" "$out" "ARGS -cp $lib/orphera-orchestrator.jar orphera.orchestrator.Main version --nodes tst0"
has "launcher: inventory default" "$out" "ORPHERA_INVENTORY=$tmp/home/.config/orphera/inventory.yaml"
has "launcher: config default" "$out" "ORPHERA_CONFIG_DIR=$tmp/home/.config/orphera/config"
has "launcher: certs default" "$out" "ORPHERA_CERTS_DIR=$tmp/home/.config/orphera/certs"
has "launcher: manifests default" "$out" "ORPHERA_MANIFESTS=$share/manifests"
has "launcher: root default" "$out" "ORPHERA_ROOT=$share"
has "launcher: catalogues default" "$out" "ORPHERA_CATALOGUES=$share/catalogues"
has "launcher: state under the home" "$out" "ORPHERA_STATE_DIR=$tmp/home/.local/state/orphera"
has "launcher: scripting jar default" "$out" "ORPHERA_SCRIPTING_JAR=$lib/orphera-scripting.jar"

# JVM options by Java version: none on an older JDK (which would refuse them), the warnings' options on a newer one
out=$(run FAKE_JAVA_VERSION=21.0.4 "$p/bin/orphera" help)
has "java 21: no extra options" "$out" "ARGS -cp $lib/orphera-orchestrator.jar"
out=$(run FAKE_JAVA_VERSION=24.0.1 "$p/bin/orphera" help)
has "java 24: native access" "$out" "ARGS --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp $lib/orphera-orchestrator.jar"
out=$(run FAKE_JAVA_VERSION=22.0.1 "$p/bin/orphera" help)
has "java 22: native access only" "$out" "ARGS --enable-native-access=ALL-UNNAMED -cp $lib/orphera-orchestrator.jar"

out=$(run ORPHERA_INVENTORY=/x/inv.yaml ORPHERA_SITE_DIR=/srv/o XDG_STATE_HOME=/var/st "$p/bin/orphera" help)
has "environment wins over the default" "$out" "ORPHERA_INVENTORY=/x/inv.yaml"
has "ORPHERA_SITE_DIR moves the other site defaults" "$out" "ORPHERA_CONFIG_DIR=/srv/o/config"
has "XDG_STATE_HOME moves the state" "$out" "ORPHERA_STATE_DIR=/var/st/orphera"

# Which site directory: the user's own, or the system-wide one when only that holds a site
site() { run "$@" "$p/bin/orphera" help | sed -n 's/^ORPHERA_SITE_DIR=//p'; }
eq "site: nothing anywhere is the user's own" "$(site)" "$tmp/home/.config/orphera"
mkdir -p "$tmp/home/.config/orphera" && echo "key" >"$tmp/home/.config/orphera/age.key"
eq "site: only a secret store in the user's directory is not a site" "$(site)" "$tmp/home/.config/orphera"
mkdir -p "$sys/certs" && : >"$sys/certs/ca.crt"
eq "site: a system-wide CA is used when the user has no site" "$(site)" "$sys"
mkdir -p "$tmp/home/.config/orphera/certs"
eq "site: the user's own wins over the system-wide one" "$(site)" "$tmp/home/.config/orphera"
eq "site: ORPHERA_SITE_DIR wins over both" "$(site ORPHERA_SITE_DIR=/srv/o)" "/srv/o"
rm -rf "$tmp/home/.config/orphera/certs" "$sys"

mkdir -p "$tmp/usrbin" && ln -s "$p/bin/orphera" "$tmp/usrbin/orphera"
out=$(run "$tmp/usrbin/orphera" help)
has "found through a symlink" "$out" "ARGS -cp $lib/orphera-orchestrator.jar"

cp -a "$p" "$tmp/moved"
out=$(run "$tmp/moved/bin/orphera" help)
has "a moved tree finds itself" "$out" "ARGS -cp $tmp/moved/lib/orphera/orphera-orchestrator.jar"

out=$(run JAVA_HOME="$tmp/jh" "$p/bin/orphera" help 2>&1); rc=$?
[ "$rc" -ne 0 ] && ok "a JAVA_HOME without java is an error" || bad "a JAVA_HOME without java is an error: $out"

rm "$tmp/moved/lib/orphera/orphera-orchestrator.jar"
out=$(run "$tmp/moved/bin/orphera" help 2>&1); rc=$?
if [ "$rc" -ne 0 ] && grep -q "incomplete" <<<"$out"; then ok "a missing jar says the installation is incomplete"; else bad "a missing jar says the installation is incomplete: $out"; fi
rm "$tmp/moved/lib/orphera/orphera-env.sh"
out=$(run "$tmp/moved/bin/orphera" help 2>&1); rc=$?
if [ "$rc" -ne 0 ] && grep -q "incomplete" <<<"$out"; then ok "a missing orphera-env.sh says the installation is incomplete"; else bad "a missing orphera-env.sh says the installation is incomplete: $out"; fi

# The shell scripts of the package
got=$(cd "$share" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" ORPHERA_SYSTEM_SITE_DIR="$sys" bash -c '. utils/orphera-sources.sh; config_dir; inventory_file; echo "$PATH"')
eq "scripts: config directory" "$(sed -n 1p <<<"$got")" "$tmp/home/.config/orphera/config"
eq "scripts: inventory" "$(sed -n 2p <<<"$got")" "$tmp/home/.config/orphera/inventory.yaml"
eq "scripts: orphera is on PATH" "$(sed -n 3p <<<"$got")" "$p/bin:/usr/bin:/bin"
got=$(cd "$share" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" ORPHERA_SYSTEM_SITE_DIR="$sys" ORPHERA_CONFIG_DIR=/y/cfg bash -c '. utils/orphera-sources.sh; config_dir')
eq "scripts: the environment still wins" "$got" "/y/cfg"
got=$(cd "$share/manifests" && env -i PATH=/usr/bin:/bin HOME="$tmp/home" ORPHERA_SYSTEM_SITE_DIR="$sys" bash -c '. ../utils/orphera-sources.sh; config_dir')
eq "scripts: found from another directory" "$got" "$tmp/home/.config/orphera/config"

# A checkout is unchanged: no lib/orphera-env.sh beside utils/
got=$(env -i PATH=/usr/bin:/bin HOME="$tmp/home" bash -c ". utils/orphera-sources.sh; config_dir; echo \"\$PATH\"")
eq "a checkout: config directory" "$(sed -n 1p <<<"$got")" "config"
eq "a checkout: PATH is untouched" "$(sed -n 2p <<<"$got")" "/usr/bin:/bin"

if [ "$fail" -eq 0 ]; then echo "test-cli-package: all checks passed"; else echo "test-cli-package: $fail failed"; exit 1; fi
