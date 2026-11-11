#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Installs what is needed to build Orphera on a clean macOS (Sonoma and later, Apple Silicon or Intel):
# Xcode command line tools, Homebrew, git, GitHub CLI, JDK 21 (Temurin), sbt, Go, Python 3, and (unless
# --minimal) the packaging and code generation tools: GNU make and tar, dpkg, rpm, protobuf and the Go
# protobuf plugins. Safe to run again: what is installed is skipped.
#
#   ./macos-setup.sh [--minimal]
#
# It can be run before the repository is cloned: copy it, or paste it into a file, and run it.
# It needs an administrator password (Homebrew asks for it once) and a network connection.
# Adds to ~/.zprofile, between "# >>> orphera build tools" and "# <<< orphera build tools" markers.
#
# What it makes possible on a Mac: sbt compile, test and assembly; the Go agent (cross-compiled for Linux);
# the Scala Native utilities (the Xcode tools provide clang); the check programs. What it does not
# change: `make orpheracli`, `make install` and the lab itself are for the Linux host (scala0); the
# release packages are built by GitHub when a v* tag is pushed.
set -euo pipefail

minimal=""
case "${1:-}" in
  --minimal) minimal=1 ;;
  "") ;;
  *) echo "usage: $0 [--minimal]" >&2; exit 2 ;;
esac

say() { printf '\n==> %s\n' "$*"; }
[ "$(uname -s)" = Darwin ] || { echo "this script is for macOS" >&2; exit 1; }
[ "$(id -u)" -ne 0 ] || { echo "run it as yourself, not root: Homebrew refuses to run as root" >&2; exit 1; }

# 1. Xcode command line tools (git, clang, make, python3)
if ! xcode-select -p >/dev/null 2>&1; then
  say "Installing the Xcode command line tools (a window opens: click Install, wait for it to finish)"
  xcode-select --install || true
  until xcode-select -p >/dev/null 2>&1; do sleep 10; done
fi

# 2. Homebrew
if ! command -v brew >/dev/null 2>&1; then
  if [ -x /opt/homebrew/bin/brew ]; then :; elif [ -x /usr/local/bin/brew ]; then :; else
    say "Installing Homebrew"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  fi
fi
if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"
elif [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
command -v brew >/dev/null 2>&1 || { echo "Homebrew is not on the PATH after its installation" >&2; exit 1; }

formula() { brew list --formula "$1" >/dev/null 2>&1 && echo "  have $1" || { say "brew install $1"; brew install "$1"; }; }
cask() { brew list --cask "$1" >/dev/null 2>&1 && echo "  have $1" || { say "brew install --cask $1"; brew install --cask "$1"; }; }

# 3. What the build itself needs
say "Build tools"
formula git
formula gh
cask temurin@21          # JDK 21; any JDK 21 builds it
formula sbt              # the launcher: project/build.properties pins sbt 1.10.7 and it fetches that
formula go               # agent-go (go 1.22 or later)
formula python@3         # utils/*.py (the OS10 and iLO tools, the schema checks)
formula openssl@3        # make certs

# 4. Packaging and code generation
if [ -z "$minimal" ]; then
  say "Packaging and code generation tools"
  formula make             # GNU make, as gmake (the Xcode make is 3.81)
  formula gnu-tar          # make cli-tar uses GNU tar options
  formula dpkg             # dpkg-deb, for make deb
  formula rpm              # rpmbuild, for make rpm
  formula protobuf         # only for agent-go/gen.sh; sbt fetches its own protoc
  command -v protoc-gen-go >/dev/null 2>&1 || GOBIN="$HOME/go/bin" go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.35.2
  command -v protoc-gen-go-grpc >/dev/null 2>&1 || GOBIN="$HOME/go/bin" go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
fi

# 5. The shell: JAVA_HOME, and GNU make and tar ahead of the Apple ones
say "Updating ~/.zprofile"
profile="$HOME/.zprofile"
touch "$profile"
if grep -q '# >>> orphera build tools' "$profile"; then
  tmp=$(mktemp)
  sed '/# >>> orphera build tools/,/# <<< orphera build tools/d' "$profile" >"$tmp" && cat "$tmp" >"$profile"
  rm -f "$tmp"
fi
{
  echo '# >>> orphera build tools'
  echo "eval \"\$($(command -v brew) shellenv)\""
  echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)'
  echo 'export PATH="$HOME/go/bin:$PATH"'
  if [ -z "$minimal" ]; then
    echo 'export PATH="$(brew --prefix)/opt/make/libexec/gnubin:$(brew --prefix)/opt/gnu-tar/libexec/gnubin:$PATH"'
  fi
  echo '# <<< orphera build tools'
} >>"$profile"

# 6. What is there now
say "Installed"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version 2>&1 | sed -n 1p
git --version
gh --version | sed -n 1p
go version
python3 --version
if [ -z "$minimal" ]; then
  "$(brew --prefix)/opt/make/libexec/gnubin/make" --version | sed -n 1p
  dpkg-deb --version | sed -n 1p
  rpmbuild --version
  protoc --version
fi

cat <<'MSG'

Next, in a new terminal window (so the PATH and JAVA_HOME above apply):

  gh auth login                                   # GitHub: choose HTTPS or SSH, log in through the browser
  gh repo clone ljalbinson/orphera-pkg ~/orphera-pkg
  cd ~/orphera-pkg
  sbt compile                                     # the first run downloads sbt 1.10.7 and the dependencies: several minutes
  sbt test
MSG
