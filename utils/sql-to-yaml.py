#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reads the inventory and the per-VM configs back out of the MariaDB tables in
schemas/mariadb/001-tables.sql: the reverse of utils/yaml-to-sql.py.

  utils/sql-to-yaml.py --mariadb "-h db.example.com -u orphera_reader --ssl-ca certs/ca.crt orphera" --compare
  utils/sql-to-yaml.py --mariadb "..." --out /tmp/from-db           # inventory.yaml and config/*.yaml
  utils/sql-to-yaml.py --sqlite copy.db --compare -I inventory.yaml -c config   # a SQLite copy, for tests

--compare checks that the database holds what the files hold (the inventory and every config, compared as data, not as
text) and exits 1 with one line per difference. --out writes the database as files into a directory that is empty or does not
exist yet (--force: write over files that are there). Run the rebuild scripts against that copy with
`-I DIR/inventory.yaml -c DIR/config` (or ORPHERA_INVENTORY / ORPHERA_CONFIG_DIR) to see whether the database is enough.

--mariadb takes the arguments for the `mariadb` command-line client, in one string. The password is not an argument: unless
MYSQL_PWD is set it is read with `orphera secrets get` from the secret --password-secret (default
orphera/inventory-db-reader) and passed to the client in its environment. Only SELECT is used.

What the files have that the database does not: the order of nodes, groups and members (the database has none, so the export
is sorted by name), and `pdisks` (not stored; utils/yaml-to-sql.py refuses a non-empty one). A config key the database has no
column for is reported by --compare as a difference.

Needs python3-yaml; --compare also needs python3-jsonschema, as validate-schemas.py does.
"""

import argparse
import importlib.util
import json
import os
import shlex
import sqlite3
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("validate_schemas", HERE / "validate-schemas.py")
vs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vs)

# The columns this tool reads. utils/test-sql-roundtrip.py checks them against 001-tables.sql.
TABLES = {
    "node": ["name", "host", "port", "never_reboot"],
    "node_var": ["node_name", "name", "value"],
    "node_group": ["name", "never_reboot"],
    "group_member": ["group_name", "node_name"],
    "group_var": ["group_name", "name", "value"],
    "vm_config": ["hostname", "hypervisor", "domainname", "os", "disk_gb", "memory_mib", "cpu", "host_type", "dns1",
                  "dns2", "nic0_nic", "nic0_ipaddress", "nic0_netmask", "nic0_cidr", "nic0_gateway", "nic0_bridge",
                  "nic0_mtu", "nic0_route_table", "nic0_priority", "extensions"],
    "vm_disk": ["hostname", "ordinal", "size_gb"],
    "vm_nic": ["hostname", "ordinal", "nic", "bridge", "mtu", "ipaddress", "netmask", "cidr", "gateway", "route_table",
               "priority"],
    "vm_package": ["hostname", "phase", "ordinal", "package_name"],
    "config_document": ["kind", "name", "document"],
}

# The params keys a VM config can have and the database holds. Anything else with a value is "unstored".
VM_KEYS = {"hypervisor", "hostname", "domainname", "os", "disk", "memory", "cpu", "host_type", "dns1", "dns2", "nic0",
           "nics", "disks", "pre_basic_packages", "basic_packages"}


# ---- reading the database --------------------------------------------------------------------------------------

def sqlite_rows(path):
    con = sqlite3.connect(path)
    con.row_factory = sqlite3.Row
    out = {}
    for table, cols in TABLES.items():
        out[table] = [dict(r) for r in con.execute(f"SELECT {', '.join(cols)} FROM {table}")]
    con.close()
    return out


def mariadb_rows(client_args, secret):
    env = dict(os.environ)
    if "MYSQL_PWD" not in env:
        got = subprocess.run(["orphera", "secrets", "get", secret], capture_output=True, text=True)
        if got.returncode != 0:
            sys.exit(f"cannot read the secret {secret}: {got.stderr.strip() or 'orphera secrets get failed'}")
        env["MYSQL_PWD"] = got.stdout.strip()
    statements = ["SET SESSION group_concat_max_len = 1073741824"]
    for table, cols in TABLES.items():
        pairs = ", ".join(f"'{c}', {c}" for c in cols)
        statements.append(f"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT({pairs})), '[]') FROM {table}")
    cmd = ["mariadb", *shlex.split(client_args), "--batch", "--raw", "--skip-column-names", "-e", "; ".join(statements)]
    run = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if run.returncode != 0:
        sys.exit(f"the mariadb client failed: {run.stderr.strip()}")
    lines = [ln for ln in run.stdout.splitlines() if ln.strip()]
    if len(lines) != len(TABLES):
        sys.exit(f"expected {len(TABLES)} result lines from the database, got {len(lines)}")
    return {table: json.loads(line) for table, line in zip(TABLES, lines)}


# ---- rows to documents -----------------------------------------------------------------------------------------

def build_inventory(rows):
    node_vars, group_vars, members = defaultdict(dict), defaultdict(dict), defaultdict(list)
    for r in rows["node_var"]:
        node_vars[r["node_name"]][r["name"]] = r["value"]
    for r in rows["group_var"]:
        group_vars[r["group_name"]][r["name"]] = r["value"]
    for r in rows["group_member"]:
        members[r["group_name"]].append(r["node_name"])
    nodes = []
    for r in sorted(rows["node"], key=lambda r: r["name"]):
        node = {"name": r["name"], "host": r["host"]}
        if int(r["port"]) != 50051:
            node["port"] = int(r["port"])
        if r["never_reboot"]:
            node["never-reboot"] = True
        if node_vars[r["name"]]:
            node["vars"] = dict(sorted(node_vars[r["name"]].items()))
        nodes.append(node)
    groups = []
    for r in sorted(rows["node_group"], key=lambda r: r["name"]):
        group = {"name": r["name"], "members": sorted(members[r["name"]])}
        if r["never_reboot"]:
            group["never-reboot"] = True
        if group_vars[r["name"]]:
            group["vars"] = dict(sorted(group_vars[r["name"]].items()))
        groups.append(group)
    doc = {"nodes": nodes}
    if groups:
        doc["groups"] = groups
    return doc


def build_vm(r, rows):
    host = r["hostname"]
    nic0 = {"nic": r["nic0_nic"], "ipaddress": str(r["nic0_ipaddress"]), "netmask": str(r["nic0_netmask"]),
            "gateway": str(r["nic0_gateway"]), "bridge": r["nic0_bridge"]}
    if r["nic0_cidr"] is not None:
        nic0["cidr"] = r["nic0_cidr"]
    nic0.update(mtu=int(r["nic0_mtu"]), table=int(r["nic0_route_table"]), priority=int(r["nic0_priority"]))
    nics = []
    for n in sorted((x for x in rows["vm_nic"] if x["hostname"] == host), key=lambda x: x["ordinal"]):
        nic = {k: n[k] for k in ("nic", "bridge", "ipaddress", "netmask", "cidr", "gateway") if n[k] is not None}
        for k, col, default in (("mtu", "mtu", 1500), ("table", "route_table", 0), ("priority", "priority", 0)):
            if int(n[col]) != default:
                nic[k] = int(n[col])
        nics.append(nic)
    disks = [{"disk": int(d["size_gb"])}
             for d in sorted((x for x in rows["vm_disk"] if x["hostname"] == host), key=lambda x: x["ordinal"])]
    packages = {"pre_basic": [], "basic": []}
    for p in sorted((x for x in rows["vm_package"] if x["hostname"] == host), key=lambda x: x["ordinal"]):
        packages[p["phase"]].append(p["package_name"])
    params = {
        "hypervisor": r["hypervisor"], "hostname": host, "domainname": r["domainname"], "os": r["os"],
        "disk": str(r["disk_gb"]), "memory": str(r["memory_mib"]), "cpu": str(r["cpu"]), "host_type": r["host_type"],
        "dns1": r["dns1"], "dns2": r["dns2"], "nic0": nic0, "nics": nics, "disks": disks, "pdisks": [],
        "pre_basic_packages": packages["pre_basic"], "basic_packages": packages["basic"],
    }
    if r["extensions"]:
        ext = json.loads(r["extensions"]) if isinstance(r["extensions"], str) else r["extensions"]
        params.update(dict(sorted(ext.items())))
    return {"params": params}


def build_configs(rows):
    """{file stem: document}"""
    out = {}
    for r in sorted(rows["vm_config"], key=lambda r: r["hostname"]):
        out[r["hostname"]] = build_vm(r, rows)
    for r in sorted(rows["config_document"], key=lambda r: (r["kind"], r["name"])):
        doc = json.loads(r["document"]) if isinstance(r["document"], str) else r["document"]
        out[r["name"]] = {"params": doc}
    return out


# ---- comparing -------------------------------------------------------------------------------------------------

def canon_inventory(doc):
    nodes = {n["name"]: {"host": n["host"], "port": int(n.get("port", 50051)),
                         "never-reboot": bool(n.get("never-reboot", False)),
                         "vars": {k: str(v) for k, v in (n.get("vars") or {}).items()}}
             for n in doc.get("nodes") or []}
    groups = {g["name"]: {"members": sorted(g.get("members") or []),
                          "never-reboot": bool(g.get("never-reboot", False)),
                          "vars": {k: str(v) for k, v in (g.get("vars") or {}).items()}}
              for g in doc.get("groups") or []}
    return {"nodes": nodes, "groups": groups}


def canon_disk(d):
    return int(d["disk"] if isinstance(d, dict) else d)


def canon_nic(n):
    return {"nic": n.get("nic"), "bridge": n.get("bridge"), "ipaddress": n.get("ipaddress"), "netmask": n.get("netmask"),
            "cidr": n.get("cidr"), "gateway": n.get("gateway"), "mtu": int(n.get("mtu", 1500)),
            "table": int(n.get("table", 0)), "priority": int(n.get("priority", 0))}


def canon_vm(params):
    out = {k: params.get(k) for k in ("hypervisor", "hostname", "domainname", "os", "host_type", "dns1", "dns2")}
    out.update(disk=int(params["disk"]), memory=int(params["memory"]), cpu=int(params["cpu"]))
    out["nic0"] = canon_nic(params["nic0"])
    out["nics"] = [canon_nic(n) for n in params.get("nics") or []]
    out["disks"] = [canon_disk(d) for d in params.get("disks") or []]
    out["pre_basic_packages"] = list(params.get("pre_basic_packages") or [])
    out["basic_packages"] = list(params.get("basic_packages") or [])
    out["extensions"] = {k: v for k, v in sorted(params.items()) if k.startswith("x-")}
    out["unstored"] = {k: v for k, v in sorted(params.items())
                       if k not in VM_KEYS and not k.startswith("x-") and v not in (None, "", [], {})}
    return out


def canon_configs(docs):
    """{('vm', hostname) | ('dns', stem): canonical params}"""
    out = {}
    for stem, doc in docs.items():
        params = doc["params"]
        if "hypervisor" in params:
            out[("vm", params["hostname"])] = canon_vm(params)
        elif "dns_servers" in params:
            out[("dns", stem)] = params
        else:
            out[("other", stem)] = params
    return out


def diff(a, b, path, a_name, b_name):
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b), key=str):
            if k not in a:
                out.append(f"{path}.{k}: only in the {b_name}: {b[k]!r}")
            elif k not in b:
                out.append(f"{path}.{k}: only in the {a_name}: {a[k]!r}")
            else:
                out += diff(a[k], b[k], f"{path}.{k}", a_name, b_name)
        return out
    return [] if a == b else [f"{path}: {a_name} {a!r} != {b_name} {b!r}"]


def files_configs(config_dir):
    docs = {}
    for p in sorted(Path(vs.local_path(config_dir)).glob("*.y*ml")):
        docs[p.stem] = vs.load_yaml(str(p))
    return docs


def compare(rows, inventory, config_dir):
    file_inv = canon_inventory(vs.load_yaml(vs.local_path(inventory)))
    db_inv = canon_inventory(build_inventory(rows))
    lines = diff(file_inv, db_inv, "inventory", "file", "database")
    file_cfg = canon_configs(files_configs(config_dir))
    db_cfg = canon_configs(build_configs(rows))
    for key in sorted(set(file_cfg) | set(db_cfg)):
        label = f"config {key[0]} {key[1]}"
        if key not in db_cfg:
            lines.append(f"{label}: only in the files")
        elif key not in file_cfg:
            lines.append(f"{label}: only in the database")
        else:
            lines += diff(file_cfg[key], db_cfg[key], label, "file", "database")
    return lines


# ---- writing files ---------------------------------------------------------------------------------------------

def dump(doc, header):
    return header + yaml.safe_dump(doc, sort_keys=False, default_flow_style=False, width=120, allow_unicode=True)


def write_out(rows, out_dir, force):
    out = Path(out_dir)
    if out.exists() and any(out.iterdir()) and not force:
        sys.exit(f"{out} is not empty; use an empty or new directory, or --force to write over files in it")
    (out / "config").mkdir(parents=True, exist_ok=True)
    header = "# Exported from the database by utils/sql-to-yaml.py; the order of nodes, groups and members is by name.\n"
    (out / "inventory.yaml").write_text(dump(build_inventory(rows), header), encoding="utf-8")
    configs = build_configs(rows)
    for stem, doc in configs.items():
        (out / "config" / f"{stem}.yaml").write_text(dump(doc, header), encoding="utf-8")
    return 1 + len(configs)


def main():
    ap = argparse.ArgumentParser(description="Read the inventory and configs back out of the MariaDB tables.")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--mariadb", metavar="ARGS", help="arguments for the mariadb client, as one string")
    src.add_argument("--sqlite", metavar="FILE", help="a SQLite copy with the same tables (tests)")
    ap.add_argument("--password-secret", default="orphera/inventory-db-reader")
    ap.add_argument("--out", metavar="DIR", help="write inventory.yaml and config/*.yaml into DIR")
    ap.add_argument("--force", action="store_true", help="with --out: write over files that are there")
    ap.add_argument("--compare", action="store_true", help="check the database against the files")
    ap.add_argument("-I", "--inventory", default=os.environ.get("ORPHERA_INVENTORY") or "inventory.yaml")
    ap.add_argument("-c", "--config-dir", default=os.environ.get("ORPHERA_CONFIG_DIR") or "config")
    args = ap.parse_args()
    if not (args.out or args.compare):
        ap.error("give --compare, --out DIR, or both")

    rows = sqlite_rows(args.sqlite) if args.sqlite else mariadb_rows(args.mariadb, args.password_secret)
    status = 0
    if args.out:
        n = write_out(rows, args.out, args.force)
        print(f"wrote {n} file(s) under {args.out}")
    if args.compare:
        lines = compare(rows, args.inventory, args.config_dir)
        for ln in lines:
            print(ln)
        print(f"{len(lines)} difference(s) between the database and {args.inventory} + {args.config_dir}"
              if lines else f"the database equals {args.inventory} + {args.config_dir}")
        status = 1 if lines else 0
    return status


if __name__ == "__main__":
    sys.exit(main())
