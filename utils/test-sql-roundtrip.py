#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks that utils/yaml-to-sql.py and utils/sql-to-yaml.py are each other's inverse, without a MariaDB server.

The tables are created in SQLite from the column lists in schemas/mariadb/001-tables.sql (no constraints, so this tests
the data going in and out, not the server's rules), the real loader fills them, and the real exporter reads them back.

  utils/test-sql-roundtrip.py        (also: make check-roundtrip)

Needs python3-yaml and python3-jsonschema.
"""
import importlib.util
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
failures = passes = 0


def check(name, ok, detail=""):
    global failures, passes
    if ok:
        passes += 1
    else:
        failures += 1
        print(f"FAIL: {name}" + (f"\n    {detail}" if detail else ""))


def run(*cmd, cwd=ROOT):
    p = subprocess.run([str(c) for c in cmd], capture_output=True, text=True, cwd=cwd)
    return p.returncode, p.stdout, p.stderr


def table_columns():
    """{table: [columns]} from the CREATE TABLE statements: lines that start with a column name."""
    text = (ROOT / "schemas" / "mariadb" / "001-tables.sql").read_text()
    out = {}
    for m in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+) \((.*?)\n\) ENGINE", text, re.S):
        cols = []
        for line in m.group(2).splitlines():
            tok = line.strip().split()
            if tok and not line.startswith("    ") and tok[0].upper() not in (
                    "PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "CHECK") and re.fullmatch(r"[a-z_0-9]+", tok[0]):
                cols.append(tok[0])
        out[m.group(1)] = cols
    return out


def make_db(path, loader_sql):
    con = sqlite3.connect(path)
    for t, cols in table_columns().items():
        con.execute(f"CREATE TABLE {t} ({', '.join(cols)})")
    inserts = [ln for ln in loader_sql.splitlines() if ln.startswith("INSERT INTO ")]
    con.executescript("\n".join(inserts))
    con.commit()
    con.close()
    return len(inserts)


def load_and_compare(label, inv, cfg, tmp):
    rc, sql, err = run(HERE / "yaml-to-sql.py", "-I", inv, "-c", cfg)
    check(f"{label}: the loader accepts the files", rc == 0, err.strip()[-300:])
    if rc != 0:
        return None
    db = tmp / f"{label}.db"
    n = make_db(db, sql)
    check(f"{label}: rows were loaded", n > 0)
    rc, out, err = run(HERE / "sql-to-yaml.py", "--sqlite", db, "--compare", "-I", inv, "-c", cfg)
    check(f"{label}: the database equals the files", rc == 0, (out + err).strip()[-600:])
    # export, then the exported files must validate and equal the database too
    exp = tmp / f"{label}-export"
    rc, out, err = run(HERE / "sql-to-yaml.py", "--sqlite", db, "--out", exp)
    check(f"{label}: the export is written", rc == 0, (out + err).strip()[-300:])
    rc, out, err = run(HERE / "validate-schemas.py", "-I", exp / "inventory.yaml", "-c", exp / "config")
    check(f"{label}: the exported files pass the schemas and rules", rc == 0, (out + err).strip()[-600:])
    rc, out, err = run(HERE / "sql-to-yaml.py", "--sqlite", db, "--compare", "-I", exp / "inventory.yaml", "-c", exp / "config")
    check(f"{label}: the exported files equal the database", rc == 0, (out + err).strip()[-600:])
    rc, _, err = run(HERE / "sql-to-yaml.py", "--sqlite", db, "--out", exp)
    check(f"{label}: --out refuses a directory that has files", rc != 0 and "not empty" in err, err.strip()[-200:])
    return db


FIXTURE_INVENTORY = """\
nodes:
  - name: hv1
    host: hv1.example.com
    never-reboot: true
    vars:
      cluster_ip: "10.0.0.1"
  - name: hv2
    host: hv2.example.com
    port: 50052
  - name: vm1
    host: vm1.example.com
    vars:
      role: mon
      cluster_ip: "10.0.0.11"
groups:
  - name: hypervisors
    members: [hv2, hv1]
    never-reboot: true
    vars:
      tier: "1"
  - name: vms
    members: [vm1]
"""

FIXTURE_VM = """\
params:
  hypervisor: hv1
  hostname: vm1
  domainname: example.com
  os: noble-server-cloudimg-amd64.img
  disk: '20'
  memory: '4096'
  cpu: '2'
  host_type: host-passthrough
  dns1: '10.0.0.53'
  dns2: '10.0.0.54'
  nic0:
    nic: enp1s0
    ipaddress: '10.0.0.11'
    gateway: '10.0.0.1'
    bridge: br0
    netmask: '255.255.255.0'
    mtu: 9000
    table: 1
    priority: 101
  nics:
    - nic: enp2s0
      bridge: br1
      ipaddress: '10.1.0.11'
      netmask: '255.255.255.0'
      gateway: '10.1.0.1'
      mtu: 1400
      table: 2
      priority: 7
    - bridge: br2
  disks:
    - 30
    - disk: 40
  pre_basic_packages: [qemu-guest-agent, openvswitch-switch]
  basic_packages: [htop, tree]
  x-note: from the fixture
"""

FIXTURE_DNS = """\
params:
  nodes: [vm1]
  dns_servers: ['10.0.0.53', '10.0.0.54']
  search_domains: [example.com]
"""


def main():
    # 1. the tool's column lists are the real tables'
    sys.path.insert(0, str(HERE))
    spec = importlib.util.spec_from_file_location("sql_to_yaml", HERE / "sql-to-yaml.py")
    s2y = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(s2y)
    real = table_columns()
    check("every table in 001-tables.sql is known to the exporter (and the reverse)",
          {t for t in real if t != "orphera_schema"} == set(s2y.TABLES), f"{sorted(real)} vs {sorted(s2y.TABLES)}")
    for t, cols in s2y.TABLES.items():
        stored = [c for c in real.get(t, []) if c not in ("updated_at", "cluster_ip_unique")]
        check(f"the exporter reads exactly the columns of {t}", stored == cols, f"{stored} vs {cols}")

    tmp = Path(tempfile.mkdtemp(prefix="sql-roundtrip-"))
    try:
        # 2. the real inventory and configs
        db = load_and_compare("real", ROOT / "inventory.yaml", ROOT / "config", tmp)

        # 3. a fixture that uses what the real files do not
        fx = tmp / "fixture"
        (fx / "config").mkdir(parents=True)
        (fx / "inventory.yaml").write_text(FIXTURE_INVENTORY)
        (fx / "config" / "vm1.yaml").write_text(FIXTURE_VM)
        (fx / "config" / "dns.yaml").write_text(FIXTURE_DNS)
        fdb = load_and_compare("fixture", fx / "inventory.yaml", fx / "config", tmp)

        # 4. differences are found
        if db:
            for label, sql, expect in (
                ("a changed host", "UPDATE node SET host = 'elsewhere.example.com' WHERE name = 'tst3'", "tst3"),
                ("a removed node variable", "DELETE FROM node_var WHERE node_name = 'tst4' AND name = 'cluster_ip'", "tst4"),
                ("a changed disk size", "UPDATE vm_disk SET size_gb = 1 WHERE hostname = 'tst0' AND ordinal = 0", "tst0"),
                ("a lost never-reboot", "UPDATE node SET never_reboot = 0 WHERE name = 'gs1'", "gs1"),
                ("a removed package", "DELETE FROM vm_package WHERE hostname = 'tst1' AND package_name = 'htop'", "tst1"),
            ):
                copy = tmp / "mutated.db"
                shutil.copy(db, copy)
                con = sqlite3.connect(copy)
                con.execute(sql)
                con.commit()
                con.close()
                rc, out, _ = run(HERE / "sql-to-yaml.py", "--sqlite", copy, "--compare")
                check(f"{label} is reported", rc == 1 and expect in out, out.strip()[-300:])
        # a config key the database has no column for is reported, and the loader refuses a non-empty pdisks
        if fdb:
            extra = tmp / "extra"
            shutil.copytree(fx, extra)
            (extra / "config" / "vm1.yaml").write_text(FIXTURE_VM + "  pdisks:\n    - /dev/sdx\n")
            rc, _, err = run(HERE / "yaml-to-sql.py", "-I", extra / "inventory.yaml", "-c", extra / "config")
            check("the loader refuses a non-empty pdisks", rc != 0 and "pdisks" in err, err.strip()[-300:])
            rc, out, _ = run(HERE / "sql-to-yaml.py", "--sqlite", fdb, "--compare", "-I", extra / "inventory.yaml",
                             "-c", extra / "config")
            check("a pdisks entry the database cannot hold is reported", rc == 1 and "pdisks" in out, out.strip()[-300:])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print(f"{failures} check(s) failed")
        return 1
    print(f"test-sql-roundtrip: all {passes} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
