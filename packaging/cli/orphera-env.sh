# SPDX-License-Identifier: Apache-2.0
#
# The defaults of an installed Orphera, for the orphera command and for the shell scripts of the
# package (utils/orphera-sources.sh sources this when it finds it). Sourced, not run, with
# ORPHERA_PREFIX set to the installation directory (/opt/orphera).
#
# Each setting is only a default: a value already in the environment wins, and so do the
# options -I, -c and -m of the orphera command. Site data (the inventory, the per-VM configs
# and the certificate authority) is in ORPHERA_SITE_DIR (default /etc/orphera), not in the
# program directory; run state goes under the user's home, not under /opt.

: "${ORPHERA_SITE_DIR:=/etc/orphera}"
: "${ORPHERA_INVENTORY:=$ORPHERA_SITE_DIR/inventory.yaml}"
: "${ORPHERA_CONFIG_DIR:=$ORPHERA_SITE_DIR/config}"
: "${ORPHERA_CERTS_DIR:=$ORPHERA_SITE_DIR/certs}"
: "${ORPHERA_MANIFESTS:=$ORPHERA_PREFIX/manifests}"
: "${ORPHERA_CATALOGUES:=$ORPHERA_PREFIX/catalogues}"
: "${ORPHERA_STATE_DIR:=${XDG_STATE_HOME:-$HOME/.local/state}/orphera}"
: "${ORPHERA_ORCHESTRATOR_JAR:=$ORPHERA_PREFIX/lib/orphera-orchestrator.jar}"
: "${ORPHERA_SCRIPTING_JAR:=$ORPHERA_PREFIX/lib/orphera-scripting.jar}"
export ORPHERA_SITE_DIR ORPHERA_INVENTORY ORPHERA_CONFIG_DIR ORPHERA_CERTS_DIR ORPHERA_MANIFESTS \
  ORPHERA_CATALOGUES ORPHERA_STATE_DIR ORPHERA_ORCHESTRATOR_JAR ORPHERA_SCRIPTING_JAR
