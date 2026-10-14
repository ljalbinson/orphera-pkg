// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Cinder (block storage), one node (tst10), Kolla images under
// podman, volumes stored in the existing Ceph cluster as RBD images.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala,
//     mariadb_haproxy_keepalived.scala)
//   - Ceph healthy with OSDs (cephadm_*.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//     (the version that writes server-chain.crt)
//   - agent on tst10; tst9's Kolla cache is optional
//
// What it builds on tst10, all as containers run by systemd units
// (orphera-<name>.service), host networking:
//   - rabbitmq        official docker.io/library/rabbitmq image (Cinder needs a
//                     message bus; this is the stock image, not a Kolla one,
//                     because its first-run user setup is a single env var)
//   - cinder-api      Kolla image, Apache + mod_wsgi on 8776, plain HTTP
//   - cinder-scheduler, cinder-volume   Kolla images; the volume service uses
//                     the RBD driver against pool `volumes`
//
// Stages:
//   - cinder-database (tst3, a Galera node): the `cinder` database and user.
//   - ceph-pool-and-user (tst0, a Ceph node): the `volumes` pool, and the
//     `client.cinder` cephx user with a FIXED test key
//     (see cephKey). A fixed key is used because a stage cannot capture
//     another node's command output (there is no register mechanism; see the
//     header of etcd_grow_cluster.scala), and importing a key we already know
//     lets tst10 be configured in the same run.
//   - install-cinder (tst10): packages, cache mirror, image pulls, CA fetched
//     from Keystone's TLS handshake, RabbitMQ, config for the three services,
//     db sync, the units, then registration in Keystone (project `service`,
//     user `cinder`, service volumev3, three endpoints).
//   - confirm-cinder-healthy (tst10): API answers, then both scheduler and
//     volume services report `up` through the API.
//
// TLS: Keystone is TLS; Cinder trusts its CA, which this playbook obtains from
// the Keystone handshake (trust on first use - fine for a lab; a real
// deployment would distribute the CA out of band). Cinder's own API is plain
// HTTP in this draft.
//
// Test-only hardcoded credentials, same convention and caveat as the other
// manifests. keystone's admin password below must match keystone_single_node.
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object cinder_single_node extends OrpheraClusterPlaybook:

  private val node = "tst10"
  private val fqdn = "tst10.ljalbinson.com"
  private val nodeIp = "10.10.5.22"

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = "orphera-test-keystone-admin-password"
  private val dbName = "cinder"
  private val dbUser = "cinder"
  private val dbPassword = "orphera-test-cinder-db-password"
  private val cinderPassword = "orphera-test-cinder-service-password"
  private val rabbitUser = "openstack"
  private val rabbitPassword = "orphera-test-rabbit-password"

  // A valid cephx key (base64 of a type/timestamp/length header plus 16 random
  // bytes), generated once and committed on purpose: test infrastructure only.
  // Rotate it with `ceph auth import` / `ceph auth del client.cinder` if this
  // repository is ever used for anything real.
  private val cephKey = "AQAAeOdoAAAAABAAZtOwIBqwSdv7t+vxPfUfpQ=="
  private val cephPool = "volumes"

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/cinder"
  private val logDir = "/var/log/kolla/cinder"
  private val stateDir = "/var/lib/orphera/cinder"
  private val caDir = "/etc/kolla/cinder-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The containers as data (orchestrator/.../kolla/Container.scala, checked by ContainerCheck).
  // Defined after confRoot, logDir and stateDir, which they read; a val used before its
  // definition is null in an object.
  //
  // Standard-library Python so nothing has to be installed on the node. Kept
  // as a plain (non-interpolated) string and filled in with replace(), so its
  // braces, percent signs and backslashes reach the file untouched. Subcommands:
  //   register | volume-services | volume-create | volume-delete <id>
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
CINDER = '@@CINDER@@'
ADMIN_PW = '@@ADMINPW@@'
CINDER_PW = '@@CINDERPW@@'
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
    return headers['X-Subject-Token'], parsed['token']['project']['id']


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = find(token, '/projects?name=service', 'projects')
    if project is None:
        s, p, _ = call('POST', KS + '/projects', {'project': {'name': 'service', 'domain_id': 'default'}}, token)
        if s != 201:
            die('creating project service failed: HTTP %s %s' % (s, p))
        project = p['project']
    user = find(token, '/users?name=cinder', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'cinder', 'domain_id': 'default', 'password': CINDER_PW}}, token)
        if s != 201:
            die('creating user cinder failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': CINDER_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to cinder failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=volumev3', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'cinderv3', 'type': 'volumev3'}}, token)
        if s != 201:
            die('creating service volumev3 failed: HTTP %s %s' % (s, p))
        service = p['service']
    url = CINDER + '/v3/%(project_id)s'
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)
    print('registered cinder in keystone (project service, user cinder, service volumev3, 3 endpoints)')


def volume_services():
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('GET', '%s/v3/%s/os-services' % (CINDER, pid), None, token)
    if s != 200:
        die('os-services failed: HTTP %s %s' % (s, p))
    up = set()
    for svc in p.get('services', []):
        print('%s on %s: status=%s state=%s' % (svc.get('binary'), svc.get('host'), svc.get('status'), svc.get('state')))
        if svc.get('state') == 'up':
            up.add(svc.get('binary'))
    sys.exit(0 if ('cinder-scheduler' in up and 'cinder-volume' in up) else 1)


def volume_create():
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('POST', '%s/v3/%s/volumes' % (CINDER, pid), {'volume': {'size': 1, 'name': 'orphera-test'}}, token)
    if s not in (200, 202):
        die('volume create failed: HTTP %s %s' % (s, p))
    vid = p['volume']['id']
    for _ in range(45):
        s, p, _ = call('GET', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
        status = p.get('volume', {}).get('status')
        if status == 'available':
            print(vid)
            return
        if status == 'error':
            die('volume %s went to error' % vid)
        time.sleep(2)
    die('volume %s not available after 90s (last status %s)' % (vid, status))


def volume_delete(vid):
    token, pid = get_token('admin', ADMIN_PW, 'admin')
    s, p, _ = call('DELETE', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
    if s not in (200, 202, 204):
        die('volume delete failed: HTTP %s %s' % (s, p))
    for _ in range(45):
        s, p, _ = call('GET', '%s/v3/%s/volumes/%s' % (CINDER, pid, vid), None, token)
        if s == 404:
            print('deleted ' + vid)
            return
        time.sleep(2)
    die('volume %s still present after 90s' % vid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'volume-services':
        volume_services()
    elif cmd == 'volume-create':
        volume_create()
    elif cmd == 'volume-delete' and len(sys.argv) > 2:
        volume_delete(sys.argv[2])
    else:
        die('usage: orphera-openstack-api register | volume-services | volume-create | volume-delete <id>')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-openstack-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@CINDER@@", s"http://$fqdn:8776")
        .replace("@@ADMINPW@@", adminPassword)
        .replace("@@CINDERPW@@", cinderPassword)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-openstack-api\necho wrote /usr/local/sbin/orphera-openstack-api\n"

  // Cinder answers with a version document (HTTP 200 or 300) once Apache is up.
  private val apiHealthScript =
    """CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8776/)
      |[ "$CODE" = "200" ] || [ "$CODE" = "300" ]""".stripMargin

  // The scheduler and volume services report in every few seconds; give them a
  // minute and a half to appear as `up` through the API.
  private val servicesScript =
    """for i in $(seq 1 18); do
      |  if /usr/local/sbin/orphera-openstack-api volume-services; then
      |    exit 0
      |  fi
      |  sleep 5
      |done
      |echo "cinder-scheduler / cinder-volume never reported up - see 'journalctl -xeu orphera-cinder-volume' (RBD/ceph errors show there)" >&2
      |exit 1""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("cinder-single-node")(
      stage("cinder-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("ceph-pool-and-user", "tst0")
        .task(s"create the $cephPool pool and the client.cinder cephx user")(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 180)
        )
        .build,

      withServices(
        stage("install-cinder", node)
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Cinder and RabbitMQ images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("write the ceph client config")(
          Task.RunCommand(List("sh", "-c", cephClientScript))
        )
        .task("write cinder.conf")(
          Task.RunCommand(List("sh", "-c", cinderConfScript))
        )
        .task("write the Kolla config for the three services")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 120
          )
        )
        .task("cinder-manage db sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
        .task("write the OpenStack API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
      )
        .task("register Cinder in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-openstack-api register"),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-cinder-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 90
          )
        )
        .task("scheduler and volume services report up")(
          Task
            .RunCommand(List("sh", "-c", servicesScript), timeoutSeconds = 150)
        )
        .task("cinder confirmed")(
          Task.Debug(
            s"Cinder up at http://$fqdn:8776 ($nodeIp): API, scheduler and volume (Ceph pool $cephPool), RabbitMQ on $nodeIp:5672, database via $vip:3306, identity from $ksUrl. Test: manifests/test_cinder.sh"
          )
        )
        .build
    )
