#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Installs what is needed to build Orphera on a clean macOS (Sonoma or later; Intel or Apple Silicon):
# the Xcode command line tools (git, clang, make, python3), JDK 21 (Eclipse Temurin), sbt, Go and the GitHub CLI.
# It does not use Homebrew (which no longer supports Intel Macs): each tool is unpacked under ~/.local from
# its own project's download, so no administrator password is needed after the Xcode tools. Safe to run
# again: what is installed is skipped.
#
#   ./macos-setup.sh
#
# It can be run before the repository is cloned. Adds to ~/.zprofile, between "# >>> orphera build tools" and
# "# <<< orphera build tools" markers.
#
# What it makes possible on a Mac: sbt compile, test and assembly; the Go agent (cross-compiled for Linux);
# the Scala Native utilities (the Xcode tools provide clang). What it leaves to Linux: the .deb and .rpm
# packages (dpkg and rpmbuild), `make orpheracli`, `make install` and the lab itself. The release packages are
# built by GitHub when a v* tag is pushed.
set -euo pipefail

SBT_VERSION=1.10.7   # the version project/build.properties pins; the project does not build with sbt 2
JDK_FEATURE=21

say() { printf '\n==> %s\n' "$*"; }
die() { echo "macos-setup: $*" >&2; exit 1; }
[ "$(uname -s)" = Darwin ] || die "this script is for macOS"
[ "$(id -u)" -ne 0 ] || die "run it as yourself, not root"

case "$(uname -m)" in
  arm64) adopt_arch=aarch64 go_arch=arm64 gh_arch=arm64 ;;
  x86_64) adopt_arch=x64 go_arch=amd64 gh_arch=amd64 ;;
  *) die "unknown processor $(uname -m)" ;;
esac

# 1. Xcode command line tools (git, clang, make, python3)
if ! xcode-select -p >/dev/null 2>&1; then
  say "Installing the Xcode command line tools (a window opens: click Install, wait for it to finish)"
  xcode-select --install || true
  until xcode-select -p >/dev/null 2>&1; do sleep 10; done
fi

local_dir="$HOME/.local"
mkdir -p "$local_dir/bin"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

download() { # URL FILE
  curl -fL --retry 3 --progress-bar -o "$2" "$1" || die "could not download $1"
}

# 2. JDK 21: Eclipse Temurin, from the Adoptium API (the newest 21.x)
jdk_dir="$local_dir/jdk-$JDK_FEATURE"
if [ ! -x "$jdk_dir/Contents/Home/bin/java" ]; then
  say "JDK $JDK_FEATURE (Temurin)"
  download "https://api.adoptium.net/v3/binary/latest/$JDK_FEATURE/ga/mac/$adopt_arch/jdk/hotspot/normal/eclipse" "$tmp/jdk.tar.gz"
  mkdir "$tmp/jdk" && tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/jdk"
  top=$(find "$tmp/jdk" -mindepth 1 -maxdepth 1 -type d | sed -n 1p)
  [ -n "$top" ] && [ -x "$top/Contents/Home/bin/java" ] || die "the JDK download is not the layout expected"
  rm -rf "$jdk_dir" && mv "$top" "$jdk_dir"
else
  echo "  have JDK $JDK_FEATURE in $jdk_dir"
fi
JAVA_HOME="$jdk_dir/Contents/Home"
export JAVA_HOME

# 3. sbt
sbt_dir="$local_dir/sbt-$SBT_VERSION"
if [ ! -x "$sbt_dir/bin/sbt" ]; then
  say "sbt $SBT_VERSION"
  download "https://github.com/sbt/sbt/releases/download/v$SBT_VERSION/sbt-$SBT_VERSION.tgz" "$tmp/sbt.tgz"
  mkdir "$tmp/sbt" && tar -xzf "$tmp/sbt.tgz" -C "$tmp/sbt"
  [ -x "$tmp/sbt/sbt/bin/sbt" ] || die "the sbt download is not the layout expected"
  rm -rf "$sbt_dir" && mv "$tmp/sbt/sbt" "$sbt_dir"
else
  echo "  have sbt $SBT_VERSION in $sbt_dir"
fi

# 4. Go (agent-go needs 1.22 or later): the current release
go_dir="$local_dir/go"
if [ ! -x "$go_dir/bin/go" ]; then
  say "Go"
  go_version=$(curl -fsSL 'https://go.dev/VERSION?m=text' | sed -n 1p)
  case "$go_version" in go1.*) ;; *) die "could not find the current Go version (got: $go_version)" ;; esac
  download "https://go.dev/dl/$go_version.darwin-$go_arch.tar.gz" "$tmp/go.tar.gz"
  mkdir "$tmp/go" && tar -xzf "$tmp/go.tar.gz" -C "$tmp/go"
  [ -x "$tmp/go/go/bin/go" ] || die "the Go download is not the layout expected"
  rm -rf "$go_dir" && mv "$tmp/go/go" "$go_dir"
else
  echo "  have Go in $go_dir"
fi

# 5. The GitHub CLI
gh_dir="$local_dir/gh"
if [ ! -x "$gh_dir/bin/gh" ]; then
  say "GitHub CLI"
  gh_tag=$(curl -fsSL https://api.github.com/repos/cli/cli/releases/latest | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | sed -n 1p)
  [ -n "$gh_tag" ] || die "could not find the current GitHub CLI version"
  download "https://github.com/cli/cli/releases/download/v$gh_tag/gh_${gh_tag}_macOS_$gh_arch.zip" "$tmp/gh.zip"
  mkdir "$tmp/gh" && unzip -q "$tmp/gh.zip" -d "$tmp/gh"
  top=$(find "$tmp/gh" -mindepth 1 -maxdepth 1 -type d | sed -n 1p)
  [ -n "$top" ] && [ -x "$top/bin/gh" ] || die "the GitHub CLI download is not the layout expected"
  rm -rf "$gh_dir" && mv "$top" "$gh_dir"
else
  echo "  have the GitHub CLI in $gh_dir"
fi

# 6. The shell
say "Updating ~/.zprofile"
profile="$HOME/.zprofile"
touch "$profile"
if grep -q '# >>> orphera build tools' "$profile"; then
  sed '/# >>> orphera build tools/,/# <<< orphera build tools/d' "$profile" >"$tmp/profile" && cat "$tmp/profile" >"$profile"
fi
cat >>"$profile" <<PROFILE
# >>> orphera build tools
export JAVA_HOME="$JAVA_HOME"
export PATH="\$JAVA_HOME/bin:$sbt_dir/bin:$go_dir/bin:\$HOME/go/bin:$gh_dir/bin:\$PATH"
# <<< orphera build tools
PROFILE
export PATH="$JAVA_HOME/bin:$sbt_dir/bin:$go_dir/bin:$gh_dir/bin:$PATH"

# 7. What is there now
say "Installed"
java -version 2>&1 | sed -n 1p
git --version
go version
gh --version | sed -n 1p
python3 --version
make --version | sed -n 1p

cat <<'MSG'

Next, in a new terminal window (so the PATH and JAVA_HOME above apply):

  gh auth login                                   # GitHub: choose HTTPS or SSH, log in through the browser
  gh repo clone ljalbinson/orphera-pkg ~/orphera-pkg
  cd ~/orphera-pkg
  sbt compile                                     # the first run downloads sbt 1.10.7's own files and the dependencies: several minutes
  sbt test
MSG
