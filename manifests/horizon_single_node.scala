// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. OpenStack Horizon (the web dashboard), Kolla image under podman,
// Apache + mod_wsgi, plain HTTP. Modelled on the other *_single_node.scala
// playbooks; read cinder_single_node.scala's header for the conventions.
//
// Where it runs: tst13, next to Glance and Placement, on port 8080
// (http://tst13.ljalbinson.com:8080/). Horizon is a stateless web front end:
// it keeps no database (sessions live in signed cookies) and talks to the other
// services through the Keystone catalog. To move it, change `node`, `fqdn`,
// `nodeIp` and `port` below (and the teardown's `node`).
//
// Prerequisites, not checked here:
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain.
//     Cinder, Glance and Placement are optional: their panels appear when they
//     are in the catalog.
//   - agent on the node; tst9's Kolla cache is optional
//   Galera and Ceph are not involved.
//
// Stages:
//   - install-horizon (the node): packages, cache mirror, image pull, Keystone's
//     CA from its TLS handshake, local_settings, the Apache vhost and Kolla
//     config, the unit.
//   - confirm-horizon-healthy (the node): the login page answers, then a real
//     login as admin succeeds (helper `orphera-horizon-check`).
//
// Sign in with user `admin`, password as in keystone_single_node.scala, domain
// Default. Test-only hardcoded credentials, same convention and caveat as the
// other manifests. Not compiled where this was written; the first run on
// scala0 is the first compile.
object horizon_single_node extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"
  private val port = 8080

  private val ksFqdn = "tst6.ljalbinson.com"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val release = "2025.1"
  private val baseTag = "ubuntu-noble"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"
  private val webImage = s"$registry/horizon:$release-$baseTag"

  private val adminPassword = "orphera-test-keystone-admin-password"
  // Signs the session cookies; a fixed test value like the other secrets.
  private val secretKey = "orphera-test-horizon-secret-key-not-for-real-use"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/horizon"
  private val logDir = "/var/log/kolla/horizon"
  private val caDir = "/etc/kolla/horizon-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // A freshly provisioned VM runs unattended-upgrades for a while after boot,
  // and apt cannot take the dpkg lock meanwhile. Wait here, with an explicit
  // task timeout, instead of relying on how long the agent's own apt call waits.
  // Matches the upgrade workers by exact command line: the always-running
  // unattended-upgrade-shutdown daemon has the same 15-character process name.
  // Note the upgrade can itself restart orphera-agent (needrestart), which
  // kills any task in flight, this one included; if that happens, rerun once
  // the upgrade has finished.
  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
      |exit 1""".stripMargin

  private val useCacheScript =
    s"""mkdir -p /etc/containers/registries.conf.d
       |cat > /etc/containers/registries.conf.d/orphera-kolla-cache.conf <<'REG'
       |[[registry]]
       |prefix = "quay.io"
       |location = "quay.io"
       |
       |[[registry.mirror]]
       |location = "$cacheHostPort"
       |insecure = true
       |REG
       |echo "podman will try $cacheHostPort before quay.io"""".stripMargin

  private val pullScript =
    s"""podman pull $webImage || { echo "could not pull $webImage - check registry/release/baseTag at the top of horizon_single_node.scala" >&2; exit 1; }
       |echo "image ready"""".stripMargin

  // Same method as the other service playbooks: the last certificate Keystone
  // presents is its CA; trust on first use.
  private val fetchCaScript =
    s"""set -e
       |mkdir -p $caDir
       |rm -f /tmp/ks-chain-*.pem
       |openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/ks-chain-" n ".pem")}'
       |COUNT=$$(ls /tmp/ks-chain-*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - re-run keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp /tmp/ks-chain-$$COUNT.pem $caDir/ca.crt
       |openssl verify -CAfile $caDir/ca.crt /tmp/ks-chain-1.pem
       |SUBJ=$$(openssl x509 -in $caDir/ca.crt -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caDir/ca.crt -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |rm -f /tmp/ks-chain-*.pem
       |if command -v update-ca-certificates >/dev/null 2>&1; then
       |  cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |  update-ca-certificates
       |else
       |  cp $caDir/ca.crt /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt
       |  update-ca-trust
       |fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  // Sessions are signed cookies and the cache is per process, so several Apache
  // worker processes need no shared session store. COMPRESS_OFFLINE matches how
  // the Kolla image prepared its static files at build time.
  private val settingsScript =
    s"""mkdir -p $srcDir $logDir
       |cat > $srcDir/local_settings.py <<'SETTINGS'
       |import os
       |from django.utils.translation import gettext_lazy as _
       |from openstack_dashboard.settings import HORIZON_CONFIG
       |
       |DEBUG = False
       |WEBROOT = '/'
       |ALLOWED_HOSTS = ['*']
       |SECRET_KEY = '$secretKey'
       |TIME_ZONE = 'UTC'
       |
       |SESSION_ENGINE = 'django.contrib.sessions.backends.signed_cookies'
       |SESSION_COOKIE_HTTPONLY = True
       |# Plain HTTP on this test deployment: Secure-flagged cookies would never be
       |# sent back by a client, which fails the CSRF check on every login.
       |CSRF_COOKIE_SECURE = False
       |SESSION_COOKIE_SECURE = False
       |CSRF_TRUSTED_ORIGINS = ['http://localhost:$port', 'http://127.0.0.1:$port', 'http://$node.ljalbinson.com:$port']
       |CACHES = {
       |    'default': {
       |        'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
       |    },
       |}
       |COMPRESS_OFFLINE = True
       |
       |# The Project dashboard's default panel (Overview) requires a compute service
       |# in the catalog, so until Nova is registered "/" -> /project/ is a 403.
       |# Land on the Identity dashboard, which needs only Keystone.
       |HORIZON_CONFIG['default_dashboard'] = 'identity'
       |
       |OPENSTACK_HOST = '$ksFqdn'
       |OPENSTACK_KEYSTONE_URL = '$ksUrl/v3'
       |OPENSTACK_SSL_CACERT = '/etc/openstack-dashboard/ca.crt'
       |OPENSTACK_KEYSTONE_MULTIDOMAIN_SUPPORT = False
       |OPENSTACK_KEYSTONE_DEFAULT_DOMAIN = 'Default'
       |OPENSTACK_KEYSTONE_DEFAULT_ROLE = 'member'
       |OPENSTACK_API_VERSIONS = {
       |    'identity': 3,
       |    'volume': 3,
       |}
       |SETTINGS
       |chmod 640 $srcDir/local_settings.py
       |echo "local_settings.py written"""".stripMargin

  // The site-packages directory (where openstack_dashboard and the collected
  // static files live) is read from the image, as the WSGI script paths are in
  // the Cinder and Placement playbooks.
  private val kollaConfigScript =
    s"""set -e
       |mkdir -p $confRoot/horizon-web
       |cp $srcDir/local_settings.py $confRoot/horizon-web/local_settings.py
       |cp $caDir/ca.crt $confRoot/horizon-web/ca.crt
       |SITE=$$(podman run --rm --entrypoint sh $webImage -c '/var/lib/kolla/venv/bin/python -c "import os, openstack_dashboard; print(os.path.dirname(os.path.dirname(openstack_dashboard.__file__)))"')
       |if [ -z "$$SITE" ]; then echo "openstack_dashboard not found in $webImage" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $webImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/horizon.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/horizon.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "site-packages: $$SITE; apache config: $$APACHE_CONF"
       |cat > $confRoot/horizon-web/horizon.conf <<WSGICONF
       |Listen 0.0.0.0:$port
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$port>
       |    LogLevel warn
       |    ErrorLog "$logDir/horizon-error.log"
       |    CustomLog "$logDir/horizon-access.log" combined
       |    WSGIScriptReloading On
       |    WSGIDaemonProcess horizon-http processes=2 threads=1 user=horizon group=horizon display-name=%{GROUP} python-path=$$SITE
       |    WSGIProcessGroup horizon-http
       |    WSGIScriptAlias / $$SITE/openstack_dashboard/wsgi.py
       |    WSGIPassAuthorization On
       |
       |    <Directory $$SITE/openstack_dashboard>
       |        Require all granted
       |    </Directory>
       |
       |    Alias /static $$SITE/static
       |    <Location "/static">
       |        SetHandler None
       |    </Location>
       |    <Directory $$SITE/static>
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $confRoot/horizon-web/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [{"source": "/var/lib/kolla/config_files/local_settings.py", "dest": "/etc/openstack-dashboard/local_settings.py", "owner": "horizon", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/openstack-dashboard/ca.crt", "owner": "horizon", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/horizon.conf", "dest": "$$APACHE_CONF", "owner": "horizon", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/horizon", "owner": "horizon:kolla", "recurse": true}]}
       |JSON
       |echo "kolla config written under $confRoot/horizon-web"""".stripMargin

  private val unitScript =
    s"""set -e
       |cat > /etc/systemd/system/orphera-horizon.service <<'UNIT'
       |[Unit]
       |Description=horizon (Kolla container) managed by Orphera
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f horizon
       |ExecStart=/usr/bin/podman run --rm --name horizon --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confRoot/horizon-web:/var/lib/kolla/config_files:ro,z -v $logDir:/var/log/kolla/horizon:z $webImage
       |ExecStop=/usr/bin/podman stop -t 30 horizon
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |systemctl enable orphera-horizon
       |systemctl restart orphera-horizon
       |sleep 10
       |if ! systemctl is-active --quiet orphera-horizon; then
       |  echo "orphera-horizon failed to start - see 'journalctl -xeu orphera-horizon' and $logDir" >&2
       |  exit 1
       |fi
       |echo "horizon running"""".stripMargin

  // Standard-library Python. Plain string filled in with replace(). Subcommands:
  //   login-page | login | static
  // login signs in as admin the way a browser does (reads the hidden form
  // fields, posts the credentials, follows redirects) and checks that the page
  // it lands on is a signed-in page; static fetches one collected static file.
  private val checkHelperPython =
    """#!/usr/bin/python3
import http.cookiejar, re, sys, urllib.parse, urllib.request, urllib.error

BASE = '@@BASE@@'
USER = 'admin'
PASSWORD = '@@ADMINPW@@'


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


JAR = http.cookiejar.CookieJar()


def opener():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(JAR))


def why(body):
    text = re.sub(r'<[^>]+>', ' ', body)
    text = re.sub(r'\s+', ' ', text)
    m = re.search(r'(Reason given for failure:.{0,160}|CSRF verification failed.{0,160}|Forbidden.{0,160})', text)
    return m.group(1) if m else text[:200]


def fetch(op, url, data=None):
    req = urllib.request.Request(url, data=data)
    req.add_header('Referer', BASE + '/auth/login/')
    try:
        r = op.open(req, timeout=60)
        return r.status, r.geturl(), r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read().decode('utf-8', 'replace')


def login_page():
    s, url, body = fetch(opener(), BASE + '/')
    if s != 200 or '/auth/login' not in url:
        die('expected the root to lead to the login page; got HTTP %s at %s' % (s, url))
    if 'csrfmiddlewaretoken' not in body:
        die('login page has no CSRF field')
    print('login page ok (%s)' % url)


def login():
    op = opener()
    s, url, body = fetch(op, BASE + '/auth/login/')
    if s != 200:
        die('login page: HTTP %s' % s)
    fields = dict(re.findall(r'<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"', body))
    fields.update(dict((n, v) for v, n in re.findall(r'<input[^>]*value="([^"]*)"[^>]*type="hidden"[^>]*name="([^"]+)"', body)))
    if 'csrfmiddlewaretoken' not in fields:
        die('login page has no CSRF field')
    fields['username'] = USER
    fields['password'] = PASSWORD
    s, url, body = fetch(op, BASE + '/auth/login/', urllib.parse.urlencode(fields).encode())
    if s != 200:
        flags = ', '.join('%s%s' % (c.name, ' [Secure]' if c.secure else '') for c in sorted(JAR, key=lambda c: c.name)) or 'none'
        tail = ''
        for name, pat, n in (('access', None, 6), ('error', r'Traceback|Error|Exception|Forbidden|Unauthorized|denied', 6)):
            try:
                with open('@@LOGDIR@@/horizon-%s.log' % name) as f:
                    lines = [l.strip() for l in f.read().splitlines() if l.strip()]
                if pat:
                    lines = [l for l in lines if re.search(pat, l) and 'UserWarning' not in l]
                tail += ' | %s log: %s' % (name, ' // '.join(l[-220:] for l in lines[-n:]))
            except Exception:
                pass
        die('login post: HTTP %s at %s - %s (cookies held: %s)%s' % (s, url, why(body), flags, tail))
    if 'auth/logout' not in body:
        m = re.search(r'alert[^>]*>\s*([^<]{3,200})', body)
        die('login did not reach a signed-in page (at %s)%s' % (url, ': ' + m.group(1).strip() if m else ''))
    print('login ok as %s (landed on %s)' % (USER, url))


def static():
    op = opener()
    s, url, body = fetch(op, BASE + '/auth/login/')
    m = re.search(r'(/static/[^"\']+\.(?:css|js))', body)
    if not m:
        die('no static file referenced by the login page')
    s, _, _ = fetch(op, BASE + m.group(1))
    if s != 200:
        die('static file %s: HTTP %s' % (m.group(1), s))
    print('static ok (%s)' % m.group(1))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'login-page':
        login_page()
    elif cmd == 'login':
        login()
    elif cmd == 'static':
        static()
    else:
        die('usage: orphera-horizon-check login-page | login | static')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-horizon-check <<'PYEOF'\n" +
      checkHelperPython
        .replace("@@BASE@@", s"http://localhost:$port")
        .replace("@@LOGDIR@@", logDir)
        .replace("@@ADMINPW@@", adminPassword) +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-horizon-check\necho wrote /usr/local/sbin/orphera-horizon-check\n"

  // The root redirects to the login page; the login page itself answers 200.
  private val webHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/auth/login/)
       |[ "$$CODE" = "200" ]""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("horizon-single-node")(
      stage("install-horizon", node)
        .task("wait until apt and dpkg are idle")(
          Task.RunCommand(List("sh", "-c", waitAptIdleScript), timeoutSeconds = 960)
        )
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Horizon image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write local_settings.py")(
          Task.RunCommand(List("sh", "-c", settingsScript))
        )
        .task("write the Kolla config and the Apache vhost")(
          Task.RunCommand(List("sh", "-c", kollaConfigScript), timeoutSeconds = 120)
        )
        .task("write the Horizon check helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("start horizon")(
          Task.RunCommand(List("sh", "-c", unitScript), timeoutSeconds = 300)
        )
        .build,

      stage("confirm-horizon-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", webHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("sign in as admin like a browser would")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-horizon-check login"),
            timeoutSeconds = 120
          )
        )
        .task("horizon confirmed")(
          Task.Debug(
            s"Horizon up at http://$fqdn:$port/ ($nodeIp): sign in as admin (domain Default) with the Keystone admin password from keystone_single_node.scala. Identity from $ksUrl. Test: manifests/test_horizon.sh"
          )
        )
        .build
    )
