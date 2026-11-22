#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""List KVM backup sets held on ZFS backup pools.

A backup set is <pool>/kvm/<YYYY-MM-DD>/<host>, holding one dataset per VM.
Each set is compared against the live VMs in <live-root>/<host> so that
incomplete sets are flagged.

Usage:
    zfs_backup_sets.py                      # runs `zfs list` itself
    zfs_backup_sets.py -v                   # also list VMs in each set
    zfs_backup_sets.py --input zfs.txt      # parse saved `zfs list` output
    zfs_backup_sets.py --pool tank-backup-ext
    zfs_backup_sets.py --delete             # show what deleting the OLDER sets would do
    zfs_backup_sets.py --delete --yes       # actually destroy the OLDER sets

--delete acts on exactly the sets the listing marks OLDER (everything except
the newest --keep sets). It is a dry run (`zfs destroy -r -n -v`) unless --yes
is given. The newest set of each host (in any pool) is never deleted, whatever --keep says.
"""
import argparse
import re
import subprocess
import sys
from collections import defaultdict

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
UNITS = {"B": 0, "K": 1, "M": 2, "G": 3, "T": 4, "P": 5}


def parse_size(s):
    """Convert '8.08G' / '96K' / raw bytes into bytes."""
    s = s.strip()
    if s.isdigit():
        return int(s)
    m = re.match(r"^([\d.]+)([BKMGTP])$", s)
    if not m:
        return 0
    return int(float(m.group(1)) * 1024 ** UNITS[m.group(2)])


def human(n):
    for unit in "BKMGTP":
        if n < 1024 or unit == "P":
            return f"{n:.0f}{unit}" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024


def read_datasets(input_file):
    """Return {name: used_bytes}."""
    if input_file:
        with open(input_file) as f:
            lines = f.read().splitlines()
    else:
        out = subprocess.run(
            ["zfs", "list", "-H", "-p", "-o", "name,used", "-t", "filesystem"],
            check=True, capture_output=True, text=True,
        ).stdout
        lines = out.splitlines()

    datasets = {}
    for line in lines:
        parts = line.split()
        # skip prompts, headers and blank lines from pasted output
        if len(parts) < 2 or parts[0] == "NAME" or "$" in parts[0] or "@" in line.split()[0]:
            continue
        datasets[parts[0]] = parse_size(parts[1])
    return datasets


def zfs_destroy(name, recursive, execute):
    """Run zfs destroy (or a dry run of it); return (ok, output)."""
    cmd = ["zfs", "destroy"]
    if recursive:
        cmd.append("-r")
    if not execute:
        cmd += ["-n", "-v"]
    cmd.append(name)
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode == 0, (r.stdout + r.stderr).strip()


def delete_old_sets(ordered, n_old, ds, execute):
    """Destroy the OLDER sets. Returns the process exit status."""
    candidates = [k for k, _ in ordered[:n_old]]
    # the newest set of each host, in any pool, is always kept
    newest = {}
    for (pool, date, host), _ in ordered:
        newest[host] = (date, pool)
    to_delete, kept = [], []
    for pool, date, host in candidates:
        (kept if newest[host] == (date, pool) else to_delete).append((pool, date, host))

    print()
    for pool, date, host in kept:
        print(f"keeping {pool}/kvm/{date}/{host}: newest set for that host")
    if not to_delete:
        print("Nothing to delete.")
        return 0

    print(("DELETING" if execute else "DRY RUN - would delete")
          + f" {len(to_delete)} set(s):")
    failed = 0
    gone = set()
    for pool, date, host in to_delete:
        name = f"{pool}/kvm/{date}/{host}"
        # never destroy anything that is not exactly <pool>/kvm/<date>/<host>
        if not re.fullmatch(r"[^/@]+/kvm/\d{4}-\d{2}-\d{2}/[^/@]+", name):
            print(f"  refusing odd name {name}")
            failed += 1
            continue
        ok, out = zfs_destroy(name, True, execute)
        print(f"  {name}: {'ok' if ok else 'FAILED'}")
        if out:
            print("    " + out.replace("\n", "\n    "))
        if ok:
            gone.add(name)
        else:
            failed += 1

    # remove date datasets that are now empty
    for pool, date in sorted({(p, d) for p, d, _ in to_delete}):
        parent = f"{pool}/kvm/{date}"
        if parent not in ds:
            continue
        left = [n for n in ds if n.startswith(parent + "/")
                and not any(n == g or n.startswith(g + "/") for g in gone)]
        if left:
            continue
        if execute:
            ok, out = zfs_destroy(parent, False, True)
            print(f"  {parent} (now empty): {'ok' if ok else 'not removed'}")
            if out and not ok:
                print("    " + out.replace("\n", "\n    "))
        else:
            print(f"  {parent} would be removed too (empty afterwards)")

    if not execute:
        print("\nDry run only. Re-run with --yes to destroy.")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", help="saved `zfs list` output instead of running zfs")
    ap.add_argument("--pool", help="only show this backup pool")
    ap.add_argument("--live-root", default="tank/kvm",
                    help="dataset holding live VMs (default: tank/kvm)")
    ap.add_argument("--keep", type=int, default=3,
                    help="number of newest sets; older ones are marked OLDER (default: 3)")
    ap.add_argument("--delete", action="store_true",
                    help="destroy the sets marked OLDER (dry run unless --yes)")
    ap.add_argument("--yes", action="store_true",
                    help="with --delete: really destroy, instead of a dry run")
    ap.add_argument("-v", "--verbose", action="store_true", help="list VMs in each set")
    args = ap.parse_args()
    if args.yes and not args.delete:
        ap.error("--yes only makes sense with --delete")
    if args.delete and args.input:
        ap.error("--delete works on the live pool; it cannot be combined with --input")
    if args.keep < 1:
        ap.error("--keep must be at least 1")

    try:
        ds = read_datasets(args.input)
    except (OSError, subprocess.CalledProcessError) as e:
        sys.exit(f"error reading datasets: {e}")

    # live VMs: <live-root>/<host>/<vm>
    live = defaultdict(set)
    prefix = args.live_root + "/"
    for name in ds:
        if name.startswith(prefix):
            rest = name[len(prefix):].split("/")
            if len(rest) == 2:
                live[rest[0]].add(rest[1])

    # backup sets: <pool>/kvm/<date>/<host>[/<vm>]
    sets = {}
    for name, used in ds.items():
        p = name.split("/")
        if len(p) < 4 or p[1] != "kvm" or not DATE_RE.match(p[2]):
            continue
        if args.pool and p[0] != args.pool:
            continue
        key = (p[0], p[2], p[3])
        s = sets.setdefault(key, {"used": 0, "vms": {}})
        if len(p) == 4:
            s["used"] = used
        elif len(p) == 5:
            s["vms"][p[4]] = used

    if not sets:
        print("No backup sets found.")
        return

    ordered = sorted(sets.items(), key=lambda kv: (kv[0][1], kv[0][0]))
    n_old = max(len(ordered) - args.keep, 0)

    hdr = f"{'AGE':<6} {'POOL':<18} {'DATE':<10}  {'HOST':<10} {'VMS':>7} {'SIZE':>8}  STATUS"
    print(hdr)
    print("-" * len(hdr))
    totals = defaultdict(int)
    old_used = 0
    for i, ((pool, date, host), s) in enumerate(ordered):
        age = "OLDER" if i < n_old else "NEWEST"
        if i < n_old:
            old_used += s["used"]
        expected = live.get(host, set())
        have = set(s["vms"])
        missing = sorted(expected - have)
        extra = sorted(have - expected)
        count = f"{len(have)}/{len(expected)}" if expected else str(len(have))
        if not expected:
            status = "no live host to compare"
        elif missing:
            status = "INCOMPLETE - missing: " + ", ".join(missing)
        else:
            status = "complete"
        if extra:
            status += "  (not live now: " + ", ".join(extra) + ")"
        print(f"{age:<6} {pool:<18} {date:<10}  {host:<10} {count:>7} {human(s['used']):>8}  {status}")
        if args.verbose:
            for vm, used in sorted(s["vms"].items()):
                print(f"{'':<49}{vm:<10} {human(used):>8}")
        totals[pool] += s["used"]

    print()
    if n_old:
        print(f"{n_old} set(s) older than the newest {args.keep}, using {human(old_used)}:")
        for (pool, date, host), _ in ordered[:n_old]:
            print(f"  {pool}/kvm/{date}/{host}")
    else:
        print(f"No sets older than the newest {args.keep}.")
    print()
    for pool, used in sorted(totals.items()):
        n = sum(1 for k in sets if k[0] == pool)
        print(f"{pool:<18} {n} set(s), {human(used)}")

    if args.delete:
        sys.exit(delete_old_sets(ordered, n_old, ds, args.yes))


if __name__ == "__main__":
    main()
