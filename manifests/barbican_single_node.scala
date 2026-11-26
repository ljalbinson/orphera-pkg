// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Barbican (key manager: stores secrets, keys and certificates for other services and users),
// Kolla images under podman, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It
// replaced the cluster playbook of the same name; a second run changes nothing and restarts nothing, and
// `--check` shows what would change. Modelled on heat_single_node.scala, which explains the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/barbican_database.scala     # the barbican database and user (tst3)
//   orphera apply manifests/barbican_rabbitmq.scala     # the `barbican` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/barbican_single_node.scala  # this one (tst20)
//
// Where it runs: tst20, next to Heat. Both are small, and no VM is free, so they share the node. Heat's
// description and this one each own their own directories, containers, units and ports; the Podman
// packages, the Kolla cache mirror file and the Keystone CA in the system trust store are written by both,
// with the same content. To move Barbican, change `node` and `fqdn` below (and the teardown's `node`);
// nothing else depends on the location.
//
// What it builds:
//   - barbican-api     the REST API, behind Apache + mod_wsgi (the image's barbican-wsgi-api), port 9311, plain
//                      HTTP like the other APIs here
//   - barbican-worker  processes the orders (for example "make me a 256 bit AES key") from the queue
// The Keystone listener, which deletes a project's secrets when Keystone deletes the project, is left out: it
// needs Keystone to publish notifications to RabbitMQ, which it does not here.
//
// The secret store is the simple_crypto plugin: each secret is encrypted with a key-encrypting key (KEK) and
// kept in the database. This is for a lab. The KEK is derived (SHA-256, base64) from the secret
// `barbican/kek` in the secret store, so it is the same on every run; LOSE IT AND EVERY STORED SECRET IS
// UNREADABLE (back up the secret store). A real deployment uses a hardware module (PKCS#11) or Vault.
//
// What it adds to Keystone (done by keystoneEntries, repeatable): user `barbican` in project
// `service` with the admin role; the roles `creator`, `observer` and `audit` (the users of a project who may
// create, read and audit secrets; admin may do all); the service `barbican` of type `key-manager`.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (rabbitmq_single_node.scala)
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved. Heat on the same node is untouched.
//
// How it stays idempotent (as for Heat):
//   - Keystone's CA is compared as a certificate with the installed copy each run; each service's copy is
//     refreshed (and the service restarted) when it changes.
//   - barbican.conf holds secrets, so it is a secret file, one per service directory. The API's Apache vhost
//     and config.json depend on what the image has (the WSGI script, the Apache flavour), so they are
//     generated into a temporary directory and compared with the installed files.
//   - `barbican-manage db upgrade` is skipped while its stamp matches and the `secrets` table exists (a query
//     as the barbican user, through the VIP).
//   - Barbican is registered in Keystone before the units start. The registration is skipped while the
//     registration check passes.
//
// Facts about Barbican this was written from, to check if a run fails: the database connection is
// given in both [DEFAULT] sql_connection and [database] connection, because releases have used either;
// [queue] enable = true makes the API hand orders to the worker (false would run them inside the API); the
// image has barbican-wsgi-api (the run stops with a message if it does not); the Kolla images are the 2025.1
// ubuntu-noble ones, as for the other services; barbican.conf is plain text, a typed version needs a
// catalogue of the image's options (manifests/dump_oslo_options.sh barbican-api).
//
// Test-only credentials come from the secret store (barbican/db, barbican/service, barbican/rabbit,
// barbican/kek), as before.
object barbican_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val fqdn = "tst20.ljalbinson.com"
  private val apiPort = 9311

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "barbican"
  private val rabbitPassword = Secrets.secret("barbican/rabbit")
  private val rabbitVhost = "barbican"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "barbican"
  private val dbUser = "barbican"
  private val dbPassword = Secrets.secret("barbican/db")
  private val barbicanPassword = Secrets.secret("barbican/service")

  // 32 bytes, base64: what the simple_crypto plugin wants as its KEK. Derived from a stored secret so that it
  // is the same on every run and on a rebuilt node.
  private val kek: String =
    java.util.Base64.getEncoder.encodeToString(
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(
          Secrets
            .secret("barbican/kek")
            .reveal
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
    )

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/barbican"
  private val stateDir = "/var/lib/orphera/barbican"
  private val caDir = "/etc/kolla/barbican-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The two containers as data: the unit file text comes from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. They share their mounts apart
  // from their own config directory. Defined after confRoot, logDir and stateDir, which they read; a val
  // used before its definition is null in an object.
  private def barbicanContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/barbican", Access.ReadWrite),
        Mount(stateDir, "/var/lib/barbican", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The API image may set a non-root USER, which cannot open Apache's log files; start it as root.
      user = if service == "barbican-api" then Some("root") else None
    )
  // The API first: svcs.head below is the one whose barbican.conf the db upgrade reads.
  private val serviceNames = List("barbican-api", "barbican-worker")
  private val barbicanServices: List[Container] =
    serviceNames.map(barbicanContainer)

  private val apiImage =
    Image("barbican-api", Release.R2025_1, Base.UbuntuNoble, registry).ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // barbican.conf, plain text (see the header). The same file goes to both containers.
  private val barbicanConf: String =
    s"""# Managed by Orphera (barbican_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/barbican
       |use_stderr = true
       |host_href = http://$fqdn:$apiPort
       |bind_port = $apiPort
       |sql_connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |db_auto_create = false
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[queue]
       |enable = true
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = barbican
       |password = ${barbicanPassword.reveal}
       |cafile = /etc/barbican/ssl/ca.crt
       |
       |[secretstore]
       |namespace = barbican.secretstore.plugin
       |enabled_secretstore_plugins = store_crypto
       |
       |[crypto]
       |namespace = barbican.crypto.plugin
       |enabled_crypto_plugins = simple_crypto
       |
       |[simple_crypto_plugin]
       |kek = $kek
       |""".stripMargin

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

  private val images = barbicanServices.map(_.image.ref)

  // The files both services' config.json copy in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/barbican.conf", "dest": "/etc/barbican/barbican.conf", "owner": "barbican", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/barbican/ssl/ca.crt", "owner": "barbican", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/barbican", "owner": "barbican:kolla", "recurse": true}, {"path": "/var/lib/barbican", "owner": "barbican:barbican", "recurse": true}]"""
  private val workerConfigJson =
    s"""{"command": "barbican-worker --config-file /etc/barbican/barbican.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin

  // The API runs behind Apache + mod_wsgi like Placement and Nova; the WSGI script path and the Apache
  // flavour are read from the image, and the run stops if the image has no barbican-wsgi-api. The vhost and
  // config.json are generated into `out` (the real directory, or a temporary one for the guard to compare
  // with).
  private def kollaApiScript(out: String): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v barbican-wsgi-api' || true)
       |if [ -z "$$WSGI" ]; then echo "barbican-wsgi-api not found in $apiImage - see the header of barbican_single_node.scala" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/barbican-api.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |  # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |  if podman run --rm --entrypoint sh $apiImage -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/barbican-api.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $out/barbican-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/barbican-apache-error.log"
       |    CustomLog "$logDir/barbican-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess barbican-api group=barbican processes=2 threads=1 user=barbican
       |    WSGIProcessGroup barbican-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/barbican-api.conf", "dest": "$$APACHE_CONF", "owner": "barbican", "perm": "0600"}], $permissions}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  // Current when every file the script generates is installed with the same content.
  private def kollaApiCurrent(generate: String, dir: String): String =
    s"""T=$$(mktemp -d)
       |$generate >/dev/null
       |R=0
       |for f in $$T/*; do cmp -s $$f $dir/$$(basename $$f) || R=1; done
       |rm -rf $$T
       |exit $$R""".stripMargin

  // barbican-manage runs in a one-off container with barbican.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint barbican-manage -v $confRoot/barbican-api/barbican.conf:/etc/barbican/barbican.conf:ro,z -v $logDir:/var/log/kolla/barbican:z $apiImage db upgrade
       |echo "barbican database upgraded"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + barbicanConf)

  // True when the barbican database, queried as the barbican user through the VIP, has the `secrets` table.
  // The query runs with the image's own Python (the interpreter named on the first line of barbican-manage),
  // which has pymysql.
  private val dbInState =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v barbican-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |    c.cursor().execute('SELECT COUNT(*) FROM secrets')
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // Standard-library Python, as in the other services. Plain string filled in with replace().
  // Subcommands:
  //   catalog | secret-list | secret-cycle | order-cycle
  // secret-cycle stores a secret, reads its payload back, deletes it and checks it is gone. order-cycle asks
  // for a 256 bit AES key, which the WORKER has to make from the queue, waits for the order to be ACTIVE,
  // reads the 32 bytes of the key back, and deletes the secret and the order.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
BARBICAN = '@@BARBICAN@@'
ADMIN_PW = '@@ADMINPW@@'
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
        parsed = {}
    return status, parsed, headers


def fetch_raw(url, token, accept):
    req = urllib.request.Request(url, method='GET')
    req.add_header('X-Auth-Token', token)
    req.add_header('Accept', accept)
    try:
        r = urllib.request.urlopen(req, timeout=60)
        return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token'], parsed


def admin_token():
    return get_token('admin', ADMIN_PW, 'admin')[0]


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def secret_list():
    token = admin_token()
    s, p, _ = call('GET', BARBICAN + '/v1/secrets', None, token)
    if s != 200:
        die('secret list failed: HTTP %s %s' % (s, p))
    for sec in p.get('secrets', []):
        print('secret %s name=%s status=%s' % (sec.get('secret_ref', '').rsplit('/', 1)[-1], sec.get('name'), sec.get('status')))
    print('secrets: %d' % len(p.get('secrets', [])))


def gone(token, ref):
    s, _, _ = call('GET', ref, None, token)
    return s == 404


def secret_cycle():
    token = admin_token()
    payload = 'orphera-secret-%d' % int(time.time())
    s, p, _ = call('POST', BARBICAN + '/v1/secrets', {'name': 'orphera-test-%d' % int(time.time()), 'payload': payload, 'payload_content_type': 'text/plain'}, token)
    if s != 201:
        die('secret create failed: HTTP %s %s' % (s, p))
    ref = p['secret_ref']
    try:
        s, body = fetch_raw(ref + '/payload', token, 'text/plain')
        if s != 200 or body.decode() != payload:
            die('secret payload read back wrongly: HTTP %s %r' % (s, body[:80]))
    finally:
        call('DELETE', ref, None, token)
    if not gone(token, ref):
        die('secret %s is still there after the delete' % ref)
    print('secret cycle ok: %s stored, payload read back, deleted' % ref.rsplit('/', 1)[-1])


def order_cycle():
    token = admin_token()
    meta = {'name': 'orphera-test-key-%d' % int(time.time()), 'algorithm': 'aes', 'bit_length': 256, 'mode': 'cbc', 'payload_content_type': 'application/octet-stream'}
    s, p, _ = call('POST', BARBICAN + '/v1/orders', {'type': 'key', 'meta': meta}, token)
    if s not in (201, 202):
        die('order create failed: HTTP %s %s' % (s, p))
    order_ref = p['order_ref']
    secret_ref = None
    try:
        end = time.time() + 120
        status = None
        while time.time() < end:
            s, p, _ = call('GET', order_ref, None, token)
            if s == 200:
                status = p.get('status')
                if status == 'ACTIVE':
                    secret_ref = p.get('secret_ref')
                    break
                if status == 'ERROR':
                    die('order %s went to ERROR: %s %s' % (order_ref, p.get('error_status_code'), p.get('error_reason')))
            time.sleep(2)
        if secret_ref is None:
            die('order %s did not become ACTIVE within 120 s (last status %s); is barbican-worker running?' % (order_ref, status))
        s, body = fetch_raw(secret_ref + '/payload', token, 'application/octet-stream')
        if s != 200 or len(body) != 32:
            die('the key made by the order is not 32 bytes: HTTP %s, %d bytes' % (s, len(body)))
    finally:
        if secret_ref:
            call('DELETE', secret_ref, None, token)
        call('DELETE', order_ref, None, token)
    print('order cycle ok: a 256 bit AES key made by the worker (%s), read back as 32 bytes, deleted' % secret_ref.rsplit('/', 1)[-1])


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'catalog':
        catalog()
    elif cmd == 'secret-list':
        secret_list()
    elif cmd == 'secret-cycle':
        secret_cycle()
    elif cmd == 'order-cycle':
        order_cycle()
    else:
        die('usage: orphera-barbican-api catalog | secret-list | secret-cycle | order-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@BARBICAN@@", s"http://$fqdn:$apiPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-barbican-api"

  // The API accepts an admin token from Keystone (it validated it with the barbican user's credentials) and
  // reaches its database.
  private val acceptsToken = s"$helperPath secret-list >/dev/null 2>&1"
  private val waitAcceptsToken =
    s"""for i in $$(seq 1 24); do $helperPath secret-list && exit 0; sleep 5; done
       |$helperPath secret-list
       |exit 1""".stripMargin

  // One service: its resources (written before it starts) and the ones that decide when it restarts.
  private final case class Svc(
      unit: ApplyResource,
      resources: List[ApplyResource],
      deps: List[ApplyResource],
      conf: ApplyResource
  )

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = pullImages(
      "pull the Barbican images",
      images,
      hint = "the Image(...) values in barbican_single_node.scala",
      timeout = 2400
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = serverCa(
      "fetch Keystone's CA from its TLS handshake",
      ksFqdn,
      5000,
      caCert,
      hint = "apply keystone_single_node.scala (it must serve server-chain.crt)"
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val svcs = serviceNames.zip(barbicanServices).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/barbican.conf",
        Secret(
          barbicanConf
        ), // it holds the database, message bus, service and key-encrypting passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = fileCopy(
        s"$dir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, dirRes).notifies(unitService)
      val kollaJson = name match
        case "barbican-worker" =>
          file(
            s"$dir/config.json",
            workerConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
        case _ =>
          command(
            s"write the Apache vhost and the Kolla config.json for $name",
            kollaApiScript(dir),
            unless = kollaApiCurrent(kollaApiScript("$T"), dir),
            timeout = 180
          ).after(pulled, dirRes, logDirectory).notifies(unitService)
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      Svc(
        unitService,
        List(dirRes, conf, kollaCa, kollaJson, unitFile),
        List(conf, kollaCa, kollaJson, unitFile),
        conf
      )
    }
    val apiConf = svcs.head.conf

    val dbSync = command(
      "barbican-manage db upgrade",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val (keystoneHelper, ensured) = keystoneEntries(
      "register Barbican in Keystone",
      "barbican",
      barbicanPassword,
      List(
        KeystoneService("barbican", "key-manager", s"http://$fqdn:$apiPort")
      ),
      ksUrl,
      adminPassword,
      caCert,
      roles = List("creator", "observer", "audit")
    )
    val registered = ensured.after(ca)

    val running = svcs.map { s =>
      s.unit.after((dbSync :: registered :: s.deps)*)
    }

    // The API answers its version document without a token (200, or 300 for a list of versions).
    val answering = waitForHttp(
      "barbican-api answers on its port",
      s"http://localhost:$apiPort/",
      status = List(200, 300),
      timeout = 120,
      hint = s"see 'journalctl -xeu orphera-barbican-api' and $logDir"
    ).after(running*)
    val authenticating = command(
      "the API accepts an admin token and reaches its database",
      waitAcceptsToken,
      unless = acceptsToken,
      timeout = 180
    ).after((answering :: running)*)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory
    ) ++ svcs.flatMap(_.resources) ++ List(
      dbSync,
      helperFile,
      keystoneHelper,
      registered
    ) ++
      running ++ List(answering, authenticating)
