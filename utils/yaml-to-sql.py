#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the inventory and the per-VM configs as SQL for the MariaDB tables in
schemas/mariadb/001-tables.sql, to load them into the database:

  utils/yaml-to-sql.py > load.sql                 # inventory.yaml and config/*.yaml
  utils/yaml-to-sql.py | mariadb orphera          # straight in
  utils/yaml-to-sql.py --replace | mariadb orphera
  utils/yaml-to-sql.py -I path/inv.yaml -c path/config-dir

The files are checked first, against the schemas and the rules in
utils/validate-schemas.py; if there is an error, no SQL is written. The SQL is one
transaction. By default it only inserts, so loading twice fails on the first
duplicate and changes nothing; with --replace it first deletes every row of the
tables it fills (the inventory and the configs it was given), so the database
ends up as the files are. This writes the SQL and does not connect to anything.

Needs python3-jsonschema and python3-yaml, as validate-schemas.py does.
"""

import argparse
import contextlib
import importlib.util
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("validate_schemas", HERE / "validate-schemas.py")
vs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vs)


def q(value):
    """A SQL literal. MariaDB treats backslash as an escape in a string by default."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def insert(table, **columns):
    names = ", ".join(columns)
    values = ", ".join(q(v) for v in columns.values())
    return f"INSERT INTO {table} ({names}) VALUES ({values});"


def as_int(v):
    return int(v)


def disk_size(entry):
    return as_int(entry["disk"] if isinstance(entry, dict) else entry)


def inventory_sql(doc):
    out = []
    for n in doc["nodes"]:
        out.append(insert("node", name=n["name"], host=n["host"], port=n.get("port", 50051),
                          never_reboot=n.get("never-reboot", False)))
        for k, v in (n.get("vars") or {}).items():
            out.append(insert("node_var", node_name=n["name"], name=k, value=v))
    for g in doc.get("groups", []):
        out.append(insert("node_group", name=g["name"], never_reboot=g.get("never-reboot", False)))
        for m in g["members"]:
            out.append(insert("group_member", group_name=g["name"], node_name=m))
        for k, v in (g.get("vars") or {}).items():
            out.append(insert("group_var", group_name=g["name"], name=k, value=v))
    return out


def vm_sql(params):
    host, nic0 = params["hostname"], params["nic0"]
    ext = {k: v for k, v in params.items() if k.startswith("x-")}
    out = [insert(
        "vm_config", hostname=host, hypervisor=params["hypervisor"], domainname=params["domainname"],
        os=params["os"], disk_gb=as_int(params["disk"]), memory_mib=as_int(params["memory"]),
        cpu=as_int(params["cpu"]), host_type=params["host_type"], dns1=params["dns1"], dns2=params["dns2"],
        nic0_nic=nic0["nic"], nic0_ipaddress=nic0["ipaddress"], nic0_netmask=nic0["netmask"],
        nic0_cidr=nic0.get("cidr"), nic0_gateway=nic0["gateway"], nic0_bridge=nic0["bridge"],
        nic0_mtu=nic0.get("mtu", 1500), nic0_route_table=nic0.get("table", 0),
        nic0_priority=nic0.get("priority", 0),
        extensions=json.dumps(ext, sort_keys=True) if ext else None)]
    for i, d in enumerate(params.get("disks", [])):
        out.append(insert("vm_disk", hostname=host, ordinal=i, size_gb=disk_size(d)))
    for i, n in enumerate(params.get("nics", [])):
        out.append(insert(
            "vm_nic", hostname=host, ordinal=i, nic=n.get("nic"), bridge=n["bridge"], mtu=n.get("mtu", 1500),
            ipaddress=n.get("ipaddress"), netmask=n.get("netmask"), cidr=n.get("cidr"),
            gateway=n.get("gateway"), route_table=n.get("table", 0), priority=n.get("priority", 0)))
    for phase, key in (("pre_basic", "pre_basic_packages"), ("basic", "basic_packages")):
        for i, p in enumerate(params.get(key, [])):
            out.append(insert("vm_package", hostname=host, phase=phase, ordinal=i, package_name=p))
    return out


# Children before parents, for --replace.
TABLES_DELETE_ORDER = ["vm_package", "vm_nic", "vm_disk", "vm_config", "config_document",
                       "group_var", "group_member", "node_group", "node_var", "node"]


def main():
    ap = argparse.ArgumentParser(description="Write the inventory and configs as SQL for MariaDB.")
    ap.add_argument("-I", "--inventory", default=os.environ.get("ORPHERA_INVENTORY") or "inventory.yaml")
    ap.add_argument("-c", "--config-dir", default=os.environ.get("ORPHERA_CONFIG_DIR") or "config")
    ap.add_argument("--replace", action="store_true", help="delete the existing rows first")
    ap.add_argument("files", nargs="*", help="config files instead of the whole config directory")
    args = ap.parse_args()

    # Check everything first, with the validator's own checks and messages.
    sys.argv = ["validate-schemas.py", "-I", args.inventory, "-c", args.config_dir] + args.files
    try:
        with contextlib.redirect_stdout(sys.stderr):  # stdout is the SQL
            vs.main()
    except SystemExit as e:
        if e.code != 0:
            print("not writing SQL: fix the errors above first", file=sys.stderr)
            sys.exit(1)
    inv = vs.load_yaml(vs.local_path(args.inventory))
    files = args.files or sorted(str(p) for p in Path(vs.local_path(args.config_dir)).glob("*.y*ml"))

    statements = inventory_sql(inv)
    for path in files:
        params = vs.load_yaml(path)["params"]
        if "hypervisor" in params:
            statements += vm_sql(params)
        elif "dns_servers" in params:
            statements.append(insert("config_document", kind="dns", name=Path(path).stem,
                                     document=json.dumps(params, sort_keys=True)))

    print("-- Generated by utils/yaml-to-sql.py; loads into schemas/mariadb/001-tables.sql.")
    # The literals above escape a backslash, as MariaDB does unless told not to.
    print("SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '');")
    print("START TRANSACTION;")
    if args.replace:
        for t in TABLES_DELETE_ORDER:
            print(f"DELETE FROM {t};")
    for s in statements:
        print(s)
    print("COMMIT;")


if __name__ == "__main__":
    main()
