#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reads a Dell SmartFabric OS10 switch (S5148F-ON and others) through its RESTCONF API. Changes nothing.

  utils/os10.py interfaces  swt4            # every port: mode, speed, MTU, and the VLANs it carries
  utils/os10.py vlans       swt4            # every VLAN: how many ports carry it, tagged and untagged
  utils/os10.py vlan        swt4 228        # one VLAN: its ports
  utils/os10.py port        swt4 1/1/49:1   # one port: its settings and its VLANs (ethernet1/1/49:1 works too)
  utils/os10.py get         swt4 ietf-interfaces:interfaces/interface=vlan228   # any path under /restconf/data
  utils/os10.py operations  swt4            # the actions the switch offers (/restconf/operations)
  utils/os10.py backup      swt4 [FILE]     # saves the whole RESTCONF data tree as JSON (default swt4-<time>.json)

The switch is named by its address or DNS name; `http://host:port` or `https://host:port` gives a full URL.
--json prints interfaces, vlans, vlan and port as JSON instead of a table.

The switch's RESTCONF server is off by default: on the switch, `configure terminal`, then `rest api restconf`
(and `copy running-configuration startup-configuration` to keep it over a reboot). It listens on HTTPS port 443.

  Login     --user NAME (or OS10_USER; default admin) and the password: the Orphera secret named after the
            switch as typed (`swt4` is the secret `swt4`), read with `orphera secrets get`; store it with
            `echo 'the password' | orphera secrets set swt4`. To override: OS10_PASSWORD in the environment,
            --password-file FILE, or --secret NAME. With none of these and no such secret, a terminal gets a
            prompt. The password is never taken from the command line.
  TLS       the switch has a self-signed certificate until you install one: --insecure (or OS10_INSECURE=1)
            skips the check, --cacert FILE checks against that certificate.

Exit code: 0 done, 1 the switch failed or refused, 2 wrong usage.
"""
import argparse
import getpass
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import base64

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ilo  # noqa: E402  (secret_key, read_secret and tls_context are shared with the iLO tool)

IloError = ilo.IloError
ACCEPT = "application/yang-data+json, application/json, */*;q=0.8"


class Switch:
    def __init__(self, target, user, password, context, request_timeout=60.0):
        t = target.strip().rstrip("/")
        self.base = t if t.startswith(("http://", "https://")) else "https://" + t
        self.name = t.split("://", 1)[-1]
        self.user = user
        self.auth = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
        self.context = context
        self.request_timeout = request_timeout

    def get(self, path):
        """GET a path under /restconf (it starts with /data or /operations); returns the decoded JSON."""
        req = urllib.request.Request(self.base + "/restconf" + path, method="GET")
        req.add_header("Authorization", self.auth)
        req.add_header("Accept", ACCEPT)
        try:
            with urllib.request.urlopen(req, timeout=self.request_timeout, context=self.context) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            body = e.read()
            if e.code == 401:
                raise IloError("%s refused the login for user %s (not retried: repeated failures can lock the account)"
                               % (self.name, self.user))
            raise IloError("%s: GET %s returned HTTP %d%s" % (self.name, path, e.code, _detail(body)))
        except urllib.error.URLError as e:
            if isinstance(e.reason, ssl.SSLCertVerificationError):
                raise IloError("%s: the certificate is not trusted (%s); use --insecure for the switch's self-signed "
                               "certificate or --cacert FILE" % (self.name, e.reason.verify_message))
            raise IloError("%s: cannot reach the switch: %s (is `rest api restconf` on?)" % (self.name, e.reason))
        except OSError as e:
            raise IloError("%s: %s" % (self.name, e))
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            raise IloError("%s: the reply to GET %s is not JSON" % (self.name, path))

    def data(self, path):
        return self.get("/data/" + path.lstrip("/"))


def _detail(body):
    try:
        errs = json.loads(body)["ietf-restconf:errors"]["error"]
        msg = errs[0].get("error-message") or errs[0].get("error-tag")
        return ": " + str(msg) if msg else ""
    except (ValueError, KeyError, IndexError, TypeError, AttributeError):
        return ""


# ---- what the interfaces document holds ----
def all_interfaces(sw):
    doc = sw.data("ietf-interfaces:interfaces/interface")
    return doc.get("ietf-interfaces:interface") or doc.get("interface") or []


def vlan_id(name):
    return int(name[4:]) if name.startswith("vlan") and name[4:].isdigit() else None


def ranges(ids):
    """[105, 106, 107, 228] -> '105-107,228'"""
    ids = sorted(set(ids))
    out, i = [], 0
    while i < len(ids):
        j = i
        while j + 1 < len(ids) and ids[j + 1] == ids[j] + 1:
            j += 1
        out.append(str(ids[i]) if i == j else "%d-%d" % (ids[i], ids[j]))
        i = j + 1
    return ",".join(out)


def split(ifaces):
    vlans = {vlan_id(i["name"]): i for i in ifaces if vlan_id(i["name"]) is not None}
    ports = [i for i in ifaces if i["name"].startswith("ethernet")]
    return vlans, ports


def membership(vlans):
    """{port: (untagged vlan ids, tagged vlan ids)}"""
    m = {}
    for vid, v in vlans.items():
        for p in v.get("dell-interface:untagged-ports") or []:
            m.setdefault(p, ([], []))[0].append(vid)
        for p in v.get("dell-interface:tagged-ports") or []:
            m.setdefault(p, ([], []))[1].append(vid)
    return m


def port_name(arg):
    return arg if arg.startswith("ethernet") else "ethernet" + arg


def natural(name):
    import re
    return [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", name)]


def table(rows, header):
    widths = [max(len(str(r[i])) for r in [header] + rows) for i in range(len(header))]
    lines = ["  ".join(str(c).ljust(w) for c, w in zip(r, widths)).rstrip() for r in [header] + rows]
    return "\n".join(lines)


# ---- the commands ----
def cmd_interfaces(sw, a):
    vlans, ports = split(all_interfaces(sw))
    mem = membership(vlans)
    rows, data = [], []
    for p in sorted(ports, key=lambda i: natural(i["name"])):
        un, tg = mem.get(p["name"], ([], []))
        mode = (p.get("dell-interface:mode") or "-").replace("MODE_", "")
        speed = (p.get("dell-interface:speed") or "-").replace("GIGE", "G")
        mtu = p.get("dell-interface:mtu", "-")
        rows.append([p["name"], mode, speed, mtu, ranges(un) or "-", ranges(tg) or "-"])
        data.append({"name": p["name"], "mode": mode, "speed": speed, "mtu": mtu,
                     "untagged": sorted(un), "tagged": sorted(tg)})
    if a.json:
        return print(json.dumps(data, indent=2))
    print(table(rows, ["PORT", "MODE", "SPEED", "MTU", "UNTAGGED", "TAGGED"]))


def cmd_vlans(sw, a):
    vlans, _ = split(all_interfaces(sw))
    rows, data = [], []
    for vid in sorted(vlans):
        v = vlans[vid]
        un, tg = v.get("dell-interface:untagged-ports") or [], v.get("dell-interface:tagged-ports") or []
        rows.append([vid, len(un), len(tg)])
        data.append({"vlan": vid, "untagged_ports": len(un), "tagged_ports": len(tg)})
    if a.json:
        return print(json.dumps(data, indent=2))
    print(table(rows, ["VLAN", "UNTAGGED", "TAGGED"]))


def cmd_vlan(sw, a):
    if not a.arg.isdigit():
        raise IloError("a VLAN is given by its number, e.g. 228")
    name = "vlan" + a.arg
    doc = sw.data("ietf-interfaces:interfaces/interface=" + urllib.parse.quote(name, safe=""))
    items = doc.get("ietf-interfaces:interface") or doc.get("interface") or []
    if not items:
        raise IloError("%s: no VLAN %s" % (sw.name, a.arg))
    v = items[0]
    un, tg = v.get("dell-interface:untagged-ports") or [], v.get("dell-interface:tagged-ports") or []
    if a.json:
        return print(json.dumps({"vlan": int(a.arg), "untagged": un, "tagged": tg}, indent=2))
    print("VLAN %s" % a.arg)
    print("  untagged (%d): %s" % (len(un), " ".join(sorted(un, key=natural)) or "-"))
    print("  tagged   (%d): %s" % (len(tg), " ".join(sorted(tg, key=natural)) or "-"))


def cmd_port(sw, a):
    name = port_name(a.arg)
    doc = sw.data("ietf-interfaces:interfaces/interface=" + urllib.parse.quote(name, safe=""))
    items = doc.get("ietf-interfaces:interface") or doc.get("interface") or []
    if not items:
        raise IloError("%s: no port %s" % (sw.name, name))
    vlans, _ = split(all_interfaces(sw))
    un, tg = membership(vlans).get(name, ([], []))
    if a.json:
        return print(json.dumps({"interface": items[0], "untagged": sorted(un), "tagged": sorted(tg)}, indent=2))
    print(json.dumps(items[0], indent=2))
    print("untagged VLANs: %s" % (ranges(un) or "-"))
    print("tagged VLANs:   %s" % (ranges(tg) or "-"))


def cmd_get(sw, a):
    print(json.dumps(sw.data(a.arg), indent=2))


def cmd_operations(sw, a):
    ops = sw.get("/operations").get("ietf-restconf:operations") or {}
    for name in sorted(ops):
        print(name)


def cmd_backup(sw, a):
    path = a.extra or "%s-%s.json" % (sw.name.replace(":", "_"), time.strftime("%Y%m%d-%H%M%S"))
    doc = sw.get("/data")
    with open(path, "w") as f:
        json.dump(doc, f, indent=2)
    print("%s: saved %d bytes to %s" % (sw.name, os.path.getsize(path), path))


COMMANDS = {
    "interfaces": (cmd_interfaces, None), "vlans": (cmd_vlans, None), "vlan": (cmd_vlan, "VLAN number"),
    "port": (cmd_port, "port, e.g. 1/1/49:1"), "get": (cmd_get, "path under /restconf/data"),
    "operations": (cmd_operations, None), "backup": (cmd_backup, None),
}


# ---- command line ----
def password_for(a, target):
    if os.environ.get("OS10_PASSWORD"):
        return os.environ["OS10_PASSWORD"]
    if a.password_file:
        try:
            with open(a.password_file) as f:
                return f.read().rstrip("\r\n")
        except OSError as e:
            raise IloError("cannot read %s: %s" % (a.password_file, e))
    name = a.secret or ilo.secret_key(target)
    value, why = ilo.read_secret(name)
    if value is not None:
        return value
    if sys.stdin.isatty():
        return getpass.getpass("password for %s on %s (no secret %s: %s): " % (a.user, target, name, why))
    raise IloError("no password for %s: no secret `%s` (%s); store it with: echo 'the password' | orphera secrets set %s"
                   % (target, name, why, name))


def parser():
    p = argparse.ArgumentParser(prog="os10.py", description="Reads a Dell OS10 switch through RESTCONF. Changes nothing.")
    p.add_argument("command", choices=list(COMMANDS))
    p.add_argument("switch", help="the switch's address or DNS name (or a full http(s):// URL)")
    p.add_argument("arg", nargs="?", help="VLAN number, port, or RESTCONF path, for vlan, port and get")
    p.add_argument("extra", nargs="?", help="backup: the file to write")
    p.add_argument("-u", "--user", default=os.environ.get("OS10_USER", "admin"))
    p.add_argument("--password-file", metavar="FILE")
    p.add_argument("--secret", metavar="NAME", help="use this Orphera secret (default: the secret named after the switch)")
    p.add_argument("-k", "--insecure", action="store_true", default=os.environ.get("OS10_INSECURE") == "1",
                   help="do not check the switch's certificate (self-signed)")
    p.add_argument("--cacert", metavar="FILE")
    p.add_argument("--json", action="store_true", help="JSON instead of a table (interfaces, vlans, vlan, port)")
    return p


def main(argv=None):
    pr = parser()
    a = pr.parse_args(argv)
    if COMMANDS[a.command][1] and not a.arg:
        pr.error("%s needs a %s" % (a.command, COMMANDS[a.command][1]))
    if a.command == "backup":
        a.extra, a.arg = a.arg or a.extra, None
    try:
        sw = Switch(a.switch, a.user, password_for(a, a.switch), ilo.tls_context(a))
        COMMANDS[a.command][0](sw, a)
    except IloError as e:
        print("FAIL  %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
