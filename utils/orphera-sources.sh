# SPDX-License-Identifier: Apache-2.0
#
# Where the shell scripts find the inventory and the per-VM configs, the same places the
# `orphera` command reads them from: ORPHERA_CONFIG_DIR and ORPHERA_INVENTORY (a plain
# path or a file: URI), else config/ and inventory.yaml in the current directory.
# A shell script cannot read any other kind of location, so for a database use
# `utils/sql-to-yaml.py --mariadb "..." --out DIR` and point both variables at DIR.
# Sourced, not run.

# In an installation (utils/ is PREFIX/share/orphera/utils, and PREFIX/lib/orphera/orphera-env.sh exists) the
# site defaults, and the orphera command on PATH, come from the installation; in a checkout there is no such
# file and nothing changes.
_orphera_utils_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ "${_orphera_utils_dir%/share/orphera/utils}" != "$_orphera_utils_dir" ] &&
  [ -f "${_orphera_utils_dir%/share/orphera/utils}/lib/orphera/orphera-env.sh" ]; then
  ORPHERA_PREFIX=${_orphera_utils_dir%/share/orphera/utils}
  [ -n "$ORPHERA_PREFIX" ] || ORPHERA_PREFIX=/
  export ORPHERA_PREFIX
  # shellcheck disable=SC1091
  . "$ORPHERA_PREFIX/lib/orphera/orphera-env.sh"
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

# The hypervisors, as one line of names separated by spaces: the members of the inventory group named
# `hypervisors` (read with `orphera nodes --groups`, so a database inventory works too); if there is no such
# group, or it is empty, or `orphera` cannot be run, the environment variable HYPERVISORS; if that is empty
# too, gs1 gs2 gs3 st0. The group comes first, so HYPERVISORS only counts while the inventory has none.
hypervisor_list() {
  local line members=""
  if command -v orphera >/dev/null 2>&1; then
    while IFS= read -r line; do
      [ "${line%%:*}" = hypervisors ] && members=${line#*:}
    done < <(orphera nodes --groups 2>/dev/null)
  fi
  members=$(printf '%s' "$members" | tr -s ' \t,' ' ' | sed 's/^ //; s/ $//')
  if [ -n "$members" ]; then
    printf '%s\n' "$members"
  elif [ -n "${HYPERVISORS:-}" ]; then
    printf '%s\n' "$HYPERVISORS"
  else
    printf '%s\n' "gs1 gs2 gs3 st0"
  fi
}
