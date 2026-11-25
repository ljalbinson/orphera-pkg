// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Horizon (the web dashboard), Kolla image under podman, Apache + mod_wsgi, HTTPS (a certificate from
// Keystone's test CA), as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the
// cluster playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows
// what would change. Modelled on cinder_single_node.scala and heat_single_node.scala, which explain the
// conventions.
//
// Two steps, since the certificate has to move between nodes, which a description cannot do:
//
//   orphera cluster-playbook manifests/dashboard_tls.scala   # key and certificate on tst13, signed on tst6
//   orphera apply manifests/horizon_single_node.scala        # this one (tst13)
//
// The description starts with a check that the certificate files are there, and fails with that message when
// they are not.
//
// Where it runs: tst13, next to Glance and Placement, on port 8080 over HTTPS
// (https://tst13.ljalbinson.com:8080/; certificate from Keystone's test CA, shared with Skyline). Horizon is a
// stateless web front end: it keeps no database (sessions are files in the container's /tmp) and talks to the
// other services through the Keystone catalog. To move it, change `node`, `fqdn`, `nodeIp` and `port` below
// (and the teardown's `node`, and dashboard_tls.scala).
//
// Prerequisites, not checked here:
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain.
//     Cinder, Glance and Placement are optional: their panels appear when they are in the catalog.
//   - the certificate (dashboard_tls.scala), checked first
//   - agent on the node; tst9's Kolla cache is optional
//   Galera and Ceph are not involved.
//
// How it stays idempotent (as for Cinder and Heat):
//   - Keystone's CA is compared as a certificate with the installed copy each run, and the copy in the Kolla
//     directory is refreshed (and the container restarted) when it changes.
//   - local_settings.py holds the session key, so it is a secret file. The certificate chain and the key are
//     copied into the Kolla directory and compared with the ones in /etc/orphera/tls, so a renewed certificate
//     reaches the container. The Apache vhost and config.json depend on what the image has (the site-packages
//     directory, the Apache flavour, the module paths), so they are generated into a temporary directory and
//     compared with the installed files.
//   - There is no database and nothing to register in Keystone.
//   - The last step signs in as admin like a browser would (the helper's `login`); it is skipped while the
//     sign-in works.
//
// Sign in with user `admin`, password as in keystone_single_node.scala, domain Default. Test-only credentials
// from the secret store (`orphera secrets`), as before.
object horizon_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"
  private val port = 8080

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  // Signs the session ids; a fixed test value like the other secrets.
  private val secretKey = Secrets.secret("horizon/secret-key")

  private val webDir = "/etc/kolla/horizon-web"
  private val logDir = "/var/log/kolla/horizon"
  private val caDir = "/etc/kolla/horizon-ca"
  private val caCert = s"$caDir/ca.crt"
  private val tlsDir = "/etc/orphera/tls"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The container as data: the unit file text comes from it (orchestrator/.../kolla/Container.scala,
  // checked by ContainerCheck). Defined after webDir and logDir, which it reads; a val used before its
  // definition is null in an object.
  private val horizonWeb = Container(
    name = ContainerName("horizon"),
    image = Image("horizon", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(webDir, "/var/lib/kolla/config_files"),
      Mount(logDir, "/var/log/kolla/horizon", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    settleSeconds = 10
  )
  private val webImage = horizonWeb.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // The Kolla cache as a mirror of quay.io (podman registries.conf.d drop-in).
  private val registryMirror =
    s"""[[registry]]
       |prefix = "quay.io"
       |location = "quay.io"
       |
       |[[registry.mirror]]
       |location = "tst21.ljalbinson.com:5000"
       |insecure = true
       |
       |[[registry.mirror]]
       |location = "$cacheHostPort"
       |insecure = true
       |""".stripMargin

  // The certificate files dashboard_tls.scala leaves on the node.
  private val tlsPresent =
    s"[ -f $tlsDir/server-chain.crt ] && [ -f $tlsDir/server.key ] && [ -f $tlsDir/ca.crt ]"
  private val tlsMissing =
    s"""echo "no certificate in $tlsDir - run: orphera cluster-playbook manifests/dashboard_tls.scala" >&2
       |exit 1""".stripMargin

  // Keystone's CA: the last certificate it presents (trust on first use),
  // which must be self-signed. `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem,
  // each holding the certificate only: the text s_client prints after the
  // last one (bytes read and written, session ticket) differs on every
  // handshake, and would make the comparison with the installed copy fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""

  // Current when the installed CA is the one Keystone presents now, and is in
  // the system trust store.
  private val caCurrent =
    s"""$chainTo
       |D=$$(mktemp -d)
       |chainTo $$D
       |N=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |R=1
       |if [ "$$N" -ge 2 ]; then
       |  $trustTarget
       |  cmp -s $$D/c$$N.pem $caCert && cmp -s $caCert "$$t" && R=0
       |fi
       |rm -rf $$D
       |exit $$R""".stripMargin

  private val caScript =
    s"""set -e
       |$chainTo
       |mkdir -p $caDir
       |D=$$(mktemp -d)
       |trap 'rm -rf $$D' EXIT
       |chainTo $$D
       |COUNT=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - apply keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp $$D/c$$COUNT.pem $caCert
       |openssl verify -CAfile $caCert $$D/c1.pem
       |SUBJ=$$(openssl x509 -in $caCert -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caCert -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |$trustTarget
       |cp $caCert "$$t"
       |if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; else update-ca-trust; fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  // Sessions are files in the container's /tmp, which both WSGI processes share; the browser holds only the
  // session id. They used to be signed cookies, but a session made by a single sign-on login (the federated
  // token and its project list) is over 4096 bytes, and browsers silently drop such a cookie, so the user came
  // straight back to the login page. Sessions do not outlive a restart of the container. The cache is per
  // process. COMPRESS_OFFLINE matches how the Kolla image prepared its static files at build time.
  private val localSettings: String =
    s"""|import os
       |from django.utils.translation import gettext_lazy as _
       |from openstack_dashboard.settings import HORIZON_CONFIG
       |
       |DEBUG = False
       |WEBROOT = '/'
       |ALLOWED_HOSTS = ['*']
       |SECRET_KEY = '${secretKey.reveal}'
       |TIME_ZONE = 'UTC'
       |
       |SESSION_ENGINE = 'django.contrib.sessions.backends.file'
       |SESSION_FILE_PATH = '/tmp'
       |SESSION_COOKIE_HTTPONLY = True
       |# HTTPS (a certificate from the test CA), so the cookies are Secure-flagged.
       |CSRF_COOKIE_SECURE = True
       |SESSION_COOKIE_SECURE = True
       |CSRF_TRUSTED_ORIGINS = ['https://$fqdn:$port', 'https://$node:$port', 'https://$nodeIp:$port']
       |CACHES = {
       |    'default': {
       |        'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
       |    },
       |}
       |COMPRESS_OFFLINE = True
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
       |
       |# Single sign-on through Keycloak (keycloak_single_node.scala). The login page offers both; Keystone sends
       |# the token back only to https://$fqdn:$port/auth/websso/ (trusted_dashboard in keystone_single_node.scala),
       |# so open Horizon by that name.
       |WEBSSO_ENABLED = True
       |WEBSSO_CHOICES = (
       |    ('credentials', _('Keystone Credentials')),
       |    ('keycloak_openid', _('Keycloak (realm openstack)')),
       |)
       |WEBSSO_IDP_MAPPING = {
       |    'keycloak_openid': ('keycloak', 'openid'),
       |}
       |WEBSSO_INITIAL_CHOICE = 'credentials'
       |""".stripMargin

  // The vhost and config.json are generated into `out` (the real directory, or a temporary one for the
  // guard to compare with). The site-packages directory (where openstack_dashboard and the collected static
  // files live), the Apache flavour and the module paths are read from the image.
  private def kollaScript(out: String): String =
    s"""set -e
       |SITE=$$(podman run --rm --entrypoint sh $webImage -c '/var/lib/kolla/venv/bin/python -c "import os, openstack_dashboard; print(os.path.dirname(os.path.dirname(openstack_dashboard.__file__)))"')
       |if [ -z "$$SITE" ]; then echo "openstack_dashboard not found in $webImage" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $webImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/horizon.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/horizon.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |SSLMOD=$$(podman run --rm --entrypoint sh $webImage -c 'ls /usr/lib/apache2/modules/mod_ssl.so /etc/httpd/modules/mod_ssl.so /usr/lib64/httpd/modules/mod_ssl.so 2>/dev/null | head -n 1')
       |HDRMOD=$$(podman run --rm --entrypoint sh $webImage -c 'ls /usr/lib/apache2/modules/mod_headers.so /etc/httpd/modules/mod_headers.so /usr/lib64/httpd/modules/mod_headers.so 2>/dev/null | head -n 1')
       |if [ -z "$$HDRMOD" ]; then echo "mod_headers.so not found in $webImage" >&2; exit 1; fi
       |if [ -z "$$SSLMOD" ]; then echo "mod_ssl.so not found in $webImage" >&2; exit 1; fi
       |echo "site-packages: $$SITE; apache config: $$APACHE_CONF; mod_ssl: $$SSLMOD"
       |cat > $out/horizon.conf <<WSGICONF
       |<IfModule !headers_module>
       |LoadModule headers_module $$HDRMOD
       |</IfModule>
       |<IfModule !ssl_module>
       |LoadModule ssl_module $$SSLMOD
       |</IfModule>
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
       |    SSLEngine on
       |    SSLCertificateFile /etc/openstack-dashboard/horizon-server-chain.crt
       |    SSLCertificateKeyFile /etc/openstack-dashboard/horizon-server.key
       |
       |    # Logout also ends the single sign-on sessions: Horizon's own logout sends the browser to its login page;
       |    # that redirect goes instead through Keystone's mod_auth_openidc logout, which ends its session and
       |    # Keycloak's and comes back to the login page. For a password login there is nothing to end there and
       |    # Keystone sends the browser straight back.
       |    <Location "/auth/logout/">
       |        Header edit Location "^(https://[^/]+)?/auth/login/([?].*)?$$" "$ksUrl/redirect_uri?logout=https://$fqdn:$port/auth/login/"
       |    </Location>
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
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [{"source": "/var/lib/kolla/config_files/local_settings.py", "dest": "/etc/openstack-dashboard/local_settings.py", "owner": "horizon", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/openstack-dashboard/ca.crt", "owner": "horizon", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/horizon.conf", "dest": "$$APACHE_CONF", "owner": "horizon", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/server-chain.crt", "dest": "/etc/openstack-dashboard/horizon-server-chain.crt", "owner": "horizon", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/server.key", "dest": "/etc/openstack-dashboard/horizon-server.key", "owner": "horizon", "perm": "0600"}], "permissions": [{"path": "/var/log/kolla/horizon", "owner": "horizon:kolla", "recurse": true}]}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  private val kollaTemp = kollaScript("$T")
  private val kollaCurrent =
    s"""T=$$(mktemp -d)
       |$kollaTemp >/dev/null
       |R=0
       |for f in $$T/*; do cmp -s $$f $webDir/$$(basename $$f) || R=1; done
       |rm -rf $$T
       |exit $$R""".stripMargin

  // Standard-library Python. Plain string filled in with replace(). Subcommands:
  //   login-page | login | static
  // login signs in as admin the way a browser does (reads the hidden form
  // fields, posts the credentials, follows redirects) and checks that the page
  // it lands on is a signed-in page; static fetches one collected static file.
  private val checkHelperPython =
    """#!/usr/bin/python3
import http.cookiejar, re, ssl, sys, urllib.parse, urllib.request, urllib.error

BASE = '@@BASE@@'
USER = 'admin'
PASSWORD = '@@ADMINPW@@'


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


JAR = http.cookiejar.CookieJar()


CTX = ssl.create_default_context(cafile='@@CA@@')
# The test CA has no keyUsage extension, which Python 3.13 and later reject by default.
CTX.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)


def opener():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(JAR), urllib.request.HTTPSHandler(context=CTX))


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
    # With single sign-on on, the form also has a choice of method; this is the password login.
    fields['auth_type'] = 'credentials'
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

  private val helper =
    checkHelperPython
      .replace("@@BASE@@", s"https://$fqdn:$port")
      .replace("@@CA@@", s"$tlsDir/ca.crt")
      .replace("@@LOGDIR@@", logDir)
      .replace("@@ADMINPW@@", adminPassword.reveal)
  private val helperPath = "/usr/local/sbin/orphera-horizon-check"

  private val signedIn = s"$helperPath login >/dev/null 2>&1"

  def describe(target: Node): List[ApplyResource] =
    val tls = command(
      s"the certificate files from dashboard_tls.scala are in $tlsDir",
      tlsMissing,
      unless = tlsPresent
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = pullImages(
      "pull the Horizon image",
      List(webImage),
      hint = "the Image(...) in horizonWeb in horizon_single_node.scala",
      timeout = 1800
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val web = directory(webDir, mode = "0755")
    val unitService = service(horizonWeb.unitName)

    val settings = secretFile(
      s"$webDir/local_settings.py",
      Secret(localSettings), // it holds the session key
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(web).notifies(unitService)
    val kollaCa = fileCopy(
      s"$webDir/ca.crt",
      caCert,
      mode = "0644"
    ).after(ca, web).notifies(unitService)
    val certCopy = command(
      "copy the certificate chain and key to the Kolla config",
      s"""cp $tlsDir/server-chain.crt $webDir/server-chain.crt
         |cp $tlsDir/server.key $webDir/server.key
         |chmod 600 $webDir/server.key
         |echo "certificate and key copied"""".stripMargin,
      unless =
        s"cmp -s $tlsDir/server-chain.crt $webDir/server-chain.crt && cmp -s $tlsDir/server.key $webDir/server.key"
    ).after(tls, web).notifies(unitService)
    val vhost = command(
      "write the Apache vhost and the Kolla config.json",
      kollaScript(webDir),
      unless = kollaCurrent,
      timeout = 180
    ).after(pulled, web, logDirectory).notifies(unitService)
    val unitFile = file(
      horizonWeb.unitPath,
      horizonWeb.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unitService)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )

    val running =
      unitService.after(settings, kollaCa, certCopy, vhost, unitFile)
    // The login page answers 200 (the root redirects to it).
    val answering = waitForHttp(
      "horizon answers on its port",
      s"https://$fqdn:$port/auth/login/",
      ca = s"$tlsDir/ca.crt",
      resolve = s"$fqdn:$port:127.0.0.1",
      timeout = 120,
      hint = s"see 'journalctl -xeu orphera-horizon' and $logDir"
    ).after(running)
    val login = command(
      "sign in as admin like a browser would",
      s"$helperPath login",
      unless = signedIn,
      timeout = 120
    ).after(answering, helperFile)

    tools ++ List(
      tls,
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      web,
      settings,
      kollaCa,
      certCopy,
      vhost,
      unitFile,
      helperFile,
      running,
      answering,
      login
    )
