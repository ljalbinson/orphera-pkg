// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 9: who may do what. Needs k8s_cluster.scala and
// k8s_cert_manager.scala (its stage copied the root CA to tst30) and the
// Keycloak on tst26 (keycloak_single_node.scala).
//
// Keycloak (tst26): a NEW realm `k8s`, separate from the OpenStack realm
// `openstack`. A public client `kubernetes` (authorization code with PKCE for
// `kubectl oidc-login`; the password grant is on only so that this playbook
// can test), a `groups` claim holding the group names, the groups
// k8s-admins, k8s-developers and k8s-viewers, and four test users: k8sadmin,
// k8sdev, k8sview and k8snogroup (in no group). Passwords are the secrets
// keycloak/k8sadmin, keycloak/k8sdev, keycloak/k8sview, keycloak/k8snogroup.
// The helper is /usr/local/sbin/orphera-keycloak-k8s on tst26.
//
// API servers (tst30, tst31, tst32), one at a time: the kube-apiserver static
// pod gets two flags. `--authentication-config` points at a file that makes
// the API server accept Keycloak ID tokens from the realm `k8s` for the client
// `kubernetes` (the username is `oidc:<preferred_username>`, each group is
// `oidc:<group>`); the test CA's certificate is in the file so the API server
// trusts tst26. `--encryption-provider-config` encrypts Secrets in etcd with
// secretbox (key from the secret `k8s/encryption-key`); existing Secrets are
// rewritten so they are encrypted too, and the playbook reads one straight
// from etcd to prove it. Before a manifest is changed it is copied to
// /etc/kubernetes/orphera/kube-apiserver.yaml.pre-oidc; if the API server does
// not become healthy again within 3 minutes the copy is put back and the
// playbook stops, so a mistake cannot take down more than one node.
//
// RBAC: group oidc:k8s-admins -> cluster-admin, oidc:k8s-developers -> edit,
// oidc:k8s-viewers -> view (ClusterRoleBindings). The last stage logs in each
// test user at Keycloak, calls the API with the ID token and checks what each
// may and may not do.
//
// The two flags are not yet in the kubeadm-config ConfigMap, so `kubeadm
// upgrade` would remove them; the upgrade step will carry them. The admin
// certificate kubeconfig on tst30 keeps working whatever Keycloak does.
//
// Human login (kubelogin, run on a workstation):
//   kubectl krew install oidc-login   (or the kubelogin release)
//   kubectl oidc-login setup --oidc-issuer-url=https://tst26.ljalbinson.com:8443/realms/k8s \
//     --oidc-client-id=kubernetes --certificate-authority=<root ca.crt>
//
// Versions and field names I could not check where this was written: the
// AuthenticationConfiguration is `apiserver.config.k8s.io/v1` (served from
// Kubernetes 1.34). If the first API server does not start, the rollback runs
// and the log says so.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_oidc extends OrpheraClusterPlaybook:

  private val kcNode = "tst26"
  private val kcFqdn = "tst26.ljalbinson.com"
  private val kcPort = 8443
  private val kcRealm = "k8s"
  private val issuer = s"https://$kcFqdn:$kcPort/realms/$kcRealm"
  private val clientId = "kubernetes"
  private val cpNodes = List("tst30", "tst31", "tst32")
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val helper = "/usr/local/sbin/orphera-keycloak-k8s"
  private val apiUrl = "https://api.k8s.ljalbinson.com:8443"

  private val kcAdminPassword = Secrets.secret("keycloak/admin")
  private val adminPw = Secrets.secret("keycloak/k8sadmin")
  private val devPw = Secrets.secret("keycloak/k8sdev")
  private val viewPw = Secrets.secret("keycloak/k8sview")
  private val nogroupPw = Secrets.secret("keycloak/k8snogroup")

  // 32 bytes, base64: the secretbox key.
  private val encryptionKey =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Secrets.get("k8s/encryption-key").getBytes("UTF-8"))
    val k = java.util.Base64.getEncoder.encodeToString(digest)
    Secrets.register(k)
    k

  // ---- Keycloak helper (runs on tst26) ----------------------------------

  private val helperPython =
    """#!/usr/bin/python3
import json, ssl, sys, urllib.error, urllib.parse, urllib.request

KC = 'https://@@FQDN@@:@@PORT@@'
REALM = '@@REALM@@'
CLIENT_ID = '@@CLIENT@@'
ADMIN_PW = '@@ADMINPW@@'
CTX = ssl.create_default_context(cafile='/etc/keycloak/tls/ca.crt')
# The test CA has no keyUsage extension; Python 3.13 and later reject that by default.
CTX.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)
GROUPS = ['k8s-admins', 'k8s-developers', 'k8s-viewers']
USERS = [
    # name, password, group (or None), last name
    ('k8sadmin', '@@PWADMIN@@', 'k8s-admins', 'Admin'),
    ('k8sdev', '@@PWDEV@@', 'k8s-developers', 'Developer'),
    ('k8sview', '@@PWVIEW@@', 'k8s-viewers', 'Viewer'),
    ('k8snogroup', '@@PWNOGROUP@@', None, 'Nogroup'),
]


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def call(method, url, body=None, headers=None, form=None):
    hdrs = dict(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdrs['Content-Type'] = 'application/x-www-form-urlencoded'
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs['Content-Type'] = 'application/json'
    elif method in ('POST', 'PUT', 'PATCH'):
        data = b''
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        r = urllib.request.urlopen(req, context=CTX, timeout=60)
        status, text, rh = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, rh = e.code, e.read().decode(), e.headers
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {'raw': text[:300]}
    return status, parsed, rh


def admin_token():
    form = {'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin', 'password': ADMIN_PW}
    s, p, _ = call('POST', KC + '/realms/master/protocol/openid-connect/token', form=form)
    if s != 200 or 'access_token' not in p:
        die('Keycloak admin token: HTTP %s %s' % (s, p))
    return p['access_token']


class Kc:
    def __init__(self):
        self.h = {'Authorization': 'Bearer ' + admin_token()}

    def __call__(self, method, path, body=None):
        return call(method, KC + '/admin/realms' + path, body, self.h)


def ensure_group(kc, name):
    s, found, _ = kc('GET', '/%s/groups?search=%s&exact=true' % (REALM, name))
    if s != 200:
        die('listing groups: HTTP %s %s' % (s, found))
    for g in found:
        if g.get('name') == name:
            return g['id']
    s, p, h = kc('POST', '/%s/groups' % REALM, {'name': name})
    if s != 201:
        die('creating group %s: HTTP %s %s' % (name, s, p))
    return h['Location'].rstrip('/').rsplit('/', 1)[1]


def ensure_user(kc, name, password, last, group_ids):
    user = {'username': name, 'enabled': True, 'email': name + '@ljalbinson.com', 'emailVerified': True,
            'firstName': 'Kubernetes', 'lastName': last, 'requiredActions': []}
    s, found, _ = kc('GET', '/%s/users?username=%s&exact=true' % (REALM, name))
    if s != 200:
        die('listing users: HTTP %s %s' % (s, found))
    if found:
        uid = found[0]['id']
        s, p, _ = kc('PUT', '/%s/users/%s' % (REALM, uid), user)
        if s != 204:
            die('updating user %s: HTTP %s %s' % (name, s, p))
    else:
        s, p, h = kc('POST', '/%s/users' % REALM, user)
        if s != 201:
            die('creating user %s: HTTP %s %s' % (name, s, p))
        uid = h['Location'].rstrip('/').rsplit('/', 1)[1]
    s, p, _ = kc('PUT', '/%s/users/%s/reset-password' % (REALM, uid), {'type': 'password', 'value': password, 'temporary': False})
    if s != 204:
        die('setting the password of %s: HTTP %s %s' % (name, s, p))
    # exactly the wanted group membership
    s, cur, _ = kc('GET', '/%s/users/%s/groups' % (REALM, uid))
    have = set(g['id'] for g in cur) if s == 200 else set()
    want = set(group_ids)
    for gid in have - want:
        kc('DELETE', '/%s/users/%s/groups/%s' % (REALM, uid, gid))
    for gid in want - have:
        s, p, _ = kc('PUT', '/%s/users/%s/groups/%s' % (REALM, uid, gid))
        if s != 204:
            die('adding %s to a group: HTTP %s %s' % (name, s, p))


def realm():
    kc = Kc()
    s, p, _ = kc('GET', '/' + REALM)
    if s == 404:
        s, p, _ = kc('POST', '', {'realm': REALM, 'enabled': True, 'displayName': 'Kubernetes (Orphera lab)'})
        if s != 201:
            die('creating realm %s: HTTP %s %s' % (REALM, s, p))
        print('realm %s created' % REALM)
    elif s != 200:
        die('reading realm %s: HTTP %s %s' % (REALM, s, p))
    client = {
        'clientId': CLIENT_ID, 'name': 'Kubernetes', 'protocol': 'openid-connect', 'enabled': True,
        'publicClient': True, 'standardFlowEnabled': True, 'directAccessGrantsEnabled': True,
        'redirectUris': ['http://localhost:8000/*', 'http://localhost:18000/*'], 'webOrigins': [],
        'attributes': {'pkce.code.challenge.method': 'S256'},
    }
    s, found, _ = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
    if s != 200:
        die('listing clients: HTTP %s %s' % (s, found))
    if found:
        s, p, _ = kc('PUT', '/%s/clients/%s' % (REALM, found[0]['id']), client)
        if s != 204:
            die('updating client: HTTP %s %s' % (s, p))
    else:
        s, p, _ = kc('POST', '/%s/clients' % REALM, client)
        if s != 201:
            die('creating client: HTTP %s %s' % (s, p))
        s, found, _ = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
    cid = found[0]['id']
    mapper = {'name': 'groups', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-group-membership-mapper',
              'config': {'claim.name': 'groups', 'full.path': 'false', 'id.token.claim': 'true',
                         'access.token.claim': 'true', 'userinfo.token.claim': 'true'}}
    s, mappers, _ = kc('GET', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid))
    if s != 200:
        die('listing mappers: HTTP %s %s' % (s, mappers))
    old = [m for m in mappers if m.get('name') == 'groups']
    if old:
        s, p, _ = kc('PUT', '/%s/clients/%s/protocol-mappers/models/%s' % (REALM, cid, old[0]['id']), dict(mapper, id=old[0]['id']))
        ok = 204
    else:
        s, p, _ = kc('POST', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid), mapper)
        ok = 201
    if s != ok:
        die('groups mapper: HTTP %s %s' % (s, p))
    gid = dict((g, ensure_group(kc, g)) for g in GROUPS)
    for name, pw, group, last in USERS:
        ensure_user(kc, name, pw, last, [gid[group]] if group else [])
    print('realm %s: client %s, groups %s, users %s' % (REALM, CLIENT_ID, ', '.join(GROUPS), ', '.join(u[0] for u in USERS)))


def check():
    # The discovery document must name the issuer the API server is configured with.
    s, p, _ = call('GET', KC + '/realms/' + REALM + '/.well-known/openid-configuration')
    if s != 200 or p.get('issuer') != KC + '/realms/' + REALM:
        die('discovery document: HTTP %s %s' % (s, p))
    print('issuer %s' % p['issuer'])


if len(sys.argv) < 2:
    die('usage: orphera-keycloak-k8s realm | check')
if sys.argv[1] == 'realm':
    realm()
elif sys.argv[1] == 'check':
    check()
else:
    die('unknown command ' + sys.argv[1])
"""

  private val helperText =
    helperPython
      .replace("@@FQDN@@", kcFqdn)
      .replace("@@PORT@@", kcPort.toString)
      .replace("@@REALM@@", kcRealm)
      .replace("@@CLIENT@@", clientId)
      .replace("@@ADMINPW@@", kcAdminPassword.reveal)
      .replace("@@PWADMIN@@", adminPw.reveal)
      .replace("@@PWDEV@@", devPw.reveal)
      .replace("@@PWVIEW@@", viewPw.reveal)
      .replace("@@PWNOGROUP@@", nogroupPw.reveal)

  private val realmScript =
    s"""set -e
       |umask 077
       |cat > $helper <<'PYEOF'
       |$helperText
       |PYEOF
       |chmod 700 $helper
       |$helper realm
       |$helper check
       |""".stripMargin

  // ---- files for the API servers (made on tst30) -------------------------

  private val configFilesScript =
    s"""set -e
       |cd $stateDir
       |[ -s ca.crt ] || { echo "$stateDir/ca.crt is missing; run k8s_cert_manager.scala first" >&2; exit 1; }
       |umask 077
       |{
       |cat <<EOF
       |apiVersion: apiserver.config.k8s.io/v1
       |kind: AuthenticationConfiguration
       |jwt:
       |- issuer:
       |    url: $issuer
       |    audiences:
       |    - $clientId
       |    certificateAuthority: |
       |EOF
       |sed 's/^/      /' ca.crt
       |cat <<EOF
       |  claimMappings:
       |    username:
       |      claim: preferred_username
       |      prefix: "oidc:"
       |    groups:
       |      claim: groups
       |      prefix: "oidc:"
       |EOF
       |} > authn.yaml
       |cat > encryption.yaml <<EOF
       |apiVersion: apiserver.config.k8s.io/v1
       |kind: EncryptionConfiguration
       |resources:
       |- resources:
       |  - secrets
       |  providers:
       |  - secretbox:
       |      keys:
       |      - name: key1
       |        secret: $encryptionKey
       |  - identity: {}
       |EOF
       |chmod 600 authn.yaml encryption.yaml
       |echo "authn.yaml and encryption.yaml written"
       |""".stripMargin

  // Adds the two flags, the volume and the mount to the kube-apiserver static
  // pod, waits for the new pod, and puts the old manifest back if it does not
  // become healthy.
  private val patchPython =
    """import sys, yaml
m = '/etc/kubernetes/manifests/kube-apiserver.yaml'
d = yaml.safe_load(open(m))
c = d['spec']['containers'][0]
dirp = '@@DIR@@'
c['command'] += ['--authentication-config=' + dirp + '/authn.yaml', '--encryption-provider-config=' + dirp + '/encryption.yaml']
c.setdefault('volumeMounts', []).append({'name': 'orphera-auth', 'mountPath': dirp, 'readOnly': True})
d['spec'].setdefault('volumes', []).append({'name': 'orphera-auth', 'hostPath': {'path': dirp, 'type': 'Directory'}})
yaml.safe_dump(d, open('/etc/kubernetes/manifests/.kube-apiserver.yaml.new', 'w'), default_flow_style=False)
"""

  private val patchScript =
    s"""set -e
       |M=/etc/kubernetes/manifests/kube-apiserver.yaml
       |if grep -q -- '--authentication-config' $$M; then echo "$$(hostname): kube-apiserver already has the flags"; exit 0; fi
       |[ -s $stateDir/authn.yaml ] && [ -s $stateDir/encryption.yaml ] || { echo "authn.yaml or encryption.yaml missing in $stateDir" >&2; exit 1; }
       |cp $$M $stateDir/kube-apiserver.yaml.pre-oidc
       |cat > /tmp/patch-apiserver.py <<'PYEOF'
       |${patchPython.replace("@@DIR@@", stateDir)}
       |PYEOF
       |python3 /tmp/patch-apiserver.py
       |rm -f /tmp/patch-apiserver.py
       |mv /etc/kubernetes/manifests/.kube-apiserver.yaml.new $$M
       |healthy() {
       |  pgrep -af kube-apiserver | grep -q -- '--authentication-config' || return 1
       |  [ "$$(curl -sk --max-time 3 https://{{cluster_ip}}:6443/livez)" = "ok" ]
       |}
       |wait_healthy() {
       |  for I in $$(seq 1 36); do healthy && return 0; sleep 5; done
       |  return 1
       |}
       |if wait_healthy; then echo "$$(hostname): kube-apiserver restarted with the Keycloak login and secret encryption"; exit 0; fi
       |echo "$$(hostname): kube-apiserver did not become healthy within 3 minutes; restoring the old manifest" >&2
       |cp $stateDir/kube-apiserver.yaml.pre-oidc $$M
       |for I in $$(seq 1 36); do
       |  [ "$$(curl -sk --max-time 3 https://{{cluster_ip}}:6443/livez)" = "ok" ] && pgrep -af kube-apiserver | grep -qv -- '--authentication-config' && break
       |  sleep 5
       |done
       |crictl ps -a --name kube-apiserver 2>/dev/null | head -5 >&2 || true
       |exit 1
       |""".stripMargin

  // After the last API server restarts, haproxy needs a few seconds to stop
  // sending requests to it, so wait for five successful answers in a row.
  private val waitApiScript =
    s"""for I in $$(seq 1 40); do
       |  OKS=0
       |  for J in 1 2 3 4 5; do $kc get --raw /readyz >/dev/null 2>&1 && OKS=$$((OKS+1)); done
       |  [ "$$OKS" -eq 5 ] && break
       |  sleep 3
       |done
       |[ "$$OKS" -eq 5 ] || { echo "the API server behind the VIP is not stable" >&2; exit 1; }
       |""".stripMargin

  private val rbacScript =
    s"""set -e
       |$waitApiScript
       |cat <<EOF | $kc apply -f -
       |apiVersion: rbac.authorization.k8s.io/v1
       |kind: ClusterRoleBinding
       |metadata:
       |  name: oidc-k8s-admins
       |roleRef: {apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: cluster-admin}
       |subjects:
       |- {apiGroup: rbac.authorization.k8s.io, kind: Group, name: "oidc:k8s-admins"}
       |---
       |apiVersion: rbac.authorization.k8s.io/v1
       |kind: ClusterRoleBinding
       |metadata:
       |  name: oidc-k8s-developers
       |roleRef: {apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: edit}
       |subjects:
       |- {apiGroup: rbac.authorization.k8s.io, kind: Group, name: "oidc:k8s-developers"}
       |---
       |apiVersion: rbac.authorization.k8s.io/v1
       |kind: ClusterRoleBinding
       |metadata:
       |  name: oidc-k8s-viewers
       |roleRef: {apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: view}
       |subjects:
       |- {apiGroup: rbac.authorization.k8s.io, kind: Group, name: "oidc:k8s-viewers"}
       |EOF
       |""".stripMargin

  // Rewrites every Secret so it is stored encrypted, then reads one from etcd.
  private val encryptScript =
    s"""set -e
       |$waitApiScript
       |$kc get secrets -A -o json | $kc replace -f - >/dev/null
       |RAW=$$($kc -n kube-system exec etcd-tst30 -- etcdctl --endpoints=https://127.0.0.1:2379 \\
       |  --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt \\
       |  --key=/etc/kubernetes/pki/etcd/server.key get /registry/secrets/cert-manager/orphera-sub-ca | grep -a -c 'k8s:enc:secretbox:v1:key1' || true)
       |[ "$$RAW" -ge 1 ] || { echo "the Secret cert-manager/orphera-sub-ca is not encrypted in etcd" >&2; exit 1; }
       |echo "etcd holds cert-manager/orphera-sub-ca encrypted (k8s:enc:secretbox:v1:key1)"
       |""".stripMargin

  private val loginTestScript =
    s"""set -e
       |CA=$stateDir/ca.crt
       |tok() {
       |  curl -s --cacert $$CA $issuer/protocol/openid-connect/token -d grant_type=password -d client_id=$clientId \\
       |    -d scope=openid -d "username=$$1" --data-urlencode "password=$$2" \\
       |    | python3 -c 'import sys,json; print(json.load(sys.stdin)["id_token"])'
       |}
       |k() { T=$$1; shift; kubectl --server=$apiUrl --certificate-authority=/etc/kubernetes/pki/ca.crt --token="$$T" "$$@"; }
       |FAIL=0
       |expect() {  # description, wanted (yes|no), command...
       |  D=$$1; W=$$2; shift 2
       |  GOT=$$("$$@" 2>/dev/null || true)
       |  GOT=$$(echo "$$GOT" | tail -n 1)
       |  if [ "$$GOT" = "$$W" ]; then echo "ok    $$D: $$GOT"; else echo "FAIL  $$D: wanted $$W, got '$$GOT'" >&2; FAIL=1; fi
       |}
       |TA=$$(tok k8sadmin '${adminPw.reveal}'); TD=$$(tok k8sdev '${devPw.reveal}')
       |TV=$$(tok k8sview '${viewPw.reveal}'); TN=$$(tok k8snogroup '${nogroupPw.reveal}')
       |echo "k8sadmin is $$(k $$TA auth whoami -o jsonpath='{.status.userInfo.username}') in $$(k $$TA auth whoami -o jsonpath='{.status.userInfo.groups}')"
       |expect "k8sadmin can do anything"          yes k $$TA auth can-i '*' '*'
       |expect "k8sdev can create pods"            yes k $$TD auth can-i create pods -n default
       |expect "k8sdev cannot create cluster roles" no k $$TD auth can-i create clusterroles
       |expect "k8sview can list pods"             yes k $$TV auth can-i list pods -A
       |expect "k8sview cannot create pods"        no k $$TV auth can-i create pods -n default
       |expect "k8snogroup cannot list pods"       no k $$TN auth can-i list pods -n default
       |[ "$$FAIL" -eq 0 ] || exit 1
       |echo "all logins and permissions as expected"
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-oidc")(
      stage("keycloak-realm", kcNode)
        .task("create the realm k8s, the client, groups and test users")(
          Task.RunCommand(List("sh", "-c", realmScript), timeoutSeconds = 180)
        )
        .build,
      stage("python-yaml", cpNodes*)
        .task("install python3-yaml")(
          Task.Install(packages = List("python3-yaml"), updateCache = true)
        )
        .build,
      stage("write-config-files", "tst30")
        .task("write authn.yaml and encryption.yaml")(
          Task.RunCommand(
            List("sh", "-c", configFilesScript),
            timeoutSeconds = 60
          )
        )
        .build,
      stage("distribute-config-files", "tst31", "tst32")
        .task("copy authn.yaml")(
          Task.DistributeFile(
            "tst30",
            s"$stateDir/authn.yaml",
            s"$stateDir/authn.yaml",
            "root",
            "root",
            384
          )
        )
        .task("copy encryption.yaml")(
          Task.DistributeFile(
            "tst30",
            s"$stateDir/encryption.yaml",
            s"$stateDir/encryption.yaml",
            "root",
            "root",
            384
          )
        )
        .build,
      stage("apiserver-tst30", "tst30")
        .task("add the flags to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("apiserver-tst31", "tst31")
        .task("add the flags to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("apiserver-tst32", "tst32")
        .task("add the flags to kube-apiserver")(
          Task.RunCommand(List("sh", "-c", patchScript), timeoutSeconds = 400)
        )
        .build,
      stage("rbac", "tst30")
        .task("bind the Keycloak groups to cluster roles")(
          Task.RunCommand(List("sh", "-c", rbacScript), timeoutSeconds = 60)
        )
        .task("encrypt the existing Secrets and check etcd")(
          Task.RunCommand(List("sh", "-c", encryptScript), timeoutSeconds = 180)
        )
        .build,
      stage("login-test", "tst30")
        .task("log in as each test user and check permissions")(
          Task
            .RunCommand(List("sh", "-c", loginTestScript), timeoutSeconds = 180)
        )
        .task("login ready")(
          Task.Debug(
            s"Keycloak realm $kcRealm is the identity provider of the API servers ($issuer, client $clientId); " +
              "groups k8s-admins, k8s-developers, k8s-viewers map to cluster-admin, edit, view; Secrets are encrypted in etcd."
          )
        )
        .build
    )
