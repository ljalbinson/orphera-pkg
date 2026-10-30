// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Trove, part 2 of two: the guest image and the MySQL datastore. Part 1 is
// trove_single_node.scala (read its header first); run it before this one.
//
// What a Trove guest is: a Nova server booted from a guest image that holds the Trove guest agent and Docker.
// The agent starts the database as a container (here MySQL, pulled from Docker Hub when an instance is made)
// and reports to the conductor over RabbitMQ. So the image only has to contain the agent and Docker.
//
// What this does:
//   - build-trove-guest-image (tst21): installs diskimage-builder (a virtualenv in /opt/diskimage-builder-env)
//     and its host tools, takes the elements from the Trove source (/opt/trove-src, branch stable/2025.1), and
//     builds an Ubuntu noble image with `disk-image-create` using the elements that `trovestack build-image`
//     uses (base vm ubuntu-minimal cloud-init-datasources pip-cache guest-agent ubuntu-docker), with
//     DEV_MODE=false so the agent code is put into the image at build time (the guest-agent element clones
//     Trove stable/2025.1 and its constraints file from opendev.org). The image is /var/lib/orphera/trove-image/
//     trove-guest-ubuntu-noble.qcow2 (5 GB). It is not rebuilt if it exists; TROVE_REBUILD_IMAGE=1 rebuilds it
//     and replaces the Glance image.
//   - The helper `orphera-trove-image` on tst21 uploads the image to Glance (public, tags `trove` and `mysql`,
//     hw_rng_model virtio) and makes the Nova flavor orphera.trove (id orphera-trove; 1 vCPU, 2048 MB, 10 GB).
//   - register-trove-datastore (tst25): `trove-manage` in a one-off container of the trove-api image makes the
//     datastore `mysql`, the version (default 8.0.29; TROVE_MYSQL_VERSION chooses another MySQL 8.0 version, which
//     is also the tag of the Docker image the guests pull), with the image tags trove,mysql, loads the
//     validation rules for configuration groups, and makes the version the default.
//   - confirm-trove-datastore (tst25): the Trove API lists the datastore and its default version.
//
// The test (test_trove_guest.sh) makes a real database instance, waits for ACTIVE and HEALTHY, reads the MySQL
// greeting on port 3306, and deletes the instance again.
//
// Prerequisites, not checked here: trove_single_node.scala has run; tst21 reaches GitHub, opendev.org,
// download.docker.com and the Ubuntu mirrors (the build downloads them); the guests reach Docker Hub, RabbitMQ
// (10.10.5.22:5672) and the Trove conductor path through RabbitMQ only; Nova has compute capacity for 2048 MB.
//
// Not in this version: other datastores (MariaDB, PostgreSQL), backups, replication.
//
// Facts this was written from, to check if the first run fails: the build is the one in
// trove/integration/scripts/functions_qemu (build_guest_image) of stable/2025.1; trove-manage takes
// `datastore_version_update DATASTORE VERSION MANAGER IMAGE_ID PACKAGES ACTIVE --image-tags T --version V`;
// trove-manage prints an error and still exits 0, so the check afterwards is the Trove API.
//
// Not compiled where this was written; the first run on scala0 is the first compile.
object trove_guest_image extends OrpheraClusterPlaybook:

  private val buildNode = "tst21"
  private val troveNode = "tst25"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val fqdn = "tst25.ljalbinson.com"
  private val troveUrl = s"http://$fqdn:8779"
  private val registry = "quay.io/openstack.kolla"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val trovePassword = Secrets.secret("trove/service")

  private val troveBranch = "stable/2025.1"
  private val srcDir = "/opt/trove-src"
  private val venvDir = "/opt/diskimage-builder-env"
  private val imageDir = "/var/lib/orphera/trove-image"
  private val cacheDir = "/var/cache/orphera-trove-dib"
  private val imageName = "trove-guest-ubuntu-noble"
  private val imageFile = s"$imageDir/$imageName.qcow2"
  private val flavorId = "orphera-trove"
  private val flavorName = "orphera.trove"

  private val mysqlVersion = sys.env
    .get("TROVE_MYSQL_VERSION")
    .map(_.trim)
    .filter(_.nonEmpty)
    .getOrElse("8.0.29")
  private val rebuild = sys.env.get("TROVE_REBUILD_IMAGE").contains("1")
  // Shell text, not an s-string: `$OUT` is the shell variable of buildScript.
  private val removeOldImage = if rebuild then "rm -f \"$OUT\"" else ":"
  private val uploadFlags = if rebuild then " --replace" else ""

  // The same locations as trove_single_node.scala.
  private val troveSrcConf = "/etc/kolla/trove"
  private val logDir = "/var/log/kolla/trove"
  private val stateDir = "/var/lib/orphera/trove"
  private val hostCaBundle = "/etc/ssl/certs/ca-certificates.crt"
  private val apiImage =
    Image("trove-api", Release.R2025_1, Base.UbuntuNoble, registry).ref

  private val buildPackages = List(
    "qemu-utils",
    "git",
    "kpartx",
    "debootstrap",
    "squashfs-tools",
    "python3-pip",
    "python3-setuptools",
    "python3-venv",
    "parted",
    "gdisk",
    "dosfstools",
    "openssl",
    "curl",
    "ca-certificates"
  )

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

  // ---- tst21: the image ------------------------------------------------

  private val prepareScript =
    s"""set -e
       |mkdir -p $imageDir $cacheDir
       |if [ ! -x $venvDir/bin/disk-image-create ]; then
       |  python3 -m venv $venvDir
       |  $venvDir/bin/pip install --upgrade pip setuptools
       |  $venvDir/bin/pip install diskimage-builder
       |fi
       |if [ ! -d $srcDir/.git ]; then
       |  git clone -q --depth 1 -b $troveBranch https://github.com/openstack/trove $srcDir
       |fi
       |ELEM=$srcDir/integration/scripts/files/elements
       |for e in guest-agent ubuntu-docker ubuntu-guest; do
       |  [ -d "$$ELEM/$$e" ] || { echo "element $$e is missing in $$ELEM" >&2; exit 1; }
       |done
       |echo "diskimage-builder $$($venvDir/bin/pip show diskimage-builder | awk '/^Version/{print $$2}'), Trove elements in $$ELEM"""".stripMargin

  private val fetchCaScript =
    s"""set -e
       |mkdir -p $imageDir
       |rm -f /tmp/ks-chain-*.pem
       |openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/ks-chain-" n ".pem")}'
       |COUNT=$$(ls /tmp/ks-chain-*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA" >&2
       |  exit 1
       |fi
       |cp /tmp/ks-chain-$$COUNT.pem $imageDir/ca.crt
       |openssl verify -CAfile $imageDir/ca.crt /tmp/ks-chain-1.pem
       |rm -f /tmp/ks-chain-*.pem
       |echo "Keystone CA saved in $imageDir/ca.crt"""".stripMargin

  // The variables are those of build_guest_image in functions_qemu; the guest-agent install script runs with
  // `set -u` and reads SYNC_LOG_TO_CONTROLLER and DEV_MODE, so they must be set.
  private val buildScript =
    s"""set -e
       |OUT=$imageFile
       |$removeOldImage
       |if [ -s "$$OUT" ]; then
       |  echo "guest image already built: $$OUT ($$(du -h "$$OUT" | cut -f1)); set TROVE_REBUILD_IMAGE=1 to build it again"
       |  exit 0
       |fi
       |. $venvDir/bin/activate
       |export ELEMENTS_PATH=$srcDir/integration/scripts/files/elements
       |export DIB_RELEASE=noble DISTRO_NAME=ubuntu
       |export DIB_CLOUD_INIT_DATASOURCES="ConfigDrive, OpenStack" DIB_CLOUD_INIT_ETC_HOSTS=localhost
       |export GUEST_USERNAME=ubuntu HOST_SCP_USERNAME=root ESCAPED_PATH_TROVE=unused DEV_MODE=false SYNC_LOG_TO_CONTROLLER=False
       |LOG=$imageDir/build.log
       |echo "building $$OUT (about 10 to 30 minutes); log: $$LOG"
       |if ! disk-image-create -a amd64 -o "$$OUT" -t qcow2 --image-size 5 --image-cache $cacheDir base vm ubuntu-minimal cloud-init-datasources pip-cache guest-agent ubuntu-docker >"$$LOG" 2>&1; then
       |  rm -f "$$OUT"
       |  echo "disk-image-create failed; the end of $$LOG:" >&2
       |  tail -60 "$$LOG" >&2
       |  exit 1
       |fi
       |[ -s "$$OUT" ] || { echo "disk-image-create produced no $$OUT; the end of $$LOG:" >&2; tail -30 "$$LOG" >&2; exit 1; }
       |qemu-img info "$$OUT" | sed -n '1,6p'
       |echo "guest image built: $$OUT"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands: upload [--replace] | flavor | present | remove
  private val imageHelperPython =
    """#!/usr/bin/python3
import json, os, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
ADMIN_PW = '@@ADMINPW@@'
NAME = '@@NAME@@'
PATH = '@@PATH@@'
FLAVOR_ID = '@@FLAVORID@@'
FLAVOR_NAME = '@@FLAVORNAME@@'
CTX = ssl.create_default_context(cafile='@@CA@@')


def call(method, url, body=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    ctx = CTX if url.startswith('https') else None
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=timeout)
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


def endpoint(parsed, svc_type):
    for c in parsed['token']['catalog']:
        if c['type'] == svc_type:
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    return e['url'].rstrip('/')
    die('no %s service in the catalog' % svc_type)


def versioned(url, suffix):
    return url if url.endswith(suffix) else url + suffix


class Clients(object):
    def __init__(self):
        self.token, parsed = get_token('admin', ADMIN_PW, 'admin')
        self.nova = versioned(endpoint(parsed, 'compute'), '/v2.1')
        self.glance = versioned(endpoint(parsed, 'image'), '/v2')

    def c(self, method, path, body=None):
        return call(method, self.nova + path, body, self.token)

    def g(self, method, path, body=None):
        return call(method, self.glance + path, body, self.token)


def trove_images(cl):
    s, p, _ = cl.g('GET', '/images?name=' + NAME)
    if s != 200:
        die('image list failed: HTTP %s %s' % (s, p))
    return [i for i in p.get('images', []) if 'trove' in i.get('tags', [])]


def upload(replace):
    cl = Clients()
    existing = trove_images(cl)
    active = [i for i in existing if i.get('status') == 'active']
    if active and not replace:
        print('guest image %s (%s) is already active in Glance' % (NAME, active[0]['id']))
        return
    for i in existing:
        cl.g('DELETE', '/images/' + i['id'])
    if not os.path.isfile(PATH):
        die('the image file %s does not exist' % PATH)
    body = {'name': NAME, 'disk_format': 'qcow2', 'container_format': 'bare', 'visibility': 'public', 'tags': ['trove', 'mysql'], 'hw_rng_model': 'virtio'}
    s, p, _ = cl.g('POST', '/images', body)
    if s != 201:
        die('image create failed: HTTP %s %s' % (s, p))
    iid = p['id']
    size = os.path.getsize(PATH)
    with open(PATH, 'rb') as fh:
        req = urllib.request.Request(cl.glance + '/images/%s/file' % iid, data=fh, method='PUT')
        req.add_header('Content-Type', 'application/octet-stream')
        req.add_header('Content-Length', str(size))
        req.add_header('X-Auth-Token', cl.token)
        ctx = CTX if req.full_url.startswith('https') else None
        try:
            urllib.request.urlopen(req, context=ctx, timeout=3600).read()
        except urllib.error.HTTPError as e:
            die('image upload failed: HTTP %s %s' % (e.code, e.read().decode()[:300]))
    end = time.time() + 600
    status = None
    while time.time() < end:
        s, p, _ = cl.g('GET', '/images/' + iid)
        status = p.get('status')
        if status == 'active':
            print('guest image uploaded: %s (%s), %d bytes, tags %s' % (NAME, iid, size, ','.join(p.get('tags', []))))
            return
        if status in ('killed', 'deleted'):
            break
        time.sleep(3)
    die('image %s did not become active (status %s)' % (iid, status))


def flavor():
    cl = Clients()
    s, p, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s == 200:
        print('flavor %s exists' % FLAVOR_ID)
        return
    if s != 404:
        die('flavor lookup failed: HTTP %s %s' % (s, p))
    s, p, _ = cl.c('POST', '/flavors', {'flavor': {'id': FLAVOR_ID, 'name': FLAVOR_NAME, 'ram': 2048, 'vcpus': 1, 'disk': 10, 'os-flavor-access:is_public': True}})
    if s not in (200, 201):
        die('creating flavor %s failed: HTTP %s %s' % (FLAVOR_ID, s, p))
    print('flavor %s created (1 vCPU, 2048 MB, 10 GB)' % FLAVOR_ID)


def present():
    cl = Clients()
    active = [i for i in trove_images(cl) if i.get('status') == 'active']
    if not active:
        die('no active Glance image %s with the tag trove' % NAME)
    tags = active[0].get('tags', [])
    if 'mysql' not in tags:
        die('image %s has the tags %s, not mysql' % (NAME, ','.join(tags)))
    s, p, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s != 200:
        die('flavor %s: HTTP %s' % (FLAVOR_ID, s))
    print('guest image %s (%s, tags %s) is active and flavor %s exists' % (NAME, active[0]['id'], ','.join(tags), FLAVOR_ID))


def remove():
    cl = Clients()
    for i in trove_images(cl):
        cl.g('DELETE', '/images/' + i['id'])
        print('deleted image %s' % i['id'])
    s, _, _ = cl.c('DELETE', '/flavors/' + FLAVOR_ID)
    print('flavor %s: HTTP %s' % (FLAVOR_ID, s))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'upload':
        upload('--replace' in sys.argv[2:])
    elif cmd == 'flavor':
        flavor()
    elif cmd == 'present':
        present()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: orphera-trove-image upload [--replace] | flavor | present | remove')
"""

  private val imageHelperPath = "/usr/local/sbin/orphera-trove-image"

  private val imageHelperScript =
    s"cat > $imageHelperPath <<'PYEOF'\n" +
      imageHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@NAME@@", imageName)
        .replace("@@PATH@@", imageFile)
        .replace("@@FLAVORID@@", flavorId)
        .replace("@@FLAVORNAME@@", flavorName)
        .replace("@@CA@@", s"$imageDir/ca.crt") +
      s"\nPYEOF\nchmod 700 $imageHelperPath\necho wrote $imageHelperPath\n"

  private val uploadScript =
    s"$imageHelperPath upload$uploadFlags"

  // ---- tst25: the datastore --------------------------------------------

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
       |echo "trove-manage finished; the next stage checks the result through the API"""".stripMargin

  // Subcommands: datastores | instance-test [--keep] | instance-cleanup
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

  private val dbHelperScript =
    s"cat > $dbHelperPath <<'PYEOF'\n" +
      dbHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@TROVE@@", s"$troveUrl/v1.0/$$(tenant_id)s")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@TROVEPW@@", trovePassword.reveal)
        .replace("@@VERSION@@", mysqlVersion)
        .replace("@@FLAVORID@@", flavorId)
        .replace("@@CA@@", "/etc/kolla/trove-ca/ca.crt") +
      s"\nPYEOF\nchmod 700 $dbHelperPath\necho wrote $dbHelperPath\n"

  val playbook: ClusterPlaybook =
    clusterPlaybook("trove-guest-image")(
      stage("build-trove-guest-image", buildNode)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install the image build tools")(
          Task.Install(packages = buildPackages, updateCache = true)
        )
        .task("diskimage-builder and the Trove elements")(
          Task.RunCommand(
            List("sh", "-c", prepareScript),
            timeoutSeconds = 900
          )
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("build the Ubuntu noble guest image")(
          Task.RunCommand(List("sh", "-c", buildScript), timeoutSeconds = 3600)
        )
        .task("write the guest image helper")(
          Task.RunCommand(List("sh", "-c", imageHelperScript))
        )
        .task("upload the image to Glance")(
          Task.RunCommand(List("sh", "-c", uploadScript), timeoutSeconds = 3600)
        )
        .task("make the flavor orphera.trove")(
          Task.RunCommand(
            List("sh", "-c", s"$imageHelperPath flavor"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("register-trove-datastore", troveNode)
        .task("write the Trove database helper")(
          Task.RunCommand(List("sh", "-c", dbHelperScript))
        )
        .task(s"register the mysql datastore version $mysqlVersion")(
          Task
            .RunCommand(List("sh", "-c", registerScript), timeoutSeconds = 600)
        )
        .build,

      stage("confirm-trove-datastore", troveNode)
        .task(
          "the Trove API lists the mysql datastore and its default version"
        )(
          Task.RunCommand(
            List("sh", "-c", s"$dbHelperPath datastores"),
            timeoutSeconds = 120
          )
        )
        .task("guest image confirmed")(
          Task.Debug(
            s"Trove guest image ready: $imageName in Glance (tags trove, mysql), flavor $flavorId, datastore mysql $mysqlVersion (default). Make a database: openstack database instance create NAME $flavorId --size 1 --datastore mysql --datastore-version $mysqlVersion. Test: manifests/test_trove_guest.sh"
          )
        )
        .build
    )
