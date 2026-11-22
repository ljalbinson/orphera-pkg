// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// OpenStack Trove, part 2 of two, the guest image on the builder tst21, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the first stage of the cluster playbook of the same name;
// the datastore registration on tst25 is trove_datastore.scala. A second run changes nothing, and `--check`
// shows what would change.
//
//   orphera apply manifests/trove_guest_image.scala      # builds the image, uploads it, makes the flavor (tst21)
//   orphera apply manifests/trove_datastore.scala        # registers the mysql datastore (tst25)
//
// What a Trove guest is: a Nova server booted from a guest image that holds the Trove guest agent and Docker.
// The agent starts the database as a container (here MySQL, pulled from Docker Hub when an instance is made)
// and reports to the conductor over RabbitMQ. So the image only has to contain the agent and Docker.
//
// What this does on tst21:
//   - installs diskimage-builder (a virtualenv in /opt/diskimage-builder-env) and its host tools, and takes the
//     elements from the Trove source (/opt/trove-src, branch stable/2025.1);
//   - builds an Ubuntu noble image with `disk-image-create` using the elements that `trovestack build-image`
//     uses (base vm ubuntu-minimal cloud-init-datasources pip-cache guest-agent ubuntu-docker), with
//     DEV_MODE=false so the agent code is put into the image at build time (the guest-agent element clones
//     Trove stable/2025.1 and its constraints file from opendev.org). The image is /var/lib/orphera/trove-image/
//     trove-guest-ubuntu-noble.qcow2 (5 GB). It is not built while the file exists; TROVE_REBUILD_IMAGE=1
//     builds it again and replaces the Glance image (and does so on every run for as long as it is set);
//   - uploads it to Glance (public, tags `trove` and `mysql`, hw_rng_model virtio), skipped while an active
//     image with those tags is there, and makes the Nova flavor orphera.trove (id orphera-trove; 1 vCPU,
//     2048 MB, 10 GB), skipped while it exists. Both go through the helper `orphera-trove-image`, whose
//     `image-ok` and `flavor-ok` are the read-only checks.
//
// Prerequisites, not checked here: trove_single_node.scala has run; tst21 reaches GitHub, opendev.org,
// download.docker.com and the Ubuntu mirrors (the build downloads them).
//
// Not in this version: other datastores (MariaDB, PostgreSQL), backups, replication.
//
// Facts this was written from, to check if a run fails: the build is the one in
// trove/integration/scripts/functions_qemu (build_guest_image) of stable/2025.1.
object trove_guest_image extends OrpheraApply:
  import Resources.*

  private val buildNode = "tst21"

  override def nodes: List[String] = List(buildNode)

  private val ksFqdn = "tst6.ljalbinson.com"

  private val adminPassword = Secrets.secret("keystone/admin")

  private val troveBranch = "stable/2025.1"
  private val srcDir = "/opt/trove-src"
  private val venvDir = "/opt/diskimage-builder-env"
  private val imageDir = "/var/lib/orphera/trove-image"
  private val cacheDir = "/var/cache/orphera-trove-dib"
  private val imageName = "trove-guest-ubuntu-noble"
  private val imageFile = s"$imageDir/$imageName.qcow2"
  private val caFile = s"$imageDir/ca.crt"
  private val flavorId = "orphera-trove"
  private val flavorName = "orphera.trove"

  private val rebuild = sys.env.get("TROVE_REBUILD_IMAGE").contains("1")
  // Shell text, not an s-string: `$OUT` is the shell variable of buildScript.
  private val removeOldImage = if rebuild then "rm -f \"$OUT\"" else ":"
  private val uploadFlags = if rebuild then " --replace" else ""

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

  // Done when the virtualenv has disk-image-create, the Trove source is cloned and the elements are there.
  private val prepared =
    s"""[ -x $venvDir/bin/disk-image-create ] && [ -d $srcDir/.git ] && for e in guest-agent ubuntu-docker ubuntu-guest; do [ -d "$srcDir/integration/scripts/files/elements/$$e" ] || exit 1; done"""

  // Keystone's CA: the last certificate it presents (trust on first use), which must be self-signed. `chainTo
  // DIR` leaves DIR/c1.pem ... DIR/cN.pem, each holding the certificate only. Only the helper reads it (it
  // is not added to the system trust store).
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val caCurrent =
    s"""$chainTo
       |D=$$(mktemp -d)
       |chainTo $$D
       |N=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |R=1
       |if [ "$$N" -ge 2 ]; then cmp -s $$D/c$$N.pem $caFile && R=0; fi
       |rm -rf $$D
       |exit $$R""".stripMargin

  private val fetchCaScript =
    s"""set -e
       |$chainTo
       |mkdir -p $imageDir
       |D=$$(mktemp -d)
       |trap 'rm -rf $$D' EXIT
       |chainTo $$D
       |COUNT=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA" >&2
       |  exit 1
       |fi
       |cp $$D/c$$COUNT.pem $caFile
       |openssl verify -CAfile $caFile $$D/c1.pem
       |echo "Keystone CA saved in $caFile"""".stripMargin

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

  // Standard-library Python, as in the other service descriptions. Plain string filled in with replace().
  // Subcommands: upload [--replace] | flavor | present | image-ok | flavor-ok | exists | remove
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


def image_ok(cl):
    active = [i for i in trove_images(cl) if i.get('status') == 'active']
    if not active:
        die('no active Glance image %s with the tag trove' % NAME)
    tags = active[0].get('tags', [])
    if 'mysql' not in tags:
        die('image %s has the tags %s, not mysql' % (NAME, ','.join(tags)))
    return active[0]


def flavor_ok(cl):
    s, p, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s != 200:
        die('flavor %s: HTTP %s' % (FLAVOR_ID, s))


def present():
    cl = Clients()
    image = image_ok(cl)
    flavor_ok(cl)
    print('guest image %s (%s, tags %s) is active and flavor %s exists' % (NAME, image['id'], ','.join(image.get('tags', [])), FLAVOR_ID))


def exists():
    # Exit 0 when the image or the flavor is there in any state. Makes no change.
    cl = Clients()
    if trove_images(cl):
        print('a guest image exists')
        return
    s, _, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s == 200:
        print('the flavor exists')
        return
    die('no guest image and no flavor')


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
    elif cmd == 'image-ok':
        image_ok(Clients())
    elif cmd == 'flavor-ok':
        flavor_ok(Clients())
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: orphera-trove-image upload [--replace] | flavor | present | image-ok | flavor-ok | exists | remove')
"""

  private val imageHelperPath = "/usr/local/sbin/orphera-trove-image"

  private val helper =
    imageHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@NAME@@", imageName)
      .replace("@@PATH@@", imageFile)
      .replace("@@FLAVORID@@", flavorId)
      .replace("@@FLAVORNAME@@", flavorName)
      .replace("@@CA@@", caFile)

  def describe(target: Node): List[ApplyResource] =
    val tools = buildPackages.map(p => pkg(p, updateCache = true))

    val imageDirectory = directory(imageDir)
    val cacheDirectory = directory(cacheDir)
    val prepare = command(
      "diskimage-builder and the Trove elements",
      prepareScript,
      unless = prepared,
      timeout = 900
    ).after(tools*)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      fetchCaScript,
      unless = caCurrent,
      timeout = 60
    ).after(imageDirectory)
    val build = command(
      "build the Ubuntu noble guest image",
      buildScript,
      // With TROVE_REBUILD_IMAGE=1 the script deletes the file and builds again, whatever is there.
      unless = if rebuild then "" else s"[ -s $imageFile ]",
      timeout = 3600
    ).after((prepare :: imageDirectory :: cacheDirectory :: Nil)*)

    val helperFile = secretFile(
      imageHelperPath,
      Secret(helper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val uploaded = command(
      "upload the image to Glance",
      s"$imageHelperPath upload$uploadFlags",
      unless =
        if rebuild then "" else s"$imageHelperPath image-ok >/dev/null 2>&1",
      timeout = 3600
    ).after(build, helperFile, ca)
    val flavor = command(
      "make the flavor orphera.trove",
      s"$imageHelperPath flavor",
      unless = s"$imageHelperPath flavor-ok >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    tools ++ List(
      imageDirectory,
      cacheDirectory,
      prepare,
      ca,
      build,
      helperFile,
      uploaded,
      flavor
    )
