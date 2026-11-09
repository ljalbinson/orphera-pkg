#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Tests utils/os10.py against a mock OS10 RESTCONF server on localhost, with data shaped like a real S5148F-ON's
(OS10 10.4.3.11). Needs no switch and no lab. Run from the repository root: make check-os10"""
import base64
import contextlib
import copy
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("os10", os.path.join(here, "os10.py"))
os10 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(os10)
os.environ["OS10_PASSWORD"] = "secret"
for v in ("OS10_USER", "OS10_INSECURE"):
    os.environ.pop(v, None)
os.environ["ILO_PASSWORD"] = "the-ilo-password"  # must never be used for a switch

fail = 0


def check(msg, cond, detail=""):
    global fail
    if cond:
        print("  ok    " + msg)
    else:
        fail += 1
        print("  FAIL  %s %s" % (msg, detail))


def eth(name, configured=True):
    d = {"name": name, "type": "iana-if-type:ethernetCsmacd", "dell-qos:qos-cfg": {"flow-control-rx": True}}
    if configured:
        d.update({"dell-interface:mtu": 9216, "dell-interface:mode": "MODE_L2HYBRID", "dell-interface:speed": "25GIGE"})
    return d


def vlan(vid, tagged=None, untagged=None):
    d = {"name": "vlan%d" % vid, "type": "iana-if-type:l2vlan"}
    if tagged:
        d["dell-interface:tagged-ports"] = tagged
    if untagged:
        d["dell-interface:untagged-ports"] = untagged
    return d


TRUNK = ["ethernet1/1/1", "ethernet1/1/49:1"]
IFACES = [
    eth("ethernet1/1/1"), eth("ethernet1/1/5", configured=False), eth("ethernet1/1/49:1"),
    {"name": "mgmt1/1/1", "type": "dell-base-interface-common:management", "dell-ip:ipv4": {"address": {"primary-addr": "192.168.1.217/24"}}},
    {"name": "null0", "type": "dell-base-interface-common:null"},
    vlan(1, untagged=["ethernet1/1/1", "ethernet1/1/5", "ethernet1/1/49:1"]),
    vlan(228, tagged=TRUNK), vlan(230, tagged=TRUNK), vlan(105, tagged=["ethernet1/1/1"]),
    vlan(106, tagged=["ethernet1/1/1"]), vlan(107, tagged=["ethernet1/1/1"]),
]


class Mock:
    def __init__(self, tls=None):
        self.requests = []  # (method, raw path)
        self.bodies = []
        self.ifaces = copy.deepcopy(IFACES)
        self.ignore_patch = False
        mock = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, doc):
                raw = json.dumps(doc).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/yang-data+json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def handle_any(self):
                mock.requests.append((self.command, self.path))
                if self.headers.get("Authorization") != "Basic " + base64.b64encode(b"admin:secret").decode():
                    return self.reply(401, {})
                p = self.path
                base = "/restconf/data/ietf-interfaces:interfaces/interface"
                if self.command == "POST" and p == "/restconf/operations/ietf-netconf-monitoring:get-schema":
                    n = int(self.headers.get("Content-Length") or 0)
                    mock.bodies.append(json.loads(self.rfile.read(n)))
                    return self.reply(200, {"ietf-netconf-monitoring:output": {"data": "module yuma-netconf { }"}})
                if self.command in ("PATCH", "DELETE"):
                    n = int(self.headers.get("Content-Length") or 0)
                    body = json.loads(self.rfile.read(n)) if n else None
                    mock.bodies.append(body)
                    rest = p[len(base) + 1:] if p.startswith(base + "=") else None
                    if rest is None:
                        return self.reply(404, {})
                    name, _, leaf = rest.partition("/")
                    name = urllib.parse.unquote(name)
                    hit = [i for i in mock.ifaces if i["name"] == name]
                    if not hit:
                        return self.reply(404, {"ietf-restconf:errors": {"error": [{"error-message": "no such interface"}]}})
                    if self.command == "PATCH" and body is None:
                        return self.reply(400, {})
                    if not mock.ignore_patch:
                        if self.command == "PATCH":
                            hit[0].update({k: v for k, v in body["ietf-interfaces:interface"][0].items() if k != "name"})
                        else:
                            hit[0].pop(leaf, None)
                    self.send_response(204)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if p == base:
                    return self.reply(200, {"ietf-interfaces:interface": mock.ifaces})
                if p.startswith(base + "="):
                    name = urllib.parse.unquote(p[len(base) + 1:])
                    hit = [i for i in mock.ifaces if i["name"] == name]
                    if hit:
                        return self.reply(200, {"ietf-interfaces:interface": hit})
                    return self.reply(404, {"ietf-restconf:errors": {"error": [{"error-type": "application", "error-tag": "invalid-value", "error-message": "no such interface"}]}})
                if p == "/restconf/operations":
                    return self.reply(200, {"ietf-restconf:operations": {"dell-system-software:boot-system": [None], "yumaworks-system:backup": [None]}})
                if p == "/restconf/data":
                    return self.reply(200, {"ietf-interfaces:interfaces": {"interface": mock.ifaces}})
                self.reply(404, {})

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_any

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        if tls:
            import ssl
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(*tls)
            self.server.socket = ctx.wrap_socket(self.server.socket, server_side=True)
        self.url = "%s://127.0.0.1:%d" % ("https" if tls else "http", self.server.server_address[1])
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    saved, sys.stdin = sys.stdin, io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = os10.main(list(argv))
        except SystemExit as e:
            code = e.code if isinstance(e.code, int) else 2
        finally:
            sys.stdin = saved
    return code, out.getvalue(), err.getvalue()


print("Reading")
m = Mock(); c, o, e = run("interfaces", m.url)
lines = o.splitlines()
check("interfaces: header and one row per port, in port order (1/1/1, 1/1/5, 1/1/49:1)",
      c == 0 and lines[0].split() == ["PORT", "MODE", "SPEED", "MTU", "UNTAGGED", "TAGGED"]
      and [l.split()[0] for l in lines[1:]] == ["ethernet1/1/1", "ethernet1/1/5", "ethernet1/1/49:1"], o + e)
check("interfaces: a configured trunk port shows mode, speed, MTU and its VLANs",
      lines[1].split() == ["ethernet1/1/1", "L2HYBRID", "25G", "9216", "1", "105-107,228,230"], lines[1])
check("interfaces: an unconfigured port shows dashes and its default VLAN",
      lines[2].split() == ["ethernet1/1/5", "-", "-", "-", "1", "-"], lines[2])
m = Mock(); c, o, e = run("interfaces", m.url, "--json")
d = json.loads(o)
check("interfaces --json is parseable", c == 0 and d[0]["tagged"] == [105, 106, 107, 228, 230] and d[0]["untagged"] == [1], o[:200])
m = Mock(); c, o, e = run("vlans", m.url)
rows = [l.split() for l in o.splitlines()[1:]]
check("vlans: sorted by number with the counts of ports", c == 0 and rows[0] == ["1", "3", "0"] and rows[1] == ["105", "0", "1"] and rows[-1] == ["230", "0", "2"], o + e)
m = Mock(); c, o, e = run("vlan", m.url, "228")
check("vlan 228 lists its ports", c == 0 and "tagged   (2): ethernet1/1/1 ethernet1/1/49:1" in o and "untagged (0): -" in o, o + e)
m = Mock(); c, o, e = run("vlan", m.url, "999")
check("an unknown VLAN is an error naming it", c == 1 and "no such interface" in e or "no VLAN 999" in e, e)
m = Mock(); c, o, e = run("vlan", m.url, "abc")
check("a VLAN must be a number", c == 1 and "number" in e, e)
m = Mock(); c, o, e = run("port", m.url, "1/1/49:1")
check("port 1/1/49:1 means ethernet1/1/49:1 and is asked for with the name percent-encoded",
      c == 0 and any(p.endswith("interface=ethernet1%2F1%2F49%3A1") for _, p in m.requests) and "tagged VLANs:   228,230" in o and "untagged VLANs: 1" in o, str(m.requests) + o + e)
m = Mock(); c, o, e = run("port", m.url, "ethernet1/1/1")
check("port ethernet1/1/1 (full name) works", c == 0 and "25GIGE" in o, o + e)
m = Mock(); c, o, e = run("port", m.url, "1/1/99")
check("an unknown port fails with the switch's message", c == 1 and "no such interface" in e, e)
m = Mock(); c, o, e = run("get", m.url, "ietf-interfaces:interfaces/interface=vlan228")
check("get prints the document", c == 0 and "vlan228" in o, o + e)
m = Mock(); c, o, e = run("operations", m.url)
check("operations lists the names", c == 0 and o.split() == ["dell-system-software:boot-system", "yumaworks-system:backup"], o + e)
d = tempfile.mkdtemp()
m = Mock(); c, o, e = run("backup", m.url, os.path.join(d, "b.json"))
check("backup writes the data tree to the file", c == 0 and "interfaces" in open(os.path.join(d, "b.json")).read() and "saved" in o, o + e)
shutil.rmtree(d)
m = Mock(); run("interfaces", m.url); run("vlans", m.url); run("port", m.url, "1/1/1"); run("get", m.url, "x"); run("operations", m.url)
check("only GET is ever sent", {meth for meth, _ in m.requests} == {"GET"}, str(m.requests))

m = Mock(); c, o, e = run("schema", m.url, "yuma-netconf")
check("schema prints the module text (a POST of get-schema, nothing written)", c == 0 and o.strip() == "module yuma-netconf { }"
      and m.bodies[0]["ietf-netconf-monitoring:input"]["identifier"] == "yuma-netconf", o + e)

print("Changes")
def port_of(m, name):
    return [i for i in m.ifaces if i["name"] == name][0]


m = Mock(); c, o, e = run("set-description", m.url, "1/1/5", "gs1 eth0", "--yes")
check("set-description PATCHes the port and reads it back", c == 0 and port_of(m, "ethernet1/1/5").get("description") == "gs1 eth0"
      and m.requests[-2][0] == "PATCH" and m.requests[-2][1].endswith("interface=ethernet1%2F1%2F5")
      and m.bodies[0] == {"ietf-interfaces:interface": [{"name": "ethernet1/1/5", "description": "gs1 eth0"}]}
      and "Not saved" in o and "(none) -> gs1 eth0" in o, str(m.requests) + o + e)
m = Mock(); run("set-description", m.url, "1/1/5", "x", "--yes"); c, o, e = run("set-description", m.url, "1/1/5", "", "--yes")
check("an empty description removes it (DELETE of the leaf)", c == 0 and "description" not in port_of(m, "ethernet1/1/5")
      and m.requests[-2][0] == "DELETE" and m.requests[-2][1].endswith("interface=ethernet1%2F1%2F5/description"), str(m.requests) + o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1", "1500", "--yes")
check("set-mtu shows old -> new and sets a number", c == 0 and port_of(m, "ethernet1/1/1")["dell-interface:mtu"] == 1500 and "9216 -> 1500" in o, o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1", "jumbo", "--yes")
check("a non-numeric MTU is refused before anything is sent", c == 1 and m.requests == [], e)
m = Mock(); c, o, e = run("set-speed", m.url, "1/1/1", "10g", "--yes")
check("set-speed 10g sends 10GIGE", c == 0 and port_of(m, "ethernet1/1/1")["dell-interface:speed"] == "10GIGE" and "25GIGE -> 10GIGE" in o, o + e)
m = Mock(); c, o, e = run("set-speed", m.url, "1/1/1", "7G", "--yes")
check("an unknown speed lists the choices and sends nothing", c == 1 and "25G" in e and m.requests == [], e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1", "9216", "--yes")
check("a value already set sends no change", c == 0 and "already" in o and {x for x, _ in m.requests} == {"GET"}, o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1", "1500", "--dry-run")
check("--dry-run shows the request and changes nothing", c == 0 and "would send: PATCH" in o and {x for x, _ in m.requests} == {"GET"}
      and port_of(m, "ethernet1/1/1")["dell-interface:mtu"] == 9216, o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1", "1500")
check("without a terminal and without --yes nothing is sent", c == 1 and "--yes" in e and {x for x, _ in m.requests} == {"GET"}, o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "vlan228", "1500", "--yes")
check("a VLAN or management interface cannot be changed", c == 1 and "only ethernet" in e and m.requests == [], o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/99", "1500", "--yes")
check("an unknown port is refused with the switch's message", c == 1 and "no such interface" in e, e)
m = Mock(); m.ignore_patch = True; c, o, e = run("set-mtu", m.url, "1/1/1", "1500", "--yes")
check("a change the switch accepts but does not apply is reported", c == 1 and "now reads 9216" in e, o + e)
m = Mock(); c, o, e = run("set-mtu", m.url, "1/1/1")
check("a value is required", c == 1 and "needs a value" in e and m.requests == [], e)

print("Errors")
os.environ["OS10_PASSWORD"] = "wrong"
m = Mock(); c, o, e = run("vlans", m.url)
check("a refused login is one request, not retried", c == 1 and len(m.requests) == 1 and "refused the login" in e, "%d: %s" % (len(m.requests), e))
os.environ["OS10_PASSWORD"] = "secret"
c, o, e = run("vlans", "http://127.0.0.1:9")
check("an unreachable switch names the likely cause", c == 1 and "rest api restconf" in e, e)
m = Mock(); c, o, e = run("vlan", m.url)
check("vlan without a number is a usage error", c == 2, "%s %s" % (c, e))

print("Password and user")
d2 = tempfile.mkdtemp()
fake = d2 + "/orphera"
with open(fake, "w") as f:
    f.write('#!/bin/sh\necho "$3" >> "%s/asked"\ncase "$3" in 127.0.0.1) echo secret;; *) echo "Error: no secret called $3" >&2; exit 1;; esac\n' % d2)
os.chmod(fake, 0o755)
saved_path = os.environ["PATH"]
os.environ["PATH"] = d2 + os.pathsep + saved_path
del os.environ["OS10_PASSWORD"]
m = Mock(); c, o, e = run("vlans", m.url)
check("with no password variable, the secret named after the switch supplies it (ILO_PASSWORD is ignored)",
      c == 0 and open(d2 + "/asked").read().split() == ["127.0.0.1"], "%s %s" % (c, e))
m = Mock(); c, o, e = run("vlans", m.url, "--secret", "nope")
check("a missing secret fails before any request and says how to store it",
      c == 1 and m.requests == [] and "orphera secrets set nope" in e, e)
os.environ["OS10_USER"] = "operator"
m = Mock(); c, o, e = run("vlans", m.url)
check("OS10_USER sets the user (the mock accepts only admin)", c == 1 and "user operator" in e, e)
m = Mock(); c, o, e = run("vlans", m.url, "--user", "admin")
check("--user overrides OS10_USER", c == 0, e)
del os.environ["OS10_USER"]
os.environ["PATH"] = saved_path
os.environ["OS10_PASSWORD"] = "secret"
shutil.rmtree(d2)

print("TLS (self-signed certificate)")
if shutil.which("openssl"):
    d3 = tempfile.mkdtemp()
    cert, key = d3 + "/c.pem", d3 + "/k.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2",
                    "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"], check=True, capture_output=True)
    m = Mock(tls=(cert, key)); c, o, e = run("vlans", m.url)
    check("a self-signed certificate is refused by default, with the way out named", c == 1 and "--insecure" in e and m.requests == [], e)
    c, o, e = run("vlans", m.url, "--insecure")
    check("--insecure accepts it", c == 0 and "VLAN" in o, o + e)
    c, o, e = run("vlans", m.url, "--cacert", cert)
    check("--cacert FILE accepts it when it is that certificate", c == 0, o + e)
    shutil.rmtree(d3)
else:
    print("  skip  openssl is not installed")

print("all passed" if not fail else "%d FAILED" % fail)
sys.exit(1 if fail else 0)
