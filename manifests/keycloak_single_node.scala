// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. Keycloak (an OpenID Connect identity provider) on a new VM, tst26, and Keystone on tst6 federated to it,
// so that a Keycloak user gets an OpenStack token. Modelled on skyline_single_node.scala (one container from the
// project's own image, a helper that does the API work) and keystone_single_node.scala (TLS from the test CA).
//
// Where it runs: tst26 (config/tst26.yaml, 10.10.5.38). To move it, change `node`, `fqdn` and `nodeIp` below, the
// teardown's `node`, and keycloakIssuer in keystone_single_node.scala.
//
// What it builds:
//   - database `keycloak` on Galera (utf8mb4), reached through the VIP like the OpenStack services;
//   - a server certificate for tst26 signed by Keystone's test CA on tst6 (the key never leaves tst26: the request
//     goes to tst6 and the certificate comes back), so every node that already trusts Keystone trusts Keycloak;
//   - one container from quay.io/keycloak/keycloak (KEYCLOAK_IMAGE overrides; it goes through the Kolla cache on
//     tst9 like the Kolla images), host network: HTTPS on 8443, the management interface (health) on 9000, plain
//     HTTP off. `start` (not --optimized): Keycloak builds itself for MariaDB at each start, which adds about half a
//     minute and needs no image build (podman build failed on this fleet before; see magnum_single_node.scala);
//   - /etc/keycloak/keycloak.conf (root:root 0640: the image's user is uid 1000 in group 0, so the container reads it
//     and the host's uid 1000, localadmin, does not), with the database, the hostname, the certificate, the local
//     cache (one node, no cluster discovery) and the first admin;
//   - the admin user `admin` of the master realm, made by Keycloak at its first start from bootstrap-admin-* (it
//     then says the account is temporary; that is expected here). Changing keycloak/admin later does not change it;
//   - through the helper `orphera-keycloak-api` (realm, federate, check, sso-check, unfederate, openrc):
//       Keycloak: realm `openstack`, confidential client `keystone` (direct access grants on, for the CLI and the
//         check) with a `groups` claim (group names) in its tokens, the groups openstack-admins,
//         openstack-members and openstack-readers, and test users kcadmin, kcdemo and kcreader (one in each) and
//         kcnogroup (in none), each with a password, email and names (a user without them is held at "update
//         profile" and cannot log in by password); a user's groups are set to exactly these on every run;
//       Keystone: domain `keycloak`, project `keycloak-demo`, and in the domain the groups keycloak-admins (admin on
//         project admin, domain Default: cloud admin), keycloak-members (member on keycloak-demo) and
//         keycloak-readers (reader on keycloak-demo); identity provider `keycloak` (remote id = the realm's issuer,
//         users in domain `keycloak`), mapping `keycloak-mapping` (preferred_username -> user name; each Keycloak
//         group in OIDC-groups -> its Keystone group; a user in none of them is not mapped, so Keystone refuses
//         them) and protocol `openid` (remote_id_attribute HTTP_OIDC_ISS). GROUP_MAP in the helper is the table.
//
// The Keystone half: keystone_single_node.scala loads mod_auth_openidc and checks bearer tokens on
// /v3/OS-FEDERATION/identity_providers/keycloak/protocols/openid/auth against the realm's published keys, and has
// `openid` in [auth] methods. The first stage here fails, saying so, if the Keystone on tst6 was set up before
// that (run keystone_single_node.scala again first).
//
// How a login works (what `check` does, and what the CLI's v3oidcpassword plugin does): password grant at Keycloak
// for client `keystone` -> access token -> POST it as a bearer token to the federation path above -> an unscoped
// Keystone token for the user, in the Keystone groups its Keycloak groups map to -> a token scoped to the project
// those groups have a role on. `check` does it for each test user, and checks that only kcadmin may list users and
// that kcnogroup is refused. `sudo orphera-keycloak-api openrc [kcdemo|kcadmin|kcreader]` on tst26 prints the
// environment for the CLI.
//
// Prerequisites, not checked here: Galera + VIP healthy; Keystone up on tst6 with its CA at
// /etc/kolla/keystone-ca; tst26 provisioned with an agent, its name in DNS; quay.io (or the cache) reachable.
//
// Stages:
//   - keystone-federation-ready (tst6): the vhost has the OIDC settings for this realm and keystone.conf has openid.
//   - keycloak-database (tst3): database and user.
//   - keycloak-tls-request (tst26): packages, directories, key and certificate request.
//   - keycloak-tls-sign (tst6): signs the request with the test CA (only when the certificate is missing, is for
//     another key, or expires within 30 days).
//   - install-keycloak (tst26): certificate back, trust store, the cache mirror, image, keycloak.conf, unit, start.
//   - confirm-keycloak-healthy (tst26): /health/ready on 9000, then the helper: realm, client, user, the Keystone
//     objects, and a federated login end to end.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/keycloak_single_node.scala` changes nothing and
// reports whether the unit on tst26 matches what this playbook writes.
//
// Facts this was written from, to check if the first run fails: Keycloak 26 option names (db, db-url-host,
// db-url-port, db-url-database, db-username, db-password, db-pool-max-lifetime, hostname as a full URL, http-enabled,
// https-port, https-certificate-file, https-certificate-key-file, health-enabled, cache, bootstrap-admin-username,
// bootstrap-admin-password); health on the management port 9000 over the same TLS; the image's entrypoint kc.sh
// with no default command; the admin REST paths /admin/realms/...; mod_auth_openidc's AuthType oauth20,
// OIDCOAuthVerifyJwksUri, OIDCCABundlePath and OIDCClaimPrefix, with claims reaching Keystone as environment
// variables OIDC-<claim> (OIDC-preferred_username has an underscore, so only the environment variable carries it,
// not the header) and the issuer as the header HTTP_OIDC_ISS. db-pool-max-lifetime is 20 s because haproxy on the
// Galera VIP closes connections idle for 30 s (see the Aodh note in telemetry_single_node.scala).
//
// Test-only credentials come from the secret store (keycloak/db, keycloak/admin, keycloak/keystone-client,
// keycloak/demo-user), made on first use. Not compiled where this was written; the first run on scala0 is the
// first compile.
object keycloak_single_node extends OrpheraClusterPlaybook:

  private val node = "tst26"
  private val fqdn = "tst26.ljalbinson.com"
  private val nodeIp = "10.10.5.38"
  private val httpsPort = 8443
  private val mgmtPort = 9000
  private val realm = "openstack"
  private val issuer = s"https://$fqdn:$httpsPort/realms/$realm"

  private val ksNode = "tst6"
  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksCaDir = "/etc/kolla/keystone-ca"
  private val ksConfDir = "/etc/kolla/keystone"
  private val vip = "10.10.5.100"
  // The dashboards that sign in through Keycloak (WebSSO); keystone_single_node.scala trusts exactly these names.
  private val horizonUrl = "https://tst13.ljalbinson.com:8080"
  private val skylineUrl = "https://tst13.ljalbinson.com:9999"

  private val keycloakImage = ExternalImage(
    sys.env.getOrElse("KEYCLOAK_IMAGE", "quay.io/keycloak/keycloak:26.8.1")
  )

  private val dbName = "keycloak"
  private val dbUser = "keycloak"
  private val dbPassword = Secrets.secret("keycloak/db")
  private val kcAdminPassword = Secrets.secret("keycloak/admin")
  private val clientSecret = Secrets.secret("keycloak/keystone-client")
  private val demoPassword = Secrets.secret("keycloak/demo-user")
  private val kcAdminUserPassword = Secrets.secret("keycloak/kcadmin")
  private val kcReaderPassword = Secrets.secret("keycloak/kcreader")
  private val kcNogroupPassword = Secrets.secret("keycloak/kcnogroup")
  private val ksAdminPassword = Secrets.secret("keystone/admin")
  private val demoUser = "kcdemo"

  private val confDir = "/etc/keycloak"
  private val tlsDir = "/etc/keycloak/tls"
  // On tst6: where the request and the signed certificate for this node live, beside the CA.
  private val signDir = s"$ksCaDir/issued/$node"
  private val helper = "/usr/local/sbin/orphera-keycloak-api"

  // Defined after the directories above, which it reads; a val used before its definition is null in an object.
  private val keycloakContainer = Container(
    name = ContainerName("keycloak"),
    image = keycloakImage,
    mounts = List(
      Mount(s"$confDir/keycloak.conf", "/opt/keycloak/conf/keycloak.conf"),
      Mount(tlsDir, "/opt/keycloak/conf/tls")
    ),
    description = Some("Keycloak (OpenID Connect provider) managed by Orphera"),
    stopGraceSeconds = 30,
    startTimeoutSeconds = 300,
    settleSeconds = 20
  )

  // The image's entrypoint (kc.sh) needs the command `start` after the image. Container has an `args` field for
  // this from d8ce67d on, but a manifest is compiled against the installed orchestrator jar, which may be older;
  // so the word is added to the ExecStart line here. Once every jar in use has `args`, use
  // `args = List("start")` above and keycloakContainer.writeUnit / unitResource.check instead.
  private val unitPath = keycloakContainer.unitPath
  private val unitText: String =
    keycloakContainer.render
      .split("\n", -1)
      .map(l => if l.startsWith("ExecStart=") then l + " start" else l)
      .mkString("\n")

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  // ---- tst6: is Keystone ready for federation? ---------------------------

  private val federationReadyScript =
    s"""V=$ksConfDir/wsgi-keystone.conf
       |C=$ksConfDir/keystone.conf
       |[ -f "$$V" ] || { echo "$$V not found: Keystone is not set up on $ksNode (keystone_single_node.scala)" >&2; exit 1; }
       |if ! grep -q 'OIDCOAuthVerifyJwksUri "$issuer/protocol/openid-connect/certs"' "$$V"; then
       |  if grep -q 'OIDCOAuthVerifyJwksUri' "$$V"; then
       |    echo "Keystone's vhost points at another Keycloak realm than $issuer: make keycloakIssuer in keystone_single_node.scala agree, then run it again" >&2
       |  else
       |    echo "Keystone's vhost has no OIDC settings: run keystone_single_node.scala again first (it adds them), or its image lacks mod_auth_openidc (it warns when it does)" >&2
       |  fi
       |  exit 1
       |fi
       |grep -Eq '^methods *= *.*openid' "$$C" || { echo "openid is not in [auth] methods in $$C: run keystone_single_node.scala again first" >&2; exit 1; }
       |[ -f $ksCaDir/ca.key ] && [ -f $ksCaDir/ca.crt ] || { echo "the test CA is not in $ksCaDir" >&2; exit 1; }
       |echo "Keystone on $ksNode accepts Keycloak tokens from $issuer"""".stripMargin

  // ---- tst3 (Galera) -----------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; ALTER USER '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- TLS: key and request on tst26, signature on tst6 --------------------

  // The key is made once and kept, so the certificate only changes when it has to.
  private val tlsRequestScript =
    s"""set -e
       |mkdir -p $confDir $tlsDir
       |chmod 750 $confDir $tlsDir
       |cd $tlsDir
       |if [ ! -f server.key ] || [ ! -f server.csr ]; then
       |  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |fi
       |chown root:root server.key server.csr
       |chmod 640 server.key
       |chmod 644 server.csr
       |echo "key and certificate request ready in $tlsDir"""".stripMargin

  private val signDirScript =
    s"""set -e
       |[ -f $ksCaDir/ca.key ] || { echo "no CA key in $ksCaDir: keystone_single_node.scala makes it" >&2; exit 1; }
       |mkdir -p $signDir
       |chmod 700 $signDir
       |echo "$signDir ready"""".stripMargin

  // Signs only when needed: no certificate yet, a certificate for another key (the key on tst26 was remade), one
  // that does not verify against the CA, lacks the name, or expires within 30 days.
  private val signScript =
    s"""set -e
       |cd $signDir
       |openssl req -in server.csr -noout -verify
       |NEED=1
       |if [ -f server.crt ] \\
       |   && [ "$$(openssl x509 -in server.crt -noout -pubkey)" = "$$(openssl req -in server.csr -noout -pubkey)" ] \\
       |   && openssl verify -CAfile $ksCaDir/ca.crt server.crt >/dev/null 2>&1 \\
       |   && openssl x509 -in server.crt -noout -checkend 2592000 >/dev/null \\
       |   && openssl x509 -in server.crt -noout -ext subjectAltName | grep -q "DNS:$fqdn"; then
       |  NEED=0
       |fi
       |if [ "$$NEED" = "1" ]; then
       |  cat > server.ext <<EXT
       |subjectAltName=DNS:$fqdn,DNS:$node,IP:$nodeIp
       |basicConstraints=CA:FALSE
       |keyUsage=digitalSignature,keyEncipherment
       |extendedKeyUsage=serverAuth
       |EXT
       |  openssl x509 -req -in server.csr -CA $ksCaDir/ca.crt -CAkey $ksCaDir/ca.key -CAcreateserial -out server.crt -days 825 -sha256 -extfile server.ext
       |  echo "signed a new certificate for $fqdn"
       |else
       |  echo "the certificate for $fqdn is still good"
       |fi
       |cp $ksCaDir/ca.crt ca.crt
       |openssl x509 -in server.crt -noout -subject -enddate""".stripMargin

  // After the certificate and the CA came back: they must fit the key, the chain is what Keycloak serves (so a
  // client can take the CA from the handshake, as for Keystone), and the CA joins the host's trust store.
  private val tlsInstallScript =
    s"""set -e
       |cd $tlsDir
       |if [ "$$(openssl x509 -in server.crt -noout -pubkey)" != "$$(openssl pkey -in server.key -pubout)" ]; then
       |  echo "the certificate from $ksNode is not for the key in $tlsDir" >&2; exit 1
       |fi
       |openssl verify -CAfile ca.crt server.crt
       |cat server.crt ca.crt > server-chain.crt
       |chown root:root server.crt server-chain.crt ca.crt
       |chmod 644 server.crt ca.crt
       |chmod 640 server-chain.crt
       |cp ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |update-ca-certificates
       |echo "TLS ready: $$(openssl x509 -in server.crt -noout -subject -enddate | tr '\\n' ' ')"""".stripMargin

  // ---- tst26 -------------------------------------------------------------

  // Same mirror order as keystone_single_node.scala: tst21's registry, then the cache on tst9, then quay.io.
  private val useCacheScript =
    """mkdir -p /etc/containers/registries.conf.d
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
      |location = "tst9.ljalbinson.com:5000"
      |insecure = true
      |REG
      |echo "podman will try tst21, then tst9, before quay.io"""".stripMargin

  private val pullScript =
    s"""${Pull.shellFunction(
        "KEYCLOAK_IMAGE or the ExternalImage in keycloak_single_node.scala"
      )}
       |pull_retry ${keycloakImage.ref} || exit 1
       |echo "image ready: $$(podman images -q ${keycloakImage.ref} | head -1)"""".stripMargin

  // Option names as in Keycloak's configuration reference; the file is read at every start.
  private val keycloakConf: String =
    s"""# Managed by Orphera (keycloak_single_node.scala)
       |db=mariadb
       |db-url-host=$vip
       |db-url-port=3306
       |db-url-database=$dbName
       |db-username=$dbUser
       |db-password=${dbPassword.reveal}
       |db-pool-max-lifetime=20s
       |hostname=https://$fqdn:$httpsPort
       |http-enabled=false
       |https-port=$httpsPort
       |https-certificate-file=/opt/keycloak/conf/tls/server-chain.crt
       |https-certificate-key-file=/opt/keycloak/conf/tls/server.key
       |health-enabled=true
       |cache=local
       |bootstrap-admin-username=admin
       |bootstrap-admin-password=${kcAdminPassword.reveal}
       |""".stripMargin

  // ---- the helper ----------------------------------------------------------

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands: realm | federate | check | sso-check [horizon|skyline|all] | unfederate | openrc
  private val apiHelperPython =
    """#!/usr/bin/python3
import html, http.cookiejar, json, re, ssl, sys, urllib.error, urllib.parse, urllib.request

KC = 'https://@@FQDN@@:@@PORT@@'
REALM = '@@REALM@@'
ISSUER = KC + '/realms/' + REALM
KS = 'https://@@KS@@:5000/v3'
KC_ADMIN_PW = '@@KCADMINPW@@'
KS_ADMIN_PW = '@@KSADMINPW@@'
CLIENT_ID = 'keystone'
CLIENT_SECRET = '@@CLIENTSECRET@@'
DEMO_USER = '@@DEMOUSER@@'
DEMO_PW = '@@DEMOPW@@'
IDP = 'keycloak'
PROTOCOL = 'openid'
MAPPING = 'keycloak-mapping'
FED_DOMAIN = 'keycloak'
FED_PROJECT = 'keycloak-demo'
# Keycloak group -> Keystone group (in FED_DOMAIN) -> role on a project. The groups claim in Keycloak's tokens decides a
# user's access; a user in none of these groups is not mapped at all, so Keystone refuses them.
GROUP_MAP = [
    # Keycloak group,     Keystone group,     role,     project,     project's domain
    ('openstack-admins', 'keycloak-admins', 'admin', 'admin', 'Default'),
    ('openstack-members', 'keycloak-members', 'member', FED_PROJECT, FED_DOMAIN),
    ('openstack-readers', 'keycloak-readers', 'reader', FED_PROJECT, FED_DOMAIN),
]
# Keystone groups an earlier version of this helper made, removed by federate.
OLD_GROUPS = ['keycloak-users']
# Test users: one per group, and one in no group, who must get no access.
USERS = [
    # name, password, Keycloak group (or None), last name
    ('kcadmin', '@@KCADMINUSERPW@@', 'openstack-admins', 'Admin'),
    (DEMO_USER, DEMO_PW, 'openstack-members', 'Demo'),
    ('kcreader', '@@KCREADERPW@@', 'openstack-readers', 'Reader'),
    ('kcnogroup', '@@KCNOGROUPPW@@', None, 'Nogroup'),
]
FED_AUTH = '%s/OS-FEDERATION/identity_providers/%s/protocols/%s/auth' % (KS, IDP, PROTOCOL)
HORIZON = '@@HORIZON@@'
SKYLINE = '@@SKYLINE@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
# The test CA (keystone_single_node.scala) has no keyUsage extension, which Python 3.13 and later reject by
# default; noble's 3.12 does not check it.
CTX.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)


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


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


# ---- Keycloak ----

def kc_token(realm, client_id, username, password, secret=None):
    form = {'grant_type': 'password', 'client_id': client_id, 'username': username, 'password': password, 'scope': 'openid'}
    if secret:
        form['client_secret'] = secret
    s, p, _ = call('POST', '%s/realms/%s/protocol/openid-connect/token' % (KC, realm), form=form)
    if s != 200 or 'access_token' not in p:
        die('Keycloak token for %s in realm %s: HTTP %s %s' % (username, realm, s, p))
    return p['access_token']


class KcAdmin:
    def __init__(self):
        self.h = {'Authorization': 'Bearer ' + kc_token('master', 'admin-cli', 'admin', KC_ADMIN_PW)}

    def __call__(self, method, path, body=None):
        return call(method, KC + '/admin/realms' + path, body, self.h)


def ensure_user(kc, name, password, last):
    user = {
        'username': name, 'enabled': True, 'email': name + '@ljalbinson.com', 'emailVerified': True,
        'firstName': 'Keycloak', 'lastName': last, 'requiredActions': [],
    }
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
    return uid


def realm():
    kc = KcAdmin()
    s, p, _ = kc('GET', '/' + REALM)
    if s == 404:
        s, p, _ = kc('POST', '', {'realm': REALM, 'enabled': True, 'displayName': 'OpenStack (Orphera lab)'})
        if s != 201:
            die('creating realm %s: HTTP %s %s' % (REALM, s, p))
        print('realm %s created' % REALM)
    elif s != 200:
        die('reading realm %s: HTTP %s %s' % (REALM, s, p))
    client = {
        'clientId': CLIENT_ID, 'name': 'OpenStack Keystone', 'protocol': 'openid-connect', 'enabled': True,
        'publicClient': False, 'clientAuthenticatorType': 'client-secret', 'secret': CLIENT_SECRET,
        'standardFlowEnabled': True, 'directAccessGrantsEnabled': True, 'serviceAccountsEnabled': False,
        'redirectUris': ['https://@@KS@@:5000/*'], 'webOrigins': [],
        # Where Keycloak may send the browser after a logout started by Keystone (the dashboards' login pages).
        'attributes': {'post.logout.redirect.uris': '@@HORIZON@@/*##@@SKYLINE@@/*'},
    }
    s, found, _ = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
    if s != 200:
        die('listing clients: HTTP %s %s' % (s, found))
    if found:
        s, p, _ = kc('PUT', '/%s/clients/%s' % (REALM, found[0]['id']), client)
        if s != 204:
            die('updating client %s: HTTP %s %s' % (CLIENT_ID, s, p))
    else:
        s, p, _ = kc('POST', '/%s/clients' % REALM, client)
        if s != 201:
            die('creating client %s: HTTP %s %s' % (CLIENT_ID, s, p))
        s, found, _ = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
    cid = found[0]['id']
    # The groups claim: the user's group names (not paths), in the ID token, the access token and userinfo, which is
    # what Keystone's mapping reads (through mod_auth_openidc, as OIDC-groups).
    mapper = {
        'name': 'groups', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-group-membership-mapper',
        'config': {'claim.name': 'groups', 'full.path': 'false', 'id.token.claim': 'true',
                   'access.token.claim': 'true', 'userinfo.token.claim': 'true'},
    }
    s, mappers, _ = kc('GET', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid))
    if s != 200:
        die('listing the client\'s mappers: HTTP %s %s' % (s, mappers))
    old = [m for m in mappers if m.get('name') == 'groups']
    if old:
        s, p, _ = kc('PUT', '/%s/clients/%s/protocol-mappers/models/%s' % (REALM, cid, old[0]['id']), dict(mapper, id=old[0]['id']))
        ok = 204
    else:
        s, p, _ = kc('POST', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid), mapper)
        ok = 201
    if s != ok:
        die('the groups claim mapper: HTTP %s %s' % (s, p))
    gids = {}
    for kc_group, _, _, _, _ in GROUP_MAP:
        s, found, _ = kc('GET', '/%s/groups?search=%s&exact=true' % (REALM, kc_group))
        if s != 200:
            die('listing groups: HTTP %s %s' % (s, found))
        match = [g for g in found if g.get('name') == kc_group]
        if not match:
            s, p, _ = kc('POST', '/%s/groups' % REALM, {'name': kc_group})
            if s != 201:
                die('creating group %s: HTTP %s %s' % (kc_group, s, p))
            s, found, _ = kc('GET', '/%s/groups?search=%s&exact=true' % (REALM, kc_group))
            match = [g for g in found if g.get('name') == kc_group]
        gids[kc_group] = match[0]['id']
    for name, password, group, last in USERS:
        uid = ensure_user(kc, name, password, last)
        s, current, _ = kc('GET', '/%s/users/%s/groups' % (REALM, uid))
        if s != 200:
            die('groups of %s: HTTP %s %s' % (name, s, current))
        have = set(g['name'] for g in current)
        for kc_group, gid in gids.items():
            if kc_group == group and kc_group not in have:
                s, p, _ = kc('PUT', '/%s/users/%s/groups/%s' % (REALM, uid, gid))
            elif kc_group != group and kc_group in have:
                s, p, _ = kc('DELETE', '/%s/users/%s/groups/%s' % (REALM, uid, gid))
            else:
                continue
            if s != 204:
                die('group %s for %s: HTTP %s %s' % (kc_group, name, s, p))
    print('realm %s: client %s (confidential, direct grants, groups claim), groups %s, users %s' % (
        REALM, CLIENT_ID, ', '.join(gids), ', '.join('%s (%s)' % (n, g or 'no group') for n, _, g, _ in USERS)))


# ---- Keystone ----

def ks_token():
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'admin', 'domain': {'name': 'Default'}, 'password': KS_ADMIN_PW}}}, 'scope': {'project': {'name': 'admin', 'domain': {'name': 'Default'}}}}}
    s, p, h = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        die('Keystone admin token: HTTP %s %s' % (s, p))
    return {'X-Auth-Token': h['X-Subject-Token']}


def ks(method, path, t, body=None):
    return call(method, KS + path, body, t)


def find(t, path, key):
    s, p, _ = ks('GET', path, t)
    if s != 200:
        die('GET %s: HTTP %s %s' % (path, s, p))
    items = p.get(key, [])
    return items[0] if items else None


def ensure(t, path, query, key, body):
    obj = find(t, path + query, key + 's')
    if obj is None:
        s, p, _ = ks('POST', path, t, {key: body})
        if s != 201:
            die('creating %s %s: HTTP %s %s' % (key, body.get('name'), s, p))
        obj = p[key]
    return obj


def put_or_patch(t, path, key, create, update):
    s, p, _ = ks('GET', path, t)
    if s == 404:
        s, p, _ = ks('PUT', path, t, {key: create})
        if s != 201:
            die('PUT %s: HTTP %s %s' % (path, s, p))
    elif s == 200:
        s, p, _ = ks('PATCH', path, t, {key: update})
        if s != 200:
            die('PATCH %s: HTTP %s %s' % (path, s, p))
    else:
        die('GET %s: HTTP %s %s' % (path, s, p))
    return p[key]


def federate():
    t = ks_token()
    dom = ensure(t, '/domains', '?name=' + FED_DOMAIN, 'domain', {'name': FED_DOMAIN, 'enabled': True, 'description': 'Users federated from Keycloak realm ' + REALM})
    ensure(t, '/projects', '?name=%s&domain_id=%s' % (FED_PROJECT, dom['id']), 'project', {'name': FED_PROJECT, 'domain_id': dom['id'], 'description': 'For users who log in through Keycloak'})
    rules = []
    for kc_group, ks_group, role_name, project, project_domain in GROUP_MAP:
        grp = ensure(t, '/groups', '?name=%s&domain_id=%s' % (ks_group, dom['id']), 'group', {'name': ks_group, 'domain_id': dom['id'], 'description': 'Members of Keycloak group %s, by the mapping %s' % (kc_group, MAPPING)})
        pdom = find(t, '/domains?name=' + project_domain, 'domains')
        proj = find(t, '/projects?name=%s&domain_id=%s' % (project, pdom['id']), 'projects') if pdom else None
        if proj is None:
            die('project %s in domain %s not found' % (project, project_domain))
        role = find(t, '/roles?name=' + role_name, 'roles')
        if role is None:
            die('role %s not found (keystone-manage bootstrap makes it)' % role_name)
        s, p, _ = ks('PUT', '/projects/%s/groups/%s/roles/%s' % (proj['id'], grp['id'], role['id']), t)
        if s not in (200, 204):
            die('granting %s on %s to group %s: HTTP %s %s' % (role_name, project, ks_group, s, p))
        rules.append({
            'local': [{'user': {'name': '{0}'}}, {'group': {'name': ks_group, 'domain': {'name': FED_DOMAIN}}}],
            'remote': [{'type': 'OIDC-preferred_username'}, {'type': 'OIDC-groups', 'any_one_of': [kc_group]}],
        })
    for old in OLD_GROUPS:
        grp = find(t, '/groups?name=%s&domain_id=%s' % (old, dom['id']), 'groups')
        if grp is not None:
            ks('DELETE', '/groups/' + grp['id'], t)
            print('removed the old group %s' % old)
    idp = put_or_patch(t, '/OS-FEDERATION/identity_providers/' + IDP, 'identity_provider',
                       {'enabled': True, 'remote_ids': [ISSUER], 'domain_id': dom['id'], 'description': 'Keycloak on @@FQDN@@'},
                       {'enabled': True, 'remote_ids': [ISSUER]})
    if idp.get('domain_id') != dom['id']:
        die('identity provider %s puts its users in domain %s, not %s: run "%s unfederate", then federate' % (IDP, idp.get('domain_id'), FED_DOMAIN, sys.argv[0]))
    put_or_patch(t, '/OS-FEDERATION/mappings/' + MAPPING, 'mapping', {'rules': rules}, {'rules': rules})
    proto = {'mapping_id': MAPPING, 'remote_id_attribute': 'HTTP_OIDC_ISS'}
    put_or_patch(t, '/OS-FEDERATION/identity_providers/%s/protocols/%s' % (IDP, PROTOCOL), 'protocol', proto, proto)
    print('keystone: identity provider %s (%s), protocol %s, mapping %s: %s' % (IDP, ISSUER, PROTOCOL, MAPPING, '; '.join(
        '%s -> %s (%s on %s/%s)' % (k, g, r, pd, pr) for k, g, r, pr, pd in GROUP_MAP)))


def unfederate():
    t = ks_token()
    for path in ('/OS-FEDERATION/identity_providers/%s/protocols/%s' % (IDP, PROTOCOL), '/OS-FEDERATION/identity_providers/' + IDP, '/OS-FEDERATION/mappings/' + MAPPING):
        s, p, _ = ks('DELETE', path, t)
        if s not in (204, 404):
            print('DELETE %s: HTTP %s %s (continuing)' % (path, s, p))
    dom = find(t, '/domains?name=' + FED_DOMAIN, 'domains')
    if dom is not None:
        names = [('/projects', 'projects', FED_PROJECT)] + [('/groups', 'groups', g) for g in [m[1] for m in GROUP_MAP] + OLD_GROUPS]
        for path, key, name in names:
            obj = find(t, '%s?name=%s&domain_id=%s' % (path, name, dom['id']), key)
            if obj is not None:
                ks('DELETE', '%s/%s' % (path, obj['id']), t)
        ks('PATCH', '/domains/' + dom['id'], t, {'domain': {'enabled': False}})
        s, p, _ = ks('DELETE', '/domains/' + dom['id'], t)
        if s not in (204, 404):
            print('deleting domain %s: HTTP %s %s (continuing)' % (FED_DOMAIN, s, p))
    print('removed the Keycloak federation from keystone')


# ---- the end-to-end check ----

def federated_login(name, password):
    # Keycloak password grant, then the access token as a bearer token at Keystone. Returns (status, body, token).
    access = kc_token(REALM, CLIENT_ID, name, password, CLIENT_SECRET)
    s, p, h = call('POST', FED_AUTH, headers={'Authorization': 'Bearer ' + access})
    return s, p, h.get('X-Subject-Token') if s == 201 else None


def scoped(unscoped, project, domain):
    body = {'auth': {'identity': {'methods': ['token'], 'token': {'id': unscoped}}, 'scope': {'project': {'name': project, 'domain': {'name': domain}}}}}
    s, p, h = call('POST', KS + '/auth/tokens', body)
    if s != 201:
        return s, [], None
    return s, sorted(r['name'] for r in p['token'].get('roles', [])), h['X-Subject-Token']


def check():
    s, p, _ = call('GET', ISSUER + '/.well-known/openid-configuration')
    if s != 200 or p.get('issuer') != ISSUER:
        die('discovery document of %s: HTTP %s, issuer %s' % (ISSUER, s, p.get('issuer')))
    kc_token('master', 'admin-cli', 'admin', KC_ADMIN_PW)
    s, p, _ = call('POST', FED_AUTH, headers={'Authorization': 'Bearer not-a-token'})
    if s != 401:
        die('a bad bearer token at %s gave HTTP %s, not 401 (is the OIDC <Location> in the Keystone vhost?) %s' % (FED_AUTH, s, p))
    where = {g[0]: g for g in GROUP_MAP}
    results = []
    for name, password, group, _ in USERS:
        s, p, unscoped = federated_login(name, password)
        if group is None:
            if s != 401:
                die('%s is in no Keycloak group, yet Keystone answered HTTP %s, not 401: %s' % (name, s, p))
            results.append('%s refused (no group)' % name)
            continue
        if s != 201:
            hint = {404: 'the identity provider or protocol is missing: run "%s federate"' % sys.argv[0],
                    401: 'Keystone refused the token: see keystone-apache-public-error.log and keystone.log on @@KS@@ (mapping; the groups claim must reach Keystone as OIDC-groups, ";"-separated)'}.get(s, '')
            die('federated login of %s at Keystone: HTTP %s %s %s' % (name, s, p, hint))
        got = p.get('token', {}).get('user', {}).get('name')
        if got != name:
            die('the federated token is for %r, not %s' % (got, name))
        _, _, role, project, domain = where[group]
        s, roles, token = scoped(unscoped, project, domain)
        if s != 201:
            die('%s could not scope to %s/%s: HTTP %s' % (name, domain, project, s))
        if role not in roles:
            die('%s has roles %s on %s/%s, not %s' % (name, roles, domain, project, role))
        if role == 'reader' and 'member' in roles:
            die('%s is only a reader but has member on %s/%s' % (name, domain, project))
        # Listing users is for cloud admins only: the admin group can, the others cannot.
        s, p, _ = call('GET', KS + '/users?limit=1', headers={'X-Auth-Token': token})
        if role == 'admin' and s != 200:
            die('%s (admin on %s) could not list users: HTTP %s %s' % (name, project, s, p))
        if role != 'admin' and s != 403:
            die('%s (%s) was not refused an admin request: HTTP %s' % (name, role, s))
        results.append('%s %s on %s/%s' % (name, ','.join(roles), domain, project))
    # kcadmin's group gives no role on keycloak-demo, and kcdemo's none on admin.
    s, _, unscoped = federated_login(DEMO_USER, DEMO_PW)
    s, _, _ = scoped(unscoped, 'admin', 'Default')
    if s == 201:
        die('%s could scope to the admin project' % DEMO_USER)
    print('federation ok: ' + '; '.join(results))


# ---- single sign-on, as a browser does it ----
# Each check starts with an empty cookie jar, follows the redirects, fills in Keycloak's login form and posts the
# token Keystone hands back to the dashboard, then asks the dashboard who is signed in.

BIG_COOKIES = []


class BrowserCookies(urllib.request.HTTPCookieProcessor):
    # Browsers drop a cookie whose name and value come to more than 4096 bytes, without a word; Python keeps it.
    # Note any such cookie, so the check fails where a browser would.
    def http_response(self, request, response):
        for header in response.headers.get_all('Set-Cookie') or []:
            pair = header.split(';', 1)[0]
            if len(pair) > 4096:
                BIG_COOKIES.append('%s (%d bytes, from %s)' % (pair.split('=', 1)[0], len(pair), request.full_url.split('?', 1)[0]))
        return urllib.request.HTTPCookieProcessor.http_response(self, request, response)

    https_response = http_response


def browser():
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(BrowserCookies(jar), urllib.request.HTTPSHandler(context=CTX))
    # mod_auth_openidc sends only what looks like a browser on to the login page; anything else (no Accept:
    # text/html) gets a plain 401.
    op.addheaders = [('User-Agent', 'Mozilla/5.0 (X11; Linux x86_64) orphera-keycloak-api sso-check'),
                     ('Accept', 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8')]
    return op


def go(op, url, form=None, referer=None):
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    req = urllib.request.Request(url, data=data)
    if referer:
        # Django checks it on every HTTPS form post, as browsers send it.
        req.add_header('Referer', referer)
    try:
        r = op.open(req, timeout=60)
        return r.status, r.geturl(), r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.geturl(), e.read().decode('utf-8', 'replace')


def short(body):
    text = re.sub(r'<[^>]+>', ' ', body)
    return re.sub(r'\s+', ' ', html.unescape(text)).strip()[:300]


def hidden_fields(body):
    fields = {}
    for tag in re.findall(r'<input[^>]*>', body):
        if 'type="hidden"' in tag:
            n = re.search(r'name="([^"]+)"', tag)
            v = re.search(r'value="([^"]*)"', tag)
            if n:
                fields[n.group(1)] = html.unescape(v.group(1)) if v else ''
    return fields


def through_keycloak(op, where, s, url, body):
    # The browser should now be at Keycloak's login page: log in as the test user. Keycloak sends it back to
    # Keystone's /redirect_uri, which sends it to the websso path, which answers with the page that posts the
    # token to the dashboard: post it, as that page's script would.
    if not url.startswith(KC) or 'kc-form-login' not in body:
        hint = ''
        if 'csrf_failure' in url:
            hint = ' (the dashboard refused the form post: %s)' % urllib.parse.unquote(url.split('csrf_failure=', 1)[1])
        if s == 401 and url.startswith(KS.rsplit('/v3', 1)[0]):
            hint = ' (mod_auth_openidc in Keystone did not send the browser to Keycloak: see keystone-apache-public-error.log on @@KSNODE@@)'
        die('%s: expected Keycloak\'s login page, got HTTP %s at %s: %s%s' % (where, s, url, short(body), hint))
    m = re.search(r'<form[^>]*id="kc-form-login"[^>]*>', body)
    a = re.search(r'action="([^"]+)"', m.group(0)) if m else None
    if not a:
        die('%s: no action on Keycloak\'s login form' % where)
    s, url, body = go(op, html.unescape(a.group(1)), {'username': DEMO_USER, 'password': DEMO_PW, 'credentialId': ''})
    if url.startswith(KC):
        die('%s: Keycloak did not accept the login (HTTP %s at %s): %s' % (where, s, url, short(body)))
    if s != 200 or 'name="token"' not in body:
        die('%s: expected Keystone\'s page that posts the token, got HTTP %s at %s: %s (an untrusted origin is refused here: see trusted_dashboard in keystone_single_node.scala)' % (where, s, url, short(body)))
    form = re.search(r'<form[^>]*action="([^"]+)"', body)
    token = re.search(r'name="token"[^>]*value="([^"]+)"', body)
    if not form or not token:
        die('%s: Keystone\'s page has no form or token: %s' % (where, short(body)))
    origin = html.unescape(form.group(1))
    print('%s: Keycloak login ok; Keystone posts the token to %s' % (where, origin))
    return go(op, origin, {'token': token.group(1)})


def sso_horizon():
    op = browser()
    s, url, body = go(op, HORIZON + '/auth/login/')
    if s != 200:
        die('horizon: login page HTTP %s at %s' % (s, url))
    if 'keycloak_openid' not in body:
        die('horizon: the login page offers no Keycloak choice (WEBSSO in horizon_single_node.scala; run it again)')
    form = hidden_fields(body)
    if 'csrfmiddlewaretoken' not in form:
        die('horizon: login page has no CSRF field')
    form['auth_type'] = 'keycloak_openid'
    s, url, body = go(op, HORIZON + '/auth/login/', form, referer=HORIZON + '/auth/login/')
    s, url, body = through_keycloak(op, 'horizon', s, url, body)
    if BIG_COOKIES:
        die('horizon: a browser would drop %s and come back to the login page (SESSION_ENGINE in horizon_single_node.scala)' % ', '.join(BIG_COOKIES))
    if s != 200 or 'auth/logout' not in body:
        die('horizon: the token post did not sign in (HTTP %s at %s): %s' % (s, url, short(body)))
    if DEMO_USER not in body:
        die('horizon: signed in, but not as %s (at %s)' % (DEMO_USER, url))
    print('horizon sso ok: signed in as %s through Keycloak (landed on %s)' % (DEMO_USER, url))
    # Logout must end the Keystone and Keycloak sessions too, so the next single sign-on asks for the password.
    s, url, body = go(op, HORIZON + '/auth/logout/', referer=HORIZON + '/project/')
    if s != 200 or not url.startswith(HORIZON + '/auth/login'):
        die('horizon: logout ended at %s (HTTP %s), not Horizon\'s login page: %s' % (url, s, short(body)))
    s, url, body = go(op, HORIZON + '/project/')
    if '/auth/login' not in url:
        die('horizon: still signed in after logout (at %s)' % url)
    s, url, body = go(op, HORIZON + '/auth/login/')
    form = hidden_fields(body)
    form['auth_type'] = 'keycloak_openid'
    s, url, body = go(op, HORIZON + '/auth/login/', form, referer=HORIZON + '/auth/login/')
    if not url.startswith(KC) or 'kc-form-login' not in body:
        die('horizon: after logout, single sign-on did not ask for the password again (HTTP %s at %s): the Keycloak or Keystone session survived the logout' % (s, url))
    print('horizon logout ok: the Horizon, Keystone and Keycloak sessions ended; the next single sign-on asks for the password')


def find_urls(obj):
    if isinstance(obj, str):
        return [obj] if 'OS-FEDERATION/' in obj and 'websso' in obj else []
    if isinstance(obj, dict):
        obj = list(obj.values())
    if isinstance(obj, list):
        return [u for o in obj for u in find_urls(o)]
    return []


def sso_skyline():
    op = browser()
    api = SKYLINE + '/api/openstack/skyline/api/v1'
    s, url, body = go(op, api + '/sso')
    try:
        info = json.loads(body)
    except ValueError:
        info = {}
    urls = find_urls(info)
    if s != 200 or not urls:
        die('skyline: %s/sso offers no websso address (HTTP %s): %s (sso_enabled in skyline_single_node.scala; run it again)' % (api, s, body[:300]))
    print('skyline: offers %s' % urls[0])
    origin = urllib.parse.parse_qs(urllib.parse.urlparse(urls[0]).query).get('origin', [''])[0]
    if origin.startswith('http://'):
        die('skyline: it asks Keystone to post the token to %s, a plain-HTTP address (behind its nginx it may not see that the request came over HTTPS); browsers will not post there from HTTPS' % origin)
    s, url, body = go(op, urls[0])
    s, url, body = through_keycloak(op, 'skyline', s, url, body)
    if s >= 400:
        die('skyline: the token post failed (HTTP %s at %s): %s' % (s, url, short(body)))
    if BIG_COOKIES:
        die('skyline: a browser would drop %s' % ', '.join(BIG_COOKIES))
    s, url, body = go(op, api + '/profile')
    if s != 200 or DEMO_USER not in body:
        die('skyline: no session for %s after the token post (profile: HTTP %s %s)' % (DEMO_USER, s, body[:300]))
    print('skyline sso ok: signed in as %s through Keycloak' % DEMO_USER)


def sso_check(which):
    if which in ('horizon', 'all'):
        sso_horizon()
    if which in ('skyline', 'all'):
        sso_skyline()
    if which not in ('horizon', 'skyline', 'all'):
        die('usage: orphera-keycloak-api sso-check [horizon | skyline | all]')


def openrc(name=DEMO_USER):
    users = {u[0]: u for u in USERS}
    if name not in users or users[name][2] is None:
        die('openrc: one of %s' % ', '.join(u[0] for u in USERS if u[2]))
    _, password, group, _ = users[name]
    _, _, _, project, domain = {g[0]: g for g in GROUP_MAP}[group]
    print('''# Keycloak user %(u)s through Keystone federation (source this file; it holds secrets: chmod 600)
export OS_AUTH_TYPE=v3oidcpassword
export OS_AUTH_URL=%(ks)s
export OS_IDENTITY_PROVIDER=%(idp)s
export OS_PROTOCOL=%(proto)s
export OS_DISCOVERY_ENDPOINT=%(iss)s/.well-known/openid-configuration
export OS_CLIENT_ID=%(cid)s
export OS_CLIENT_SECRET=%(cs)s
export OS_OPENID_SCOPE=openid
export OS_USERNAME=%(u)s
export OS_PASSWORD=%(pw)s
export OS_PROJECT_NAME=%(proj)s
export OS_PROJECT_DOMAIN_NAME=%(dom)s
export OS_IDENTITY_API_VERSION=3
# Keystone and Keycloak both have certificates from the test CA, which utils/openstack-client-setup.sh copies here,
# and that script's virtualenv holds the openstack command.
export OS_CACERT="${OS_CACERT:-$HOME/.config/openstack/keystone-ca.crt}"
if [ -d "$HOME/.venvs/openstack/bin" ]; then case ":$PATH:" in *":$HOME/.venvs/openstack/bin:"*) ;; *) export PATH="$HOME/.venvs/openstack/bin:$PATH" ;; esac; fi''' % {'u': name, 'ks': KS, 'idp': IDP, 'proto': PROTOCOL, 'iss': ISSUER, 'cid': CLIENT_ID,
                                        'cs': CLIENT_SECRET, 'pw': password, 'proj': project, 'dom': domain})


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    actions = {'realm': realm, 'federate': federate, 'check': check, 'unfederate': unfederate, 'openrc': openrc}
    if cmd == 'sso-check':
        sso_check(sys.argv[2] if len(sys.argv) > 2 else 'all')
    elif cmd == 'openrc' and len(sys.argv) > 2:
        openrc(sys.argv[2])
    elif cmd in actions:
        actions[cmd]()
    else:
        die('usage: orphera-keycloak-api realm | federate | check | sso-check [horizon|skyline|all] | unfederate | openrc [kcdemo|kcadmin|kcreader]')
"""

  private val helperScript =
    s"cat > $helper <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@FQDN@@", fqdn)
        .replace("@@PORT@@", httpsPort.toString)
        .replace("@@REALM@@", realm)
        .replace("@@KS@@", ksFqdn)
        .replace("@@KCADMINPW@@", kcAdminPassword.reveal)
        .replace("@@KSADMINPW@@", ksAdminPassword.reveal)
        .replace("@@CLIENTSECRET@@", clientSecret.reveal)
        .replace("@@DEMOUSER@@", demoUser)
        .replace("@@DEMOPW@@", demoPassword.reveal)
        .replace("@@KCADMINUSERPW@@", kcAdminUserPassword.reveal)
        .replace("@@KCREADERPW@@", kcReaderPassword.reveal)
        .replace("@@KCNOGROUPPW@@", kcNogroupPassword.reveal)
        .replace("@@CA@@", s"$tlsDir/ca.crt")
        .replace("@@KSNODE@@", ksNode)
        .replace("@@HORIZON@@", horizonUrl)
        .replace("@@SKYLINE@@", skylineUrl) +
      s"\nPYEOF\nchmod 700 $helper\necho wrote $helper\n"

  // --resolve: the name on the certificate, the address of this host, whatever DNS says.
  private val healthScript =
    s"""CODE=$$(curl -s --cacert $tlsDir/ca.crt --resolve $fqdn:$mgmtPort:127.0.0.1 -o /tmp/kc-ready.json -w '%{http_code}' https://$fqdn:$mgmtPort/health/ready)
       |[ "$$CODE" = "200" ] && grep -q '"UP"' /tmp/kc-ready.json""".stripMargin

  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("keycloak-single-node-check")(
        stage("check-keycloak", node)
          .task(s"check ${keycloakContainer.unitName}")(
            Resource
              .checkTask(keycloakContainer.unitName, unitPath, unitText, Nil)
          )
          .build
      )
    else applyPlaybook

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("keycloak-single-node")(
      stage("keystone-federation-ready", ksNode)
        .task("Keystone checks Keycloak tokens (vhost and keystone.conf)")(
          Task.RunCommand(List("sh", "-c", federationReadyScript))
        )
        .build,

      stage("keycloak-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("keycloak-tls-request", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install podman and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("make the TLS key and certificate request")(
          Task
            .RunCommand(List("sh", "-c", tlsRequestScript), timeoutSeconds = 60)
        )
        .build,

      stage("keycloak-tls-sign", ksNode)
        .task(s"prepare $signDir")(
          Task.RunCommand(List("sh", "-c", signDirScript))
        )
        .task(s"fetch the certificate request from $node")(
          Task.DistributeFile(
            node,
            s"$tlsDir/server.csr",
            s"$signDir/server.csr",
            "root",
            "root",
            420 // 0644
          )
        )
        .task(s"sign the certificate for $fqdn with the test CA")(
          Task.RunCommand(List("sh", "-c", signScript), timeoutSeconds = 60)
        )
        .build,

      stage("install-keycloak", node)
        .task(s"fetch the certificate from $ksNode")(
          Task.DistributeFile(
            ksNode,
            s"$signDir/server.crt",
            s"$tlsDir/server.crt",
            "root",
            "root",
            420
          )
        )
        .task(s"fetch the CA certificate from $ksNode")(
          Task.DistributeFile(
            ksNode,
            s"$signDir/ca.crt",
            s"$tlsDir/ca.crt",
            "root",
            "root",
            420
          )
        )
        .task("install the certificate chain and trust the CA")(
          Task.RunCommand(
            List("sh", "-c", tlsInstallScript),
            timeoutSeconds = 60
          )
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Keycloak image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("write keycloak.conf")(
          Task.WriteFile(
            content = keycloakConf,
            dest = s"$confDir/keycloak.conf",
            owner = "root",
            group = "root",
            mode = 416 // 0640: the container's user is in group 0
          )
        )
        .task(s"write ${keycloakContainer.unitName}")(
          Task.WriteFile(
            content = unitText,
            dest = unitPath,
            owner = "root",
            group = "root",
            mode = 420 // 0644
          )
        )
        .task(s"start ${keycloakContainer.unitName}")(keycloakContainer.start)
        .task("write the Keycloak API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .build,

      stage("confirm-keycloak-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 420
          )
        )
        .task(s"realm $realm, client keystone and user $demoUser")(
          Task.RunCommand(
            List("sh", "-c", s"$helper realm"),
            timeoutSeconds = 120
          )
        )
        .task("identity provider, mapping and protocol in Keystone")(
          Task.RunCommand(
            List("sh", "-c", s"$helper federate"),
            timeoutSeconds = 120
          )
        )
        .task(
          s"a federated login: $demoUser through Keycloak to a Keystone token"
        )(
          Task.RunCommand(
            List("sh", "-c", s"$helper check"),
            timeoutSeconds = 120
          )
        )
        .task("keycloak confirmed")(
          Task.Debug(
            s"Keycloak up at https://$fqdn:$httpsPort/ ($nodeIp) from ${keycloakImage.ref}, database $dbName via $vip:3306. Admin console: https://$fqdn:$httpsPort/admin/ (user admin, secret keycloak/admin). Keystone federates realm $realm as identity provider keycloak: Keycloak groups openstack-admins, openstack-members and openstack-readers map to admin on Default/admin, member and reader on keycloak/keycloak-demo; test users kcadmin, $demoUser and kcreader (secrets keycloak/kcadmin, keycloak/demo-user, keycloak/kcreader). CLI environment: sudo $helper openrc [user] on $node. Test: manifests/test_keycloak.sh"
          )
        )
        .build
    )
