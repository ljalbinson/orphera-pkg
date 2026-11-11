# SPDX-License-Identifier: Apache-2.0
# Installed by `make orpheracli` as /etc/profile.d/orphera-utils.sh: puts the utility commands
# (vmctl, start-vm, ...) in /opt/orphera/bin on the PATH of login shells.
case ":$PATH:" in
  *":/opt/orphera/bin:"*) ;;
  *) PATH="$PATH:/opt/orphera/bin"; export PATH ;;
esac
