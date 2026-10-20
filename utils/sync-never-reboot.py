#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Puts the never-reboot marker on every host the inventory protects.

The inventory's `never-reboot: true` is checked by the orphera command, but an older
orphera does not know it. A host that carries /etc/orphera-agent/never-reboot is protected
by its own agent, whichever orphera calls it (agents built with this check refuse
TriggerReboot and TriggerShutdown while the file exists). This script creates that file on
the protected hosts, using the same rule as orphera: the node itself, or any group it is in.

  utils/sync-never-reboot.py                 show what it would do (nothing is changed)
  utils/sync-never-reboot.py --apply         create the marker on the protected hosts
  utils/sync-never-reboot.py --apply --prune also remove it from hosts that are not protected
  utils/sync-never-reboot.py -I FILE         read another inventory (default: $ORPHERA_INVENTORY,
                                             else inventory.yaml; a plain path or file: URI)

The agent looks at the file on every request, so no restart is needed. The agent on the
host must be a build that has the check (deploy it first); an older agent ignores the file.
Check afterwards with:  orphera run sh -c 'ls -l /etc/orphera-agent/never-reboot' --nodes <host>
"""
import argparse
import os
import shlex
import subprocess
import sys

import yaml

MARKER = "/etc/orphera-agent/never-reboot"


def load(path: str):
    if path.startswith("file:"):
        path = path[5:]
        while path.startswith("//"):
            path = path[1:]
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def protected_nodes(inv: dict):
    """(protected, all) node names, by the rule orphera uses (Inventory.neverReboot)."""
    names, protected = [], set()
    for n in inv.get("nodes") or []:
        names.append(n["name"])
        if n.get("never-reboot") is True:
            protected.add(n["name"])
    for g in inv.get("groups") or []:
        if g.get("never-reboot") is True:
            protected.update(m for m in g.get("members") or [])
    protected &= set(names)
    return sorted(protected), names


def orphera(command: str, nodes: list, dry: bool) -> int:
    argv = ["orphera", "run", "sh", "-c", command, "--nodes", ",".join(nodes)]
    print("  " + " ".join(shlex.quote(a) for a in argv))
    if dry:
        return 0
    return subprocess.run(argv).returncode


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("-I", "--inventory", default=os.environ.get("ORPHERA_INVENTORY", "inventory.yaml"))
    ap.add_argument("--apply", action="store_true", help="do it (default: show only)")
    ap.add_argument("--prune", action="store_true", help="also remove the marker from unprotected hosts")
    args = ap.parse_args()

    try:
        inv = load(args.inventory)
    except (OSError, yaml.YAMLError) as e:
        print(f"cannot read {args.inventory}: {e}", file=sys.stderr)
        return 1
    protected, names = protected_nodes(inv)
    others = [n for n in names if n not in protected]
    dry = not args.apply

    print(f"inventory: {args.inventory}")
    print(f"protected ({len(protected)}): {', '.join(protected) or 'none'}")
    rc = 0
    if protected:
        print(f"{'would create' if dry else 'creating'} {MARKER} on the protected hosts:")
        rc |= orphera(f"install -D -m 644 /dev/null {MARKER}", protected, dry)
    if args.prune and others:
        print(f"{'would remove' if dry else 'removing'} {MARKER} from the {len(others)} other hosts:")
        rc |= orphera(f"rm -f {MARKER}", others, dry)
    if dry:
        print("dry run: nothing was changed. Add --apply to do it.")
    return 1 if rc else 0


if __name__ == "__main__":
    sys.exit(main())
