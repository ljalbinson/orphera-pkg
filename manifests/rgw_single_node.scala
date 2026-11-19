// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Object storage: the Ceph RADOS Gateway (RGW), with its Swift API and its S3 API both authenticated by
// Keystone, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows what would
// change. RGW runs as a cephadm service on the Ceph cluster itself, so there is no new VM and no Kolla image:
// the description runs on tst0 only. Modelled on manila_single_node.scala (which also prepares Ceph from tst0)
// and barbican_single_node.scala (the Keystone helper); read their headers for the conventions.
//
//   orphera apply manifests/rgw_single_node.scala --check
//   orphera apply manifests/rgw_single_node.scala
//
// What it builds (all on tst0, 10.10.5.12):
//   - the cephadm service `rgw.orphera`: ONE RGW daemon placed on the Ceph host with the address `rgwIp`,
//     the beast front end on port 8080, plain HTTP like the other APIs here. The daemon is given the host's
//     CA bundle (which includes Keystone's test CA, installed below) so it can verify Keystone's TLS.
//   - the RGW options, set with `ceph config set client.rgw.orphera ...`: Keystone as the identity service
//     (v3, user `rgw` in project `service`), the accepted roles `member` and `admin`, one namespace per
//     project (implicit tenants), the account in the Swift URL, and S3 requests checked against Keystone's
//     EC2 credentials. The daemon is restarted only when this configuration changes (a hash of it is kept in
//     /var/lib/orphera/rgw-applied-sha), so a repeated run does not interrupt service. The hash also covers
//     /var/lib/orphera/rgw-keystone-state, which `register` updates when the `rgw` user is new or its password was
//     set: RGW keeps the admin token it got at start-up, so after a Keystone rebuild it needs a restart.
//   The pools (.rgw.root, default.rgw.*) are made by RGW itself when it first runs.
//
// What it adds to Keystone (done by the helper's `register`, skipped while `registered` passes): user `rgw` in
// project `service` with the admin role; the service `swift` of type `object-store` with the endpoints
// http://<rgwIp>:8080/swift/v1/AUTH_%(project_id)s (public and internal) and .../swift/v1 (admin); the
// service `s3` of type `s3` with the endpoint http://<rgwIp>:8080.
//
// How people use it:
//   Swift   a token for a project with the role member or admin, then the URL in the catalog under
//           object-store (`openstack container create`, `swift upload`...)
//   S3      `openstack ec2 credentials create` gives an access key and a secret key for the project; use them
//           with any S3 client against http://<rgwIp>:8080, path-style addressing, region us-east-1.
//
// Prerequisites, not checked here:
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - Ceph healthy with OSDs and the orchestrator (cephadm_*.scala); tst0 has cephadm and an agent
//   - Galera, RabbitMQ and the other OpenStack services are NOT needed
//
// How it stays idempotent (as for the other services):
//   - Each step that changes the node is a command with a guard that tests what it makes: Keystone's CA
//     compared as a certificate with the installed copy and the trust store, the Keystone registration (the
//     helper's `registered`), and the RGW configuration (the hash above equals the one last applied, and the
//     orchestrator lists the daemon as running). The step that applies the spec and options ends by waiting
//     for the Swift capabilities document to answer three times in a row.
//   - The last two steps make a container and an object through Swift with a Keystone token, and a bucket and
//     an object through S3 with EC2 credentials made for the run and removed again; each is skipped while its
//     round trip works.
//
// Facts about RGW this was written from, to check if the first run fails: `ceph orch apply -i` with a spec of
// service_type rgw (service_id, placement.hosts, spec.rgw_frontend_port, extra_container_args); the daemon
// reads its options from the section `client.rgw.orphera`; the Ceph image is CentOS based, so libcurl reads
// its CA bundle from /etc/pki/tls/certs/ca-bundle.crt (the host's /etc/ssl/certs/ca-certificates.crt is
// mounted there); `rgw_s3_auth_use_keystone` makes RGW check S3 signatures with Keystone; the account is in
// the Swift URL (`rgw_swift_account_in_url`). The options are stored in the monitors' configuration database,
// and so is the service password: acceptable for this lab, not for production.
//
// Test-only credentials come from the secret store (rgw/service), made on first use.
object rgw_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst0"
  private val rgwIp = "10.10.5.12"
  private val rgwPort = 8080
  private val serviceId = "orphera"

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val rgwPassword = Secrets.secret("rgw/service")

  private val caDir = "/etc/orphera/rgw-ca"
  private val caCert = s"$caDir/ca.crt"
  private val stateDir = "/var/lib/orphera"

  private val hostPackages =
    List("openssl", "curl", "ca-certificates", "python3")

  // A fresh node may still be running unattended-upgrades (see disable_unattended_upgrades.scala); wait for it
  // before touching packages.
  private val aptIdle =
    """! pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null && ! pgrep -x apt-get >/dev/null && ! pgrep -x apt >/dev/null && ! pgrep -x dpkg >/dev/null"""
  private val waitAptIdleScript =
    s"""for i in $$(seq 1 180); do
       |  if $aptIdle; then
       |    echo "apt and dpkg are idle"
       |    exit 0
       |  fi
       |  sleep 5
       |done
       |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
       |exit 1""".stripMargin

  // Keystone's CA: the last certificate it presents (trust on first use), which must be self-signed.
  // `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem, each holding the certificate only: the text s_client
  // prints after the last one differs on every handshake, and would make the comparison fail. Installed into
  // the host's trust store, which RGW then gets as its bundle.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""
  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""
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

  // Standard-library Python, as in the other service descriptions. Plain string filled in with replace().
  // Subcommands: register | registered | unregister | catalog | swift-cycle | s3-cycle
  // registered: read-only; user rgw gets a token and both services have the endpoints register makes
  // swift-cycle: with an admin token, the object-store URL from the catalog (so the %(project_id)s
  // substitution is exercised), a container and an object are created, read back, and deleted.
  // s3-cycle: EC2 credentials are made in Keystone for the admin user and project, a bucket and an object are
  // created with an AWS Signature V4 request, read back and deleted, and the credentials are removed.
  private val apiHelperPython =
    """#!/usr/bin/python3
import hashlib, hmac, json, os, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
RGW = 'http://@@RGW@@'
ADMIN_PW = '@@ADMINPW@@'
RGW_PW = '@@RGWPW@@'
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


def raw(method, url, headers, data=b''):
    # data is bytes, never None, so a PUT carries Content-Length: 0
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
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


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def ensure(token, what, path, key, body, create_key):
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def ensure_endpoint(token, service, iface, url):
    ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
    if ep is None:
        s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
        if s != 201:
            die('creating %s endpoint for %s failed: HTTP %s %s' % (iface, service['type'], s, p))
    elif ep['url'] != url:
        call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)


STATE_FILE = '/var/lib/orphera/rgw-keystone-state'


def note_identity(user_id, patched):
    # RGW keeps the admin token it got for user rgw until it restarts. A new rgw user (Keystone was rebuilt) or
    # a changed password (Keystone revokes the old tokens) makes that token useless, so the description mixes this
    # file into the hash that decides whether to restart the daemon: its number goes up when either happens.
    old = ''
    try:
        with open(STATE_FILE) as f:
            old = f.read()
    except OSError:
        pass
    parts = old.split()
    gen = int(parts[1]) if len(parts) == 2 and parts[1].isdigit() else 0
    if len(parts) != 2 or parts[0] != user_id or patched:
        gen += 1
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, 'w') as f:
        f.write('%s %d\n' % (user_id, gen))


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=rgw', 'users')
    patched = False
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'rgw', 'domain_id': 'default', 'password': RGW_PW}}, token)
        if s != 201:
            die('creating user rgw failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        # Setting a password makes Keystone revoke every token the user holds. RGW keeps its admin token and
        # does not renew it on the s3tokens path, so S3 requests then fail until RGW restarts. Only set the
        # password when the current one does not work.
        probe = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'rgw', 'domain': {'name': 'Default'}, 'password': RGW_PW}}}}}
        status, _, _ = call('POST', KS + '/auth/tokens', probe)
        if status != 201:
            call('PATCH', KS + '/users/' + user['id'], {'user': {'password': RGW_PW}}, token)
            patched = True
    note_identity(user['id'], patched)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to rgw failed: HTTP %s %s' % (s, p))
    ensure(token, 'roles', '/roles?name=member', 'roles', {'role': {'name': 'member'}}, 'role')
    swift = ensure(token, 'services', '/services?type=object-store', 'services', {'service': {'name': 'swift', 'type': 'object-store'}}, 'service')
    ensure_endpoint(token, swift, 'public', RGW + '/swift/v1/AUTH_%(project_id)s')
    ensure_endpoint(token, swift, 'internal', RGW + '/swift/v1/AUTH_%(project_id)s')
    ensure_endpoint(token, swift, 'admin', RGW + '/swift/v1')
    s3 = ensure(token, 'services', '/services?type=s3', 'services', {'service': {'name': 's3', 'type': 's3'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ensure_endpoint(token, s3, iface, RGW)
    print('registered rgw in keystone (user rgw, role member, service types object-store and s3, endpoints)')


def registered():
    get_token('rgw', RGW_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for typ, urls in (('object-store', {'public': RGW + '/swift/v1/AUTH_%(project_id)s', 'internal': RGW + '/swift/v1/AUTH_%(project_id)s', 'admin': RGW + '/swift/v1'}), ('s3', {'public': RGW, 'internal': RGW, 'admin': RGW})):
        service = find(token, '/services?type=' + typ, 'services')
        if service is None:
            die('service type %s is not registered' % typ)
        for iface, url in urls.items():
            ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
            if ep is None or ep['url'] != url:
                die('%s %s endpoint is missing or differs' % (typ, iface))
    print('rgw is registered in keystone')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for typ in ('object-store', 's3'):
        service = find(token, '/services?type=' + typ, 'services')
        if service is not None:
            call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=rgw', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed rgw from keystone (services object-store and s3, their endpoints, user rgw)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))
    for c in p['token']['catalog']:
        if c['type'] == 'object-store':
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    print('object-store url: ' + e['url'])


def swift_cycle():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    url = None
    for c in parsed['token']['catalog']:
        if c['type'] == 'object-store':
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    url = e['url']
    if not url:
        die('no object-store endpoint in the catalog; run register')
    if '%(' in url:
        die('the catalog URL still has an unreplaced placeholder: %s' % url)
    h = {'X-Auth-Token': token}
    name = 'orphera-test-%d' % int(time.time())
    payload = ('orphera-swift-%d' % int(time.time())).encode()
    s, b = raw('PUT', '%s/%s' % (url, name), h)
    if s not in (201, 202):
        die('container create failed: HTTP %s %r (is rgw_keystone configured, and the role member or admin?)' % (s, b[:200]))
    try:
        s, b = raw('PUT', '%s/%s/obj' % (url, name), h, payload)
        if s != 201:
            die('object upload failed: HTTP %s %r' % (s, b[:200]))
        s, b = raw('GET', '%s/%s/obj' % (url, name), h)
        if s != 200 or b != payload:
            die('object read back wrongly: HTTP %s %r' % (s, b[:80]))
    finally:
        raw('DELETE', '%s/%s/obj' % (url, name), h)
        raw('DELETE', '%s/%s' % (url, name), h)
    s, _ = raw('GET', '%s/%s' % (url, name), h)
    if s != 404:
        die('container %s is still there after the delete: HTTP %s' % (name, s))
    print('swift cycle ok: container and object made at %s, read back, deleted' % url)


def s3_call(method, path, ak, sk, body=b''):
    host = '@@RGW@@'
    amz = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    date = amz[:8]
    phash = hashlib.sha256(body).hexdigest()
    signed = 'host;x-amz-content-sha256;x-amz-date'
    canon = '\n'.join([method, path, '', 'host:%s\nx-amz-content-sha256:%s\nx-amz-date:%s\n' % (host, phash, amz), signed, phash])
    scope = '%s/us-east-1/s3/aws4_request' % date
    sts = '\n'.join(['AWS4-HMAC-SHA256', amz, scope, hashlib.sha256(canon.encode()).hexdigest()])

    def mac(key, msg):
        return hmac.new(key, msg.encode(), hashlib.sha256).digest()

    k = mac(('AWS4' + sk).encode(), date)
    k = mac(k, 'us-east-1')
    k = mac(k, 's3')
    k = mac(k, 'aws4_request')
    sig = hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()
    headers = {'x-amz-content-sha256': phash, 'x-amz-date': amz,
               'Authorization': 'AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s' % (ak, scope, signed, sig)}
    return raw(method, 'http://' + host + path, headers, body)


def s3_cycle():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    uid = parsed['token']['user']['id']
    pid = parsed['token']['project']['id']
    s, p, _ = call('POST', KS + '/users/%s/credentials/OS-EC2' % uid, {'tenant_id': pid}, token)
    if s != 201:
        die('creating EC2 credentials failed: HTTP %s %s' % (s, p))
    ak, sk = p['credential']['access'], p['credential']['secret']
    bucket = 'orphera-test-%d' % int(time.time())
    payload = ('orphera-s3-%d' % int(time.time())).encode()
    try:
        s, b = s3_call('PUT', '/' + bucket, ak, sk)
        if s != 200:
            die('bucket create failed: HTTP %s %r (is rgw_s3_auth_use_keystone set?)' % (s, b[:200]))
        try:
            s, b = s3_call('PUT', '/%s/obj' % bucket, ak, sk, payload)
            if s != 200:
                die('object upload failed: HTTP %s %r' % (s, b[:200]))
            s, b = s3_call('GET', '/%s/obj' % bucket, ak, sk)
            if s != 200 or b != payload:
                die('object read back wrongly: HTTP %s %r' % (s, b[:80]))
        finally:
            s3_call('DELETE', '/%s/obj' % bucket, ak, sk)
            s3_call('DELETE', '/' + bucket, ak, sk)
        s, _ = s3_call('HEAD', '/' + bucket, ak, sk)
        if s != 404:
            die('bucket %s is still there after the delete: HTTP %s' % (bucket, s))
    finally:
        call('DELETE', KS + '/users/%s/credentials/OS-EC2/%s' % (uid, ak), None, token)
    print('s3 cycle ok: bucket and object made with EC2 credentials, read back, deleted')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'registered':
        registered()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'swift-cycle':
        swift_cycle()
    elif cmd == 's3-cycle':
        s3_cycle()
    else:
        die('usage: orphera-rgw-api register | registered | unregister | catalog | swift-cycle | s3-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@RGW@@", s"$rgwIp:$rgwPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@RGWPW@@", rgwPassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-rgw-api"

  // The RGW options as lines `option value`. Their hash decides whether the daemon is restarted. No
  // rgw_keystone_api_version: Ceph 20 has dropped it (v3 only), and `config set` refuses it.
  private val rgwOptions: List[(String, String)] = List(
    "rgw_keystone_url" -> ksUrl,
    "rgw_keystone_admin_user" -> "rgw",
    "rgw_keystone_admin_password" -> rgwPassword.reveal,
    "rgw_keystone_admin_domain" -> "Default",
    "rgw_keystone_admin_project" -> "service",
    "rgw_keystone_accepted_roles" -> "member,admin",
    "rgw_keystone_implicit_tenants" -> "true",
    "rgw_keystone_verify_ssl" -> "true",
    "rgw_swift_account_in_url" -> "true",
    "rgw_swift_url" -> s"http://$rgwIp:$rgwPort",
    "rgw_s3_auth_use_keystone" -> "true"
  )

  // The Ceph host that has the address rgwIp.
  private val hostSnippet =
    s"""HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
       |for h in json.load(sys.stdin):
       |    if h.get('addr') == '$rgwIp':
       |        print(h['hostname'])
       |        break")""".stripMargin

  private val hashInput = rgwOptions.map((k, v) => s"$k=$v").mkString("\n")
  // `spec` prints the service spec, `newhash` the hash of spec + options + the Keystone state file: the value
  // kept in rgw-applied-sha. The same text is used by the guard and by the step, so they agree.
  private val hashFns =
    s"""spec() {
       |cat <<SPEC
       |service_type: rgw
       |service_id: $serviceId
       |placement:
       |  hosts:
       |    - $$HOST
       |  count_per_host: 1
       |spec:
       |  rgw_frontend_port: $rgwPort
       |extra_container_args:
       |  - "-v"
       |  - "/etc/ssl/certs/ca-certificates.crt:/etc/pki/tls/certs/ca-bundle.crt:ro"
       |SPEC
       |}
       |newhash() {
       |  { spec; cat <<'OPTS'
       |$hashInput
       |OPTS
       |cat $stateDir/rgw-keystone-state 2>/dev/null || true
       |} | sha256sum | cut -d' ' -f1
       |}""".stripMargin

  // The spec and the options go in through `cephadm shell`; --mount makes a file visible at /mnt/<name>.
  // `orch apply` of an unchanged spec is a no-op, `config set` is a set, and the daemon is restarted only
  // when the hash of spec + options differs from the last applied one.
  private val rgwScript: String =
    val sets = rgwOptions
      .map((k, v) =>
        s"cephadm shell -- ceph config set client.rgw.$serviceId $k '$v'"
      )
      .mkString("\n       |")
    s"""set -e
       |$hostSnippet
       |if [ -z "$$HOST" ]; then
       |  echo "no Ceph orchestrator host with address $rgwIp (ceph orch host ls)" >&2
       |  exit 1
       |fi
       |$hashFns
       |# Ceph 20 (Tentacle) has no rgw_keystone_api_version (Keystone v3 is the only one it speaks) and refuses to
       |# set it; a node set up with an older Ceph may still hold it, so it is cleared.
       |cephadm shell -- ceph config rm client.rgw.$serviceId rgw_keystone_api_version 2>/dev/null || true
       |$sets
       |spec > /tmp/rgw-spec.yaml
       |cephadm shell --mount /tmp/rgw-spec.yaml -- ceph orch apply -i /mnt/rgw-spec.yaml
       |NEW=$$(newhash)
       |rm -f /tmp/rgw-spec.yaml
       |mkdir -p $stateDir
       |OLD=$$(cat $stateDir/rgw-applied-sha 2>/dev/null || true)
       |STATE=unchanged
       |if [ "$$NEW" != "$$OLD" ]; then
       |  # cephadm makes the daemon later; one that is not there yet starts with the options already set
       |  if cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.$serviceId'; then
       |    STATE="changed, daemon restarted"
       |    cephadm shell -- ceph orch restart rgw.$serviceId
       |  else
       |    STATE="changed, daemon starting"
       |  fi
       |  echo "$$NEW" > $stateDir/rgw-applied-sha
       |fi
       |echo "RGW configuration $$STATE"
       |# RGW answers on its port while it starts or stops, so wait for the Swift capabilities document (HTTP 200
       |# on /info or /swift/info) three times in a row, three seconds apart.
       |OK=0
       |for i in $$(seq 1 100); do
       |  C1=$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/info || true)
       |  C2=$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/swift/info || true)
       |  if [ "$$C1" = "200" ] || [ "$$C2" = "200" ]; then OK=$$((OK + 1)); else OK=0; fi
       |  if [ "$$OK" -ge 3 ]; then echo "RGW answers on $rgwIp:$rgwPort (Swift capabilities, three times in a row)"; exit 0; fi
       |  sleep 3
       |done
       |echo "RGW is not serving Swift on $rgwIp:$rgwPort after 5 minutes - check 'ceph orch ps --daemon-type rgw' and 'cephadm logs --name rgw.$serviceId...'" >&2
       |exit 1""".stripMargin

  // True when the configuration last applied is the one wanted now, and the orchestrator lists the daemon as
  // running.
  private val rgwCurrent =
    s"""$hostSnippet
       |[ -n "$$HOST" ] && [ -f $stateDir/rgw-applied-sha ] || exit 1
       |$hashFns
       |[ "$$(newhash)" = "$$(cat $stateDir/rgw-applied-sha)" ] || exit 1
       |cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.$serviceId.*running'""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val aptWait = command(
      "wait for apt and dpkg to be idle",
      waitAptIdleScript,
      unless = aptIdle,
      timeout = 960
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true).after(aptWait))

    val caDirectory = directory(caDir, mode = "0755").after(tools*)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and rgw passwords
      mode = "0700",
      owner = "root",
      group = "root"
    ).after(ca)
    val registered = command(
      "register object-store and s3 in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile)

    val rgw = command(
      "apply the RGW service and its Keystone options",
      rgwScript,
      unless = rgwCurrent,
      timeout = 900
    ).after(registered, ca)

    val swift = command(
      "a container and an object through the Swift API with a Keystone token",
      s"$helperPath swift-cycle",
      unless = s"$helperPath swift-cycle >/dev/null 2>&1",
      timeout = 180
    ).after(rgw)
    val s3 = command(
      "a bucket and an object through the S3 API with EC2 credentials",
      s"$helperPath s3-cycle",
      unless = s"$helperPath s3-cycle >/dev/null 2>&1",
      timeout = 180
    ).after(rgw)

    tools ++ List(aptWait, caDirectory, ca, helperFile, registered, rgw, swift, s3)
