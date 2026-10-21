# SPDX-License-Identifier: Apache-2.0
#
# Where the shell scripts find the inventory and the per-VM configs, the same places the
# `orphera` command reads them from: ORPHERA_CONFIG_DIR and ORPHERA_INVENTORY (a plain
# path or a file: URI), else config/ and inventory.yaml in the current directory.
# A shell script cannot read any other kind of location, so for a database use
# `utils/sql-to-yaml.py --mariadb "..." --out DIR` and point both variables at DIR.
# Sourced, not run.

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
