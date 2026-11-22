// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Heat (orchestration: whole stacks of networks, servers and volumes from one template),
// Kolla images under podman, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It
// replaced the cluster playbook of the same name; a second run changes nothing and restarts nothing, and
// `--check` shows what would change. Modelled on cinder_single_node.scala and nova_single_node.scala, which
// explain the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/heat_database.scala     # the heat database and user (tst3)
//   orphera apply manifests/heat_rabbitmq.scala     # the `heat` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/heat_single_node.scala  # this one (tst20)
//
// Where it runs: tst20, which had no role. Heat is three small Python services (the API, the CloudFormation
// compatible API and the engine), so one 4 GB VM holds all of it. To move it, change `node` and `fqdn`
// below (and the teardown's `node`); nothing else depends on the location.
//
// What it builds:
//   - heat-api       the REST API (Apache + mod_wsgi when the image has heat-wsgi-api, else the standalone
//                    heat-api), port 8004, plain HTTP like the other APIs here
//   - heat-api-cfn   the CloudFormation compatible API (heat-wsgi-api-cfn or heat-api-cfn), port 8000; the
//                    instances of a stack call it for wait conditions and metadata
//   - heat-engine    does the work: reads the template, calls Nova, Neutron, Cinder ... as the stack's owner
//                    through a Keystone trust, and keeps the state in the database
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `heat` in project `service`
// with the admin role; the stack user domain `heat` with its admin user `heat_domain_admin` (Heat makes
// the users that instances use for metadata there, so they never touch the Default domain); the roles
// `heat_stack_owner` and `heat_stack_user`; the services `heat` (type orchestration, endpoint
// .../v1/%(tenant_id)s) and `heat-cfn` (type cloudformation, .../v1).
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (rabbitmq_single_node.scala)
//   - Nova (nova_single_node.scala) and Neutron: a stack that makes servers or networks needs them; the
//     test stack here makes only a random string and a Nova flavor
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// How it stays idempotent (as for Cinder and Nova):
//   - Keystone's CA is compared as a certificate with the installed copy each run; each service's copy is
//     refreshed (and the service restarted) when it changes.
//   - heat.conf holds secrets, so it is a secret file, one per service directory. The two APIs' Apache vhost
//     and config.json depend on what the image has (the WSGI script, the Apache flavour), so they are
//     generated into a temporary directory and compared with the installed files.
//   - `heat-manage db_sync` is skipped while its stamp matches and the `stack` table exists (a query as the
//     heat user, through the VIP).
//   - Heat is registered in Keystone before the units start (the stack domain must exist before the engine
//     does). The registration is skipped while the helper's `registered` check passes.
//
// Facts about Heat this was written from, to check if a run fails: heat.conf needs stack_user_domain_name,
// stack_domain_admin and its password (the domain admin made by `register`); trusts are the default way the
// engine acts for a user (deferred_auth_method), and trusts_delegated_roles is left unset, which delegates
// all of the user's roles; the Kolla images are the 2025.1 ubuntu-noble ones, as for the other services;
// heat.conf is plain text, a typed version needs a catalogue of the image's options
// (manifests/dump_oslo_options.sh heat-api).
//
// Test-only credentials come from the secret store (heat/db, heat/service, heat/rabbit,
// heat/domain-admin), as before.
object heat_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst20"
  private val fqdn = "tst20.ljalbinson.com"
  private val apiPort = 8004
  private val cfnPort = 8000

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "heat"
  private val rabbitPassword = Secrets.secret("heat/rabbit")
  private val rabbitVhost = "heat"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "heat"
  private val dbUser = "heat"
  private val dbPassword = Secrets.secret("heat/db")
  private val heatPassword = Secrets.secret("heat/service")
  private val domainAdminPassword = Secrets.secret("heat/domain-admin")

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/heat"
  private val stateDir = "/var/lib/orphera/heat"
  private val caDir = "/etc/kolla/heat-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The three containers as data: the unit file text comes from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. They share their mounts apart
  // from their own config directory. Defined after confRoot, logDir and stateDir, which they read; a val
  // used before its definition is null in an object.
  private def heatContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/heat", Access.ReadWrite),
        Mount(stateDir, "/var/lib/heat", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The API images may set a non-root USER, which cannot open Apache's log files; start them as root.
      user = if service.startsWith("heat-api") then Some("root") else None
    )
  private val serviceNames = List("heat-engine", "heat-api", "heat-api-cfn")
  private val heatServices: List[Container] = serviceNames.map(heatContainer)

  private def imageRef(service: String): String =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val apiImage = imageRef("heat-api")
  private val cfnImage = imageRef("heat-api-cfn")

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  // heat.conf, plain text (see the header). The same file goes to all three containers. The stack user
  // domain and its admin are made by the helper's `register`. `trustee` and the `clients` groups are how the
  // engine reaches Keystone (and, through the catalog, Nova, Neutron and the rest) as the stack's owner.
  private val heatConf: String =
    s"""# Managed by Orphera (heat_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/heat
       |use_stderr = true
       |heat_metadata_server_url = http://$fqdn:$cfnPort
       |heat_waitcondition_server_url = http://$fqdn:$cfnPort/v1/waitcondition
       |stack_user_domain_name = heat
       |stack_domain_admin = heat_domain_admin
       |stack_domain_admin_password = ${domainAdminPassword.reveal}
       |num_engine_workers = 2
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[heat_api]
       |bind_host = 0.0.0.0
       |bind_port = $apiPort
       |workers = 2
       |
       |[heat_api_cfn]
       |bind_host = 0.0.0.0
       |bind_port = $cfnPort
       |workers = 2
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = heat
       |password = ${heatPassword.reveal}
       |cafile = /etc/heat/ssl/ca.crt
       |
       |[trustee]
       |auth_type = password
       |auth_url = $ksUrl
       |username = heat
       |password = ${heatPassword.reveal}
       |user_domain_name = Default
       |cafile = /etc/heat/ssl/ca.crt
       |
       |[clients_keystone]
       |auth_uri = $ksUrl
       |ca_file = /etc/heat/ssl/ca.crt
       |
       |[clients]
       |ca_file = /etc/heat/ssl/ca.crt
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

  private val images = heatServices.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in heat_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(
        " "
      )}; do podman image exists $$img || exit 1; done"""

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

  // The files every service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/heat.conf", "dest": "/etc/heat/heat.conf", "owner": "heat", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/heat/ssl/ca.crt", "owner": "heat", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/heat", "owner": "heat:kolla", "recurse": true}, {"path": "/var/lib/heat", "owner": "heat:heat", "recurse": true}]"""
  private val engineConfigJson =
    s"""{"command": "heat-engine --config-file /etc/heat/heat.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin

  // The two APIs run behind Apache + mod_wsgi like Placement and Nova when the image has the WSGI script,
  // else as the standalone program. Which one is read from the image. The vhost (WSGI case only) and
  // config.json are generated into `out` (the real directory, or a temporary one for the guard to compare
  // with). `svc` is `api` or `api-cfn`.
  private def kollaWebScript(
      svc: String,
      wsgiCmd: String,
      standalone: String,
      port: Int,
      image: String,
      out: String
  ): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $image -c "command -v $wsgiCmd" || true)
       |if [ -n "$$WSGI" ]; then
       |  if podman run --rm --entrypoint sh $image -c 'test -d /etc/apache2/conf-enabled'; then
       |    APACHE_CONF=/etc/apache2/conf-enabled/heat-$svc.conf
       |    APACHE_CMD="apache2 -DFOREGROUND"
       |    # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |    if podman run --rm --entrypoint sh $image -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |  else
       |    APACHE_CONF=/etc/httpd/conf.d/heat-$svc.conf
       |    APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |  fi
       |  echo "$svc: wsgi script $$WSGI; apache config $$APACHE_CONF"
       |cat > $out/heat-$svc.conf <<WSGICONF
       |Listen 0.0.0.0:$port
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$port>
       |    ErrorLog "$logDir/heat-$svc-apache-error.log"
       |    CustomLog "$logDir/heat-$svc-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess heat-$svc group=heat processes=2 threads=1 user=heat
       |    WSGIProcessGroup heat-$svc
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/heat-$svc.conf", "dest": "$$APACHE_CONF", "owner": "heat", "perm": "0600"}], $permissions}
       |JSON
       |else
       |  echo "$svc: no $wsgiCmd in $image; running the standalone $standalone"
       |cat > $out/config.json <<JSON
       |{"command": "$standalone --config-file /etc/heat/heat.conf", "config_files": [$commonFiles], $permissions}
       |JSON
       |fi
       |echo "kolla config written under $out"""".stripMargin

  // Current when every file the script generates is installed with the same content.
  private def kollaWebCurrent(generate: String, dir: String): String =
    s"""T=$$(mktemp -d)
       |$generate >/dev/null
       |R=0
       |for f in $$T/*; do cmp -s $$f $dir/$$(basename $$f) || R=1; done
       |rm -rf $$T
       |exit $$R""".stripMargin

  // heat-manage runs in a one-off container with heat.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint heat-manage -v $confRoot/heat-api/heat.conf:/etc/heat/heat.conf:ro,z -v $logDir:/var/log/kolla/heat:z $apiImage db_sync
       |echo "heat database synced"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + heatConf)

  // True when the heat database, queried as the heat user through the VIP, has the `stack` table. The query
  // runs with the image's own Python (the interpreter named on the first line of heat-manage), which has
  // pymysql.
  private val dbInState =
    s"""podman run --rm -i --network host --entrypoint sh $apiImage -c 'exec $$(head -1 "$$(command -v heat-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |    c.cursor().execute('SELECT COUNT(*) FROM stack')
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
  //   register | registered | unregister | catalog | stack-list | engines-up | stack-cycle [basic]
  // stack-cycle makes a stack from a template (a random string, and a Nova flavor unless `basic`), waits for
  // CREATE_COMPLETE, reads an output back, deletes the stack and waits until it is gone. The flavor is made
  // by the engine through a Keystone trust, so a pass means the whole path works: API, RabbitMQ, engine,
  // database, trust, Nova.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
HEAT = '@@HEAT@@'
CFN = '@@CFN@@'
ADMIN_PW = '@@ADMINPW@@'
HEAT_PW = '@@HEATPW@@'
DOMAIN_ADMIN_PW = '@@DOMAINADMINPW@@'
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


def ensure(token, what, path, key, body, create_key):
    # the first object that matches `path`, or the one made from `body`
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def ensure_service(token, svc_type, name, url):
    service = ensure(token, 'services', '/services?type=' + svc_type, 'services', {'service': {'name': name, 'type': svc_type}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint of %s failed: HTTP %s %s' % (iface, name, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=heat', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'heat', 'domain_id': 'default', 'password': HEAT_PW}}, token)
        if s != 201:
            die('creating user heat failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': HEAT_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to heat failed: HTTP %s %s' % (s, p))
    for role in ('heat_stack_owner', 'heat_stack_user'):
        ensure(token, 'roles', '/roles?name=' + role, 'roles', {'role': {'name': role}}, 'role')
    domain = ensure(token, 'domains', '/domains?name=heat', 'domains', {'domain': {'name': 'heat', 'description': 'Owns the users and projects that Heat makes for stacks'}}, 'domain')
    dadmin = find(token, '/users?name=heat_domain_admin&domain_id=' + domain['id'], 'users')
    if dadmin is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'heat_domain_admin', 'domain_id': domain['id'], 'password': DOMAIN_ADMIN_PW}}, token)
        if s != 201:
            die('creating user heat_domain_admin failed: HTTP %s %s' % (s, p))
        dadmin = p['user']
    else:
        call('PATCH', KS + '/users/' + dadmin['id'], {'user': {'password': DOMAIN_ADMIN_PW}}, token)
    s, p, _ = call('PUT', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin on domain heat to heat_domain_admin failed: HTTP %s %s' % (s, p))
    ensure_service(token, 'orchestration', 'heat', HEAT + '/%(tenant_id)s')
    ensure_service(token, 'cloudformation', 'heat-cfn', CFN)
    print('registered heat in keystone (user heat, domain heat with heat_domain_admin, roles heat_stack_owner and heat_stack_user, services orchestration and cloudformation with 3 endpoints each)')


def registered():
    # Exit 0 only when everything register() makes is in place: the heat user can get a token in project
    # service, the roles, the stack domain and its admin (who can authenticate and holds admin on the
    # domain), and both services with their three endpoints. Makes no change.
    get_token('heat', HEAT_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for role in ('heat_stack_owner', 'heat_stack_user'):
        if find(token, '/roles?name=' + role, 'roles') is None:
            die('role %s missing' % role)
    domain = find(token, '/domains?name=heat', 'domains')
    if domain is None:
        die('no domain heat')
    dadmin = find(token, '/users?name=heat_domain_admin&domain_id=' + domain['id'], 'users')
    if dadmin is None:
        die('no user heat_domain_admin')
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'heat_domain_admin', 'domain': {'name': 'heat'}, 'password': DOMAIN_ADMIN_PW}}}}}
    s, p, _ = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('heat_domain_admin cannot get a token: HTTP %s %s' % (s, p))
    admin_role = find(token, '/roles?name=admin', 'roles')
    s, p, _ = call('HEAD', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
    if s != 204:
        die('heat_domain_admin does not hold admin on domain heat: HTTP %s' % s)
    for svc_type, url in (('orchestration', HEAT + '/%(tenant_id)s'), ('cloudformation', CFN)):
        service = find(token, '/services?type=' + svc_type, 'services')
        if service is None:
            die('no service of type ' + svc_type)
        for iface in ('public', 'internal', 'admin'):
            ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
            if ep is None or ep['url'] != url:
                die('endpoint %s of %s missing or wrong' % (iface, svc_type))
    print('heat is registered in keystone')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for svc_type in ('orchestration', 'cloudformation'):
        service = find(token, '/services?type=' + svc_type, 'services')
        if service is not None:
            call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=heat', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    domain = find(token, '/domains?name=heat', 'domains')
    if domain is not None:
        # Heat makes users and projects in this domain for its stacks: remove them all, then the domain.
        s, p, _ = call('GET', KS + '/users?domain_id=' + domain['id'], None, token)
        for u in p.get('users', []):
            call('DELETE', KS + '/users/' + u['id'], None, token)
        s, p, _ = call('GET', KS + '/projects?domain_id=' + domain['id'], None, token)
        for pr in p.get('projects', []):
            call('DELETE', KS + '/projects/' + pr['id'], None, token)
        call('PATCH', KS + '/domains/' + domain['id'], {'domain': {'enabled': False}}, token)
        call('DELETE', KS + '/domains/' + domain['id'], None, token)
    print('removed heat from keystone (services orchestration and cloudformation, their endpoints, user heat, domain heat)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def heat_session():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    return token, HEAT + '/' + parsed['token']['project']['id']


def stack_list():
    token, base = heat_session()
    s, p, _ = call('GET', base + '/stacks', None, token)
    if s != 200:
        die('stack list failed: HTTP %s %s' % (s, p))
    for st in p.get('stacks', []):
        print('stack %s name=%s status=%s' % (st.get('id'), st.get('stack_name'), st.get('stack_status')))
    print('stacks: %d' % len(p.get('stacks', [])))


def engines_up():
    token, base = heat_session()
    s, p, _ = call('GET', base + '/services', None, token)
    if s != 200:
        die('service list failed: HTTP %s %s' % (s, p))
    up = sorted(set(x.get('host') for x in p.get('services', []) if x.get('binary') == 'heat-engine' and x.get('status') == 'up'))
    if not up:
        die('no heat-engine is up: %s' % p)
    print('heat-engine is up on %s' % ', '.join(up))


def stack_cycle(basic):
    token, base = heat_session()
    name = 'orphera-test-%d' % int(time.time())
    resources = {'secret': {'type': 'OS::Heat::RandomString', 'properties': {'length': 12}}}
    outputs = {'secret': {'value': {'get_attr': ['secret', 'value']}}}
    if not basic:
        resources['flavor'] = {'type': 'OS::Nova::Flavor', 'properties': {'ram': 64, 'vcpus': 1, 'disk': 1, 'is_public': False}}
    template = {'heat_template_version': '2018-08-31', 'resources': resources, 'outputs': outputs}
    s, p, _ = call('POST', base + '/stacks', {'stack_name': name, 'template': template, 'timeout_mins': 5}, token)
    if s != 201:
        die('stack create failed: HTTP %s %s' % (s, p))
    stack_id = p['stack']['id']
    url = '%s/stacks/%s/%s' % (base, name, stack_id)
    try:
        end = time.time() + 240
        status = None
        value = None
        while time.time() < end:
            s, p, _ = call('GET', url, None, token)
            if s == 200:
                st = p['stack']
                status = st['stack_status']
                if status == 'CREATE_COMPLETE':
                    for o in st.get('outputs', []):
                        if o.get('output_key') == 'secret':
                            value = o.get('output_value')
                    break
                if status.endswith('FAILED'):
                    die('stack %s: %s (%s)' % (name, status, st.get('stack_status_reason')))
            time.sleep(3)
        if status != 'CREATE_COMPLETE':
            die('stack %s did not reach CREATE_COMPLETE within 240 s (last status %s)' % (name, status))
        if not value:
            die('stack %s has no output `secret`' % name)
    finally:
        call('DELETE', url, None, token)
    end = time.time() + 240
    while time.time() < end:
        s, p, _ = call('GET', url, None, token)
        # Heat keeps answering 200 with DELETE_COMPLETE for a deleted stack read by name and id; 404 is
        # accepted too, in case a release hides it.
        if s == 404 or (s == 200 and p['stack']['stack_status'] == 'DELETE_COMPLETE'):
            print('stack cycle ok: %s created (%s resources), output read back, stack deleted' % (name, len(resources)))
            return
        if s == 200 and p['stack']['stack_status'] == 'DELETE_FAILED':
            die('stack %s: DELETE_FAILED (%s)' % (name, p['stack'].get('stack_status_reason')))
        time.sleep(3)
    die('stack %s was not deleted within 240 s' % name)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    args = sys.argv[2:]
    if cmd == 'register':
        register()
    elif cmd == 'registered':
        registered()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'stack-list':
        stack_list()
    elif cmd == 'engines-up':
        engines_up()
    elif cmd == 'stack-cycle':
        stack_cycle(args == ['basic'])
    else:
        die('usage: orphera-heat-api register | registered | unregister | catalog | stack-list | engines-up | stack-cycle [basic]')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@HEAT@@", s"http://$fqdn:$apiPort/v1")
      .replace("@@CFN@@", s"http://$fqdn:$cfnPort/v1")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@HEATPW@@", heatPassword.reveal)
      .replace("@@DOMAINADMINPW@@", domainAdminPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-heat-api"

  // Both APIs answer their version document without a token (300 for a list of versions, or 200).
  private val healthy =
    s"""for port in $apiPort $cfnPort; do CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$$port/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ] || exit 1; done"""
  private val waitHealthy =
    s"""for i in $$(seq 1 24); do
       |  ( $healthy ) && exit 0
       |  sleep 5
       |done
       |echo "heat-api or heat-api-cfn does not answer on $apiPort / $cfnPort - see 'journalctl -xeu orphera-heat-api' and $logDir" >&2
       |exit 1""".stripMargin

  private val enginesUp = s"$helperPath engines-up >/dev/null 2>&1"
  private val waitEnginesUp =
    s"""for i in $$(seq 1 24); do $helperPath engines-up && exit 0; sleep 5; done
       |$helperPath engines-up
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
    val pulled = command(
      "pull the Heat images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val svcs = serviceNames.zip(heatServices).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = service(c.unitName)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/heat.conf",
        Secret(
          heatConf
        ), // it holds the database, message bus and service passwords
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
        case "heat-engine" =>
          file(
            s"$dir/config.json",
            engineConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
        case _ =>
          val (svc, wsgi, standalone, port, image) =
            if name == "heat-api" then
              ("api", "heat-wsgi-api", "heat-api", apiPort, apiImage)
            else
              (
                "api-cfn",
                "heat-wsgi-api-cfn",
                "heat-api-cfn",
                cfnPort,
                cfnImage
              )
          val real = kollaWebScript(svc, wsgi, standalone, port, image, dir)
          val temporary =
            kollaWebScript(svc, wsgi, standalone, port, image, "$T")
          command(
            s"write the Apache vhost and the Kolla config.json for $name",
            real,
            unless = kollaWebCurrent(temporary, dir),
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
    val apiConf = svcs(1).conf

    val dbSync = command(
      "heat-manage db_sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin, heat and domain admin passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start: the engine needs the stack domain.
    val registered = command(
      "register Heat in Keystone (the stack domain and its admin)",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val running = svcs.map { s =>
      s.unit.after((dbSync :: registered :: s.deps)*)
    }

    val answering = command(
      "heat-api and heat-api-cfn answer on their ports",
      waitHealthy,
      unless = healthy,
      timeout = 180
    ).after(running*)
    val reporting = command(
      "the engine reports in through the API",
      waitEnginesUp,
      unless = enginesUp,
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
    ) ++ svcs.flatMap(_.resources) ++ List(dbSync, helperFile, registered) ++
      running ++ List(answering, reporting)
