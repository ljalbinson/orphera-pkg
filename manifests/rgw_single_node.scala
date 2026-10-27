// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Object storage: the Ceph RADOS Gateway (RGW), with its Swift API and its S3 API both authenticated
// by Keystone. RGW runs as a cephadm service on the Ceph cluster itself, so there is no new VM and no Kolla
// image: the playbook runs on tst0 only. Modelled on manila_single_node.scala (which also prepares Ceph from
// tst0) and barbican_single_node.scala (the Keystone helper); read their headers for the conventions.
//
// What it builds (all on tst0, 10.10.5.12):
//   - the cephadm service `rgw.orphera`: ONE RGW daemon placed on the Ceph host with the address `rgwIp`,
//     the beast front end on port 8080, plain HTTP like the other APIs here. The daemon is given the host's
//     CA bundle (which includes Keystone's test CA, installed below) so it can verify Keystone's TLS.
//   - the RGW options, set with `ceph config set client.rgw.orphera ...`: Keystone as the identity service
//     (v3, user `rgw` in project `service`), the accepted roles `member` and `admin`, one namespace per
//     project (implicit tenants), the account in the Swift URL, and S3 requests checked against Keystone's
//     EC2 credentials. The daemon is restarted only when this configuration changes (a hash of it is kept in
//     /var/lib/orphera/rgw-applied-sha), so a repeated run does not interrupt service.
//   The pools (.rgw.root, default.rgw.*) are made by RGW itself when it first runs.
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `rgw` in project `service` with
// the admin role; the service `swift` of type `object-store` with the endpoints
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
// Stages (both on tst0):
//   - install-rgw: packages, Keystone's CA from its TLS handshake, the helper and the registration in
//     Keystone, the RGW service spec and options, then wait until RGW answers on 8080.
//   - confirm-rgw-healthy: a container and an object are made, read back and deleted through the Swift API
//     with a Keystone token, and a bucket and an object through the S3 API with EC2 credentials made in
//     Keystone for the run (and removed again).
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/rgw_single_node.scala` changes nothing and
// reports whether the RGW daemon is running.
//
// Facts about RGW this was written from, to check if the first run fails: `ceph orch apply -i` with a spec of
// service_type rgw (service_id, placement.hosts, spec.rgw_frontend_port, extra_container_args); the daemon
// reads its options from the section `client.rgw.orphera`; the Ceph image is CentOS based, so libcurl reads
// its CA bundle from /etc/pki/tls/certs/ca-bundle.crt (the host's /etc/ssl/certs/ca-certificates.crt is
// mounted there); `rgw_s3_auth_use_keystone` makes RGW check S3 signatures with Keystone; the account is in
// the Swift URL (`rgw_swift_account_in_url`). The options are stored in the monitors' configuration database,
// and so is the service password: acceptable for this lab, not for production.
//
// Test-only credentials come from the secret store (rgw/service), made on first use. Not compiled where this
// was written; the first run on scala0 is the first compile.
object rgw_single_node extends OrpheraClusterPlaybook:

  private val node = "tst0"
  private val rgwIp = "10.10.5.12"
  private val rgwPort = 8080
  private val serviceId = "orphera"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val rgwPassword = Secrets.secret("rgw/service")

  private val caDir = "/etc/orphera/rgw-ca"
  private val stateDir = "/var/lib/orphera"

  private val hostPackages =
    List("openssl", "curl", "ca-certificates", "python3")

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

  // Same method as placement_single_node.scala: the last certificate Keystone presents is its CA; trust on
  // first use. Installed into the host's trust store, which RGW then gets as its bundle.
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

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands: register | unregister | catalog | swift-cycle | s3-cycle
  // swift-cycle: with an admin token, the object-store URL from the catalog (so the %(project_id)s
  // substitution is exercised), a container and an object are created, read back, and deleted.
  // s3-cycle: EC2 credentials are made in Keystone for the admin user and project, a bucket and an object are
  // created with an AWS Signature V4 request, read back and deleted, and the credentials are removed.
  private val apiHelperPython =
    """#!/usr/bin/python3
import hashlib, hmac, json, ssl, sys, time, urllib.request, urllib.error

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


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=rgw', 'users')
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
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'swift-cycle':
        swift_cycle()
    elif cmd == 's3-cycle':
        s3_cycle()
    else:
        die('usage: orphera-rgw-api register | unregister | catalog | swift-cycle | s3-cycle')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-rgw-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@RGW@@", s"$rgwIp:$rgwPort")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@RGWPW@@", rgwPassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-rgw-api\necho wrote /usr/local/sbin/orphera-rgw-api\n"

  // The RGW options as lines `option value`. Their hash decides whether the daemon is restarted.
  private val rgwOptions: List[(String, String)] = List(
    "rgw_keystone_url" -> ksUrl,
    "rgw_keystone_api_version" -> "3",
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

  // The spec and the options go in through `cephadm shell`; --mount makes a file visible at /mnt/<name>.
  // Everything is repeatable: `orch apply` of an unchanged spec is a no-op, `config set` is a set, and the
  // daemon is restarted only when the hash of spec + options differs from the last applied one.
  private val rgwScript: String =
    val sets = rgwOptions
      .map((k, v) => s"cephadm shell -- ceph config set client.rgw.$serviceId $k '$v'")
      .mkString("\n       |")
    val hashInput = rgwOptions.map((k, v) => s"$k=$v").mkString("\n")
    s"""set -e
       |HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
       |for h in json.load(sys.stdin):
       |    if h.get('addr') == '$rgwIp':
       |        print(h['hostname'])
       |        break")
       |if [ -z "$$HOST" ]; then
       |  echo "no Ceph orchestrator host with address $rgwIp (ceph orch host ls)" >&2
       |  exit 1
       |fi
       |$sets
       |cat > /tmp/rgw-spec.yaml <<SPEC
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
       |cephadm shell --mount /tmp/rgw-spec.yaml -- ceph orch apply -i /mnt/rgw-spec.yaml
       |NEW=$$( { cat /tmp/rgw-spec.yaml; cat <<'OPTS'
       |$hashInput
       |OPTS
       |} | sha256sum | cut -d' ' -f1)
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

  // The Swift capabilities document is served only once RGW is fully up (the port answers earlier).
  private val healthScript =
    s"""C1=$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/info || true)
       |C2=$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/swift/info || true)
       |[ "$$C1" = "200" ] || [ "$$C2" = "200" ]""".stripMargin

  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("rgw-single-node-check")(
        stage("check-rgw", node)
          .task("the RGW daemon runs")(
            Task.RunCommand(
              List(
                "sh",
                "-c",
                s"cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.$serviceId.*running'"
              )
            )
          )
          .build
      )
    else applyPlaybook

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("rgw-single-node")(
      stage("install-rgw", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install the tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the RGW API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register object-store and s3 in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-rgw-api register"),
            timeoutSeconds = 120
          )
        )
        .task("apply the RGW service and its Keystone options")(
          Task.RunCommand(List("sh", "-c", rgwScript), timeoutSeconds = 900)
        )
        .build,

      stage("confirm-rgw-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("make an object through the Swift API with a Keystone token")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-rgw-api swift-cycle"),
            timeoutSeconds = 180
          )
        )
        .task("make an object through the S3 API with EC2 credentials")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-rgw-api s3-cycle"),
            timeoutSeconds = 180
          )
        )
        .task("rgw confirmed")(
          Task.Debug(
            s"Object storage up: RGW on $node at http://$rgwIp:$rgwPort (Swift at /swift/v1, S3 path-style), identity from $ksUrl, catalog types object-store and s3. Test: manifests/test_rgw.sh"
          )
        )
        .build
    )
