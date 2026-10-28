// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Magnum (Kubernetes clusters as a service) with the Cluster API Helm driver
// (magnum-capi-helm), Kolla images under podman. Modelled on octavia_single_node.scala and
// manila_single_node.scala; read their headers for the conventions.
//
// Where it runs: tst23 (10.10.5.35), the same VM as the Cluster API management cluster that
// capi_management_cluster.scala builds (apply that first). Magnum does not build clusters itself: for every
// Kubernetes cluster the driver makes an application credential for the user, stores it as a Secret in the
// management cluster and installs the Helm chart `openstack-cluster` there; Cluster API and the OpenStack
// provider (CAPO) then create the VMs, networks and load balancer in OpenStack, and the Azimuth addon
// provider installs the cluster's add-ons (Calico, the OpenStack cloud controller, the Cinder CSI driver).
//
// What it builds (two Kolla containers on the host network):
//   - magnum-api         the REST API, port 9511, plain HTTP like the other APIs here
//   - magnum-conductor   does the work; loads the driver
// The Kolla images do not contain the Cluster API driver or helm, so the playbook builds two small images on
// the node from them (podman build): `localhost/orphera/magnum-api-capi` and `.../magnum-conductor-capi`, which
// add the pip package `magnum-capi-helm` (installed without its dependencies, then imported once as a check)
// and the helm binary that capi_management_cluster.scala installed. MAGNUM_CAPI_HELM_SPEC sets the pip
// requirement (default `magnum-capi-helm`).
//
// What it makes besides the containers (the helper's subcommands, repeatable):
//   - Keystone: user `magnum` (project service, admin), service `magnum` of type `container-infra` with the
//     endpoint http://<fqdn>:9511/v1, and the trustee domain `magnum` with its admin user `magnum_domain_admin`
//     (Magnum creates its per-cluster trustee users there)
//   - the Nova flavor `orphera.k8s` (id orphera-k8s; 2 vCPU, 3072 MB, 30 GB), the driver's minimum being 2 vCPU
//     and 2048 MB
//   - a Kubernetes node image in Glance (public, with os_distro, os_version and kube_version properties,
//     which the driver reads). The image is downloaded on the node from the Azimuth image releases manifest;
//     K8S_VERSION (for example v1.31.4) or K8S_MINOR (1.31) chooses the version, K8S_IMAGE_URL names an image
//     directly (then K8S_VERSION and K8S_OS_VERSION are needed too). Without any of them the newest in the
//     manifest is taken, which may be newer than the chart supports; pin it if clusters do not build.
//   - the public cluster template `orphera-k8s` (Calico, the provider network as the external network, DNS
//     server MAGNUM_DNS, default 1.1.1.1)
//
// Magnum's certificates are kept in its database (x509keypair), not in Barbican. The Keystone CA is passed to
// the driver ([drivers] openstack_ca_file), which puts it into the cloud credentials that CAPO and the
// cluster's OpenStack integrations use.
//
// Versions: the Helm chart is MAGNUM_CHART_VERSION (default 0.26.0, the driver's own default). The management
// cluster has Cluster API v1.14, which still serves the v1beta1 API versions the driver uses.
//
// Prerequisites, not checked here: capi_management_cluster.scala applied on tst23; Heat is NOT needed. Galera,
// Keystone, RabbitMQ (tst10), Glance, Nova with compute nodes, Neutron with the L3 agent and the provider
// network, and Octavia (the API load balancer of every cluster is an Octavia amphora) healthy; tst23 can reach
// the floating IP network (10.10.6.0/24) and the clusters' nodes can reach the internet through their router;
// the compute nodes have room: one test cluster is a control-plane node and a worker (2 vCPU, 3 GB each) and an
// amphora (1 GB). The Magnum images (magnum-api, magnum-conductor) on tst21's registry or quay.io.
//
// Stages:
//   - magnum-database (tst3), magnum-rabbitmq (tst10)
//   - install-magnum (tst23): packages, cache mirror, image pulls, the driver images, Keystone's CA, helper,
//     registration and trustee domain, kubeconfig and magnum.conf, Kolla config, `magnum-db-manage upgrade`,
//     the units
//   - magnum-resources (tst23): flavor, node image, cluster template
//   - confirm-magnum-healthy (tst23): the API answers, the conductor is up, and a real cluster (one control-plane
//     node, one worker) is made, its API address answers /version, and it is deleted. This takes tens of minutes.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/magnum_single_node.scala`.
//
// Facts about Magnum this was written from, to check if the first run fails: console scripts magnum-api,
// magnum-conductor and magnum-db-manage are in the images; the config sections [keystone_auth], [trust],
// [certificates], [drivers] (openstack_ca_file, verify_ca) and [capi_helm]; the driver reads the image
// properties os_distro and kube_version; the Azimuth manifest.json is a JSON object whose values have `name`,
// `url` and `checksum`. Not compiled where this was written; the first run on scala0 is the first compile.
//
// Test-only credentials come from the secret store (magnum/db, magnum/service, magnum/rabbit,
// magnum/domain-admin), made on first use.
object magnum_single_node extends OrpheraClusterPlaybook:

  private val node = "tst23"
  private val fqdn = "tst23.ljalbinson.com"
  private val nodeIp = "10.10.5.35"
  private val apiPort = 9511

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
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
  private val dnsServer = env("MAGNUM_DNS", "1.1.1.1")
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
  private val srcDir = "/etc/kolla/magnum"
  private val logDir = "/var/log/kolla/magnum"
  private val stateDir = "/var/lib/orphera/magnum"
  private val dataDir = "/var/lib/orphera/magnum/data"
  private val buildDir = "/var/lib/orphera/magnum/build"
  private val caDir = "/etc/kolla/magnum-ca"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val mgmtKubeconfig = "/var/lib/orphera/capi/kubeconfig"
  private val helperPath = "/usr/local/sbin/orphera-magnum-api"

  private val apiBase =
    Image("magnum-api", Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val conductorBase =
    Image("magnum-conductor", Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val apiCapiImage = "localhost/orphera/magnum-api-capi:2025.1-ubuntu-noble"
  private val conductorCapiImage =
    "localhost/orphera/magnum-conductor-capi:2025.1-ubuntu-noble"

  // The two containers as data. They run the locally built images. Defined after the directory vals, which
  // they read; a val used before its definition is null in an object.
  private def magnumContainer(service: String, image: String): Container =
    Container(
      name = ContainerName(service),
      image = ExternalImage(image),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/magnum", Access.ReadWrite),
        Mount(dataDir, "/var/lib/magnum", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      user = Some("root"),
      settleSeconds = 12
    )
  private val magnumServices: List[Container] =
    List(
      magnumContainer("magnum-conductor", conductorCapiImage),
      magnumContainer("magnum-api", apiCapiImage)
    )

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

  private def fill(s: String): String =
    s.replace("@@CONF@@", confRoot)
      .replace("@@SRC@@", srcDir)
      .replace("@@LOG@@", logDir)
      .replace("@@STATE@@", stateDir)
      .replace("@@DATA@@", dataDir)
      .replace("@@BUILD@@", buildDir)
      .replace("@@CA@@", caDir)
      .replace("@@HELPER@@", helperPath)
      .replace("@@KUBECONFIG@@", mgmtKubeconfig)

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes" >&2
      |exit 1""".stripMargin

  // ---- tst3 / tst10 ----------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val rabbitScript =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - run cinder_single_node.scala first" >&2
       |  exit 1
       |fi
       |if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$rabbitVhost'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$rabbitVhost'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$rabbitUser'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$rabbitUser' '${rabbitPassword.reveal}'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$rabbitUser' '${rabbitPassword.reveal}'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$rabbitVhost' '$rabbitUser' '.*' '.*' '.*'
       |echo "rabbitmq vhost $rabbitVhost and user $rabbitUser ready"""".stripMargin

  // ---- the node --------------------------------------------------------

  private val useCacheScript =
    s"""mkdir -p /etc/containers/registries.conf.d
       |cat > /etc/containers/registries.conf.d/orphera-kolla-cache.conf <<'REG'
       |[[registry]]
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
       |REG
       |echo "podman will try tst21.ljalbinson.com:5000 (built images), then $cacheHostPort, before quay.io"""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in magnum_single_node.scala"
      )}
       |for img in $apiBase $conductorBase; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  private val baseCheckScript =
    s"""for spec in "$apiBase magnum-api" "$conductorBase magnum-conductor" "$apiBase magnum-db-manage"; do
       |  set -- $$spec
       |  if ! podman run --rm --entrypoint sh $$1 -c "command -v $$2 >/dev/null"; then
       |    echo "$$2 not found in $$1" >&2
       |    exit 1
       |  fi
       |done
       |echo "the Magnum commands are in the images"""".stripMargin

  private val fetchCaScript =
    fill(
      s"""set -e
         |mkdir -p @@CA@@
         |rm -f /tmp/ks-chain-*.pem
         |openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/ks-chain-" n ".pem")}'
         |COUNT=$$(ls /tmp/ks-chain-*.pem 2>/dev/null | wc -l)
         |if [ "$$COUNT" -lt 2 ]; then
         |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - re-run keystone_single_node.scala (it must serve server-chain.crt)" >&2
         |  exit 1
         |fi
         |cp /tmp/ks-chain-$$COUNT.pem @@CA@@/ca.crt
         |openssl verify -CAfile @@CA@@/ca.crt /tmp/ks-chain-1.pem
         |SUBJ=$$(openssl x509 -in @@CA@@/ca.crt -noout -subject | sed 's/^subject=//')
         |ISS=$$(openssl x509 -in @@CA@@/ca.crt -noout -issuer | sed 's/^issuer=//')
         |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
         |rm -f /tmp/ks-chain-*.pem
         |cp @@CA@@/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
         |update-ca-certificates
         |echo "Keystone CA installed: $$SUBJ"""".stripMargin
    )

  private val dirsScript =
    fill(
      "mkdir -p @@SRC@@ @@LOG@@ @@STATE@@ @@DATA@@ @@DATA@@/tmp @@BUILD@@\necho directories ready"
    )

  // The driver images: the Kolla image plus the pip package and the helm binary. The package is installed
  // without its dependencies, so pip cannot replace the Magnum and oslo libraries in the image; a missing
  // dependency shows up in the import check, which names it.
  private def containerfile(base: String): String =
    s"""FROM $base
       |USER root
       |COPY helm /usr/local/bin/helm
       |RUN chmod 0755 /usr/local/bin/helm \\
       | && PY=/var/lib/kolla/venv/bin/python; [ -x "$$PY" ] || PY=$$(command -v python3) \\
       | && if command -v uv >/dev/null 2>&1; then uv pip install --python "$$PY" --no-deps '$capiHelmSpec'; else "$$PY" -m pip install --no-deps '$capiHelmSpec'; fi \\
       | && "$$PY" -c 'import magnum_capi_helm.driver; print("driver import ok")'
       |""".stripMargin

  private val buildScript =
    fill(s"""set -e
         |if [ ! -x /usr/local/bin/helm ]; then
         |  echo "helm is not installed - apply capi_management_cluster.scala first" >&2
         |  exit 1
         |fi
         |cd @@BUILD@@
         |cp /usr/local/bin/helm helm
         |cat > Containerfile.api <<'CF'
         |${containerfile(apiBase)}CF
         |cat > Containerfile.conductor <<'CF'
         |${containerfile(conductorBase)}CF
         |podman build --format docker -t $apiCapiImage -f Containerfile.api .
         |podman build --format docker -t $conductorCapiImage -f Containerfile.conductor .
         |for img in $apiCapiImage $conductorCapiImage; do
         |  podman run --rm --entrypoint helm $$img version --short
         |done
         |echo "driver images built"
         |""".stripMargin)

  // Standard-library Python, as in the other service playbooks. Subcommands:
  //   register | unregister | catalog | mservices | resources | image-present | image-upload FILE KUBE_VERSION
  //   OS_VERSION | template | cluster-cycle | teardown
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
            if t.get('image_id') == img['id']:
                return t['uuid']
            s, _, _ = cl.m('DELETE', '/clustertemplates/' + t['uuid'])
            if s not in (204, 202):
                die('the template %s uses another image and cannot be replaced (clusters use it): HTTP %s' % (TEMPLATE, s))
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


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'mservices':
        mservices()
    elif cmd == 'resources':
        resources()
    elif cmd == 'image-present':
        image_present()
    elif cmd == 'image-upload' and len(sys.argv) > 4:
        image_upload(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == 'template':
        template()
    elif cmd == 'cluster-cycle':
        cluster_cycle()
    elif cmd == 'teardown':
        teardown()
    else:
        die('usage: orphera-magnum-api register | unregister | catalog | mservices | resources | image-present | image-upload FILE KUBE_VERSION OS_VERSION | template | cluster-cycle | teardown')
"""

  private val helperScript =
    "cat > " + helperPath + " <<'PYEOF'\n" +
      fill(apiHelperPython)
        .replace("@@KS@@", ksFqdn)
        .replace("@@MAGNUM@@", s"http://$fqdn:$apiPort")
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@MAGNUMPW@@", magnumPassword.reveal)
        .replace("@@DOMAINPW@@", domainAdminPassword.reveal)
        .replace("@@CAFILE@@", s"$caDir/ca.crt")
        .replace("@@FLAVORID@@", k8sFlavorId)
        .replace("@@DNS@@", dnsServer) +
      "\nPYEOF\nchmod 700 " + helperPath + "\necho wrote " + helperPath + "\n"

  // ---- magnum.conf -----------------------------------------------------

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

  private val kollaConfigScript =
    fill(
      """set -e
        |for svc in api conductor; do
        |  d=@@CONF@@/magnum-$svc
        |  mkdir -p $d
        |  cp @@SRC@@/magnum.conf @@CA@@/ca.crt @@SRC@@/kubeconfig $d/
        |  chmod 600 $d/kubeconfig
        |  cat > $d/config.json <<JSON
        |{"command": "magnum-$svc", "config_files": [
        |  {"source": "/var/lib/kolla/config_files/magnum.conf", "dest": "/etc/magnum/magnum.conf", "owner": "magnum", "perm": "0600"},
        |  {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/magnum/ssl/ca.crt", "owner": "magnum", "perm": "0644"},
        |  {"source": "/var/lib/kolla/config_files/kubeconfig", "dest": "/etc/magnum/kubeconfig", "owner": "magnum", "perm": "0600"}
        |], "permissions": [
        |  {"path": "/var/log/kolla/magnum", "owner": "magnum:kolla", "recurse": true},
        |  {"path": "/var/lib/magnum", "owner": "magnum:magnum", "recurse": true}
        |]}
        |JSON
        |  python3 -c "import json; json.load(open('$d/config.json'))"
        |done
        |echo "kolla config written under @@CONF@@"
        |""".stripMargin
    )

  private val kubeconfigScript =
    fill(
      """set -e
        |if [ ! -s @@KUBECONFIG@@ ]; then
        |  echo "no kubeconfig at @@KUBECONFIG@@ - apply capi_management_cluster.scala first" >&2
        |  exit 1
        |fi
        |install -m 0600 @@KUBECONFIG@@ @@SRC@@/kubeconfig
        |echo "management cluster kubeconfig copied"
        |""".stripMargin
    )

  private val dbSyncScript =
    fill(
      s"""set -e
         |podman run --rm --network host --user root --entrypoint magnum-db-manage -v @@SRC@@/magnum.conf:/etc/magnum/magnum.conf:ro,z -v @@LOG@@:/var/log/kolla/magnum:z $apiCapiImage --config-file /etc/magnum/magnum.conf upgrade
         |echo "magnum database synced"""".stripMargin
    )

  // ---- the Kubernetes node image ---------------------------------------

  private val imageScript =
    fill(
      s"""set -e
         |if @@HELPER@@ image-present; then
         |  exit 0
         |fi
         |K8S_VERSION='$k8sVersion'
         |K8S_MINOR='$k8sMinor'
         |IMAGE_URL='$k8sImageUrl'
         |OS_VERSION='$k8sOsVersion'
         |MANIFEST_URL='$manifestUrl'
         |IMG=@@STATE@@/k8s-image.qcow2
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
         |if [ ! -s $$IMG ]; then
         |  curl -fL --retry 3 --connect-timeout 30 -o $$IMG.part "$$IMAGE_URL" || { rm -f $$IMG.part; echo "cannot download the image - put it at $$IMG and run again" >&2; exit 1; }
         |  mv $$IMG.part $$IMG
         |fi
         |if [ -n "$$SUM" ]; then
         |  echo "$$SUM  $$IMG" | sha256sum -c - || { echo "checksum mismatch - delete $$IMG and run again" >&2; exit 1; }
         |fi
         |MAGIC=$$(head -c 3 $$IMG)
         |if [ "$$MAGIC" != "QFI" ]; then
         |  echo "$$IMG is not a qcow2 file - delete it and run again" >&2
         |  exit 1
         |fi
         |ls -l $$IMG
         |@@HELPER@@ image-upload $$IMG $$K8S_VERSION $$OS_VERSION""".stripMargin
    )

  // The API answers its version document without a token.
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  private val routeCheckScript =
    """ip route get 10.10.6.200
      |echo "route to the floating IP network ok"""".stripMargin

  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")
  private val unitResource = summon[Resource[Container]]

  private def withServices(b: StageBuilder): StageBuilder =
    val written = magnumServices.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    magnumServices.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("magnum-single-node-check")(
        magnumServices
          .foldLeft(stage("check-magnum", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          )
          .build
      )
    else applyPlaybook

  private def runScript(script: String, seconds: Int = 60): Task =
    Task.RunCommand(List("sh", "-c", script), timeoutSeconds = seconds)

  private def installMagnum: StageBuilder =
    withServices(
      stage("install-magnum", node)
        .task("wait for apt and dpkg to be idle")(
          runScript(waitAptIdleScript, 960)
        )
        .task("install podman, openssl and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          runScript(useCacheScript)
        )
        .task("pull the Magnum images")(runScript(pullScript, 1800))
        .task("check that the commands are in the images")(
          runScript(baseCheckScript, 300)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          runScript(fetchCaScript)
        )
        .task("create the Magnum directories")(runScript(dirsScript))
        .task("build the images with the Cluster API driver and helm")(
          runScript(buildScript, 1800)
        )
        .task("write the Magnum API helper")(runScript(helperScript))
        .task("register Magnum and its trustee domain in Keystone")(
          runScript(s"$helperPath register", 120)
        )
        .task("copy the management cluster kubeconfig")(
          runScript(kubeconfigScript)
        )
        .task("write magnum.conf")(
          Task.WriteFile(
            content = magnumConf,
            dest = s"$srcDir/magnum.conf",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("write the Kolla config")(runScript(kollaConfigScript, 240))
        .task("magnum-db-manage upgrade")(runScript(dbSyncScript, 600))
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("magnum-single-node")(
      stage("magnum-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          runScript(createDatabaseScript)
        )
        .build,

      stage("magnum-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(runScript(rabbitScript, 120))
        .build,

      installMagnum.build,

      stage("magnum-resources", node)
        .task("make the Kubernetes node flavor and key pair")(
          runScript(s"$helperPath resources", 120)
        )
        .task("download and upload the Kubernetes node image")(
          runScript(imageScript, 3600)
        )
        .build,

      stage("confirm-magnum-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 180
          )
        )
        .task("the conductor is up")(runScript(s"$helperPath mservices", 180))
        .task("make the cluster template")(
          runScript(s"$helperPath template", 120)
        )
        .task("this node reaches the floating IP network")(
          runScript(routeCheckScript)
        )
        .task(
          "make a Kubernetes cluster, ask its API for the version, delete it"
        )(runScript(s"$helperPath cluster-cycle", 5400))
        .task("magnum confirmed")(
          Task.Debug(
            s"Magnum up at http://$fqdn:$apiPort ($nodeIp): magnum-api and magnum-conductor with the Cluster API Helm driver (chart $chartVersion) against the management cluster on this node; template orphera-k8s; RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_magnum.sh"
          )
        )
        .build
    )
