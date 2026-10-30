// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Skyline, the dashboard that replaces Horizon's look and adds a console, on tst13 next to
// Horizon (which stays on 8080). One container under podman from the image the Skyline project documents for
// installation, 99cloud/skyline (the apiserver, gunicorn on a Unix socket, and nginx in front of it serving the
// console on port 9999). It is not a Kolla image, so tst21 does not build it. Modelled on
// horizon_single_node.scala and magnum_single_node.scala; read their headers for the conventions.
//
// Where it runs: tst13 (Glance, Placement, Horizon). To move it, change `node`, `fqdn` and `nodeIp` below (and
// the teardown's `node`); nothing else depends on the location.
//
// Why this image: the nginx configuration of Skyline has one location block per service endpoint in the
// Keystone catalog (/api/openstack/<region>/<service>/ ...). The image writes it from the catalog every time
// it starts. A Kolla-style split into apiserver and console containers needs that file made by hand.
//
// What it does:
//   - database `skyline` on Galera (stage skyline-database) with the settings the Skyline documents give
//     (utf8, utf8_general_ci);
//   - the Keystone user `skyline` (project `service`, role admin), made by the helper's `register`; Skyline
//     itself has no service entry in the catalog;
//   - skyline.yaml (database, Keystone URL, the service password, a secret key from the secret store, the
//     Prometheus on tst7 for the monitoring pages, `ssl_enabled: false` because the console is served over
//     plain HTTP; with it true the browser drops the session cookie and every login fails);
//   - a PyMySQL driver, which the image lacks, installed with the image's own Python into
//     /var/lib/orphera/skyline/pylib and put on PYTHONPATH (as for the Magnum driver);
//   - `skyline_bootstrap` (the image's KOLLA_BOOTSTRAP mode: the Alembic migrations), then the unit
//     `orphera-skyline.service`.
// The container trusts the test CA: the host's CA bundle (which the playbook updates with Keystone's CA) is
// mounted over the image's and REQUESTS_CA_BUNDLE points at it.
//
// Because the nginx file comes from the catalog at start, the playbook restarts the container whenever the
// catalog's public endpoints have changed since the last run (a hash kept in /var/lib/orphera/skyline). Run it
// again after adding a service and the new service appears in the console.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - the services you want in the console registered (the console shows what the catalog lists)
//   - the agent on tst13; Docker Hub reachable from tst13 (the image is docker.io/99cloud/skyline; set
//     SKYLINE_IMAGE to another reference or a tag, or point podman at a mirror)
//
// Stages:
//   - skyline-database (tst3): the `skyline` database + user.
//   - install-skyline (tst13): packages, image pull, Keystone's CA, the helper and the registration, the
//     PyMySQL driver, skyline.yaml, `skyline_bootstrap`, the unit, a restart if the catalog changed.
//   - confirm-skyline-healthy (tst13): the console answers on 9999, the API docs answer, and the admin user
//     logs in through the login API.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/skyline_single_node.scala` changes nothing
// and reports whether the unit on the node matches what this playbook writes.
//
// Facts this was written from, to check if the first run fails: the documented bootstrap is
// `-e KOLLA_BOOTSTRAP=""` with skyline.yaml mounted and the host network; the API docs are at
// /api/openstack/skyline/docs; the login API is POST /api/openstack/skyline/api/v1/login with region,
// domain, username and password; the default listen address is 0.0.0.0:9999; skyline.yaml keys are those of
// the Skyline settings reference (default.database_url, default.secret_key, default.ssl_enabled,
// default.prometheus_endpoint, openstack.keystone_url, openstack.system_user_password and so on).
//
// Test-only credentials come from the secret store (skyline/db, skyline/service, skyline/secret-key), made on
// first use. Not compiled where this was written; the first run on scala0 is the first compile.
object skyline_single_node extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"
  private val port = 9999

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"
  private val promUrl = "http://tst7.ljalbinson.com:9090"
  private val region = "RegionOne"

  private val skylineImage = ExternalImage(
    sys.env.getOrElse("SKYLINE_IMAGE", "docker.io/99cloud/skyline:latest")
  )

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "skyline"
  private val dbUser = "skyline"
  private val dbPassword = Secrets.secret("skyline/db")
  private val skylinePassword = Secrets.secret("skyline/service")
  private val secretKey = Secrets.secret("skyline/secret-key")

  private val confDir = "/etc/skyline"
  private val caDir = "/etc/skyline/ca"
  private val logDir = "/var/log/skyline"
  private val stateDir = "/var/lib/orphera/skyline"
  private val pylibDir = "/var/lib/orphera/skyline/pylib"
  private val socketDir = "/var/lib/orphera/skyline/run"
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
      Mount(
        "/etc/ssl/certs/ca-certificates.crt",
        "/etc/ssl/certs/ca-certificates.crt",
        Access.ReadOnlyUnlabelled
      )
    ),
    env = List(
      "PYTHONPATH" -> pylibDir,
      "REQUESTS_CA_BUNDLE" -> "/etc/ssl/certs/ca-certificates.crt",
      "SSL_CERT_FILE" -> "/etc/ssl/certs/ca-certificates.crt"
    ),
    description = Some("OpenStack Skyline dashboard"),
    startTimeoutSeconds = 300,
    settleSeconds = 15
  )

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName DEFAULT CHARACTER SET utf8 DEFAULT COLLATE utf8_general_ci; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ALTER USER '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- the node --------------------------------------------------------

  private val pullScript =
    s"""${Pull.shellFunction(
        "SKYLINE_IMAGE or the Image in skyline_single_node.scala"
      )}
       |pull_retry ${skylineImage.ref} || exit 1
       |echo "image ready: $$(podman images -q ${skylineImage.ref} | head -1)"""".stripMargin

  // Same method as manila_single_node.scala: the last certificate Keystone presents is its CA; trust on first
  // use. update-ca-certificates also puts it in /etc/ssl/certs/ca-certificates.crt, which the container mounts.
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
       |cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |update-ca-certificates
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  private val dirsScript =
    s"""mkdir -p $confDir $caDir $logDir $stateDir $pylibDir $socketDir
       |echo "directories ready"""".stripMargin

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
       |  ssl_enabled: false
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
       |  # only to http://$fqdn:$port/api/openstack/skyline/api/v1/websso (trusted_dashboard in
       |  # keystone_single_node.scala), so open Skyline by that name.
       |  sso_enabled: true
       |  sso_protocols:
       |    - openid
       |  sso_region: $region
       |""".stripMargin

  // The documented bootstrap: the image's start script runs the migrations when KOLLA_BOOTSTRAP is set (even
  // empty) and exits.
  private val bootstrapScript =
    s"""podman rm -f skyline_bootstrap >/dev/null 2>&1 || true
       |RC=0
       |podman run --rm --name skyline_bootstrap --network host --user root -e KOLLA_BOOTSTRAP= -e PYTHONPATH=$pylibDir -e REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt -v $confDir/skyline.yaml:/etc/skyline/skyline.yaml:ro,z -v $logDir:/var/log/skyline:z -v $pylibDir:$pylibDir:ro,z -v /etc/ssl/certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro ${skylineImage.ref} >/tmp/skyline-bootstrap.log 2>&1 || RC=$$?
       |tail -n 25 /tmp/skyline-bootstrap.log
       |if [ "$$RC" != "0" ]; then echo "skyline_bootstrap failed (exit $$RC) - the output above names the reason" >&2; exit 1; fi
       |echo "skyline database migrated"""".stripMargin

  // After the container is up: if the catalog's public endpoints differ from those the last start saw,
  // restart it so nginx.conf is written again.
  private val catalogRestartScript =
    s"""set -e
       |NEW=$$(/usr/local/sbin/orphera-skyline-api catalog-hash)
       |OLD=$$(cat $stateDir/catalog.sha 2>/dev/null || true)
       |if [ "$$NEW" != "$$OLD" ]; then
       |  if [ -n "$$OLD" ]; then
       |    echo "the Keystone catalog changed since the last run; restarting skyline to write nginx.conf again"
       |    systemctl restart orphera-skyline.service
       |  fi
       |  echo "$$NEW" > $stateDir/catalog.sha
       |fi
       |echo "catalog hash $$NEW"""".stripMargin

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands: register | unregister | catalog-hash | login-check
  private val apiHelperPython =
    """#!/usr/bin/python3
import hashlib, json, ssl, sys, urllib.error, urllib.request

KS = 'https://@@KS@@:5000/v3'
SKY = 'http://localhost:@@PORT@@'
REGION = '@@REGION@@'
ADMIN_PW = '@@ADMINPW@@'
SKYLINE_PW = '@@SKYLINEPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')


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
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog-hash':
        catalog_hash()
    elif cmd == 'login-check':
        login_check()
    else:
        die('usage: orphera-skyline-api register | unregister | catalog-hash | login-check')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-skyline-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@PORT@@", port.toString)
        .replace("@@REGION@@", region)
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@SKYLINEPW@@", skylinePassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-skyline-api\necho wrote /usr/local/sbin/orphera-skyline-api\n"

  private val healthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/)
       |[ "$$CODE" = "200" ]""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if the
  // unit differs from what this playbook would write.
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("skyline-single-node-check")(
        stage("check-skyline", node)
          .task(s"check ${skylineContainer.unitName}")(
            unitResource.check(skylineContainer)
          )
          .build
      )
    else applyPlaybook

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("skyline-single-node")(
      stage("skyline-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-skyline", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("pull the Skyline image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("create the Skyline directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the Skyline API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register the Skyline user in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-skyline-api register"),
            timeoutSeconds = 120
          )
        )
        .task("install the PyMySQL driver with the image's Python")(
          Task.RunCommand(
            List("sh", "-c", installDriverScript),
            timeoutSeconds = 300
          )
        )
        .task("write skyline.yaml")(
          Task.WriteFile(
            content = skylineYaml,
            dest = s"$confDir/skyline.yaml",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("skyline_bootstrap (database migrations)")(
          Task
            .RunCommand(List("sh", "-c", bootstrapScript), timeoutSeconds = 600)
        )
        .task(s"write ${skylineContainer.unitName}")(skylineContainer.writeUnit)
        .task(s"start ${skylineContainer.unitName}")(skylineContainer.start)
        .task("restart skyline if the Keystone catalog changed")(
          Task.RunCommand(
            List("sh", "-c", catalogRestartScript),
            timeoutSeconds = 180
          )
        )
        .build,

      stage("confirm-skyline-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 180
          )
        )
        .task("the console, the API docs and an admin login answer")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-skyline-api login-check"),
            timeoutSeconds = 120
          )
        )
        .task("skyline confirmed")(
          Task.Debug(
            s"Skyline up at http://$fqdn:$port/ ($nodeIp): one container (apiserver + nginx) from ${skylineImage.ref}, database $dbName via $vip:3306, identity from $ksUrl, monitoring pages read $promUrl. Log in as admin (domain Default); the monitoring pages are not in the menu in this build, open /monitor-center/physical-node-admin. Test: manifests/test_skyline.sh"
          )
        )
        .build
    )
