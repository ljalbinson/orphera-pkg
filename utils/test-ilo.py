#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Tests utils/ilo.py against a mock iLO (Redfish and the older HP REST API) on localhost.
Needs no iLO and no lab. Run from the repository root: make check-ilo"""
import contextlib
import importlib.util
import io
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ilo", os.path.join(here, "ilo.py"))
ilo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ilo)
ilo.POLL_SECONDS = 0.02
os.environ["ILO_PASSWORD"] = "secret"
os.environ.pop("ILO_USER", None)

fail = 0


def ok(msg):
    print("  ok    " + msg)


def bad(msg):
    global fail
    fail += 1
    print("  FAIL  " + msg)


def check(msg, cond, detail=""):
    ok(msg) if cond else bad("%s %s" % (msg, detail))


class Mock:
    """One mock iLO: its state, what it accepts, and every POST it received."""

    def __init__(self, legacy=False, power="On", allowed=("On", "ForceOff", "ForceRestart", "PushPowerButton", "Nmi"),
                 shutdown_works=True, tls=None):
        self.legacy, self.power, self.allowed, self.shutdown_works = legacy, power, list(allowed), shutdown_works
        self.posts, self.requests = [], 0
        mock = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, doc):
                raw = json.dumps(doc).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def authed(self):
                mock.requests += 1
                import base64
                if self.headers.get("Authorization") != "Basic " + base64.b64encode(b"Administrator:secret").decode():
                    self.reply(401, {"error": {"message": "login failed"}})
                    return False
                return True

            def do_GET(self):
                if not self.authed():
                    return
                p = self.path
                if p == "/redfish/v1/Systems/":
                    return self.reply(404, {}) if mock.legacy else self.reply(200, {"Members": [{"@odata.id": "/redfish/v1/Systems/1/"}]})
                if p == "/rest/v1/Systems":
                    return self.reply(200, {"links": {"Member": [{"href": "/rest/v1/Systems/1"}]}})
                if p == "/redfish/v1/Systems/1/":
                    return self.reply(200, {"PowerState": mock.power, "Actions": {"#ComputerSystem.Reset": {
                        "target": "/redfish/v1/Systems/1/Actions/ComputerSystem.Reset/",
                        "ResetType@Redfish.AllowableValues": mock.allowed}}})
                if p == "/rest/v1/Systems/1":
                    return self.reply(200, {"Power": mock.power})
                self.reply(404, {})

            def do_POST(self):
                if not self.authed():
                    return
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                rt = body.get("ResetType")
                mock.posts.append((self.path, body))
                if rt == "On" and mock.power == "On":
                    return self.reply(400, {"error": {"@Message.ExtendedInfo": [{"Message": "The server is already on"}]}})
                if rt == "On":
                    mock.power = "On"
                elif rt == "ForceOff":
                    mock.power = "Off"
                elif rt == "PushPowerButton":
                    mock.power = "Off" if mock.power == "On" and mock.shutdown_works else ("On" if mock.power == "Off" else mock.power)
                elif rt == "GracefulShutdown" and mock.shutdown_works:
                    mock.power = "Off"
                self.reply(200, {})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        if tls:
            import ssl
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(tls[0], tls[1])
            self.server.socket = ctx.wrap_socket(self.server.socket, server_side=True)
        self.url = "%s://127.0.0.1:%d" % ("https" if tls else "http", self.server.server_address[1])
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def resets(self):
        return [b["ResetType"] for _, b in self.posts]


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    saved, sys.stdin = sys.stdin, io.StringIO()  # never a terminal, so a confirmation prompt cannot hang the test
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = ilo.main(list(argv))
        except SystemExit as e:
            code = e.code if isinstance(e.code, int) else 2
            err.write(str(e.code) + "\n")
        finally:
            sys.stdin = saved
    return code, out.getvalue(), err.getvalue()


Y = ["--yes", "--timeout", "2"]

print("Redfish")
m = Mock(); c, o, e = run("status", m.url)
check("status shows On", c == 0 and "On" in o, o + e)
m = Mock(); c, o, e = run("on", m.url)
check("on when on: nothing sent", c == 0 and m.posts == [] and "already on" in o, o + e)
m = Mock(power="Off"); c, o, e = run("on", m.url)
check("on when off: POST On, now On", c == 0 and m.resets() == ["On"] and m.power == "On", str(m.posts) + e)
check("the POST goes to the action target", m.posts and m.posts[0][0] == "/redfish/v1/Systems/1/Actions/ComputerSystem.Reset/")
m = Mock(); c, o, e = run("off", m.url, *Y)
check("off: ForceOff, now Off", c == 0 and m.resets() == ["ForceOff"] and m.power == "Off", str(m.posts) + e)
m = Mock(power="Off"); c, o, e = run("off", m.url, *Y)
check("off when off: nothing sent", c == 0 and m.posts == [], str(m.posts))
m = Mock(); c, o, e = run("shutdown", m.url, *Y)
check("shutdown on iLO 4 (no GracefulShutdown): PushPowerButton", c == 0 and m.resets() == ["PushPowerButton"] and m.power == "Off", str(m.posts) + e)
m = Mock(allowed=("On", "GracefulShutdown", "ForceOff")); c, o, e = run("shutdown", m.url, *Y)
check("shutdown uses GracefulShutdown when offered", c == 0 and m.resets() == ["GracefulShutdown"], str(m.posts))
m = Mock(power="Off"); c, o, e = run("shutdown", m.url, *Y)
check("shutdown when off never presses the button", c == 0 and m.posts == [], str(m.posts))
m = Mock(shutdown_works=False); c, o, e = run("shutdown", m.url, "--yes", "--timeout", "1")
check("shutdown that does not complete fails and does not force", c == 1 and m.resets() == ["PushPowerButton"] and "off" in e, str(m.posts) + e)
m = Mock(); c, o, e = run("reset", m.url, *Y)
check("reset: ForceRestart, still On", c == 0 and m.resets() == ["ForceRestart"] and m.power == "On", str(m.posts) + e)
m = Mock(power="Off"); c, o, e = run("reset", m.url, *Y)
check("reset when off fails, nothing sent", c == 1 and m.posts == [] and "not on" in e, str(m.posts) + e)
m = Mock(); c, o, e = run("soft-reset", m.url, *Y)
check("soft-reset on iLO 4: button, then On", c == 0 and m.resets() == ["PushPowerButton", "On"] and m.power == "On", str(m.posts) + e)
m = Mock(allowed=("On", "GracefulRestart", "ForceOff")); c, o, e = run("soft-reset", m.url, *Y)
check("soft-reset uses GracefulRestart when offered", c == 0 and m.resets() == ["GracefulRestart"], str(m.posts) + e)
m = Mock(shutdown_works=False); c, o, e = run("soft-reset", m.url, "--yes", "--timeout", "1")
check("soft-reset whose shutdown stalls fails before powering on", c == 1 and m.resets() == ["PushPowerButton"], str(m.posts) + e)
m = Mock(power="Off"); c, o, e = run("soft-reset", m.url, *Y)
check("soft-reset when off fails, nothing sent", c == 1 and m.posts == [], str(m.posts) + e)
m = Mock(); c, o, e = run("reset", m.url, "--yes", "--dry-run")
check("--dry-run sends nothing and says what it would", c == 0 and m.posts == [] and "would send" in o and "ForceRestart" in o, o + e)
m = Mock(); c, o, e = run("off", m.url, "--yes", "--no-wait")
check("--no-wait returns after the request", c == 0 and m.resets() == ["ForceOff"] and "requested" in o, o + e)

print("HP REST API (iLO 4 before Redfish)")
m = Mock(legacy=True); c, o, e = run("status", m.url)
check("status through /rest/v1", c == 0 and "On" in o, o + e)
m = Mock(legacy=True); c, o, e = run("off", m.url, *Y)
check("off: POST Action Reset ForceOff to the system", c == 0 and m.posts == [("/rest/v1/Systems/1", {"Action": "Reset", "ResetType": "ForceOff"})], str(m.posts) + e)
m = Mock(legacy=True, power="Off"); c, o, e = run("on", m.url)
check("on through /rest/v1", c == 0 and m.power == "On", str(m.posts) + e)

print("Errors and safety")
os.environ["ILO_PASSWORD"] = "wrong"
m = Mock(); c, o, e = run("status", m.url)
check("a refused login is one request, not retried", c == 1 and m.requests == 1 and "refused the login" in e, "%d requests: %s" % (m.requests, e))
os.environ["ILO_PASSWORD"] = "secret"
c, o, e = run("status", "http://127.0.0.1:9")
check("an unreachable iLO fails with a message", c == 1 and "cannot reach" in e, e)
m = Mock(); c, o, e = run("off", m.url)
check("off without --yes and without a terminal is refused", c == 2 and m.posts == [], "%s %s" % (c, e))
a, b = Mock(), Mock(power="Off")
c, o, e = run("reset", b.url, a.url, *Y)
check("several iLOs: one fails, the next still done, exit 1", c == 1 and a.resets() == ["ForceRestart"] and b.posts == [], "%s %s" % (c, e))
m = Mock(power="On"); c, o, e = run("on", m.url + "/", "--no-wait")
check("a trailing slash in the URL is fine", c == 0, e)

print("TLS (self-signed certificate)")
import shutil
import subprocess
import tempfile
if shutil.which("openssl"):
    d = tempfile.mkdtemp()
    cert, key = d + "/c.pem", d + "/k.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2",
                    "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"], check=True, capture_output=True)
    m = Mock(tls=(cert, key)); c, o, e = run("status", m.url)
    check("a self-signed certificate is refused by default, with the way out named", c == 1 and "--insecure" in e and m.requests == 0, e)
    c, o, e = run("status", m.url, "--insecure")
    check("--insecure accepts it", c == 0 and "On" in o, o + e)
    c, o, e = run("status", m.url, "--cacert", cert)
    check("--cacert FILE accepts it when it is that certificate", c == 0 and "On" in o, o + e)
    shutil.rmtree(d)
else:
    print("  skip  openssl is not installed")

print("Password from the secret store")
d2 = tempfile.mkdtemp()
fake = d2 + "/orphera"
with open(fake, "w") as f:
    f.write("#!/bin/sh\n"
            'echo "$3" >> "%s/asked"\n'
            '[ "$1 $2" = "secrets get" ] || exit 2\n'
            'case "$3" in 127.0.0.1) echo secret;; *) echo "Error: no secret called $3" >&2; exit 1;; esac\n' % d2)
os.chmod(fake, 0o755)
saved_path = os.environ["PATH"]
os.environ["PATH"] = d2 + os.pathsep + saved_path
del os.environ["ILO_PASSWORD"]
check("the secret is named after the iLO as typed", ilo.secret_key("ilo-tst50") == "ilo-tst50"
      and ilo.secret_key("ilo-tst50.example.com/") == "ilo-tst50.example.com"
      and ilo.secret_key("https://ilo-tst50.example.com:8443/") == "ilo-tst50.example.com")
m = Mock(); c, o, e = run("status", m.url)
asked = open(d2 + "/asked").read().split()
check("with no password variable, `orphera secrets get <ilo>` supplies it", c == 0 and "On" in o and asked == ["127.0.0.1"], "%s %s %s" % (c, asked, e))
m = Mock(); c, o, e = run("status", m.url, "--secret", "other/name")
check("--secret NAME names another secret (here one that does not exist)", c == 1 and "other/name" in e and "orphera secrets set other/name" in e, e)
check("a missing secret fails before any request and says how to store it", m.requests == 0 and "echo 'the password' | orphera secrets set" in e, e)
os.environ["ILO_PASSWORD"] = "secret"
open(d2 + "/asked", "w").close()
m = Mock(); c, o, e = run("status", m.url)
check("ILO_PASSWORD overrides the store (the store is not asked)", c == 0 and open(d2 + "/asked").read() == "", "")
del os.environ["ILO_PASSWORD"]
a_, b_ = Mock(), Mock()
c, o, e = run("status", a_.url, "http://localhost:%d" % b_.server.server_address[1])
check("each iLO has its own secret: one found, the other not, exit 1", c == 1 and "On" in o and "no secret `localhost`" in e, "%s %s" % (o, e))
os.environ["PATH"] = saved_path
os.environ["ILO_PASSWORD"] = "secret"
shutil.rmtree(d2)

print("%s" % ("all passed" if not fail else "%d FAILED" % fail))
sys.exit(1 if fail else 0)
