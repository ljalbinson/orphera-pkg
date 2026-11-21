// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Magnum (Kubernetes clusters as a service) with the Cluster API Helm driver (magnum-capi-helm), Kolla
// images under podman, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows what would
// change. Modelled on manila_single_node.scala and trove_single_node.scala, which explain the conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order, after
// capi_management_cluster.scala (a cluster playbook) has been applied on tst23:
//
//   orphera apply manifests/magnum_database.scala      # the magnum database and user (tst3)
//   orphera apply manifests/magnum_rabbitmq.scala      # the `magnum` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/magnum_single_node.scala   # this one (tst23)
//
// Where it runs: tst23 (10.10.5.35), the same VM as the Cluster API management cluster. To move it, change `node`
// and `fqdn` below (and the teardown's `node`).
//
// Magnum does not build clusters itself: for every Kubernetes cluster the driver makes an application credential
// for the user, stores it as a Secret in the management cluster and installs the Helm chart `openstack-cluster`
// there; Cluster API and the OpenStack provider (CAPO) then create the VMs, networks and load balancer in
// OpenStack, and the Azimuth addon provider installs the cluster's add-ons (Calico, the OpenStack cloud
// controller, the Cinder CSI driver).
//
// What it builds (two Kolla containers on the host network):
//   - magnum-api         the REST API, port 9511, plain HTTP like the other APIs here
//   - magnum-conductor   does the work; loads the driver
// The Kolla images do not contain the Cluster API driver or helm. podman build and podman commit both failed on
// these images, so the images stay as they are: the pip package `magnum-capi-helm` (without its dependencies) is
// installed into a host directory, /var/lib/orphera/magnum/pylib, using the image's own Python, and the
// containers mount that directory and the helm binary that capi_management_cluster.scala installed, with
// PYTHONPATH pointing at it. MAGNUM_CAPI_HELM_SPEC sets the pip requirement (default `magnum-capi-helm`).
//
// What it makes besides the containers (the helper's subcommands, each with a read-only check that decides
// whether it runs):
//   - Keystone: user `magnum` (project service, admin), service `magnum` of type `container-infra` with the
//     endpoint http://<fqdn>:9511/v1, and the trustee domain `magnum` with its admin user `magnum_domain_admin`
//     (Magnum creates its per-cluster trustee users there)
//   - the Nova flavor `orphera.k8s` (id orphera-k8s; 2 vCPU, 3072 MB, 30 GB), the driver's minimum being 2 vCPU
//     and 2048 MB, and the key pair `orphera-magnum`
//   - a Kubernetes node image in Glance (public, with os_distro, os_version and kube_version properties, which
//     the driver reads). The image is downloaded on the node from the Azimuth image releases manifest; K8S_VERSION
//     (for example v1.31.4) or K8S_MINOR (1.31) chooses the version, K8S_IMAGE_URL names an image directly (then
//     K8S_VERSION and K8S_OS_VERSION are needed too). Without any of them the newest in the manifest is taken,
//     which may be newer than the chart supports; pin it if clusters do not build.
//   - the public cluster template `orphera-k8s` (Calico, the provider network as the external network, DNS server
//     MAGNUM_DNS, default 192.168.1.70, the lab resolver, which must resolve the Keystone name for the cluster's
//     cloud controller)
//
// The Kolla image also contains the Cluster API driver of another project (magnum_cluster_api, entry points
// k8s_cluster_api_*). Magnum instantiates every installed driver to validate a template, and that one fails
// without a kubeconfig, so [drivers] disabled_drivers switches those five off; magnum-capi-helm is
// k8s_capi_helm_v1.
//
// Magnum's certificates are kept in its database (x509keypair), not in Barbican. The Keystone CA is passed to the
// driver ([drivers] openstack_ca_file), which puts it into the cloud credentials that CAPO and the cluster's
// OpenStack integrations use.
//
// Versions: the Helm chart is MAGNUM_CHART_VERSION (default 0.26.0, the driver's own default). The management
// cluster has Cluster API v1.14, which still serves the v1beta1 API versions the driver uses.
//
// Prerequisites, not checked here: capi_management_cluster.scala applied on tst23 (helm, and the kubeconfig at
// /var/lib/orphera/capi/kubeconfig); Heat is NOT needed. Galera, Keystone, RabbitMQ (tst10), Glance, Nova with
// compute nodes, Neutron with the L3 agent and the provider network, and Octavia (the API load balancer of every
// cluster is an Octavia amphora) healthy; tst23 can reach the floating IP network (10.10.6.0/24) and the
// clusters' nodes can reach the internet through their router; the compute nodes have room: one test cluster is
// a control-plane node and a worker (2 vCPU, 3 GB each) and an amphora (1 GB). The Magnum images (magnum-api,
// magnum-conductor) on tst21's registry or quay.io.
//
// How it stays idempotent (as for Manila, Trove and Cinder):
//   - Keystone's CA is compared as a certificate with the installed copy each run; the copies in the two Kolla
//     directories are compared with it, and a service restarts when its copy changes.
//   - Each service has its own `magnum.conf` (a secret file; it holds the database, message bus and service
//     passwords), CA copy, copy of the management cluster's kubeconfig and `config.json` in its Kolla directory.
//   - The driver is installed again when its stamp changes (the install script, MAGNUM_CAPI_HELM_SPEC and the two
//     images) or its import fails, and both services restart. The pip package itself is not pinned: delete
//     /var/lib/orphera/magnum/driver.stamp to take a newer release.
//   - `magnum-db-manage upgrade` is skipped while its stamp matches and the `cluster_template` table exists (a
//     query as the magnum user, through the VIP).
//   - Magnum is registered in Keystone before the units start, skipped while the helper's `registered` passes.
//   - The flavor and key pair, the node image and the cluster template each run only while their read-only
//     checks (`resources-ok`, `image-present`, `template-ok`) fail.
//   - The test cluster (a control-plane node and a worker are made, the API answers /version, the cluster is
//     deleted: 15 to 40 minutes) is made again only when its stamp changes (a hash of magnum.conf, the driver
//     stamp, the DNS server and the Kubernetes image choice), so a repeated run does not build a cluster.
//     manifests/test_magnum.sh makes one more by hand. Delete /var/lib/orphera/magnum/cycle.stamp to have the
//     next run make one.
//
// Facts about Magnum this was written from, to check if a run fails: console scripts magnum-api,
// magnum-conductor and magnum-db-manage are in the images; the config sections [keystone_auth], [trust],
// [certificates], [drivers] (openstack_ca_file, verify_ca) and [capi_helm]; the driver reads the image
// properties os_distro and kube_version; the Azimuth manifest.json is a JSON object whose values have `name`,
// `url` and `checksum`.
//
// Test-only credentials come from the secret store (magnum/db, magnum/service, magnum/rabbit,
// magnum/domain-admin), made on first use.
object magnum_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst23"
  private val fqdn = "tst23.ljalbinson.com"
  private val apiPort = 9511

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "magnum"
  private val rabbitPassword = Secrets.secret("magnum/rabbit")
  private val rabbitVhost = "magnum"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "magnum"
  private val dbUser = "magnum"
  private val dbPassword = Secrets.secret("magnum/db")
  private val magnumPassword = Secrets.secret("magnum/service")
  private val domainAdminPassword = Secrets.secret("magnum/domain-admin")

  private def env(name: String, default: String = ""): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private val chartVersion = env("MAGNUM_CHART_VERSION", "0.26.0")
  private val dnsServer = env("MAGNUM_DNS", "192.168.1.70")
  private val capiHelmSpec = env("MAGNUM_CAPI_HELM_SPEC", "magnum-capi-helm")
  private val k8sVersion = env("K8S_VERSION")
  private val k8sMinor = env("K8S_MINOR")
  private val k8sImageUrl = env("K8S_IMAGE_URL")
  private val k8sOsVersion = env("K8S_OS_VERSION")
  private val manifestUrl = env(
    "K8S_MANIFEST_URL",
    "https://github.com/azimuth-cloud/azimuth-images/releases/latest/download/manifest.json"
  )
  private val k8sFlavorId = "orphera-k8s"

  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/magnum"
  private val stateDir = "/var/lib/orphera/magnum"
  private val dataDir = "/var/lib/orphera/magnum/data"
  private val buildDir = "/var/lib/orphera/magnum/build"
  private val pylibDir = "/var/lib/orphera/magnum/pylib"
  private val caDir = "/etc/kolla/magnum-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val mgmtKubeconfig = "/var/lib/orphera/capi/kubeconfig"
  private val helperPath = "/usr/local/sbin/orphera-magnum-api"

  private val apiBase =
    Image("magnum-api", Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val conductorBase =
    Image("magnum-conductor", Release.R2025_1, Base.UbuntuNoble, registry).ref

  // The conductor first, the API last.
  private val serviceNames = List("magnum-conductor", "magnum-api")

  // The two containers as data. They run the stock Kolla images. Defined after the directory vals, which they
  // read; a val used before its definition is null in an object.
  private def magnumContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = ExternalImage(if service == "magnum-api" then apiBase else conductorBase),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/magnum", Access.ReadWrite),
        Mount(dataDir, "/var/lib/magnum", Access.ReadWrite),
        Mount(pylibDir, pylibDir),
        Mount("/usr/local/bin/helm", "/usr/local/bin/helm")
      ),
      env = List(
        "KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS",
        "PYTHONPATH" -> pylibDir
      ),
      user = Some("root"),
      settleSeconds = 12
    )
  private val containers: List[Container] = serviceNames.map(magnumContainer)

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  private val images = List(apiBase, conductorBase)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in magnum_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(" ")}; do podman image exists $$img || exit 1; done"""

  // The commands the service needs are in its image, and the image can switch off the other Cluster API driver.
  private val imageCommandsScript =
    s"""for spec in "$apiBase magnum-api" "$conductorBase magnum-conductor" "$apiBase magnum-db-manage"; do
       |  set -- $$spec
       |  if ! podman run --rm --entrypoint sh $$1 -c "command -v $$2 >/dev/null"; then
       |    echo "$$2 not found in $$1" >&2
       |    exit 1
       |  fi
       |done
       |if ! podman run --rm --entrypoint sh $apiBase -c "grep -q disabled_drivers /var/lib/kolla/venv/lib/python3*/site-packages/magnum/conf/drivers.py"; then
       |  echo "this Magnum has no [drivers] disabled_drivers option; the other Cluster API driver in the image cannot be switched off" >&2
       |  exit 1
       |fi
       |echo "the Magnum commands are in the images"""".stripMargin
  private val imageCommandsOk = s"( $imageCommandsScript ) >/dev/null 2>&1"

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

  // ---- the Cluster API driver --------------------------------------------

  // The driver goes into pylibDir with the image's own Python, so the wheel matches the Python in the
  // containers. No dependencies are installed (pip cannot replace the Magnum and oslo libraries); a missing
  // dependency shows up in the import check, which names it. Only `podman run` is used.
  private val installInImage =
    s"""PY=/var/lib/kolla/venv/bin/python; [ -x "$$PY" ] || PY=$$(command -v python3)
       |rm -rf /out/*
       |"$$PY" -m pip install --no-deps --target /out '$capiHelmSpec' || { echo "pip is not usable in the image" >&2; exit 1; }
       |chmod -R a+rX /out
       |PYTHONPATH=/out "$$PY" -c 'import magnum_capi_helm.driver; print("driver import ok")'""".stripMargin

  private val driverStamp = Resource.sha256(installInImage + apiBase + conductorBase)
  private val driverStampFile = s"$stateDir/driver.stamp"
  // True when the driver imports in the conductor image with the directory mounted, helm is there, and the stamp
  // is the one the last install made.
  private val driverCurrent =
    s"""[ "$$(cat $driverStampFile 2>/dev/null)" = "$driverStamp" ] && [ -x /usr/local/bin/helm ] && podman run --rm --user root --entrypoint sh -e PYTHONPATH=$pylibDir -v $pylibDir:$pylibDir:ro,z $conductorBase -c "/var/lib/kolla/venv/bin/python -c 'import magnum_capi_helm.driver'" >/dev/null 2>&1"""

  private val driverScript =
    s"""set -e
       |if [ ! -x /usr/local/bin/helm ]; then
       |  echo "helm is not installed - apply capi_management_cluster.scala first" >&2
       |  exit 1
       |fi
       |mkdir -p $pylibDir $buildDir
       |cat > $buildDir/install-in-image.sh <<'INSTALL'
       |$installInImage
       |INSTALL
       |podman run --rm --user root --entrypoint sh -v $pylibDir:/out:z -v $buildDir/install-in-image.sh:/install-in-image.sh:ro,z $conductorBase /install-in-image.sh
       |podman run --rm --user root --entrypoint sh -e PYTHONPATH=$pylibDir -v $pylibDir:$pylibDir:ro,z $apiBase -c "/var/lib/kolla/venv/bin/python -c 'import magnum_capi_helm.driver' && echo api-image-import-ok"
       |helm version --short
       |echo $driverStamp > $driverStampFile
       |echo "driver installed in $pylibDir"""".stripMargin

  // ---- the helper ----------------------------------------------------------

  private val apiHelperPython =
    """#!/usr/bin/python3
import json, os, ssl, subprocess, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
MAGNUM = '@@MAGNUM@@'
ADMIN_PW = '@@ADMINPW@@'
MAGNUM_PW = '@@MAGNUMPW@@'
DOMAIN_PW = '@@DOMAINPW@@'
CTX = ssl.create_default_context(cafile='@@CAFILE@@')
FLAVOR_ID = '@@FLAVORID@@'
FLAVOR_NAME = 'orphera.k8s'
TEMPLATE = 'orphera-k8s'
KEYPAIR = 'orphera-magnum'
DNS = '@@DNS@@'
IMAGE_PREFIX = 'orphera-k8s-'


def call(method, url, body=None, token=None, extra=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    for k, v in (extra or {}).items():
        req.add_header(k, v)
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


class Clients(object):
    def __init__(self):
        self.token, parsed = get_token('admin', ADMIN_PW, 'admin')
        self.neutron = versioned(endpoint(parsed, 'network'), '/v2.0')
        self.nova = versioned(endpoint(parsed, 'compute'), '/v2.1')
        self.glance = versioned(endpoint(parsed, 'image'), '/v2')
        self.magnum_url = MAGNUM

    def n(self, method, path, body=None):
        return call(method, self.neutron + path, body, self.token)

    def c(self, method, path, body=None):
        return call(method, self.nova + path, body, self.token)

    def g(self, method, path, body=None):
        return call(method, self.glance + path, body, self.token)

    def m(self, method, path, body=None):
        return call(method, MAGNUM + '/v1' + path, body, self.token, {'OpenStack-API-Version': 'container-infra latest'}, 120)


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=magnum', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'magnum', 'domain_id': 'default', 'password': MAGNUM_PW}}, token)
        if s != 201:
            die('creating user magnum failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'magnum', 'domain': {'name': 'Default'}, 'password': MAGNUM_PW}}}}}
        s, _, _ = call('POST', KS + '/auth/tokens', body)
        if s != 201:
            call('PATCH', KS + '/users/' + user['id'], {'user': {'password': MAGNUM_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to magnum failed: HTTP %s %s' % (s, p))
    service = ensure(token, 'services', '/services?type=container-infra', 'services', {'service': {'name': 'magnum', 'type': 'container-infra'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': MAGNUM + '/v1', 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != MAGNUM + '/v1':
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': MAGNUM + '/v1'}}, token)
    domain = ensure(token, 'domains', '/domains?name=magnum', 'domains', {'domain': {'name': 'magnum', 'description': 'Owns users and projects created by magnum'}}, 'domain')
    dadmin = find(token, '/users?name=magnum_domain_admin&domain_id=' + domain['id'], 'users')
    if dadmin is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'magnum_domain_admin', 'domain_id': domain['id'], 'password': DOMAIN_PW}}, token)
        if s != 201:
            die('creating user magnum_domain_admin failed: HTTP %s %s' % (s, p))
        dadmin = p['user']
    else:
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'magnum_domain_admin', 'domain': {'name': 'magnum'}, 'password': DOMAIN_PW}}}}}
        s, _, _ = call('POST', KS + '/auth/tokens', body)
        if s != 201:
            call('PATCH', KS + '/users/' + dadmin['id'], {'user': {'password': DOMAIN_PW}}, token)
    s, p, _ = call('PUT', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin on the domain failed: HTTP %s %s' % (s, p))
    print('registered magnum in keystone (user magnum, service type container-infra, 3 endpoints, domain magnum with magnum_domain_admin)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def mservices():
    cl = Clients()
    s, p, _ = cl.m('GET', '/mservices')
    if s != 200:
        die('service list failed: HTTP %s %s' % (s, p))
    svcs = p.get('mservices', [])
    print(' '.join('%s@%s=%s' % (x.get('binary'), x.get('host'), 'up' if not x.get('disabled') and x.get('state') == 'up' else x.get('state')) for x in svcs))
    print('mservices: %d' % len(svcs))
    if not any(x.get('binary') == 'magnum-conductor' and x.get('state') == 'up' for x in svcs):
        die('magnum-conductor is not up')


def resources():
    cl = Clients()
    s, p, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s == 404:
        s, p, _ = cl.c('POST', '/flavors', {'flavor': {'id': FLAVOR_ID, 'name': FLAVOR_NAME, 'ram': 3072, 'vcpus': 2, 'disk': 30, 'os-flavor-access:is_public': True}})
        if s not in (200, 201):
            die('creating flavor %s failed: HTTP %s %s' % (FLAVOR_ID, s, p))
    elif s != 200:
        die('flavor lookup failed: HTTP %s %s' % (s, p))
    s, p, _ = cl.c('GET', '/os-keypairs/' + KEYPAIR)
    if s == 404:
        s, p, _ = cl.c('POST', '/os-keypairs', {'keypair': {'name': KEYPAIR}})
        if s not in (200, 201):
            die('creating key pair failed: HTTP %s %s' % (s, p))
    print('resources ready: flavor %s, key pair %s' % (FLAVOR_ID, KEYPAIR))


def k8s_images(cl):
    s, p, _ = cl.g('GET', '/images?os_distro=ubuntu&status=active')
    if s != 200:
        die('image list failed: HTTP %s %s' % (s, p))
    return [i for i in p.get('images', []) if i.get('name', '').startswith(IMAGE_PREFIX) and i.get('kube_version')]


def image_present():
    cl = Clients()
    imgs = k8s_images(cl)
    if imgs:
        print('kubernetes image %s (%s, kube_version %s) is active in Glance' % (imgs[0]['name'], imgs[0]['id'], imgs[0]['kube_version']))
        return
    sys.exit(1)


def image_upload(path, kube_version, os_version):
    cl = Clients()
    for i in cl.g('GET', '/images?os_distro=ubuntu')[1].get('images', []):
        if i.get('name', '').startswith(IMAGE_PREFIX) and i.get('status') != 'active':
            cl.g('DELETE', '/images/' + i['id'])
    name = IMAGE_PREFIX + kube_version
    body = {'name': name, 'disk_format': 'qcow2', 'container_format': 'bare', 'visibility': 'public', 'os_distro': 'ubuntu', 'os_version': os_version, 'kube_version': kube_version}
    s, p, _ = cl.g('POST', '/images', body)
    if s != 201:
        die('image create failed: HTTP %s %s' % (s, p))
    iid = p['id']
    size = os.path.getsize(path)
    with open(path, 'rb') as fh:
        req = urllib.request.Request(cl.glance + '/images/%s/file' % iid, data=fh, method='PUT')
        req.add_header('Content-Type', 'application/octet-stream')
        req.add_header('Content-Length', str(size))
        req.add_header('X-Auth-Token', cl.token)
        try:
            urllib.request.urlopen(req, timeout=3600).read()
        except urllib.error.HTTPError as e:
            die('image upload failed: HTTP %s %s' % (e.code, e.read().decode()[:300]))
    end = time.time() + 600
    status = None
    while time.time() < end:
        s, p, _ = cl.g('GET', '/images/' + iid)
        status = p.get('status')
        if status == 'active':
            print('kubernetes image uploaded: %s (%s), %d bytes' % (name, iid, size))
            return
        if status in ('killed', 'deleted'):
            break
        time.sleep(3)
    die('image %s did not become active (status %s)' % (iid, status))


def external_network(cl):
    s, p, _ = cl.n('GET', '/networks?router:external=True')
    nets = p.get('networks', []) if s == 200 else []
    for n in nets:
        if n.get('provider:network_type') == 'flat':
            return n
    if nets:
        return nets[0]
    die('no external network: mark the flat provider network external (openstack network set --external <name>)')


def ensure_template(cl):
    imgs = k8s_images(cl)
    if not imgs:
        die('no Kubernetes image in Glance (os_distro ubuntu, name %s*, kube_version set)' % IMAGE_PREFIX)
    img = imgs[0]
    s, p, _ = cl.m('GET', '/clustertemplates')
    if s != 200:
        die('template list failed: HTTP %s %s' % (s, p))
    for t in p.get('clustertemplates', []):
        if t.get('name') == TEMPLATE:
            if t.get('image_id') == img['id'] and t.get('dns_nameserver') == DNS:
                return t['uuid']
            s, _, _ = cl.m('DELETE', '/clustertemplates/' + t['uuid'])
            if s not in (204, 202):
                die('the template %s uses another image or DNS server and cannot be replaced (clusters use it): HTTP %s' % (TEMPLATE, s))
    ext = external_network(cl)
    body = {'name': TEMPLATE, 'image_id': img['id'], 'coe': 'kubernetes', 'external_network_id': ext['id'], 'flavor_id': FLAVOR_NAME, 'master_flavor_id': FLAVOR_NAME, 'dns_nameserver': DNS, 'network_driver': 'calico', 'public': True, 'master_lb_enabled': True, 'floating_ip_enabled': True, 'labels': {}}
    s, p, _ = cl.m('POST', '/clustertemplates', body)
    if s not in (200, 201):
        die('template create failed: HTTP %s %s' % (s, p))
    return p['uuid']


def template():
    cl = Clients()
    uuid = ensure_template(cl)
    print('cluster template %s ready (%s)' % (TEMPLATE, uuid))


def cluster_cycle():
    cl = Clients()
    tpl = ensure_template(cl)
    stamp = int(time.time())
    name = 'orphera-test-%d' % stamp
    s, p, _ = cl.m('POST', '/clusters', {'name': name, 'cluster_template_id': tpl, 'master_count': 1, 'node_count': 1, 'keypair': KEYPAIR, 'create_timeout': 60})
    if s not in (200, 201, 202):
        die('cluster create failed: HTTP %s %s' % (s, p))
    cid = p['uuid']
    try:
        end = time.time() + 3000
        last = None
        cluster = {}
        while time.time() < end:
            s, cluster, _ = cl.m('GET', '/clusters/' + cid)
            if s == 200:
                st = cluster.get('status')
                if st != last:
                    sys.stderr.write('[%s] cluster %s: %s\n' % (time.strftime('%H:%M:%S'), name, st))
                    last = st
                if st == 'CREATE_COMPLETE':
                    break
                if st and st.endswith('FAILED'):
                    die('cluster %s went to %s: %s' % (name, st, cluster.get('status_reason')))
            time.sleep(15)
        if cluster.get('status') != 'CREATE_COMPLETE':
            die('cluster %s is %s after 50 minutes: %s' % (name, cluster.get('status'), cluster.get('status_reason')))
        api = cluster.get('api_address')
        if not api:
            die('cluster %s has no api_address' % name)
        end = time.time() + 300
        body = ''
        while time.time() < end:
            r = subprocess.run(['curl', '-sk', '-m', '10', api.rstrip('/') + '/version'], capture_output=True, text=True)
            body = r.stdout
            if 'gitVersion' in body:
                break
            time.sleep(10)
        if 'gitVersion' not in body:
            die('the Kubernetes API at %s did not answer /version from this node within 300 s (last answer: %r)' % (api, body[:80]))
        version = json.loads(body).get('gitVersion')
        print('cluster cycle ok: cluster %s CREATE_COMPLETE, %s answers with Kubernetes %s, then deleted' % (name, api, version))
    finally:
        cl.m('DELETE', '/clusters/' + cid)
        end = time.time() + 1800
        while time.time() < end:
            s, c, _ = cl.m('GET', '/clusters/' + cid)
            if s == 404:
                break
            if c.get('status') == 'DELETE_FAILED':
                sys.stderr.write('cluster %s is DELETE_FAILED: %s\n' % (name, c.get('status_reason')))
                break
            time.sleep(15)


def delete_clusters():
    cl = Clients()
    s, p, _ = cl.m('GET', '/clusters')
    for c in p.get('clusters', []) if s == 200 else []:
        cl.m('DELETE', '/clusters/' + c['uuid'])
        print('deleting cluster %s' % c['name'])
    end = time.time() + 1800
    while time.time() < end:
        s, p, _ = cl.m('GET', '/clusters')
        if s != 200 or not p.get('clusters'):
            print('no clusters left')
            return
        time.sleep(15)
    die('clusters are still there after 30 minutes')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=container-infra', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=magnum', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    domain = find(token, '/domains?name=magnum', 'domains')
    if domain is not None:
        call('PATCH', KS + '/domains/' + domain['id'], {'domain': {'enabled': False}}, token)
        call('DELETE', KS + '/domains/' + domain['id'], None, token)
    print('removed magnum from keystone (service container-infra, its endpoints, user magnum, domain magnum)')


def teardown():
    cl = Clients()
    s, p, _ = cl.m('GET', '/clusters')
    for c in p.get('clusters', []) if s == 200 else []:
        cl.m('DELETE', '/clusters/' + c['uuid'])
    end = time.time() + 1800
    while time.time() < end:
        s, p, _ = cl.m('GET', '/clusters')
        if s != 200 or not p.get('clusters'):
            break
        time.sleep(15)
    s, p, _ = cl.m('GET', '/clustertemplates')
    for t in p.get('clustertemplates', []) if s == 200 else []:
        if t.get('name') == TEMPLATE:
            cl.m('DELETE', '/clustertemplates/' + t['uuid'])
    cl.c('DELETE', '/flavors/' + FLAVOR_ID)
    cl.c('DELETE', '/os-keypairs/' + KEYPAIR)
    for i in k8s_images(cl):
        cl.g('DELETE', '/images/' + i['id'])
    unregister()
    print('removed the Magnum clusters, template, flavor, key pair, image and Keystone entries')


def registered():
    # Exit 0 only when everything register() makes is in place: the user magnum (able to log in with the stored
    # password) with the admin role on project service, the service of type container-infra with its three
    # endpoints, the trustee domain magnum and its admin user (able to log in, with the admin role on the
    # domain). Makes no change.
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = find(token, '/projects?name=service', 'projects')
    user = find(token, '/users?name=magnum', 'users')
    admin_role = find(token, '/roles?name=admin', 'roles')
    if project is None or user is None or admin_role is None:
        die('project service, user magnum or role admin is missing')
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'magnum', 'domain': {'name': 'Default'}, 'password': MAGNUM_PW}}}}}
    s, _, _ = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('user magnum cannot log in with the stored password')
    s, _, _ = call('GET', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s != 204:
        die('user magnum has no admin role on project service (HTTP %s)' % s)
    service = find(token, '/services?type=container-infra', 'services')
    if service is None:
        die('service of type container-infra is missing')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != MAGNUM + '/v1':
            die('the %s endpoint is missing or differs' % iface)
    domain = find(token, '/domains?name=magnum', 'domains')
    if domain is None:
        die('domain magnum is missing')
    dadmin = find(token, '/users?name=magnum_domain_admin&domain_id=' + domain['id'], 'users')
    if dadmin is None:
        die('user magnum_domain_admin is missing')
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'magnum_domain_admin', 'domain': {'name': 'magnum'}, 'password': DOMAIN_PW}}}}}
    s, _, _ = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('user magnum_domain_admin cannot log in with the stored password')
    s, _, _ = call('GET', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
    if s != 204:
        die('user magnum_domain_admin has no admin role on domain magnum (HTTP %s)' % s)
    print('magnum is registered in keystone')


def resources_ok():
    # Exit 0 when the flavor and the key pair that resources() makes exist. Makes no change.
    cl = Clients()
    s, _, _ = cl.c('GET', '/flavors/' + FLAVOR_ID)
    if s != 200:
        die('flavor %s is missing (HTTP %s)' % (FLAVOR_ID, s))
    s, _, _ = cl.c('GET', '/os-keypairs/' + KEYPAIR)
    if s != 200:
        die('key pair %s is missing (HTTP %s)' % (KEYPAIR, s))
    print('flavor %s and key pair %s exist' % (FLAVOR_ID, KEYPAIR))


def template_ok():
    # Exit 0 when the template exists with the Kubernetes image Glance has now and the DNS server chosen, which
    # is what ensure_template() leaves. Makes no change.
    cl = Clients()
    imgs = k8s_images(cl)
    if not imgs:
        die('no Kubernetes image in Glance')
    s, p, _ = cl.m('GET', '/clustertemplates')
    if s != 200:
        die('template list failed: HTTP %s %s' % (s, p))
    for t in p.get('clustertemplates', []):
        if t.get('name') == TEMPLATE and t.get('image_id') == imgs[0]['id'] and t.get('dns_nameserver') == DNS:
            print('cluster template %s is in place (%s)' % (TEMPLATE, t['uuid']))
            return
    die('cluster template %s is missing or differs' % TEMPLATE)


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
    elif cmd == 'mservices':
        mservices()
    elif cmd == 'resources':
        resources()
    elif cmd == 'resources-ok':
        resources_ok()
    elif cmd == 'image-present':
        image_present()
    elif cmd == 'image-upload' and len(sys.argv) > 4:
        image_upload(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == 'template':
        template()
    elif cmd == 'template-ok':
        template_ok()
    elif cmd == 'cluster-cycle':
        cluster_cycle()
    elif cmd == 'delete-clusters':
        delete_clusters()
    elif cmd == 'teardown':
        teardown()
    else:
        die('usage: orphera-magnum-api register | registered | unregister | catalog | mservices | resources | resources-ok | image-present | image-upload FILE KUBE_VERSION OS_VERSION | template | template-ok | cluster-cycle | delete-clusters | teardown')
"""


  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@MAGNUM@@", s"http://$fqdn:$apiPort")
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@MAGNUMPW@@", magnumPassword.reveal)
      .replace("@@DOMAINPW@@", domainAdminPassword.reveal)
      .replace("@@CAFILE@@", caCert)
      .replace("@@FLAVORID@@", k8sFlavorId)
      .replace("@@DNS@@", dnsServer)

  // ---- magnum.conf ---------------------------------------------------------

  private val magnumConf: String =
    s"""# Managed by Orphera (magnum_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/magnum
       |use_stderr = true
       |state_path = /var/lib/magnum
       |
       |[api]
       |host = 0.0.0.0
       |port = $apiPort
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = magnum
       |password = ${magnumPassword.reveal}
       |cafile = /etc/magnum/ssl/ca.crt
       |
       |[keystone_auth]
       |auth_url = $ksUrl/v3
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = magnum
       |password = ${magnumPassword.reveal}
       |cafile = /etc/magnum/ssl/ca.crt
       |
       |[trust]
       |trustee_domain_name = magnum
       |trustee_domain_admin_name = magnum_domain_admin
       |trustee_domain_admin_password = ${domainAdminPassword.reveal}
       |trustee_keystone_interface = public
       |
       |[certificates]
       |cert_manager_type = x509keypair
       |
       |[drivers]
       |openstack_ca_file = /etc/magnum/ssl/ca.crt
       |verify_ca = true
       |disabled_drivers = k8s_cluster_api_ubuntu,k8s_cluster_api_ubuntu_focal,k8s_cluster_api_debian,k8s_cluster_api_flatcar,k8s_cluster_api_rockylinux
       |
       |[capi_helm]
       |kubeconfig_file = /etc/magnum/kubeconfig
       |default_helm_chart_version = $chartVersion
       |
       |[nova_client]
       |region_name = RegionOne
       |endpoint_type = publicURL
       |ca_file = /etc/magnum/ssl/ca.crt
       |
       |[glance_client]
       |region_name = RegionOne
       |endpoint_type = publicURL
       |ca_file = /etc/magnum/ssl/ca.crt
       |
       |[neutron_client]
       |region_name = RegionOne
       |endpoint_type = publicURL
       |ca_file = /etc/magnum/ssl/ca.crt
       |
       |[cinder_client]
       |region_name = RegionOne
       |endpoint_type = publicURL
       |ca_file = /etc/magnum/ssl/ca.crt
       |
       |[octavia_client]
       |region_name = RegionOne
       |endpoint_type = publicURL
       |ca_file = /etc/magnum/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/magnum/tmp
       |""".stripMargin


  // The files every service's config.json copies in, and the ownership it sets.
  private def configJson(service: String): String =
    s"""{"command": "$service", "config_files": [
       |  {"source": "/var/lib/kolla/config_files/magnum.conf", "dest": "/etc/magnum/magnum.conf", "owner": "magnum", "perm": "0600"},
       |  {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/magnum/ssl/ca.crt", "owner": "magnum", "perm": "0644"},
       |  {"source": "/var/lib/kolla/config_files/kubeconfig", "dest": "/etc/magnum/kubeconfig", "owner": "magnum", "perm": "0600"}
       |], "permissions": [
       |  {"path": "/var/log/kolla/magnum", "owner": "magnum:kolla", "recurse": true},
       |  {"path": "/var/lib/magnum", "owner": "magnum:magnum", "recurse": true}
       |]}
       |""".stripMargin

  // magnum-db-manage runs in a one-off container with magnum.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run) and the driver directory on the path. `:z` relabels for SELinux hosts and is
  // ignored elsewhere.
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint magnum-db-manage -v $confRoot/magnum-api/magnum.conf:/etc/magnum/magnum.conf:ro,z -v $logDir:/var/log/kolla/magnum:z -e PYTHONPATH=$pylibDir -v $pylibDir:$pylibDir:ro,z $apiBase --config-file /etc/magnum/magnum.conf upgrade
       |echo "magnum database synced"""".stripMargin

  // Runs a Python snippet in the magnum-api image, with its own interpreter (the one named on the first line of
  // magnum-db-manage), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`. Exit 0
  // when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $apiBase -c 'exec $$(head -1 "$$(command -v magnum-db-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbStamp = Resource.sha256(dbSyncScript + magnumConf)
  // True when the magnum database, queried as the magnum user through the VIP, has the `cluster_template` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM cluster_template')")
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // ---- the Kubernetes node image -------------------------------------------

  private def fill(s: String): String =
    s.replace("@@STATE@@", stateDir).replace("@@HELPER@@", helperPath)

  // A step that runs for minutes with no output can lose its connection to the agent, so the download and the
  // upload each print a line every 20 seconds while they work.
  private val heartbeat =
    """heartbeat() { "$@" & P=$!; while kill -0 $P 2>/dev/null; do sleep 20; echo "still working ($(date +%H:%M:%S))"; done; wait $P; }"""

  // What was asked for, to tell a downloaded image from one for another choice.
  private val imageChoice =
    Resource.sha256(k8sVersion + "|" + k8sMinor + "|" + k8sImageUrl + "|" + k8sOsVersion + "|" + manifestUrl)

  // Step one: choose the image, download it and check it, and note what was chosen in image.current.
  private val fetchImageScript =
    fill(
      s"""set -e
         |$heartbeat
         |K8S_VERSION='$k8sVersion'
         |K8S_MINOR='$k8sMinor'
         |IMAGE_URL='$k8sImageUrl'
         |OS_VERSION='$k8sOsVersion'
         |MANIFEST_URL='$manifestUrl'
         |SUM=
         |if [ -z "$$IMAGE_URL" ]; then
         |  curl -fsSL --retry 3 --connect-timeout 30 -o @@STATE@@/manifest.json "$$MANIFEST_URL" || { echo "cannot download $$MANIFEST_URL - set K8S_IMAGE_URL, K8S_VERSION and K8S_OS_VERSION" >&2; exit 1; }
         |  python3 -I - <<'PY' > @@STATE@@/image.env
         |import json, os, re, sys
         |data = json.load(open(os.environ.get('MANIFEST', '@@STATE@@/manifest.json')))
         |want_version = os.environ.get('WANT_VERSION', '')
         |want_minor = os.environ.get('WANT_MINOR', '')
         |entries = []
         |def walk(obj):
         |    if isinstance(obj, dict):
         |        if 'url' in obj and 'name' in obj:
         |            entries.append(obj)
         |        else:
         |            for v in obj.values():
         |                walk(v)
         |    elif isinstance(obj, list):
         |        for v in obj:
         |            walk(v)
         |walk(data)
         |found = []
         |for e in entries:
         |    m = re.search(r'kube-v(\\d+)\\.(\\d+)\\.(\\d+)', e['name'])
         |    if not m:
         |        continue
         |    ver = tuple(int(x) for x in m.groups())
         |    vs = 'v%d.%d.%d' % ver
         |    if want_version and vs != want_version:
         |        continue
         |    if want_minor and '%d.%d' % ver[:2] != want_minor:
         |        continue
         |    osv = '24.04' if 'noble' in e['name'] else '22.04'
         |    found.append((ver, osv, vs, e))
         |if not found:
         |    sys.stderr.write('no image in the manifest matches; names seen: %s\\n' % [e.get('name') for e in entries][:10])
         |    sys.exit(1)
         |found.sort(key=lambda t: (t[0], t[1]))
         |ver, osv, vs, e = found[-1]
         |print("IMAGE_URL='%s'" % e['url'])
         |print("K8S_VERSION='%s'" % vs)
         |print("OS_VERSION='%s'" % osv)
         |print("SUM='%s'" % str(e.get('checksum', '')).replace('sha256:', ''))
         |PY
         |  . @@STATE@@/image.env
         |fi
         |if [ -z "$$IMAGE_URL" ] || [ -z "$$K8S_VERSION" ] || [ -z "$$OS_VERSION" ]; then
         |  echo "need the image URL, K8S_VERSION and K8S_OS_VERSION" >&2
         |  exit 1
         |fi
         |echo "image: $$IMAGE_URL ($$K8S_VERSION, Ubuntu $$OS_VERSION)"
         |IMG=@@STATE@@/k8s-image-$$K8S_VERSION.qcow2
         |rm -f @@STATE@@/k8s-image.qcow2
         |if [ -s $$IMG ] && [ -n "$$SUM" ] && ! echo "$$SUM  $$IMG" | sha256sum -c - >/dev/null 2>&1; then
         |  echo "the kept image file does not match its checksum; downloading it again"
         |  rm -f $$IMG
         |fi
         |if [ ! -s $$IMG ]; then
         |  heartbeat curl -fsSL --retry 3 --connect-timeout 30 -o $$IMG.part "$$IMAGE_URL" || { rm -f $$IMG.part; echo "cannot download the image - put it at $$IMG and run again" >&2; exit 1; }
         |  mv $$IMG.part $$IMG
         |fi
         |if [ -n "$$SUM" ]; then
         |  echo "$$SUM  $$IMG" | sha256sum -c - || { echo "checksum mismatch after the download - the image at $$IMAGE_URL differs from its manifest entry" >&2; exit 1; }
         |fi
         |MAGIC=$$(head -c 3 $$IMG)
         |if [ "$$MAGIC" != "QFI" ]; then
         |  echo "$$IMG is not a qcow2 file - delete it and run again" >&2
         |  exit 1
         |fi
         |ls -l $$IMG
         |printf "IMG='%s'\\nK8S_VERSION='%s'\\nOS_VERSION='%s'\\nCHOICE='%s'\\n" "$$IMG" "$$K8S_VERSION" "$$OS_VERSION" "$imageChoice" > @@STATE@@/image.current
         |echo "image ready: $$IMG"""".stripMargin
    )

  // Step two: upload what step one noted.
  private val uploadImageScript =
    fill(
      s"""set -e
         |$heartbeat
         |. @@STATE@@/image.current
         |heartbeat @@HELPER@@ image-upload "$$IMG" "$$K8S_VERSION" "$$OS_VERSION"""".stripMargin
    )

  private val imagePresent = s"$helperPath image-present >/dev/null 2>&1"
  // Nothing to fetch when Glance has an image, or the file for this choice is there.
  private val imageFetched =
    s"""$imagePresent || ( [ -s $stateDir/image.current ] && . $stateDir/image.current && [ "$$CHOICE" = "$imageChoice" ] && [ -s "$$IMG" ] )"""


  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val healthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  private val waitHealthy =
    s"""for i in $$(seq 1 36); do
       |  ( $healthy ) && exit 0
       |  sleep 5
       |done
       |echo "magnum-api does not answer on $apiPort - see 'journalctl -xeu orphera-magnum-api' and $logDir" >&2
       |exit 1""".stripMargin

  // The conductor reports in every few seconds; give it three minutes to appear as `up` through the API.
  private val conductorUp = s"$helperPath mservices >/dev/null 2>&1"
  private val waitConductorUp =
    s"""for i in $$(seq 1 36); do $helperPath mservices && exit 0; sleep 5; done
       |echo "magnum-conductor never reported up - see 'journalctl -xeu orphera-magnum-conductor'" >&2
       |exit 1""".stripMargin

  private val routeOk = "ip route get 10.10.6.200 >/dev/null 2>&1"
  private val routeScript =
    """ip route get 10.10.6.200
      |echo "route to the floating IP network ok"""".stripMargin

  // The test cluster is made again when this changes: the configuration, the driver and the image choice.
  private val cycleStamp = Resource.sha256(
    magnumConf + driverStamp + dnsServer + k8sVersion + k8sMinor + k8sImageUrl + k8sOsVersion + manifestUrl
  )
  private val cycleStampFile = s"$stateDir/cycle.stamp"
  private val cycleCurrent =
    s"""[ "$$(cat $cycleStampFile 2>/dev/null)" = "$cycleStamp" ]"""
  private val cycleScript =
    s"""set -e
       |$heartbeat
       |heartbeat $helperPath cluster-cycle
       |echo $cycleStamp > $cycleStampFile""".stripMargin

  // One service: its resources (written before it starts), the ones that decide when it restarts.
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
      "pull the Magnum images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)
    val commandsOk = command(
      "check that the commands are in the images",
      imageCommandsScript,
      unless = imageCommandsOk,
      timeout = 300
    ).after(pulled)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)
    val dataDirectory = directory(dataDir).after(stateDirectory)
    val tmpDirectory = directory(s"$dataDir/tmp").after(dataDirectory)
    val buildDirectory = directory(buildDir).after(stateDirectory)
    val pylibDirectory = directory(pylibDir).after(stateDirectory)

    val unitServices = containers.map(c => service(c.unitName))
    val driver = command(
      "install the Cluster API driver for Magnum",
      driverScript,
      unless = driverCurrent,
      timeout = 1800
    ).after(
      (commandsOk :: pulled :: podman :: tools(1) :: stateDirectory :: pylibDirectory :: buildDirectory :: Nil)*
    ).notifies(unitServices*)

    // One config directory per service, mounted by Kolla at /var/lib/kolla/config_files.
    val svcs = serviceNames.zip(containers).zip(unitServices).map { case ((name, c), unitService) =>
      val dir = s"$confRoot/$name"
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/magnum.conf",
        Secret(magnumConf), // it holds the database, message bus and service passwords
        mode = "0600",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = command(
        s"copy Keystone's CA to the $name config",
        s"cp $caCert $dir/ca.crt",
        unless = s"cmp -s $caCert $dir/ca.crt"
      ).after(ca, dirRes).notifies(unitService)
      // The management cluster's kubeconfig (capi_management_cluster.scala) is a file on this node.
      val kubeconfig = command(
        s"copy the management cluster kubeconfig to the $name config",
        s"""if [ ! -s $mgmtKubeconfig ]; then echo "no kubeconfig at $mgmtKubeconfig - apply capi_management_cluster.scala first" >&2; exit 1; fi
           |install -m 0600 $mgmtKubeconfig $dir/kubeconfig
           |echo "management cluster kubeconfig copied"""".stripMargin,
        unless = s"[ -s $mgmtKubeconfig ] && cmp -s $mgmtKubeconfig $dir/kubeconfig"
      ).after(dirRes).notifies(unitService)
      val kollaJson = file(
        s"$dir/config.json",
        configJson(name),
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      Svc(
        unitService,
        List(dirRes, conf, kollaCa, kubeconfig, kollaJson, unitFile),
        List(conf, kollaCa, kubeconfig, kollaJson, unitFile),
        conf
      )
    }
    val apiConf = svcs.last.conf

    val dbSync = command(
      "magnum-db-manage upgrade",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(commandsOk, driver, apiConf, logDirectory, stateDirectory)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin, magnum and trustee domain admin passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Magnum and its trustee domain in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    val running = svcs.map { s =>
      s.unit.after(
        (dbSync :: registered :: pulled :: driver :: logDirectory :: dataDirectory :: tmpDirectory :: s.deps)*
      )
    }

    val answering = command(
      "magnum-api answers on its port",
      waitHealthy,
      unless = healthy,
      timeout = 240
    ).after(running*)
    val reporting = command(
      "the conductor reports up through the API",
      waitConductorUp,
      unless = conductorUp,
      timeout = 240
    ).after((answering :: running)*)
    val flavor = command(
      "make the Kubernetes node flavor and key pair",
      s"$helperPath resources",
      unless = s"$helperPath resources-ok >/dev/null 2>&1",
      timeout = 120
    ).after(reporting)
    val fetchedImage = command(
      "download the Kubernetes node image",
      fetchImageScript,
      unless = imageFetched,
      timeout = 3600
    ).after(flavor, stateDirectory)
    val nodeImage = command(
      "upload the Kubernetes node image to Glance",
      uploadImageScript,
      unless = imagePresent,
      timeout = 3600
    ).after(fetchedImage)
    val clusterTemplate = command(
      "make the cluster template",
      s"$helperPath template",
      unless = s"$helperPath template-ok >/dev/null 2>&1",
      timeout = 120
    ).after(nodeImage)
    val route = command(
      "this node reaches the floating IP network",
      routeScript,
      unless = routeOk
    ).after(clusterTemplate)
    val cycle = command(
      "make a Kubernetes cluster, ask its API for the version, delete it",
      cycleScript,
      unless = cycleCurrent,
      timeout = 5400
    ).after(route, stateDirectory)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      commandsOk,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory,
      dataDirectory,
      tmpDirectory,
      buildDirectory,
      pylibDirectory,
      driver
    ) ++ svcs.flatMap(_.resources) ++ List(dbSync, helperFile, registered) ++
      running ++ List(answering, reporting, flavor, fetchedImage, nodeImage, clusterTemplate, route, cycle)
