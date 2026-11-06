#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Power control of HPE ProLiant servers (DL385p Gen8 and others with iLO 4 or later) through the iLO.

  utils/ilo.py status      ilo-tst50 [ilo-tst51 ...]
  utils/ilo.py on          ilo-tst50                # power on
  utils/ilo.py off         ilo-tst50                # hard power off: the power is cut, the OS is not asked
  utils/ilo.py shutdown    ilo-tst50                # graceful power off: the OS is asked to shut down (ACPI)
  utils/ilo.py reset       ilo-tst50                # hard reset: the server restarts at once, as if reset were pressed
  utils/ilo.py soft-reset  ilo-tst50                # soft reset: the OS shuts down, then the server is powered on again

The iLO is named by its address or DNS name; `http://host:port` or `https://host:port` gives a full URL.
Several iLOs can be named: they are handled one after the other, and the exit code is 1 if any failed.

It talks to the iLO's REST interface with the Python standard library, nothing else to install: the Redfish
API (/redfish/v1) and, for iLO 4 firmware that predates it, the HP REST API (/rest/v1). The iLO's web
service must be on (it is by default); IPMI over LAN is not needed.

  Login     --user NAME (or ILO_USER; default Administrator). The password of each iLO is the Orphera secret
            named after the iLO, as typed on the command line (`ilo-tst50` is the secret `ilo-tst50`,
            `ilo-tst50.example.com` the secret `ilo-tst50.example.com`; for a URL, its host name), read with
            `orphera secrets get`. Store it once with `echo 'the password' | orphera secrets set ilo-tst50`.
            To override: ILO_PASSWORD in the environment, --password-file FILE, or --secret NAME (one secret
            for all the iLOs named). With none of these and no such secret, a terminal gets a prompt. The
            password is never taken from the command line.
  TLS       an iLO has a self-signed certificate until a real one is installed: --insecure (or ILO_INSECURE=1)
            skips the check, --cacert FILE checks against that certificate.
  Safety    off, shutdown, reset and soft-reset ask for confirmation (type yes); --yes skips it, and is
            required when there is no terminal. --dry-run shows what would be sent and sends nothing.
  Waiting   each command waits until the server has reached the state it asked for (--timeout SECONDS, default
            300; --no-wait to send the request and return). `shutdown` and `soft-reset` never cut the power:
            if the OS has not shut down in time they fail, and `off` is the way to force it.

Exit code: 0 done, 1 an iLO failed, 2 wrong usage.
"""
import argparse
import base64
import getpass
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

POLL_SECONDS = 3.0


class IloError(Exception):
    pass


class Ilo:
    def __init__(self, target, user, password, context, request_timeout=30.0, dry_run=False, log=print):
        t = target.rstrip("/")
        self.base = t if t.startswith(("http://", "https://")) else "https://" + t
        self.name = t.split("://", 1)[-1]
        self.auth = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
        self.user = user
        self.context = context
        self.request_timeout = request_timeout
        self.dry_run = dry_run
        self.log = log
        self.system = None   # path of the computer system resource
        self.legacy = False  # True for the HP REST API (/rest/v1), False for Redfish

    # ---- HTTP ----
    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", self.auth)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.request_timeout, context=self.context) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                doc = json.loads(raw) if raw else {}
            except ValueError:
                doc = {}
            if e.code == 401:
                raise IloError("%s refused the login for user %s (not retried: repeated failures can lock the account)"
                               % (self.name, self.user))
            raise IloError("%s: %s %s returned HTTP %d%s" % (self.name, method, path, e.code, _detail(doc)))
        except urllib.error.URLError as e:
            reason = e.reason
            if isinstance(reason, ssl.SSLCertVerificationError):
                raise IloError("%s: the certificate is not trusted (%s); use --insecure for a self-signed iLO certificate "
                               "or --cacert FILE" % (self.name, reason.verify_message))
            raise IloError("%s: cannot reach the iLO: %s" % (self.name, reason))
        except (TimeoutError, OSError) as e:
            raise IloError("%s: %s" % (self.name, e))

    # ---- the computer system ----
    def discover(self):
        if self.system:
            return
        try:
            _, coll = self.call("GET", "/redfish/v1/Systems/")
            members = coll.get("Members") or []
            if members:
                self.system = members[0]["@odata.id"]
                return
        except IloError as e:
            if "HTTP 404" not in str(e):
                raise
        _, coll = self.call("GET", "/rest/v1/Systems")  # iLO 4 firmware before Redfish
        links = (coll.get("links") or {}).get("Member") or []
        if not links:
            items = coll.get("Items") or []
            links = [{"href": ((i.get("links") or {}).get("self") or {}).get("href")} for i in items]
        if not links or not links[0].get("href"):
            raise IloError("%s: no computer system found under /redfish/v1 or /rest/v1" % self.name)
        self.system = links[0]["href"]
        self.legacy = True

    def read(self):
        self.discover()
        return self.call("GET", self.system)[1]

    def power_state(self):
        doc = self.read()
        state = doc.get("PowerState") or doc.get("Power")
        if not state:
            raise IloError("%s: the system reports no power state" % self.name)
        return str(state).strip().title()

    def allowed(self, doc):
        act = (doc.get("Actions") or {}).get("#ComputerSystem.Reset") or {}
        return act.get("ResetType@Redfish.AllowableValues") or []

    def send_reset(self, reset_type):
        """Sends a ResetType; returns False when --dry-run kept it from being sent."""
        doc = self.read()
        if self.legacy:
            path, body = self.system, {"Action": "Reset", "ResetType": reset_type}
        else:
            act = (doc.get("Actions") or {}).get("#ComputerSystem.Reset") or {}
            path = act.get("target") or self.system.rstrip("/") + "/Actions/ComputerSystem.Reset/"
            body = {"ResetType": reset_type}
        if self.dry_run:
            self.log("%s: would send POST %s %s" % (self.name, path, json.dumps(body)))
            return False
        self.call("POST", path, body)
        return True

    # ---- waiting ----
    def wait_for(self, want, timeout):
        deadline = time.monotonic() + timeout
        last = None
        while True:
            try:
                last = self.power_state()
                if last == want:
                    return
            except IloError as e:
                last = "unreadable (%s)" % e
            if time.monotonic() >= deadline:
                raise IloError("%s: still %s after %d s, wanted %s" % (self.name, last, timeout, want))
            time.sleep(POLL_SECONDS)


def _detail(doc):
    err = doc.get("error") or {}
    ext = err.get("@Message.ExtendedInfo") or err.get("MessageExtendedInfo") or []
    msg = (ext[0].get("Message") or ext[0].get("MessageId")) if ext and isinstance(ext[0], dict) else err.get("message")
    return ": " + str(msg) if msg else ""


# ---- the operations ----
def _done(ilo, sent, a, text):
    """Reports a finished action; says nothing under --dry-run, which has already said what it would send."""
    if sent:
        ilo.log("%s: %s%s" % (ilo.name, text, "" if a.wait else " requested"))


def _state(doc):
    return str(doc.get("PowerState") or doc.get("Power") or "").title()


def op_status(ilo, a):
    ilo.log("%s: %s" % (ilo.name, ilo.power_state()))


def op_on(ilo, a):
    if ilo.power_state() == "On":
        return ilo.log("%s: already on" % ilo.name)
    sent = ilo.send_reset("On")
    if sent and a.wait:
        ilo.wait_for("On", a.timeout)
    _done(ilo, sent, a, "powered on")


def op_off(ilo, a):
    if ilo.power_state() == "Off":
        return ilo.log("%s: already off" % ilo.name)
    sent = ilo.send_reset("ForceOff")
    if sent and a.wait:
        ilo.wait_for("Off", a.timeout)
    _done(ilo, sent, a, "hard power off")


def _require_on(ilo, doc):
    state = _state(doc)
    if state != "On":
        raise IloError("%s: the server is %s, not on; this needs a running server (use `on`)"
                       % (ilo.name, state or "in an unknown power state"))


def _graceful_off(ilo, a, doc):
    """Asks the OS to shut down. Only for a server that is on: pressing the button of one that is off powers it on."""
    kind = "GracefulShutdown" if "GracefulShutdown" in ilo.allowed(doc) else "PushPowerButton"
    sent = ilo.send_reset(kind)
    if sent and a.wait:
        try:
            ilo.wait_for("Off", a.timeout)
        except IloError as e:
            raise IloError("%s; the OS has not shut down (`off` cuts the power)" % e)
    return sent


def op_shutdown(ilo, a):
    doc = ilo.read()
    if _state(doc) == "Off":
        return ilo.log("%s: already off" % ilo.name)
    _require_on(ilo, doc)
    _done(ilo, _graceful_off(ilo, a, doc), a, "graceful shutdown")


def op_reset(ilo, a):
    _require_on(ilo, ilo.read())
    sent = ilo.send_reset("ForceRestart")
    if sent and a.wait:
        time.sleep(min(POLL_SECONDS, 5))
        ilo.wait_for("On", a.timeout)
    _done(ilo, sent, a, "hard reset")


def op_soft_reset(ilo, a):
    doc = ilo.read()
    _require_on(ilo, doc)
    if "GracefulRestart" in ilo.allowed(doc):
        sent = ilo.send_reset("GracefulRestart")
        if sent and a.wait:
            time.sleep(min(POLL_SECONDS, 5))
            ilo.wait_for("On", a.timeout)
        return _done(ilo, sent, a, "soft reset")
    # iLO 4 has no graceful restart: shut the OS down, then power on
    if not _graceful_off(ilo, a, doc):
        return ilo.log("%s: would then wait for the shutdown and send POST On" % ilo.name)
    if not a.wait:
        return ilo.log("%s: shutdown requested; --no-wait leaves the power on to you" % ilo.name)
    ilo.send_reset("On")
    ilo.wait_for("On", a.timeout)
    ilo.log("%s: soft reset (shut down, then powered on)" % ilo.name)


OPS = {
    "status": (op_status, False, "show the power state"),
    "on": (op_on, False, "power on"),
    "off": (op_off, True, "hard power off"),
    "shutdown": (op_shutdown, True, "graceful power off"),
    "reset": (op_reset, True, "hard reset"),
    "soft-reset": (op_soft_reset, True, "soft reset: graceful shutdown, then power on"),
}


# ---- command line ----
def secret_key(target):
    """The secret that holds an iLO's password: its address or name as given, or the host of a URL."""
    t = target.strip().rstrip("/")
    if "://" in t:
        return urllib.parse.urlsplit(t).hostname or t
    return t


def read_secret(name):
    """The value of an Orphera secret; None when there is none (or the store cannot be read: message in .err)."""
    try:
        r = subprocess.run(["orphera", "secrets", "get", name], capture_output=True, text=True)
    except FileNotFoundError:
        return None, "`orphera` is not installed here"
    if r.returncode != 0 or not r.stdout.strip():
        return None, (r.stderr.strip() or "no value")
    return r.stdout.rstrip("\r\n"), ""


def password_for(a, target):
    """The password for one iLO: ILO_PASSWORD, --password-file, --secret NAME, or the secret named after the iLO."""
    if os.environ.get("ILO_PASSWORD"):
        return os.environ["ILO_PASSWORD"]
    if a.password_file:
        try:
            with open(a.password_file) as f:
                return f.read().rstrip("\r\n")
        except OSError as e:
            raise IloError("cannot read %s: %s" % (a.password_file, e))
    name = a.secret or secret_key(target)
    value, why = read_secret(name)
    if value is not None:
        return value
    if sys.stdin.isatty():
        return getpass.getpass("iLO password for %s (no secret %s: %s): " % (target, name, why))
    raise IloError("no password for %s: no secret `%s` (%s); store it with: echo 'the password' | orphera secrets set %s"
                   % (target, name, why, name))


def parser():
    p = argparse.ArgumentParser(
        prog="ilo.py", description="Power control of HPE ProLiant servers (DL385p and others) through the iLO.",
        epilog="Actions: " + "; ".join("%s = %s" % (k, v[2]) for k, v in OPS.items()))
    p.add_argument("action", choices=list(OPS))
    p.add_argument("ilo", nargs="+", help="iLO address or DNS name (or a full http(s):// URL)")
    p.add_argument("-u", "--user", default=os.environ.get("ILO_USER", "Administrator"))
    p.add_argument("--password-file", metavar="FILE", help="read the password from this file, for every iLO named")
    p.add_argument("--secret", metavar="NAME", help="use this Orphera secret for every iLO named (default: a secret named after each iLO)")
    p.add_argument("-k", "--insecure", action="store_true", default=os.environ.get("ILO_INSECURE") == "1",
                   help="do not check the iLO's certificate (self-signed)")
    p.add_argument("--cacert", metavar="FILE", help="check the iLO's certificate against this file")
    p.add_argument("-y", "--yes", action="store_true", help="do not ask for confirmation")
    p.add_argument("-n", "--dry-run", action="store_true", help="show what would be sent, send nothing")
    p.add_argument("--no-wait", dest="wait", action="store_false", help="return once the request is accepted")
    p.add_argument("--timeout", type=int, default=300, metavar="SECONDS", help="how long to wait (default 300)")
    return p


def tls_context(a):
    if a.insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return ssl.create_default_context(cafile=a.cacert)


def confirm(a):
    func, destructive, desc = OPS[a.action]
    if not destructive or a.yes or a.dry_run:
        return
    if not sys.stdin.isatty():
        sys.exit("ilo.py: %s needs --yes when there is no terminal" % a.action)
    print("%s (%s): %s" % (a.action, desc, ", ".join(a.ilo)))
    if input("type yes to go on: ").strip() != "yes":
        sys.exit("ilo.py: not confirmed, nothing sent")


def main(argv=None):
    a = parser().parse_args(argv)
    confirm(a)
    ctx = tls_context(a)
    failed = 0
    for target in a.ilo:
        try:
            ilo = Ilo(target, a.user, password_for(a, target), ctx, dry_run=a.dry_run)
            OPS[a.action][0](ilo, a)
        except IloError as e:
            print("FAIL  %s" % e, file=sys.stderr)
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
