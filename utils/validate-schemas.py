#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks the inventory and the config files against their JSON schemas
(schemas/), and then against the rules a schema cannot state: names are unique,
group members exist, a VM's hypervisor is in the inventory, addresses agree.

  utils/validate-schemas.py                      # inventory.yaml and config/*.yaml
  utils/validate-schemas.py -I path/inv.yaml -c path/config-dir
  utils/validate-schemas.py config/tst7.yaml     # just these files (and the inventory)

Which schema a config file is checked against follows from its content:
`params.hypervisor` makes it a VM config, `params.dns_servers` a DNS config.
Anything else is reported and skipped.

Needs python3 with the `jsonschema` and `yaml` modules
(apt install python3-jsonschema python3-yaml). It reads files only: a location
that is not a plain path or a file: URI is refused. Exit code 1 if any error
is found (warnings alone do not fail it), 2 if it could not run.
"""

import argparse
import ipaddress
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError as e:
    print(f"validate-schemas.py needs python3-jsonschema and python3-yaml ({e})", file=sys.stderr)
    sys.exit(2)

SCHEMAS = Path(__file__).resolve().parent.parent / "schemas"
errors = 0
warnings = 0


def report(where, level, message):
    global errors, warnings
    if level == "error":
        errors += 1
    else:
        warnings += 1
    print(f"{where}: {level}: {message}")


def local_path(location):
    """A plain path or file: URI as a path; anything else is not readable here."""
    parsed = urlparse(location)
    if parsed.scheme in ("", "file") or (len(parsed.scheme) == 1):
        return parsed.path if parsed.scheme == "file" else location
    print(f"{location}: this script reads files only (scheme '{parsed.scheme}')", file=sys.stderr)
    sys.exit(2)


def load_schema(name):
    with open(SCHEMAS / name) as f:
        return Draft202012Validator(json.load(f), format_checker=FormatChecker())


def load_yaml(path):
    try:
        with open(path) as f:
            return yaml.safe_load(f)
    except (OSError, yaml.YAMLError) as e:
        report(path, "error", f"cannot read: {e}")
        return None


def check_schema(path, doc, validator):
    ok = True
    for err in sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path)):
        where = "/".join(str(p) for p in err.absolute_path) or "(top level)"
        message = err.message
        if err.context:  # anyOf / oneOf: say what each alternative objected to
            message += " (" + "; ".join(sorted({c.message for c in err.context})) + ")"
        report(path, "error", f"{where}: {message}")
        ok = False
    return ok


def check_inventory(path, doc):
    nodes = doc.get("nodes", [])
    names, hosts, ips = {}, {}, {}
    for n in nodes:
        name = n["name"]
        if name in names:
            report(path, "error", f"node name '{name}' is used twice")
        names[name] = n
        host = n["host"].lower()
        if host in hosts and hosts[host] != name:
            report(path, "warning", f"nodes '{hosts[host]}' and '{name}' have the same host {n['host']}")
        hosts[host] = name
        ip = (n.get("vars") or {}).get("cluster_ip")
        if ip:
            if ip in ips:
                report(path, "error", f"cluster_ip {ip} is on both '{ips[ip]}' and '{name}'")
            ips[ip] = name
    seen = set()
    for g in doc.get("groups", []):
        if g["name"] in seen:
            report(path, "error", f"group name '{g['name']}' is used twice")
        seen.add(g["name"])
        for m in g["members"]:
            if m not in names:
                report(path, "error", f"group '{g['name']}' has member '{m}', which is not a node")
    return names


def network_of(ip, mask):
    try:
        return ipaddress.ip_network(f"{ip}/{mask}", strict=False)
    except ValueError:
        return None


def check_nic(where, nic, label):
    ip, mask, cidr, gw = nic.get("ipaddress"), nic.get("netmask"), nic.get("cidr"), nic.get("gateway")
    net = network_of(ip, mask) if ip and mask else None
    if cidr:
        try:
            declared = ipaddress.ip_network(cidr, strict=True)
        except ValueError:
            report(where, "error", f"{label}: cidr {cidr} has host bits set")
            declared = None
        if declared is not None and ip:
            if ipaddress.ip_address(ip) not in declared:
                report(where, "error", f"{label}: ipaddress {ip} is not in cidr {cidr}")
            if net is not None and net != declared:
                report(where, "error", f"{label}: netmask {mask} does not agree with cidr {cidr}")
        net = net or declared
    if gw and net is not None and ipaddress.ip_address(gw) not in net:
        report(where, "error", f"{label}: gateway {gw} is not in {net}")


def check_vm_config(path, params, inventory_names, seen):
    host, hv = params["hostname"], params["hypervisor"]
    if Path(path).stem != host:
        report(path, "warning", f"file name does not match hostname '{host}' (config/<hostname>.yaml is the convention)")
    if host in seen["hostnames"]:
        report(path, "error", f"hostname '{host}' is also in {seen['hostnames'][host]}")
    seen["hostnames"][host] = path
    if hv == host:
        report(path, "error", "hypervisor is the VM itself")
    if inventory_names is not None:
        if hv not in inventory_names:
            report(path, "error", f"hypervisor '{hv}' is not a node of the inventory")
        if host not in inventory_names:
            report(path, "warning", f"hostname '{host}' is not in the inventory yet (add it after provisioning)")
    nic0 = params["nic0"]
    check_nic(path, nic0, "nic0")
    ip = nic0["ipaddress"]
    if ip in seen["ips"]:
        report(path, "error", f"nic0 ipaddress {ip} is also used by {seen['ips'][ip]}")
    seen["ips"][ip] = path
    for i, nic in enumerate(params.get("nics", []), start=1):
        check_nic(path, nic, f"nics[{i - 1}]")


def main():
    ap = argparse.ArgumentParser(description="Check the inventory and config files against their schemas.")
    ap.add_argument("-I", "--inventory", default=os.environ.get("ORPHERA_INVENTORY") or "inventory.yaml")
    ap.add_argument("-c", "--config-dir", default=os.environ.get("ORPHERA_CONFIG_DIR") or "config")
    ap.add_argument("files", nargs="*", help="config files to check instead of the whole config directory")
    args = ap.parse_args()

    inv_path = local_path(args.inventory)
    cfg_dir = local_path(args.config_dir)

    inventory_names = None
    inv_doc = load_yaml(inv_path)
    if inv_doc is not None:
        if check_schema(inv_path, inv_doc, load_schema("inventory.schema.json")):
            inventory_names = check_inventory(inv_path, inv_doc)

    files = args.files or sorted(str(p) for p in Path(cfg_dir).glob("*.y*ml"))
    vm_schema, dns_schema = load_schema("vm-config.schema.json"), load_schema("dns-config.schema.json")
    seen = {"hostnames": {}, "ips": {}}
    checked = 0
    for path in files:
        doc = load_yaml(path)
        if doc is None:
            continue
        params = doc.get("params") if isinstance(doc, dict) else None
        if not isinstance(params, dict):
            report(path, "error", "no top-level params: map")
            continue
        if "hypervisor" in params:
            checked += 1
            if check_schema(path, doc, vm_schema):
                check_vm_config(path, params, inventory_names, seen)
        elif "dns_servers" in params:
            checked += 1
            if check_schema(path, doc, dns_schema) and inventory_names is not None:
                for n in params.get("nodes", []):
                    if n not in inventory_names:
                        report(path, "error", f"node '{n}' is not in the inventory")
        else:
            report(path, "warning", "neither a VM config (params.hypervisor) nor a DNS config (params.dns_servers); not checked")

    print(f"checked the inventory and {checked} config file(s): {errors} error(s), {warnings} warning(s)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
