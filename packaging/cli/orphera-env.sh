# SPDX-License-Identifier: Apache-2.0
#
# The defaults of an installed Orphera, for the orphera command and for the shell scripts of the
# package (utils/orphera-sources.sh sources this when it finds it). Sourced, not run, with
# ORPHERA_PREFIX set to the installation directory (/opt/orphera).
#
# Each setting is only a default: a value already in the environment wins, and so do the
# options -I, -c and -m of the orphera command. Site data (the inventory, the per-VM configs
# and the certificate authority) is in ORPHERA_SITE_DIR, not in the program directory. Unless that is set,
# it is the user's own ~/.config/orphera (next to the secret store, so two users of one machine do not
# share a CA key or an inventory), or the system-wide /etc/orphera when that holds a site and the user's
# directory does not (see below); ORPHERA_ROOT is where the playbooks' relative files (files/motd) are found when
# they are not in the current directory; run state goes under the user's home, not under /opt.

# The site directory, when ORPHERA_SITE_DIR is not set: the user's own if it holds an inventory, configs or
# certificates; else the system-wide one if that holds an inventory or a CA (a machine whose site is shared,
# or set up before the per-user directory); else the user's own, which `orphera init-ca` creates.
# ORPHERA_SYSTEM_SITE_DIR names the system-wide directory (default /etc/orphera) for the checks.
if [ -z "${ORPHERA_SITE_DIR:-}" ]; then
  _orphera_user_site="${HOME:-}/.config/orphera"
  _orphera_system_site="${ORPHERA_SYSTEM_SITE_DIR:-/etc/orphera}"
  if [ -e "$_orphera_user_site/inventory.yaml" ] || [ -d "$_orphera_user_site/config" ] || [ -d "$_orphera_user_site/certs" ]; then
    ORPHERA_SITE_DIR=$_orphera_user_site
  elif [ -e "$_orphera_system_site/inventory.yaml" ] || [ -e "$_orphera_system_site/certs/ca.crt" ]; then
    ORPHERA_SITE_DIR=$_orphera_system_site
  else
    ORPHERA_SITE_DIR=$_orphera_user_site
  fi
  unset _orphera_user_site _orphera_system_site
fi
: "${ORPHERA_INVENTORY:=$ORPHERA_SITE_DIR/inventory.yaml}"
: "${ORPHERA_CONFIG_DIR:=$ORPHERA_SITE_DIR/config}"
: "${ORPHERA_CERTS_DIR:=$ORPHERA_SITE_DIR/certs}"
: "${ORPHERA_ROOT:=$ORPHERA_PREFIX}"
: "${ORPHERA_MANIFESTS:=$ORPHERA_PREFIX/manifests}"
: "${ORPHERA_CATALOGUES:=$ORPHERA_PREFIX/catalogues}"
: "${ORPHERA_STATE_DIR:=${XDG_STATE_HOME:-$HOME/.local/state}/orphera}"
: "${ORPHERA_ORCHESTRATOR_JAR:=$ORPHERA_PREFIX/lib/orphera-orchestrator.jar}"
: "${ORPHERA_SCRIPTING_JAR:=$ORPHERA_PREFIX/lib/orphera-scripting.jar}"
export ORPHERA_ROOT ORPHERA_SITE_DIR ORPHERA_INVENTORY ORPHERA_CONFIG_DIR ORPHERA_CERTS_DIR ORPHERA_MANIFESTS \
  ORPHERA_CATALOGUES ORPHERA_STATE_DIR ORPHERA_ORCHESTRATOR_JAR ORPHERA_SCRIPTING_JAR
