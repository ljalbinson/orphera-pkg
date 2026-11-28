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
//     EC2 credentials. cephConfig sets them only when `ceph config get` differs, and cephOrchService applies
//     the spec only when its hash (kept in /var/lib/orphera/ceph-orch-rgw.orphera.sha) changed or the service
//     is not listed (cephadm redeploys the daemon itself when the spec changed). The daemon is restarted only
//     when an option changed or the Keystone entries below were made or put right in this run (the step
//     "restart RGW so it takes its new Keystone token and options", which a change of either triggers): RGW
//     keeps the admin token it got at start-up, and a new `rgw` user (Keystone was rebuilt) or a changed
//     password makes that token useless. A repeated run interrupts nothing.
//   The pools (.rgw.root, default.rgw.*) are made by RGW itself when it first runs.
//
// What it adds to Keystone (keystoneEntries, skipped while its check passes): user `rgw` in project `service`
// with the admin role, and the role `member`; the service `swift` of type `object-store` with the endpoints
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
//     helper's `registered`), the RGW options (`ceph config get`), the service (spec hash and listing) and the
//     Swift capabilities document answering. The restart step and the wait step end by waiting for it to
//     answer three times in a row.
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
  private val rgwUrl = s"http://$rgwIp:$rgwPort"
  private val serviceId = "orphera"

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val rgwPassword = Secrets.secret("rgw/service")

  private val caDir = "/etc/orphera/rgw-ca"
  private val caCert = s"$caDir/ca.crt"

  private val hostPackages =
    List("openssl", "curl", "ca-certificates", "python3")

  // Standard-library Python, as in the other service descriptions. Plain string filled in with replace().
  // Subcommands: catalog | swift-cycle | s3-cycle (the Keystone entries are made by keystoneEntries, below)
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
        die('no object-store endpoint in the catalog; apply rgw_single_node.scala again)')
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
    if cmd == 'catalog':
        catalog()
    elif cmd == 'swift-cycle':
        swift_cycle()
    elif cmd == 's3-cycle':
        s3_cycle()
    else:
        die('usage: orphera-rgw-api catalog | swift-cycle | s3-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@RGW@@", s"$rgwIp:$rgwPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
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

  // The cephadm service spec; @@HOST@@ becomes the orchestrator host that has the address rgwIp (or the name
  // `node`). The daemon gets the host's CA bundle where its libcurl looks for one.
  private val rgwSpec =
    s"""service_type: rgw
       |service_id: $serviceId
       |placement:
       |  hosts:
       |    - @@HOST@@
       |  count_per_host: 1
       |spec:
       |  rgw_frontend_port: $rgwPort
       |extra_container_args:
       |  - "-v"
       |  - "/etc/ssl/certs/ca-certificates.crt:/etc/pki/tls/certs/ca-bundle.crt:ro"""".stripMargin

  // Waits for the Swift capabilities document: RGW answers on its port while it starts or stops, so the wait is for
  // HTTP 200 on /info or /swift/info three times in a row, three seconds apart. Ends the script (0 or 1).
  private val waitForSwift: String =
    s"""# RGW answers on its port while it starts or stops, so wait for the Swift capabilities document (HTTP 200
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

  // Restarts the daemon, when the orchestrator lists one, and waits for it to serve Swift again. It runs when the
  // Keystone entries were made or put right: RGW keeps the admin token it got for user rgw until it restarts, and
  // setting a user's password makes Keystone revoke the tokens it holds (after a Keystone rebuild the user is new).
  private val restartScript: String =
    s"""set -e
       |if ! cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.$serviceId'; then
       |  echo "no RGW daemon to restart"
       |  exit 0
       |fi
       |cephadm shell -- ceph orch restart rgw.$serviceId
       |$waitForSwift""".stripMargin
  private val daemonListed =
    s"cephadm shell -- ceph orch ps --daemon-type rgw 2>/dev/null | grep -q 'rgw.$serviceId'"

  // One answer from the Swift capabilities document (the step that follows waits for three in a row).
  private val swiftAnswers =
    s"""[ "$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/info || true)" = "200" ] || [ "$$(curl -s -o /dev/null -w '%{http_code}' http://$rgwIp:$rgwPort/swift/info || true)" = "200" ]"""

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))

    val caDirectory = directory(caDir, mode = "0755").after(tools*)
    val ca = serverCa(
      "fetch Keystone's CA from its TLS handshake",
      ksFqdn,
      5000,
      caCert,
      hint = "apply keystone_single_node.scala (it must serve server-chain.crt)"
    ).after(caDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    ).after(ca)
    val (keystoneHelper, ensured) = keystoneEntries(
      "register object-store and s3 in Keystone",
      "rgw",
      rgwPassword,
      List(
        KeystoneService(
          "swift",
          "object-store",
          s"$rgwUrl/swift/v1/AUTH_%(project_id)s",
          Some(s"$rgwUrl/swift/v1")
        ),
        KeystoneService("s3", "s3", rgwUrl)
      ),
      ksUrl,
      adminPassword,
      caCert,
      roles = List("member")
    )
    val registered = ensured.after(ca)

    // The old option that Ceph 20 refuses (a node set up with an older Ceph may still hold it) is cleared first.
    val oldOption = absentCephConfig(
      "clear the Keystone API version option (Ceph 20 has none)",
      s"client.rgw.$serviceId",
      "rgw_keystone_api_version"
    ).after(registered)
    val config = cephConfig(
      "set the RGW Keystone options",
      s"client.rgw.$serviceId",
      rgwOptions
    )
      .after(oldOption, ca)
    // The options go in before the service, so a daemon that is new starts with them.
    val service = cephOrchService(
      "apply the RGW service",
      "rgw",
      serviceId,
      rgwSpec,
      rgwIp,
      node
    ).after(config)
    val restarted = command(
      "restart RGW so it takes its new Keystone token and options",
      restartScript,
      onlyIf = daemonListed,
      onChangeOnly = true,
      timeout = 600
    ).after(service)
    val waited = command(
      s"wait for RGW to serve Swift on $rgwIp:$rgwPort",
      waitForSwift,
      unless = swiftAnswers,
      timeout = 360
    ).after(restarted)
    val registeredAndNotifying = registered.notifies(restarted)
    val configAndNotifying = config.notifies(restarted)

    val swift = command(
      "a container and an object through the Swift API with a Keystone token",
      s"$helperPath swift-cycle",
      unless = s"$helperPath swift-cycle >/dev/null 2>&1",
      timeout = 180
    ).after(waited)
    val s3 = command(
      "a bucket and an object through the S3 API with EC2 credentials",
      s"$helperPath s3-cycle",
      unless = s"$helperPath s3-cycle >/dev/null 2>&1",
      timeout = 180
    ).after(waited)

    tools ++ List(
      caDirectory,
      ca,
      helperFile,
      keystoneHelper,
      registeredAndNotifying,
      oldOption,
      configAndNotifying,
      service,
      restarted,
      waited,
      swift,
      s3
    )
