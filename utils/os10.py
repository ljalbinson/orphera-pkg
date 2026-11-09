#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reads, and changes a little of, a Dell SmartFabric OS10 switch (S5148F-ON and others) through its RESTCONF API.

  utils/os10.py interfaces  swt4            # every port: mode, speed, MTU, and the VLANs it carries
  utils/os10.py vlans       swt4            # every VLAN: how many ports carry it, tagged and untagged
  utils/os10.py vlan        swt4 228        # one VLAN: its ports
  utils/os10.py port        swt4 1/1/49:1   # one port: its settings and its VLANs (ethernet1/1/49:1 works too)
  utils/os10.py get         swt4 ietf-interfaces:interfaces/interface=vlan228   # any path under /restconf/data
  utils/os10.py operations  swt4            # the actions the switch offers (/restconf/operations)
  utils/os10.py schema      swt4 yuma-netconf   # a YANG module's source, from the switch (read-only POST of get-schema)
  utils/os10.py save        swt4              # copy running-configuration startup-configuration (asks for yes; --yes, --dry-run)
  utils/os10.py backup      swt4 [FILE]     # saves the whole RESTCONF data tree as JSON (default swt4-<time>.json)

  utils/os10.py set-description swt4 1/1/5 "gs1 eth0"   # a port's description ("" removes it)
  utils/os10.py set-mtu         swt4 1/1/5 9216          # a port's MTU
  utils/os10.py set-mode        swt4 1/1/5 trunk        # trunk (needed before a port can carry tagged VLANs) or access
  utils/os10.py set-speed       swt4 1/1/5 25G           # a port's speed (10G 25G 40G 50G 100G, or auto)

  utils/os10.py vlan-create swt4 600                    # a new VLAN (2-4093)
  utils/os10.py vlan-delete swt4 600                    # only a VLAN that has no ports
  utils/os10.py vlan-add    swt4 600 1/1/44 [--untagged]   # a port into a VLAN, tagged unless --untagged
  utils/os10.py vlan-remove swt4 600 1/1/44             # a port out of a VLAN

The set-* and vlan-* commands change the switch's RUNNING configuration only; nothing is saved to the startup configuration (verified on
an S5148F-ON), so a reload undoes them until `os10.py save` (or `write memory` on the switch) is run. Each shows the old and the new value and asks for `yes`
(--yes skips it, --dry-run sends nothing), then reads the port back to check the switch took it. Only ports (ethernet...)
can be changed. Changing a port's speed or MTU can drop its link: do not do it to the port you are connected through.

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

    def request(self, method, path, body=None, text_ok=False):
        """Send a request to a path under /restconf (it starts with /data or /operations); returns the decoded JSON."""
        payload = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + "/restconf" + path, data=payload, method=method)
        req.add_header("Authorization", self.auth)
        req.add_header("Accept", ACCEPT)
        if payload is not None:
            req.add_header("Content-Type", "application/yang-data+json")
        try:
            with urllib.request.urlopen(req, timeout=self.request_timeout, context=self.context) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            body = e.read()
            if e.code == 401:
                raise IloError("%s refused the login for user %s (not retried: repeated failures can lock the account)"
                               % (self.name, self.user))
            err = IloError("%s: %s %s returned HTTP %d%s" % (self.name, method, path, e.code, _detail(body)))
            err.status = e.code
            raise err
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
            if text_ok:
                return raw.decode("utf-8", "replace")
            raise IloError("%s: the reply to %s %s is not JSON" % (self.name, method, path))

    def get(self, path):
        return self.request("GET", path)

    def data(self, path):
        return self.get("/data/" + path.lstrip("/"))


def _detail(body):
    try:
        errs = json.loads(body)["ietf-restconf:errors"]["error"]
        msg = errs[0].get("error-message") or errs[0].get("error-tag")
        return ": " + str(msg) if msg else ""
    except (ValueError, KeyError, IndexError, TypeError, AttributeError):
        return ""


def _missing(e):
    """True when the switch's error says the interface does not exist (it answers 400 'unknown resource instance',
    other RESTCONF servers 404)."""
    st = getattr(e, "status", None)
    return st == 404 or (st == 400 and "unknown resource" in str(e))


def get_interface(sw, name):
    """The interface document, or None if the switch has no interface of that name."""
    try:
        doc = sw.data("ietf-interfaces:interfaces/interface=" + urllib.parse.quote(name, safe=""))
    except IloError as e:
        if _missing(e):
            return None
        raise
    items = doc.get("ietf-interfaces:interface") or doc.get("interface") or []
    return items[0] if items else None


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
    v = get_interface(sw, "vlan" + a.arg)
    if v is None:
        raise IloError("%s: no VLAN %s" % (sw.name, a.arg))
    un, tg = v.get("dell-interface:untagged-ports") or [], v.get("dell-interface:tagged-ports") or []
    if a.json:
        return print(json.dumps({"vlan": int(a.arg), "untagged": un, "tagged": tg}, indent=2))
    print("VLAN %s" % a.arg)
    print("  untagged (%d): %s" % (len(un), " ".join(sorted(un, key=natural)) or "-"))
    print("  tagged   (%d): %s" % (len(tg), " ".join(sorted(tg, key=natural)) or "-"))


def cmd_port(sw, a):
    name = port_name(a.arg)
    port = get_interface(sw, name)
    if port is None:
        raise IloError("%s: no port %s" % (sw.name, name))
    vlans, _ = split(all_interfaces(sw))
    un, tg = membership(vlans).get(name, ([], []))
    if a.json:
        return print(json.dumps({"interface": port, "untagged": sorted(un), "tagged": sorted(tg)}, indent=2))
    print(json.dumps(port, indent=2))
    print("untagged VLANs: %s" % (ranges(un) or "-"))
    print("tagged VLANs:   %s" % (ranges(tg) or "-"))


def cmd_get(sw, a):
    print(json.dumps(sw.data(a.arg), indent=2))


def cmd_operations(sw, a):
    ops = sw.get("/operations").get("ietf-restconf:operations") or {}
    for name in sorted(ops):
        print(name)


def cmd_schema(sw, a):
    body = {"ietf-netconf-monitoring:input": {"identifier": a.arg, "format": "yang"}}
    doc = sw.request("POST", "/operations/ietf-netconf-monitoring:get-schema", body, text_ok=True)
    if isinstance(doc, str) and doc.lstrip().startswith("<"):  # XML: the module text is the element's text
        try:
            import xml.etree.ElementTree as ET
            doc = "".join(ET.fromstring(doc).itertext())
        except ET.ParseError:
            pass

    def first_text(x):
        if isinstance(x, str):
            return x
        for v in (x.values() if isinstance(x, dict) else x if isinstance(x, list) else []):
            t = first_text(v)
            if t:
                return t
    if isinstance(doc, str) and doc.startswith('{"ietf-netconf-monitoring:output":{"data":'):
        # the switch sends the text inside JSON that is not valid JSON: strip the wrapper
        doc = doc[len('{"ietf-netconf-monitoring:output":{"data":'):].rstrip().rstrip("}").rstrip()
    if isinstance(doc, str) and "&lt;" in doc:
        import html
        doc = html.unescape(doc)
    text = first_text(doc)
    if not text:
        raise IloError("%s: no schema text for %s in the reply" % (sw.name, a.arg))
    print(text)


def cmd_save(sw, a):
    """copy running-configuration startup-configuration, as yuma-netconf:copy-config (source running, target startup)."""
    body = {"yuma-netconf:input": {"source": {"running": [None]}, "target": {"startup": [None]}}}
    print("%s: save the running configuration to the startup configuration (copy-config running -> startup)" % sw.name)
    if a.dry_run:
        return print("dry run, would send: POST /restconf/operations/yuma-netconf:copy-config %s" % json.dumps(body))
    if not a.yes:
        if not sys.stdin.isatty():
            raise IloError("save needs --yes when there is no terminal")
        if input("type yes to go on: ").strip() != "yes":
            raise IloError("not confirmed, nothing sent")
    sw.request("POST", "/operations/yuma-netconf:copy-config", body, text_ok=True)
    print("%s: saved. Check on the switch with `show startup-configuration`." % sw.name)


def cmd_backup(sw, a):
    path = a.extra or "%s-%s.json" % (sw.name.replace(":", "_"), time.strftime("%Y%m%d-%H%M%S"))
    doc = sw.get("/data")
    with open(path, "w") as f:
        json.dump(doc, f, indent=2)
    print("%s: saved %d bytes to %s" % (sw.name, os.path.getsize(path), path))


# ---- changes (running configuration only) ----
SPEEDS = {"10G": "10GIGE", "25G": "25GIGE", "40G": "40GIGE", "50G": "50GIGE", "100G": "100GIGE", "AUTO": "auto"}


def _port_doc(sw, name):
    port = get_interface(sw, name)
    if port is None:
        raise IloError("%s: no port %s" % (sw.name, name))
    return port


def _change(sw, a, label, key, new, shown_new=None):
    """Set one leaf of a port (new None deletes it): show old -> new, confirm, send, read back."""
    if a.arg[:1].isalpha() and not a.arg.startswith("ethernet"):
        raise IloError("only ethernet ports can be changed, not %s" % a.arg)
    name = port_name(a.arg)
    old = _port_doc(sw, name).get(key)
    shown_old = "(none)" if old is None else old
    shown_new = "(none)" if new is None else (new if shown_new is None else shown_new)
    path = "/data/ietf-interfaces:interfaces/interface=" + urllib.parse.quote(name, safe="")
    if old == new:
        print("%s: %s %s is already %s, nothing to do" % (sw.name, name, label, shown_new))
        return
    print("%s: %s %s: %s -> %s   (running configuration only)" % (sw.name, name, label, shown_old, shown_new))
    if new is None:
        method, url, body = "DELETE", path + "/" + key, None
    else:
        method, url, body = "PATCH", path, {"ietf-interfaces:interface": [{"name": name, key: new}]}
    if a.dry_run:
        print("dry run, would send: %s /restconf%s %s" % (method, url, json.dumps(body) if body else ""))
        return
    if not a.yes:
        if not sys.stdin.isatty():
            raise IloError("%s needs --yes when there is no terminal" % a.command)
        if input("type yes to go on: ").strip() != "yes":
            raise IloError("not confirmed, nothing sent")
    sw.request(method, url, body)
    got = _port_doc(sw, name).get(key)
    if got != new:
        raise IloError("%s: sent, but %s now reads %s, not %s" % (sw.name, label, "(none)" if got is None else got, shown_new))
    print("%s: %s %s is now %s. Not saved to the startup configuration: `os10.py save` or `write memory` keeps it." % (sw.name, name, label, shown_new))


def _value(a):
    if a.extra is None:
        raise IloError("%s needs a value after the port" % a.command)
    return a.extra


def cmd_set_description(sw, a):
    text = _value(a)
    _change(sw, a, "description", "description", text if text else None)


def cmd_set_mtu(sw, a):
    v = _value(a)
    if not v.isdigit() or not 64 <= int(v) <= 65535:
        raise IloError("an MTU is a number of bytes, e.g. 9216, not %r" % v)
    _change(sw, a, "MTU", "dell-interface:mtu", int(v))


def cmd_set_speed(sw, a):
    v = _value(a)
    raw = SPEEDS.get(v.upper())
    if raw is None:
        raise IloError("speed %r: use one of %s" % (v, " ".join(sorted(SPEEDS, key=lambda k: (k == "AUTO", len(k), k)))))
    _change(sw, a, "speed", "dell-interface:speed", raw)


def cmd_set_mode(sw, a):
    v = _value(a).lower()
    if v not in ("trunk", "access"):
        raise IloError("a mode is trunk (several VLANs, tagged) or access (one untagged VLAN), not %r" % a.extra)
    # trunk is MODE_L2HYBRID (what the trunk ports on the switch show); access is no mode leaf at all
    _change(sw, a, "mode", "dell-interface:mode", "MODE_L2HYBRID" if v == "trunk" else None,
            shown_new="trunk (MODE_L2HYBRID)" if v == "trunk" else "access (no mode set)")


# ---- VLANs (running configuration only) ----
IFACES = "/data/ietf-interfaces:interfaces/interface"
TAGGED, UNTAGGED = "dell-interface:tagged-ports", "dell-interface:untagged-ports"


def _q(name):
    return urllib.parse.quote(name, safe="")


def _vlan_number(a, member=False):
    if not a.arg.isdigit() or not (1 if member else 2) <= int(a.arg) <= 4093:
        raise IloError("a VLAN is a number from 2 to 4093 (VLAN 1, the default VLAN, can only have ports added or removed)")
    return int(a.arg)


def _vlan_doc(sw, vid):
    """The VLAN's interface document, or None if the switch has no such VLAN."""
    return get_interface(sw, "vlan%d" % vid)


def _confirm(sw, a):
    if not a.yes:
        if not sys.stdin.isatty():
            raise IloError("%s needs --yes when there is no terminal" % a.command)
        if input("type yes to go on: ").strip() != "yes":
            raise IloError("not confirmed, nothing sent")


def _run(sw, a, headline, steps):
    """steps: dicts with method, url, body, and optionally `undo` (a step to take back this one if a later one fails)
    and `fallback` (another step to try if the switch answers 405 or 501). Shows them, confirms, sends in order."""
    print("%s: %s   (running configuration only)" % (sw.name, headline))
    if a.dry_run:
        for st in steps:
            print("dry run, would send: %s /restconf%s %s" % (st["method"], st["url"], json.dumps(st["body"]) if st.get("body") else ""))
        return False
    _confirm(sw, a)
    done = []
    for st in steps:
        try:
            try:
                sw.request(st["method"], st["url"], st.get("body"), text_ok=True)
            except IloError as e:
                if getattr(e, "status", None) in (405, 501) and st.get("fallback"):
                    st = st["fallback"]
                    sw.request(st["method"], st["url"], st.get("body"), text_ok=True)
                else:
                    raise
        except IloError as e:
            for prev in reversed(done):
                if prev.get("undo"):
                    try:
                        sw.request(prev["undo"]["method"], prev["undo"]["url"], prev["undo"].get("body"), text_ok=True)
                        print("%s: took back the earlier step (%s %s)" % (sw.name, prev["method"], prev["url"]))
                    except IloError as e2:
                        print("%s: could NOT take back %s %s: %s" % (sw.name, prev["method"], prev["url"], e2), file=sys.stderr)
            raise e
        done.append(st)
    return True


def _saved_note(sw):
    print("%s: not saved to the startup configuration: `os10.py save` or `write memory` keeps it." % sw.name)


def cmd_vlan_create(sw, a):
    vid = _vlan_number(a)
    if _vlan_doc(sw, vid) is not None:
        return print("%s: VLAN %d already exists, nothing to do" % (sw.name, vid))
    name = "vlan%d" % vid
    entry = {"ietf-interfaces:interface": [{"name": name, "type": "iana-if-type:l2vlan"}]}
    put = {"method": "PUT", "url": IFACES + "=" + _q(name), "body": entry,
           "fallback": {"method": "POST", "url": "/data/ietf-interfaces:interfaces", "body": entry}}
    if not _run(sw, a, "create VLAN %d" % vid, [put]):
        return
    if _vlan_doc(sw, vid) is None:
        raise IloError("%s: sent, but VLAN %d is not there afterwards" % (sw.name, vid))
    print("%s: VLAN %d created" % (sw.name, vid))
    _saved_note(sw)


def cmd_vlan_delete(sw, a):
    vid = _vlan_number(a)
    v = _vlan_doc(sw, vid)
    if v is None:
        raise IloError("%s: no VLAN %d" % (sw.name, vid))
    members = (v.get(UNTAGGED) or []) + (v.get(TAGGED) or [])
    if members:
        raise IloError("%s: VLAN %d still has %d port(s) (%s): take them out first with vlan-remove"
                       % (sw.name, vid, len(members), " ".join(sorted(members, key=natural)[:6]) + (" ..." if len(members) > 6 else "")))
    if not _run(sw, a, "delete VLAN %d (it has no ports)" % vid, [{"method": "DELETE", "url": IFACES + "=" + _q("vlan%d" % vid)}]):
        return
    if _vlan_doc(sw, vid) is not None:
        raise IloError("%s: sent, but VLAN %d is still there" % (sw.name, vid))
    print("%s: VLAN %d deleted" % (sw.name, vid))
    _saved_note(sw)


def _vlan_port(sw, a):
    vid = _vlan_number(a, member=True)  # VLAN 1 can have ports put back into it
    if a.extra is None:
        raise IloError("%s needs a VLAN and a port, e.g. %s swt4 600 1/1/44" % (a.command, a.command))
    if a.extra[:1].isalpha() and not a.extra.startswith("ethernet"):
        raise IloError("only ethernet ports can be changed, not %s" % a.extra)
    port = port_name(a.extra)
    _port_doc(sw, port)  # the port exists
    v = _vlan_doc(sw, vid)
    if v is None:
        raise IloError("%s: no VLAN %d (create it with vlan-create)" % (sw.name, vid))
    return vid, port, v


def _members_now(sw, vid):
    v = _vlan_doc(sw, vid) or {}
    return set(v.get(UNTAGGED) or []), set(v.get(TAGGED) or [])


def cmd_vlan_add(sw, a):
    vid, port, v = _vlan_port(sw, a)
    kind = UNTAGGED if a.untagged else TAGGED
    word = "untagged" if a.untagged else "tagged"
    if port in (v.get(kind) or []):
        return print("%s: %s is already %s in VLAN %d, nothing to do" % (sw.name, port, word, vid))
    other = TAGGED if a.untagged else UNTAGGED
    if port in (v.get(other) or []):
        raise IloError("%s: %s is %s in VLAN %d: take it out first with vlan-remove" % (sw.name, port, "tagged" if a.untagged else "untagged", vid))
    patch = {"method": "PATCH", "url": IFACES + "=" + _q("vlan%d" % vid),
             "body": {"ietf-interfaces:interface": [{"name": "vlan%d" % vid, kind: [port]}]}}
    olds = [ovid for ovid, odoc in sorted(split(all_interfaces(sw))[0].items())
            if a.untagged and ovid != vid and port in (odoc.get(UNTAGGED) or [])]
    try:
        ran = _run(sw, a, "put %s in VLAN %d %s" % (port, vid, word), [patch])
        if not ran and olds:
            print("%s: if the switch refuses because the port is untagged in VLAN %s, it is retried after taking the port out of that VLAN"
                  % (sw.name, ", ".join(map(str, olds))))
    except IloError as first:
        if not olds:
            raise
        print("%s: the switch refused (%s)" % (sw.name, first))
        old_list = ", ".join(map(str, olds))
        a.yes = True  # already confirmed for this change
        steps = [{"method": "DELETE", "url": IFACES + "=" + _q("vlan%d" % o) + "/" + UNTAGGED + "=" + _q(port),
                  "undo": {"method": "PATCH", "url": IFACES + "=" + _q("vlan%d" % o),
                           "body": {"ietf-interfaces:interface": [{"name": "vlan%d" % o, UNTAGGED: [port]}]}}} for o in olds]
        ran = _run(sw, a, "put %s in VLAN %d untagged, moving it out of VLAN %s first, where it is untagged" % (port, vid, old_list),
                   steps + [patch])
    if not ran:
        return
    # the switch may leave the port untagged in its old VLAN too: take it out
    for o in olds:
        if port in _members_now(sw, o)[0]:
            print("%s: %s is still untagged in VLAN %d: taking it out" % (sw.name, port, o))
            sw.request("DELETE", IFACES + "=" + _q("vlan%d" % o) + "/" + UNTAGGED + "=" + _q(port), text_ok=True)
    un, tg = _members_now(sw, vid)
    if port not in (un if a.untagged else tg):
        raise IloError("%s: sent, but %s is not %s in VLAN %d afterwards" % (sw.name, port, word, vid))
    print("%s: %s is now %s in VLAN %d" % (sw.name, port, word, vid))
    _saved_note(sw)


def cmd_vlan_remove(sw, a):
    vid, port, v = _vlan_port(sw, a)
    kind = UNTAGGED if port in (v.get(UNTAGGED) or []) else TAGGED if port in (v.get(TAGGED) or []) else None
    if kind is None:
        return print("%s: %s is not in VLAN %d, nothing to do" % (sw.name, port, vid))
    word = "untagged" if kind == UNTAGGED else "tagged"
    step = {"method": "DELETE", "url": IFACES + "=" + _q("vlan%d" % vid) + "/" + kind + "=" + _q(port)}
    if not _run(sw, a, "take %s out of VLAN %d (it is %s there)" % (port, vid, word), [step]):
        return
    un, tg = _members_now(sw, vid)
    if port in un | tg:
        raise IloError("%s: sent, but %s is still in VLAN %d" % (sw.name, port, vid))
    print("%s: %s is no longer in VLAN %d" % (sw.name, port, vid))
    _saved_note(sw)


COMMANDS = {
    "interfaces": (cmd_interfaces, None), "vlans": (cmd_vlans, None), "vlan": (cmd_vlan, "VLAN number"),
    "port": (cmd_port, "port, e.g. 1/1/49:1"), "get": (cmd_get, "path under /restconf/data"),
    "operations": (cmd_operations, None), "schema": (cmd_schema, "module name, e.g. yuma-netconf"), "save": (cmd_save, None), "backup": (cmd_backup, None),
    "set-description": (cmd_set_description, "port, e.g. 1/1/5"), "set-mtu": (cmd_set_mtu, "port, e.g. 1/1/5"),
    "set-speed": (cmd_set_speed, "port, e.g. 1/1/5"), "set-mode": (cmd_set_mode, "port, e.g. 1/1/5"),
    "vlan-create": (cmd_vlan_create, "VLAN number"), "vlan-delete": (cmd_vlan_delete, "VLAN number"),
    "vlan-add": (cmd_vlan_add, "VLAN number and a port"), "vlan-remove": (cmd_vlan_remove, "VLAN number and a port"),
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
    p = argparse.ArgumentParser(prog="os10.py", description="Reads, and changes ports of, a Dell OS10 switch through RESTCONF.")
    p.add_argument("command", choices=list(COMMANDS))
    p.add_argument("switch", help="the switch's address or DNS name (or a full http(s):// URL)")
    p.add_argument("arg", nargs="?", help="VLAN number, port, or RESTCONF path, for vlan, port and get")
    p.add_argument("extra", nargs="?", help="backup: the file to write; set-*: the new value")
    p.add_argument("--yes", action="store_true", help="do not ask before a change")
    p.add_argument("--untagged", action="store_true", help="vlan-add: put the port in the VLAN untagged (default: tagged)")
    p.add_argument("--dry-run", action="store_true", help="show the change and send nothing")
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
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
