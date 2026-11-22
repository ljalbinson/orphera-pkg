// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// The mysql datastore of Trove, on the Trove node tst25, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). It was the second stage of the cluster playbook trove_guest_image.scala; the image
// itself is trove_guest_image.scala (tst21).
//
//   orphera apply manifests/trove_datastore.scala --check
//   orphera apply manifests/trove_datastore.scala
//
// What it does: installs the helper `orphera-trove-db` and, through `trove-manage` in a one-off container of
// the trove-api image, makes the datastore `mysql`, the version (default 8.0.29; TROVE_MYSQL_VERSION chooses
// another MySQL 8.0 version, which is also the tag of the Docker image the guests pull), with the image tags
// trove,mysql, loads the validation rules for configuration groups, and makes the version the default. The
// registration is skipped while the Trove API lists the datastore with that version as its default
// (`orphera-trove-db datastores`, a read-only check); trove-manage prints its errors and still exits 0, so the
// same check runs after it.
//
// The helper also has `instance-test`, which test_trove_guest.sh uses to make a real database instance, wait for
// ACTIVE and HEALTHY, read the MySQL version from the guest's container and delete the instance again, and
// `instance-cleanup`.
//
// Prerequisites, not checked here: trove_single_node.scala has run (it writes /etc/kolla/trove/trove.conf and the
// Keystone CA this reads); the guest image and flavor exist (trove_guest_image.scala) before an instance is made.
//
// Facts this was written from, to check if a run fails: trove-manage takes
// `datastore_version_update DATASTORE VERSION MANAGER IMAGE_ID PACKAGES ACTIVE --image-tags T --version V`.
object trove_datastore extends OrpheraApply:
  import Resources.*

  private val troveNode = "tst25"

  override def nodes: List[String] = List(troveNode)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val fqdn = "tst25.ljalbinson.com"
  private val troveUrl = s"http://$fqdn:8779"
  private val registry = "quay.io/openstack.kolla"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val trovePassword = Secrets.secret("trove/service")

  private val flavorId = "orphera-trove"

  private val mysqlVersion = sys.env
    .get("TROVE_MYSQL_VERSION")
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("8.0.29")

  // The same locations as trove_single_node.scala.
  private val troveSrcConf = "/etc/kolla/trove"
  private val logDir = "/var/log/kolla/trove"
  private val stateDir = "/var/lib/orphera/trove"
  private val hostCaBundle = "/etc/ssl/certs/ca-certificates.crt"
  private val apiImage =
    Image("trove-api", Release.R2025_1, Base.UbuntuNoble, registry).ref

  // Runs inside a one-off container of the trove-api image. trove-manage prints its errors and exits 0, so the
  // API is checked afterwards.
  private val registerInnerScript =
    """set -e
RULES=$(find /var/lib/kolla /usr -path '*/trove/templates/mysql/validation-rules.json' 2>/dev/null | head -1)
[ -n "$RULES" ] || { echo "no mysql validation-rules.json in the trove-api image" >&2; exit 1; }
echo "validation rules: $RULES"
M="trove-manage --config-file /etc/trove/trove.conf"
$M datastore_update mysql ""
$M datastore_version_update mysql @@VERSION@@ mysql "" "" 1 --image-tags trove,mysql --version @@VERSION@@
$M db_load_datastore_config_parameters mysql @@VERSION@@ "$RULES" --version @@VERSION@@
$M datastore_update mysql @@VERSION@@
"""

  private val registerScript =
    s"""set -e
       |mkdir -p $stateDir
       |cat > $stateDir/register-datastore.sh <<'REGEOF'
       |${registerInnerScript.replace("@@VERSION@@", mysqlVersion)}REGEOF
       |podman run --rm --network host --user root --entrypoint sh -v $troveSrcConf/trove.conf:/etc/trove/trove.conf:ro,z -v $logDir:/var/log/kolla/trove:z -v $stateDir/register-datastore.sh:/register-datastore.sh:ro,z -v $hostCaBundle:$hostCaBundle:ro -e REQUESTS_CA_BUNDLE=$hostCaBundle $apiImage /register-datastore.sh
       |echo "trove-manage finished; the next step checks the result through the API"""".stripMargin

  // Subcommands: datastores | instance-test | instance-cleanup
  private val dbHelperPython =
    """#!/usr/bin/python3
import json, os, re, secrets, socket, ssl, subprocess, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
TROVE = '@@TROVE@@'
ADMIN_PW = '@@ADMINPW@@'
TROVE_PW = '@@TROVEPW@@'
VERSION = '@@VERSION@@'
FLAVOR_ID = '@@FLAVORID@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
TEST_NAME = 'orphera-trove-test'
KEY = '/var/lib/orphera/trove/trove-mgmt.pem'


def call(method, url, body=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=timeout)
        status, text = r.status, r.read().decode()
        headers = r.headers
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


def trove_url(parsed):
    return TROVE.replace('$(tenant_id)s', parsed['token']['project']['id'])


def datastores():
    token, parsed = get_token('admin', ADMIN_PW, 'admin')
    url = trove_url(parsed)
    s, p, _ = call('GET', url + '/datastores', None, token)
    if s != 200:
        die('GET %s/datastores: HTTP %s %s' % (url, s, p))
    for d in p.get('datastores', []):
        if d.get('name') != 'mysql':
            continue
        names = []
        default_name = None
        for v in d.get('versions', []):
            names.append(v.get('name'))
            if v.get('id') == d.get('default_version'):
                default_name = v.get('name')
        if VERSION not in names:
            die('datastore mysql has the versions %s, not %s' % (names, VERSION))
        if default_name != VERSION:
            die('datastore mysql: the default version is %s, not %s' % (default_name, VERSION))
        print('datastore mysql: versions %s, default %s' % (','.join(names), default_name))
        return
    die('the Trove API lists no datastore mysql: %s' % [d.get('name') for d in p.get('datastores', [])])


def find_tests(url, token):
    s, p, _ = call('GET', url + '/instances', None, token)
    if s != 200:
        die('GET %s/instances: HTTP %s %s' % (url, s, p))
    return [i for i in p.get('instances', []) if i.get('name') == TEST_NAME]


def delete_instance(url, token, iid, wait=900):
    call('DELETE', url + '/instances/' + iid, None, token)
    end = time.time() + wait
    while time.time() < end:
        s, _, _ = call('GET', url + '/instances/' + iid, None, token)
        if s == 404:
            return True
        time.sleep(5)
    return False


def cleanup():
    token, parsed = get_token('trove', TROVE_PW, 'service')
    url = trove_url(parsed)
    found = find_tests(url, token)
    for i in found:
        gone = delete_instance(url, token, i['id'])
        print('instance %s deleted: %s' % (i['id'], gone))
    if not found:
        print('no test instance')


def guest_address(token, parsed):
    # The guest's only port is the management port, which Trove does not list in the instance details, so
    # the address is read from the Nova server (same name as the instance, in project service).
    nova = endpoint_of(parsed, 'compute')
    s, p, _ = call('GET', nova + '/servers/detail?name=' + TEST_NAME, None, token)
    if s != 200:
        die('listing Nova servers failed: HTTP %s %s' % (s, p))
    for srv in p.get('servers', []):
        if srv.get('name') != TEST_NAME:
            continue
        for addrs in (srv.get('addresses') or {}).values():
            for a in addrs:
                if a.get('version') == 4:
                    return a['addr']
    return None


def endpoint_of(parsed, svc_type):
    for c in parsed['token']['catalog']:
        if c['type'] == svc_type:
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    url = e['url'].rstrip('/')
                    return url if (svc_type != 'compute' or url.endswith('/v2.1')) else url + '/v2.1'
    die('no %s service in the catalog' % svc_type)


def ssh(ip, command, timeout=90):
    try:
        r = subprocess.run(['ssh', '-i', KEY, '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null',
                            '-o', 'ConnectTimeout=15', '-o', 'LogLevel=ERROR', 'ubuntu@' + ip, command],
                           capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 255, 'ssh timed out'
    return r.returncode, (r.stdout + r.stderr).strip()


def container_version(ip):
    # The database is a Docker container named `database` on the guest; ask mysqld in it for its version.
    last = ''
    for _ in range(6):
        rc, out = ssh(ip, "sudo docker ps --format '{{.Image}} {{.Names}} {{.Status}}'; sudo docker exec database mysqld --version")
        last = out
        m = re.search(r'Ver (\S+)', out)
        if rc == 0 and m:
            return m.group(1), out
        time.sleep(10)
    die('could not read the MySQL version from the guest %s: %s' % (ip, last[:400]))


def greeting(ip):
    # Informational: can this node reach the guest's MySQL port? Not required to pass.
    try:
        sock = socket.create_connection((ip, 3306), timeout=10)
        data = sock.recv(256)
        sock.close()
        if len(data) > 6 and data[4] == 10:
            return 'reachable, server version ' + data[5:data.index(b'\x00', 5)].decode('ascii', 'replace')
        return 'connected, unexpected greeting %r' % data[:24]
    except (OSError, ValueError) as e:
        return 'not reachable (%s)' % e


def instance_test(keep):
    token, parsed = get_token('trove', TROVE_PW, 'service')
    url = trove_url(parsed)
    for i in find_tests(url, token):
        print('removing the leftover test instance %s' % i['id'])
        if not delete_instance(url, token, i['id']):
            die('the leftover instance %s did not go away' % i['id'])
    password = secrets.token_hex(8)
    body = {'instance': {
        'name': TEST_NAME,
        'flavorRef': FLAVOR_ID,
        'volume': {'size': 1},
        'datastore': {'type': 'mysql', 'version': VERSION},
        'databases': [{'name': 'orphera'}],
        'users': [{'name': 'orphera', 'password': password, 'databases': [{'name': 'orphera'}]}],
        'access': {'is_public': False, 'allowed_cidrs': ['10.10.5.0/24']},
    }}
    s, p, _ = call('POST', url + '/instances', body, token)
    if s not in (200, 201, 202):
        die('creating the instance failed: HTTP %s %s' % (s, p))
    iid = p['instance']['id']
    print('instance %s requested' % iid)
    ok = False
    try:
        end = time.time() + 1800
        last = None
        inst = {}
        while time.time() < end:
            s, p, _ = call('GET', url + '/instances/' + iid, None, token)
            if s != 200:
                die('GET instance: HTTP %s %s' % (s, p))
            inst = p['instance']
            state = (inst.get('status'), inst.get('operating_status'))
            if state != last:
                print('  status %s operating_status %s' % state)
                last = state
            if inst.get('status') in ('ERROR', 'FAILED'):
                die('the instance went to %s: fault %s' % (inst.get('status'), json.dumps(inst.get('fault') or {k: inst.get(k) for k in ('status', 'operating_status', 'task')})[:800]))
            if inst.get('status') == 'ACTIVE' and inst.get('operating_status', 'HEALTHY') == 'HEALTHY':
                break
            time.sleep(10)
        else:
            die('the instance was not ACTIVE and HEALTHY after 30 minutes (last: %s)' % (last,))
        ip = guest_address(token, parsed)
        if not ip:
            die('no IPv4 address on the Nova server %s' % TEST_NAME)
        print('guest address %s' % ip)
        version, out = container_version(ip)
        for line in out.splitlines():
            print('  guest: ' + line[:160])
        print('mysql in the guest container: server version %s' % version)
        if not version.startswith(VERSION):
            die('expected a server version starting with %s' % VERSION)
        print('port 3306 from this node: %s' % greeting(ip))
        ok = True
    finally:
        if keep:
            print('instance %s kept' % iid)
        else:
            gone = delete_instance(url, token, iid)
            print('instance %s deleted: %s' % (iid, gone))
            if not gone:
                ok = False
    if not ok:
        sys.exit(1)
    print('instance test ok: MySQL %s ran in a Trove guest (ACTIVE, HEALTHY), the container reported its version, and the instance was deleted' % VERSION)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'datastores':
        datastores()
    elif cmd == 'instance-test':
        instance_test('--keep' in sys.argv[2:])
    elif cmd == 'instance-cleanup':
        cleanup()
    else:
        die('usage: orphera-trove-db datastores | instance-test [--keep] | instance-cleanup')
"""

  private val dbHelperPath = "/usr/local/sbin/orphera-trove-db"

  private val helper =
    dbHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@TROVE@@", s"$troveUrl/v1.0/$$(tenant_id)s")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@TROVEPW@@", trovePassword.reveal)
      .replace("@@VERSION@@", mysqlVersion)
      .replace("@@FLAVORID@@", flavorId)
      .replace("@@CA@@", "/etc/kolla/trove-ca/ca.crt")

  private val registered = s"$dbHelperPath datastores >/dev/null 2>&1"

  def describe(target: Node): List[ApplyResource] =
    val helperFile = secretFile(
      dbHelperPath,
      Secret(helper), // it holds the admin and trove passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val register = command(
      s"register the mysql datastore version $mysqlVersion",
      registerScript,
      unless = registered,
      timeout = 600
    ).after(helperFile)
    // trove-manage exits 0 even when it fails; this reads the result through the API.
    val confirmed = command(
      "the Trove API lists the mysql datastore and its default version",
      s"$dbHelperPath datastores",
      unless = registered,
      timeout = 120
    ).after(register)
    List(helperFile, register, confirmed)
