# SPDX-License-Identifier: Apache-2.0
#
# Where the shell scripts find the inventory and the per-VM configs, the same places the
# `orphera` command reads them from: ORPHERA_CONFIG_DIR and ORPHERA_INVENTORY (a plain
# path or a file: URI), else config/ and inventory.yaml in the current directory.
# A shell script cannot read any other kind of location, so for a database use
# `utils/sql-to-yaml.py --mariadb "..." --out DIR` and point both variables at DIR.
# Sourced, not run.

# In an installed package (lib/orphera-env.sh beside utils/) the site defaults, and the orphera
# command on PATH, come from the installation; in a checkout there is no such file and nothing changes.
_orphera_utils_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$_orphera_utils_dir/../lib/orphera-env.sh" ]; then
  ORPHERA_PREFIX=$(cd "$_orphera_utils_dir/.." && pwd)
  export ORPHERA_PREFIX
  . "$_orphera_utils_dir/../lib/orphera-env.sh"
  case ":$PATH:" in
    *":$ORPHERA_PREFIX/bin:"*) ;;
    *) PATH="$ORPHERA_PREFIX/bin:$PATH"; export PATH ;;
  esac
fi
unset _orphera_utils_dir

# Value of a top-level `key:` under `params:` in a config file, without quotes.
config_value() {
  awk -v key="$2" '
    $0 ~ "^  " key ":" {
      v = $0
      sub("^  " key ":[ \t]*", "", v)
      sub(/[ \t]*#.*$/, "", v)
      gsub(/^["\x27]|["\x27]$/, "", v)
      print v
      exit
    }' "$1"
}

# The directory of config files: ORPHERA_CONFIG_DIR, as a path or a file: URI,
# else config. Any other kind of location cannot be read by a shell script.
config_dir() {
  local d="${ORPHERA_CONFIG_DIR:-config}"
  d="${d#file://}"
  case "$d" in
    *://*) echo "ORPHERA_CONFIG_DIR is $d: these scripts read files only" >&2; return 1 ;;
  esac
  printf '%s\n' "${d%/}"
}

# The inventory file: ORPHERA_INVENTORY as a path or a file: URI, else inventory.yaml.
inventory_file() {
  local f="${ORPHERA_INVENTORY:-inventory.yaml}"
  f="${f#file://}"
  case "$f" in
    *://*) echo "ORPHERA_INVENTORY is $f: these scripts read files only" >&2; return 1 ;;
  esac
  printf '%s\n' "$f"
}
