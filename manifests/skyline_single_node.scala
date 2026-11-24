// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Skyline, the dashboard that replaces Horizon's look and adds a console, on tst13 next to Horizon
// (which stays on 8080), as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the
// cluster playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows
// what would change. One container under podman from the image the Skyline project documents for installation,
// 99cloud/skyline (the apiserver, gunicorn on a Unix socket, and nginx in front of it serving the console on
// port 9999). It is not a Kolla image, so tst21 does not build it. Modelled on horizon_single_node.scala, which
// explains the conventions.
//
// Apply in this order (one description cannot order its nodes):
//
//   orphera cluster-playbook manifests/dashboard_tls.scala   # key and certificate on tst13, signed on tst6
//   orphera apply manifests/skyline_database.scala           # the database on Galera (tst3)
//   orphera apply manifests/skyline_single_node.scala        # this one (tst13)
//
// dashboard_tls.scala is the same one Horizon uses; Skyline used to make the certificate itself when it was
// missing, and now starts with a check that the files are there, as Horizon does.
//
// Where it runs: tst13 (Glance, Placement, Horizon). To move it, change `node` and `fqdn` below (and
// the teardown's `node`, and dashboard_tls.scala); nothing else depends on the location.
//
// Why this image: the nginx configuration of Skyline has one location block per service endpoint in the
// Keystone catalog (/api/openstack/<region>/<service>/ ...). The image writes it from the catalog every time
// it starts. A Kolla-style split into apiserver and console containers needs that file made by hand.
//
// What it does:
//   - the Keystone user `skyline` (project `service`, role admin), made by the helper's `register`; Skyline
//     itself has no service entry in the catalog;
//   - skyline.yaml (database, Keystone URL, the service password, a secret key from the secret store, the
//     Prometheus on tst7 for the monitoring pages, `ssl_enabled: true` because the console is served over
//     HTTPS: the image's nginx gets the node's certificate (signed by Keystone's test CA, shared with Horizon)
//     through SSL_CERTFILE and SSL_KEYFILE; ssl_enabled marks the session cookie Secure, which breaks logins
//     over plain HTTP but is right over HTTPS);
//   - a PyMySQL driver, which the image lacks, installed with the image's own Python into
//     /var/lib/orphera/skyline/pylib and put on PYTHONPATH (as for the Magnum driver);
//   - `skyline_bootstrap` (the image's KOLLA_BOOTSTRAP mode: the Alembic migrations), then the unit
//     `orphera-skyline.service`.
// The container trusts the test CA: the host's CA bundle (which the description updates with Keystone's CA) is
// mounted over the image's and REQUESTS_CA_BUNDLE points at it.
//
// Because the nginx file comes from the catalog at start, the description restarts the container whenever the
// catalog's public endpoints have changed since the last run (a hash kept in /var/lib/orphera/skyline). Run it
// again after adding a service and the new service appears in the console.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - the certificate (dashboard_tls.scala), checked first
//   - the services you want in the console registered (the console shows what the catalog lists)
//   - the agent on tst13; Docker Hub reachable from tst13 (the image is docker.io/99cloud/skyline; set
//     SKYLINE_IMAGE to another reference or a tag, or point podman at a mirror)
//
// How it stays idempotent (as for Horizon):
//   - Each step that changes the node is a command with a guard that tests what it makes: the image, the
//     certificate copy (compared with the files in /etc/orphera/tls, and owned by the image's user), the
//     PyMySQL driver, the Keystone user (a token as `skyline`), and the migrations (a stamp of the step and
//     the image, and tables in the database, read with the image's own Python).
//   - skyline.yaml holds the passwords, so it is a secret file; it and the unit notify the service, so a change
//     restarts it. Keystone's CA is compared as a certificate each run (a changed one also updates the bundle
//     the container mounts, so the container is restarted).
//   - The last steps check that the console and the API docs answer and that the admin user logs in.
//
// Facts this was written from, to check if the first run fails: the documented bootstrap is
// `-e KOLLA_BOOTSTRAP=""` with skyline.yaml mounted and the host network; the API docs are at
// /api/openstack/skyline/docs; the login API is POST /api/openstack/skyline/api/v1/login with region,
// domain, username and password; the default listen address is 0.0.0.0:9999; skyline.yaml keys are those of
// the Skyline settings reference (default.database_url, default.secret_key, default.ssl_enabled,
// default.prometheus_endpoint, openstack.keystone_url, openstack.system_user_password and so on).
//
// Test-only credentials come from the secret store (skyline/db, skyline/service, skyline/secret-key), made on
// first use.
object skyline_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val port = 9999

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"
  private val promUrl = "http://tst7.ljalbinson.com:9090"
  private val region = "RegionOne"

  private val skylineImage = ExternalImage(
    sys.env.getOrElse("SKYLINE_IMAGE", "docker.io/99cloud/skyline:latest")
  )
  private val imageRef = skylineImage.ref

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "skyline"
  private val dbUser = "skyline"
  private val dbPassword = Secrets.secret("skyline/db")
  private val skylinePassword = Secrets.secret("skyline/service")
  private val secretKey = Secrets.secret("skyline/secret-key")

  private val confDir = "/etc/skyline"
  private val caDir = "/etc/skyline/ca"
  private val caCert = s"$caDir/ca.crt"
  private val logDir = "/var/log/skyline"
  private val stateDir = "/var/lib/orphera/skyline"
  private val pylibDir = "/var/lib/orphera/skyline/pylib"
  private val socketDir = "/var/lib/orphera/skyline/run"
  // A copy of the node's certificate chain and key, owned by the image's user (the shared files are root's).
  private val skylineTlsDir = "/var/lib/orphera/skyline/tls"
  // The certificate files dashboard_tls.scala leaves on the node.
  private val tlsDir = "/etc/orphera/tls"
  private val ksUrl = s"https://$ksFqdn:5000"

  // Defined after the directories above, which they read; a val used before its definition is null in an
  // object. Host network. The CA bundle is mounted unlabelled (relabelling a system file is not wanted).
  private val skylineContainer = Container(
    name = ContainerName("skyline"),
    image = skylineImage,
    mounts = List(
      Mount(s"$confDir/skyline.yaml", "/etc/skyline/skyline.yaml"),
      Mount(logDir, "/var/log/skyline", Access.ReadWrite),
      Mount(socketDir, "/var/lib/skyline", Access.ReadWrite),
      Mount(pylibDir, pylibDir),
      Mount(skylineTlsDir, "/etc/skyline/tls"),
      Mount(
        "/etc/ssl/certs/ca-certificates.crt",
        "/etc/ssl/certs/ca-certificates.crt",
        Access.ReadOnlyUnlabelled
      )
    ),
    env = List(
      "PYTHONPATH" -> pylibDir,
      "REQUESTS_CA_BUNDLE" -> "/etc/ssl/certs/ca-certificates.crt",
      "SSL_CERT_FILE" -> "/etc/ssl/certs/ca-certificates.crt",
      // The image's nginx serves HTTPS when these are set (Skyline's container install documents them).
      "SSL_CERTFILE" -> "/etc/skyline/tls/server-chain.crt",
      "SSL_KEYFILE" -> "/etc/skyline/tls/server.key"
    ),
    description = Some("OpenStack Skyline dashboard"),
    startTimeoutSeconds = 300,
    settleSeconds = 15
  )

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  private val tlsPresent =
    s"[ -f $tlsDir/server-chain.crt ] && [ -f $tlsDir/server.key ] && [ -f $tlsDir/ca.crt ]"
  private val tlsMissing =
    s"""echo "no certificate in $tlsDir - run: orphera cluster-playbook manifests/dashboard_tls.scala" >&2
       |exit 1""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "SKYLINE_IMAGE or the Image in skyline_single_node.scala"
      )}
       |pull_retry $imageRef || exit 1
       |echo "image ready: $$(podman images -q $imageRef | head -1)"""".stripMargin
  private val imagePresent = s"podman image exists $imageRef"

  // Keystone's CA: the last certificate it presents (trust on first use), which must be self-signed.
  // `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem, each holding the certificate only: the text s_client
  // prints after the last one differs on every handshake, and would make the comparison fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""
  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""
  // Current when the installed CA is the one Keystone presents now, and is in the system trust store.
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
  // update-ca-certificates also puts it in /etc/ssl/certs/ca-certificates.crt, which the container mounts.
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

  // The key goes to a directory of Skyline's own, owned by the user the image runs as, whoever that is.
  private val skylineTlsScript =
    s"""set -e
       |mkdir -p $skylineTlsDir
       |chmod 750 $skylineTlsDir
       |cp $tlsDir/server-chain.crt $tlsDir/server.key $skylineTlsDir/
       |U=$$(podman run --rm --entrypoint id $imageRef -u)
       |G=$$(podman run --rm --entrypoint id $imageRef -g)
       |chown -R $$U:$$G $skylineTlsDir
       |chmod 600 $skylineTlsDir/server.key
       |chmod 644 $skylineTlsDir/server-chain.crt
       |echo "certificate and key for Skyline in $skylineTlsDir (uid $$U)"""".stripMargin
  private val skylineTlsCurrent =
    s"""podman image exists $imageRef &&
       |cmp -s $tlsDir/server-chain.crt $skylineTlsDir/server-chain.crt &&
       |cmp -s $tlsDir/server.key $skylineTlsDir/server.key &&
       |[ "$$(stat -c %u:%g $skylineTlsDir/server.key)" = "$$(podman run --rm --entrypoint id $imageRef -u):$$(podman run --rm --entrypoint id $imageRef -g)" ]""".stripMargin

  // PyMySQL goes into pylibDir with the image's own Python, so it matches the Python in the container. Only
  // `podman run` is used. A bare entrypoint avoids the image's start script.
  private val installDriverScript =
    s"""set -e
       |podman run --rm --user root --entrypoint sh -v $pylibDir:/out:z ${skylineImage.ref} -c '
       |PY=/var/lib/kolla/venv/bin/python; [ -x "$$PY" ] || PY=$$(command -v python3)
       |"$$PY" -m pip install --no-deps --upgrade --target /out PyMySQL || { echo "pip is not usable in the image" >&2; exit 1; }
       |chmod -R a+rX /out
       |PYTHONPATH=/out "$$PY" -c "import pymysql; print(\\"pymysql\\", pymysql.__version__)"'""".stripMargin

  // skyline.yaml. The same names the Skyline settings reference gives.
  private val skylineYaml: String =
    s"""# Managed by Orphera (skyline_single_node.scala)
       |default:
       |  database_url: mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |  debug: false
       |  log_dir: /var/log/skyline
       |  secret_key: ${secretKey.reveal}
       |  ssl_enabled: true
       |  prometheus_endpoint: $promUrl
       |  prometheus_enable_basic_auth: false
       |openstack:
       |  keystone_url: $ksUrl/v3/
       |  default_region: $region
       |  interface_type: public
       |  system_project: service
       |  system_project_domain: Default
       |  system_user_name: skyline
       |  system_user_domain: Default
       |  system_user_password: ${skylinePassword.reveal}
       |  # Single sign-on through Keycloak (keycloak_single_node.scala). Skyline 8.x offers one choice per
       |  # protocol and sends the browser to Keystone's protocol-wide websso path; Keystone posts the token back
       |  # only to https://$fqdn:$port/api/openstack/skyline/api/v1/websso (trusted_dashboard in
       |  # keystone_single_node.scala), so open Skyline by that name.
       |  sso_enabled: true
       |  sso_protocols:
       |    - openid
       |  sso_region: $region
       |""".stripMargin

  // The documented bootstrap: the image's start script runs the migrations when KOLLA_BOOTSTRAP is set (even
  // empty) and exits.
  private val bootstrapRun =
    s"""podman rm -f skyline_bootstrap >/dev/null 2>&1 || true
       |RC=0
       |podman run --rm --name skyline_bootstrap --network host --user root -e KOLLA_BOOTSTRAP= -e PYTHONPATH=$pylibDir -e REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt -v $confDir/skyline.yaml:/etc/skyline/skyline.yaml:ro,z -v $logDir:/var/log/skyline:z -v $pylibDir:$pylibDir:ro,z -v /etc/ssl/certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro $imageRef >/tmp/skyline-bootstrap.log 2>&1 || RC=$$?
       |tail -n 25 /tmp/skyline-bootstrap.log
       |if [ "$$RC" != "0" ]; then echo "skyline_bootstrap failed (exit $$RC) - the output above names the reason" >&2; exit 1; fi
       |echo "skyline database migrated"""".stripMargin
  // The migrations are current when this stamp (the step and the image) is the one the last run left, and the
  // database has tables, read as the skyline user with the image's own Python.
  private val stampValue =
    s"""echo "${Resource.sha256(
        bootstrapRun
      )}-$$(podman images -q $imageRef | head -1)""""
  private val bootstrapScript =
    s"""$bootstrapRun
       |$stampValue > $stateDir/bootstrap.stamp
       |echo "stamped"""".stripMargin
  private val migrated =
    s"""[ "$$(cat $stateDir/bootstrap.stamp 2>/dev/null)" = "$$($stampValue)" ] &&
       |podman image exists $imageRef &&
       |podman run --rm -i --network host --user root -e PYTHONPATH=$pylibDir -v $pylibDir:$pylibDir:ro,z --entrypoint sh $imageRef -c 'PY=/var/lib/kolla/venv/bin/python; [ -x "$$PY" ] || PY=$$(command -v python3); exec "$$PY" -' <<'PYEOF'
       |import sys, pymysql
       |c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |cur = c.cursor()
       |cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", ('$dbName',))
       |sys.exit(0 if cur.fetchone()[0] > 0 else 1)
       |PYEOF""".stripMargin

  // After the container is up: if the catalog's public endpoints differ from those the last start saw,
  // restart it so nginx.conf is written again.
  private val helperPath = "/usr/local/sbin/orphera-skyline-api"
  private val catalogRestartScript =
    s"""set -e
       |NEW=$$($helperPath catalog-hash)
       |OLD=$$(cat $stateDir/catalog.sha 2>/dev/null || true)
       |if [ "$$NEW" != "$$OLD" ]; then
       |  if [ -n "$$OLD" ]; then
       |    echo "the Keystone catalog changed since the last run; restarting skyline to write nginx.conf again"
       |    systemctl restart orphera-skyline.service
       |  fi
       |  echo "$$NEW" > $stateDir/catalog.sha
       |fi
       |echo "catalog hash $$NEW"""".stripMargin
  private val catalogCurrent =
    s"""[ "$$($helperPath catalog-hash 2>/dev/null)" = "$$(cat $stateDir/catalog.sha 2>/dev/null)" ]"""

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands: register | registered | unregister | catalog-hash | login-check
  private val apiHelperPython =
    """#!/usr/bin/python3
import hashlib, json, ssl, sys, urllib.error, urllib.request

KS = 'https://@@KS@@:5000/v3'
SKY = 'https://@@FQDN@@:@@PORT@@'
REGION = '@@REGION@@'
ADMIN_PW = '@@ADMINPW@@'
SKYLINE_PW = '@@SKYLINEPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
# The test CA has no keyUsage extension, which Python 3.13 and later reject by default.
CTX.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)


def call(method, url, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=60)
        status, text, headers = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, headers = e.code, e.read().decode(), e.headers
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {'raw': text[:300]}
    return status, parsed, headers


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token'], parsed


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = find(token, '/projects?name=service', 'projects')
    if project is None:
        s, p, _ = call('POST', KS + '/projects', {'project': {'name': 'service', 'domain_id': 'default'}}, token)
        if s != 201:
            die('creating project service failed: HTTP %s %s' % (s, p))
        project = p['project']
    user = find(token, '/users?name=skyline', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'skyline', 'domain_id': 'default', 'password': SKYLINE_PW}}, token)
        if s != 201:
            die('creating user skyline failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': SKYLINE_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to skyline failed: HTTP %s %s' % (s, p))
    print('registered skyline in keystone (user skyline, role admin in project service)')


def registered():
    # The Skyline user exists, has its password from the secret store and a role in project service.
    get_token('skyline', SKYLINE_PW, 'service')
    print('skyline is registered in keystone')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    user = find(token, '/users?name=skyline', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed user skyline from keystone')


def catalog_hash():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    rows = []
    for c in p['token']['catalog']:
        for e in c['endpoints']:
            if e['interface'] == 'public':
                rows.append('%s %s' % (c['type'], e['url']))
    print(hashlib.sha256('\n'.join(sorted(rows)).encode()).hexdigest()[:16])


def login_check():
    s, p, _ = call('GET', SKY + '/')
    if s != 200:
        die('console page: HTTP %s' % s)
    s, p, _ = call('GET', SKY + '/api/openstack/skyline/docs')
    if s != 200:
        die('API docs at /api/openstack/skyline/docs: HTTP %s %s' % (s, p))
    body = {'region': REGION, 'domain': 'Default', 'username': 'admin', 'password': ADMIN_PW}
    s, p, _ = call('POST', SKY + '/api/openstack/skyline/api/v1/login', body)
    if s != 200:
        die('login as admin failed: HTTP %s %s' % (s, p))
    print('login ok: console page, API docs and the admin login answer HTTP 200')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'registered':
        registered()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog-hash':
        catalog_hash()
    elif cmd == 'login-check':
        login_check()
    else:
        die('usage: orphera-skyline-api register | registered | unregister | catalog-hash | login-check')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@PORT@@", port.toString)
      .replace("@@FQDN@@", fqdn)
      .replace("@@REGION@@", region)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@SKYLINEPW@@", skylinePassword.reveal)
      .replace("@@CA@@", caCert)

  private val loggedIn = s"$helperPath login-check >/dev/null 2>&1"

  def describe(target: Node): List[ApplyResource] =
    val tls = command(
      s"the certificate files from dashboard_tls.scala are in $tlsDir",
      tlsMissing,
      unless = tlsPresent
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    val pulled = command(
      "pull the Skyline image",
      pullScript,
      unless = imagePresent,
      timeout = 1800
    ).after(podman)

    val confDirectory = directory(confDir)
    val caDirectory =
      directory(caDir, mode = "0755").after(confDirectory, podman)
    val logDirectory = directory(logDir)
    val state = directory(stateDir)
    val pylib = directory(pylibDir).after(state)
    val socket = directory(socketDir).after(state)

    val unitService = service(skylineContainer.unitName)

    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1)).notifies(unitService)
    val tlsCopy = command(
      "give Skyline its copy of the certificate and key",
      skylineTlsScript,
      unless = skylineTlsCurrent,
      timeout = 120
    ).after(tls, pulled, state).notifies(unitService)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and skyline passwords
      mode = "0700",
      owner = "root",
      group = "root"
    ).after(ca)
    val registered = command(
      "register the Skyline user in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile)

    val driver = command(
      "install the PyMySQL driver with the image's Python",
      installDriverScript,
      creates = s"$pylibDir/pymysql/__init__.py",
      timeout = 300
    ).after(pulled, pylib)

    val config = secretFile(
      s"$confDir/skyline.yaml",
      Secret(
        skylineYaml
      ), // it holds the database and service passwords and the session key
      mode = "0600",
      owner = "root",
      group = "root"
    ).after(confDirectory).notifies(unitService)

    val bootstrap = command(
      "skyline_bootstrap (database migrations)",
      bootstrapScript,
      unless = migrated,
      timeout = 600
    ).after(pulled, config, driver, ca, logDirectory, state)

    val unitFile = file(
      skylineContainer.unitPath,
      skylineContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unitService)

    val running = unitService.after(
      pulled,
      config,
      ca,
      tlsCopy,
      driver,
      bootstrap,
      registered,
      unitFile,
      logDirectory,
      socket,
      pylib
    )
    val catalogRestart = command(
      "restart skyline if the Keystone catalog changed",
      catalogRestartScript,
      unless = catalogCurrent,
      timeout = 180
    ).after(running, helperFile)
    val answering = waitForHttp(
      "skyline answers on its port",
      s"https://$fqdn:$port/",
      ca = s"$tlsDir/ca.crt",
      resolve = s"$fqdn:$port:127.0.0.1",
      timeout = 180,
      hint = s"see 'journalctl -xeu orphera-skyline' and $logDir"
    ).after(catalogRestart)
    val login = command(
      "the console, the API docs and an admin login answer",
      s"$helperPath login-check",
      unless = loggedIn,
      timeout = 120
    ).after(answering, helperFile)

    tools ++ List(
      tls,
      pulled,
      confDirectory,
      caDirectory,
      logDirectory,
      state,
      pylib,
      socket,
      ca,
      tlsCopy,
      helperFile,
      registered,
      driver,
      config,
      bootstrap,
      unitFile,
      running,
      catalogRestart,
      answering,
      login
    )
