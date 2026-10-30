// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Keystone (identity service), one node, TLS from the start, run
// from the Kolla container image rather than distro packages.
//
// Same shape as wordpress_site.scala: an ordinary application on top of the
// already-working Galera cluster behind the 10.10.5.100 VIP. Prerequisite,
// not checked here: mariadb_galera_cluster.scala AND
// mariadb_haproxy_keepalived.scala are applied and healthy on tst3-tst5.
//
// Why Kolla: a pinned, tested image replaces the distro package (no
// package-owned Apache site or debconf questions to fight), and the same image
// runs on Ubuntu and Rocky. Orphera does the part kolla-ansible normally does:
// it writes the config files the image's `kolla_start` expects and starts the
// container. See https://docs.openstack.org/kolla/latest/admin/kolla_api.html
// (config.json at /var/lib/kolla/config_files, KOLLA_CONFIG_STRATEGY).
//
// Stages:
//   - create-keystone-database (tst3): `keystone` database and user, via the
//     unix-socket root login, same as wordpress_site.scala.
//   - install-keystone (keystoneNode): podman, pull the image, a test CA and
//     server certificate, keystone.conf, the Apache vhost and Kolla
//     config.json (written after inspecting the image, so the WSGI script path
//     and the Apache flavour are detected, not assumed), one-off containers for
//     db_sync / fernet / credential setup / bootstrap, a systemd unit that runs
//     the long-lived container, and a small helper that prints an admin token.
//   - confirm-keystone-healthy (keystoneNode): a separate stage because a
//     stage's waitFor is checked BEFORE its own tasks run (see wordpress_site.scala).
//     Polls GET /v3/ over TLS, then authenticates for real.
//
// Scope, deliberately small: one node, no memcached, no HAProxy frontend.
// Fernet keys live on this node only (/var/lib/orphera/keystone/fernet-keys).
//
// Layout on the node:
//   /etc/kolla/keystone/        mounted read-only into the container
//       config.json keystone.conf wsgi-keystone.conf ssl/{server.crt,server.key,ca.crt}
//   /etc/kolla/keystone-ca/     CA key and certificate (NOT mounted)
//   /var/lib/orphera/keystone/  fernet-keys, credential-keys (bind-mounted rw)
//   /var/log/kolla/keystone/    logs
//   /etc/systemd/system/orphera-keystone.service
//   /usr/local/sbin/keystone-admin-token
//
// The certificate Apache serves is the server certificate followed by the CA
// (server-chain.crt), so a new client node can fetch the CA from the TLS
// handshake itself (trust on first use; see cinder_single_node.scala).
//
// TLS: the self-signed CA is created once and reused on re-runs, and added to
// the node's system trust store. Other hosts that talk to Keystone need
// /etc/kolla/keystone-ca/ca.crt.
//
// Test-only hardcoded credentials, same convention and caveat as
// wordpress_site.scala. Not compiled where this was written (no Scala
// toolchain there); the first run on scala0 is the first compile.
object keystone_single_node extends OrpheraClusterPlaybook:

  // Change these to move Keystone to another node. The node must be in
  // inventory.yaml and have the agent installed.
  private val node = "tst6"
  private val fqdn = "tst6.ljalbinson.com"
  private val nodeIp = "10.10.5.18"

  // Kolla image: <registry>/keystone:<release>-<distro>-<version>, built from the
  // Container below (kolla/Container.scala). Confirm the tag exists (`podman pull`)
  // before changing the Release or Base; the pull task prints a hint if it does not.
  // The base must match the node's distro family so the image and host agree on
  // Ubuntu/Rocky conventions (the config files are detected from the image itself,
  // so the host can be either).
  private val registry = "quay.io/openstack.kolla"

  private val dbName = "keystone"
  private val dbUser = "keystone"
  private val dbPassword = Secrets.secret("keystone/db")
  private val adminPassword = Secrets.secret("keystone/admin")

  // Floating IP held by keepalived on whichever of tst3-tst5 is current.
  private val vip = "10.10.5.100"

  private val endpoint = s"https://$fqdn:5000/v3/"

  private val confDir = "/etc/kolla/keystone"
  private val sslDir = s"$confDir/ssl"
  private val caDir = "/etc/kolla/keystone-ca"
  private val dataDir = "/var/lib/orphera/keystone"
  private val logDir = "/var/log/kolla/keystone"
  private val caCert = s"$caDir/ca.crt"

  // The container as data: the unit file text and the start task come from it
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined after
  // confDir, dataDir and logDir, which it reads; a val used before its definition is
  // null in an object.
  private val keystoneContainer = Container(
    name = ContainerName("keystone"),
    image = Image("keystone", Release.R2025_1, Base.UbuntuNoble, registry),
    mounts = List(
      Mount(confDir, "/var/lib/kolla/config_files"),
      Mount(
        s"$dataDir/fernet-keys",
        "/etc/keystone/fernet-keys",
        Access.ReadWrite
      ),
      Mount(
        s"$dataDir/credential-keys",
        "/etc/keystone/credential-keys",
        Access.ReadWrite
      ),
      Mount(logDir, "/var/log/kolla/keystone", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    settleSeconds = 5
  )
  private val image = keystoneContainer.image.ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates")

  // Kolla image cache from kolla_cache.scala (tst9). podman tries this mirror
  // first and falls back to quay.io if it is unreachable, so the manifest
  // still works with the cache down or not yet built.
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

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

  // tst3 only. IF NOT EXISTS / re-GRANT make a re-run a no-op. `@'%'` for the
  // same reason as wordpress_site.scala: haproxy's tcp mode hides the real
  // client address.
  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val pullScript =
    s"""${Pull.shellFunction(
        "the registry path and tag, the Image(...) in keystoneContainer in keystone_single_node.scala"
      )}
       |pull_retry $image || exit 1
       |echo "image ready: $image"""".stripMargin

  // CA and server certificate are created once and then reused, so the CA
  // clients already trust never changes under them. SAN: FQDN, short name, IP.
  // Trust-store step covers both Debian-family and Red Hat-family hosts.
  private val tlsScript =
    s"""set -e
       |mkdir -p $caDir $sslDir
       |chmod 700 $caDir
       |cd $caDir
       |if [ ! -f ca.key ] || [ ! -f ca.crt ]; then
       |  openssl req -x509 -newkey rsa:4096 -nodes -keyout ca.key -out ca.crt -days 3650 -subj "/CN=Orphera Test CA"
       |  chmod 600 ca.key
       |fi
       |cd $sslDir
       |if [ ! -f server.key ] || [ ! -f server.crt ]; then
       |  cat > server.ext <<EXT
       |subjectAltName=DNS:$fqdn,DNS:$node,IP:$nodeIp
       |basicConstraints=CA:FALSE
       |keyUsage=digitalSignature,keyEncipherment
       |extendedKeyUsage=serverAuth
       |EXT
       |  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |  openssl x509 -req -in server.csr -CA $caDir/ca.crt -CAkey $caDir/ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext
       |  chmod 600 server.key
       |fi
       |cp $caDir/ca.crt $sslDir/ca.crt
       |cat $sslDir/server.crt $caDir/ca.crt > $sslDir/server-chain.crt
       |openssl verify -CAfile $caDir/ca.crt $sslDir/server.crt
       |if command -v update-ca-certificates >/dev/null 2>&1; then
       |  cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |  update-ca-certificates
       |else
       |  cp $caDir/ca.crt /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt
       |  update-ca-trust
       |fi
       |echo "TLS material ready in $sslDir"""".stripMargin

  // The directories keystone.conf and the logs go in. keystone.conf itself is
  // written below from a typed value; the container sees it at /etc/keystone
  // (copied by kolla_set_configs).
  private val keystoneDirsScript =
    s"""mkdir -p $confDir $logDir
       |echo "directories ready"""".stripMargin

  // The option lists of the image (keystone), from catalogues/. Every section and key of the
  // files below is checked against them when this manifest is built.
  private val keystoneCatalogue: Catalogue =
    Catalogues.load("keystone", Set.empty[String])

  // Federation with Keycloak on tst26 (keycloak_single_node.scala). Keystone only
  // checks the bearer token Keycloak issued: mod_auth_openidc, in the vhost below,
  // verifies its signature against the realm's published keys (OAuth 2.0 resource
  // server mode, no client secret here) and passes its claims on with the prefix
  // OIDC-. The identity provider `keycloak`, its protocol `openid` and the mapping
  // are made through the API by keycloak_single_node.scala. Until that has run, the
  // federation path answers 404 and nothing else changes. Keep in step with
  // keycloak_single_node.scala (fqdn, port, realm).
  private val keycloakIssuer =
    "https://tst26.ljalbinson.com:8443/realms/openstack"
  private val federationIdp = "keycloak"
  private val federationProtocol = "openid"

  // Browser single sign-on (WebSSO). A dashboard sends the browser to Keystone's websso path; mod_auth_openidc,
  // here as an OpenID Connect client of Keycloak (client `keystone`, its secret from keycloak/keystone-client),
  // sends it on to Keycloak's login page, takes the code back on /redirect_uri, and Keystone then posts a token
  // to the dashboard through sso_callback_template, but only to an address listed in trusted_dashboard. Horizon
  // uses the path for identity provider keycloak; Skyline (8.x) uses the protocol-wide path, for which Keystone
  // finds the identity provider from [federation] remote_id_attribute. Keep the two dashboard addresses in step
  // with horizon_single_node.scala and skyline_single_node.scala; users must open the dashboards by these names.
  // Both are HTTPS: the page Keystone answers with is HTTPS, and browsers (Safari silently) refuse to post a form
  // from it to a plain-HTTP address.
  private val keycloakClientSecret = Secrets.secret("keycloak/keystone-client")
  private val oidcCryptoPassphrase = Secrets.secret("keystone/oidc-crypto")
  private val trustedDashboards = List(
    "https://tst13.ljalbinson.com:8080/auth/websso/",
    "https://tst13.ljalbinson.com:9999/api/openstack/skyline/api/v1/websso"
  )
  private val ssoTemplatePath = "/etc/keystone/sso_callback_template.html"

  // [federation] is the last section, so the other trusted_dashboard lines (a multi-valued option, which
  // ConfFile holds once) can follow it in the written file; the first one is still checked by the catalogue.
  private val keystoneConf: ConfFile =
    ConfFile.text(s"$confDir/keystone.conf", keystoneCatalogue)(
      ("DEFAULT", "log_dir", "/var/log/kolla/keystone"),
      ("DEFAULT", "use_stderr", "true"),
      (
        "database",
        "connection",
        s"mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName"
      ),
      ("token", "provider", "fernet"),
      // `openid` is the federation method (the mapped plugin under that name); the
      // default list's external, oauth1 and mapped are not used here.
      ("auth", "methods", "password,token,openid,application_credential"),
      ("federation", "remote_id_attribute", "HTTP_OIDC_ISS"),
      ("federation", "sso_callback_template", ssoTemplatePath),
      ("federation", "trusted_dashboard", trustedDashboards.head)
    )
  private val keystoneConfText: String =
    val base = keystoneConf.render
    require(
      base.split("\n\n").last.startsWith("[federation]"),
      "keystone.conf: [federation] must be the last section"
    )
    base + trustedDashboards.tail.map(d => s"trusted_dashboard = $d\n").mkString

  // The page Keystone answers the websso path with: it posts the token to the dashboard. The same as the one in
  // Keystone's source tree ($host and $token are filled in by Keystone), written here so it does not depend on
  // where the image keeps its copy.
  private val ssoCallbackTemplate =
    """<!DOCTYPE html>
      |<html xmlns="http://www.w3.org/1999/xhtml">
      |  <head>
      |    <title>Keystone WebSSO redirect</title>
      |  </head>
      |  <body>
      |     <form id="sso" name="sso" action="$host" method="post">
      |       Please wait...
      |       <br/>
      |       <input type="hidden" name="token" id="token" value="$token"/>
      |       <noscript>
      |         <input type="submit" name="submit_no_javascript" id="submit_no_javascript"
      |            value="If your JavaScript is disabled, please click to continue"/>
      |       </noscript>
      |     </form>
      |     <script type="text/javascript">
      |       window.onload = function() {
      |         document.forms['sso'].submit();
      |       }
      |     </script>
      |  </body>
      |</html>
      |""".stripMargin

  // Inspects the image for the two things that differ between builds: where
  // keystone-wsgi-public lives (a venv under /var/lib/kolla in recent images),
  // and whether Apache is the Debian (apache2, conf-enabled) or Red Hat
  // (httpd, conf.d) flavour. Then writes the vhost and Kolla's config.json.
  private val kollaConfigScript =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $image -c 'command -v keystone-wsgi-public')
       |if [ -z "$$WSGI" ]; then echo "keystone-wsgi-public not found in $image" >&2; exit 1; fi
       |if podman run --rm --entrypoint sh $image -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/wsgi-keystone.conf
       |  APACHE_CMD="apache2"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/wsgi-keystone.conf
       |  APACHE_CMD="/usr/sbin/httpd"
       |fi
       |# kolla_start appends -DFOREGROUND itself. mod_ssl is not always loaded in
       |# the image, so load it explicitly from wherever the image keeps it.
       |SSLMOD=$$(podman run --rm --entrypoint sh $image -c 'ls /usr/lib/apache2/modules/mod_ssl.so /etc/httpd/modules/mod_ssl.so /usr/lib64/httpd/modules/mod_ssl.so 2>/dev/null | head -n 1')
       |if [ -z "$$SSLMOD" ]; then echo "mod_ssl.so not found in $image" >&2; exit 1; fi
       |# mod_auth_openidc, for federation with Keycloak. Kolla's keystone image ships it;
       |# if this build does not, Keystone is set up without federation and says so.
       |OIDCMOD=$$(podman run --rm --entrypoint sh $image -c 'ls /usr/lib/apache2/modules/mod_auth_openidc.so /etc/httpd/modules/mod_auth_openidc.so /usr/lib64/httpd/modules/mod_auth_openidc.so 2>/dev/null | head -n 1')
       |OIDC_LOAD=""
       |OIDC_VHOST=""
       |if [ -n "$$OIDCMOD" ]; then
       |  OIDC_LOAD="<IfModule !auth_openidc_module>
       |LoadModule auth_openidc_module $$OIDCMOD
       |</IfModule>"
       |  OIDC_VHOST=$$(cat <<'OIDC'
       |    # Federation: a Keycloak access token (Authorization: Bearer) is checked
       |    # against the realm's keys; its claims reach Keystone as OIDC-<claim>.
       |    OIDCClaimPrefix "OIDC-"
       |    # A claim with several values (groups) reaches Keystone as one string; Keystone's mapping splits on ";".
       |    OIDCClaimDelimiter ";"
       |    OIDCOAuthVerifyJwksUri "$keycloakIssuer/protocol/openid-connect/certs"
       |    OIDCCABundlePath /etc/keystone/ssl/ca.crt
       |    <Location ~ "/v3/OS-FEDERATION/identity_providers/$federationIdp/protocols/$federationProtocol/auth">
       |        AuthType oauth20
       |        Require valid-user
       |    </Location>
       |    # WebSSO: Keystone as an OpenID Connect client of Keycloak, for the dashboards.
       |    OIDCProviderMetadataURL "$keycloakIssuer/.well-known/openid-configuration"
       |    OIDCClientID "keystone"
       |    OIDCClientSecret "${keycloakClientSecret.reveal}"
       |    OIDCCryptoPassphrase "${oidcCryptoPassphrase.reveal}"
       |    OIDCRedirectURI "https://$fqdn:5000/redirect_uri"
       |    OIDCResponseType "code"
       |    OIDCScope "openid email profile"
       |    # Logout: a dashboard sends the browser to /redirect_uri?logout=<its login page>; mod_auth_openidc ends its own
       |    # session, then Keycloak's (end_session_endpoint with the id token), and Keycloak sends the browser back. Only
       |    # these return addresses are accepted.
       |    OIDCRedirectURLsAllowed ^https://tst13[.]ljalbinson[.]com:(8080|9999)/
       |    <Location /redirect_uri>
       |        AuthType openid-connect
       |        Require valid-user
       |    </Location>
       |    <Location ~ "/v3/auth/OS-FEDERATION/websso/$federationProtocol">
       |        AuthType openid-connect
       |        Require valid-user
       |    </Location>
       |    <Location ~ "/v3/auth/OS-FEDERATION/identity_providers/$federationIdp/protocols/$federationProtocol/websso">
       |        AuthType openid-connect
       |        Require valid-user
       |    </Location>
       |OIDC
       |)
       |  echo "mod_auth_openidc: $$OIDCMOD (federation with $keycloakIssuer)"
       |else
       |  echo "WARNING: mod_auth_openidc not found in $image; Keystone is set up without federation" >&2
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF; mod_ssl: $$SSLMOD"
       |cat > $confDir/wsgi-keystone.conf <<WSGICONF
       |<IfModule !ssl_module>
       |LoadModule ssl_module $$SSLMOD
       |</IfModule>
       |$$OIDC_LOAD
       |Listen 0.0.0.0:5000
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:5000>
       |    ErrorLog "$logDir/keystone-apache-public-error.log"
       |    CustomLog "$logDir/keystone-apache-public-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess keystone-public group=keystone processes=2 threads=1 user=keystone
       |    WSGIProcessGroup keystone-public
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    SSLEngine on
       |    SSLCertificateFile /etc/keystone/ssl/server.crt
       |    SSLCertificateKeyFile /etc/keystone/ssl/server.key
       |
       |$$OIDC_VHOST
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |# It holds the OIDC client secret and passphrase.
       |chmod 600 $confDir/wsgi-keystone.conf
       |cat > $confDir/config.json <<JSON
       |{
       |  "command": "$$APACHE_CMD",
       |  "config_files": [
       |    {"source": "/var/lib/kolla/config_files/keystone.conf", "dest": "/etc/keystone/keystone.conf", "owner": "keystone", "perm": "0600"},
       |    {"source": "/var/lib/kolla/config_files/wsgi-keystone.conf", "dest": "$$APACHE_CONF", "owner": "keystone", "perm": "0600"},
       |    {"source": "/var/lib/kolla/config_files/ssl/server-chain.crt", "dest": "/etc/keystone/ssl/server.crt", "owner": "keystone", "perm": "0644"},
       |    {"source": "/var/lib/kolla/config_files/ssl/server.key", "dest": "/etc/keystone/ssl/server.key", "owner": "keystone", "perm": "0600"},
       |    {"source": "/var/lib/kolla/config_files/ssl/ca.crt", "dest": "/etc/keystone/ssl/ca.crt", "owner": "keystone", "perm": "0644"},
       |    {"source": "/var/lib/kolla/config_files/sso_callback_template.html", "dest": "$ssoTemplatePath", "owner": "keystone", "perm": "0644"}
       |  ],
       |  "permissions": [
       |    {"path": "/var/log/kolla/keystone", "owner": "keystone:kolla", "recurse": true},
       |    {"path": "/etc/keystone/fernet-keys", "owner": "keystone:keystone", "recurse": true},
       |    {"path": "/etc/keystone/credential-keys", "owner": "keystone:keystone", "recurse": true}
       |  ]
       |}
       |JSON
       |echo "kolla config written to $confDir"""".stripMargin

  // One-off containers run keystone-manage directly (entrypoint overridden, so
  // kolla_set_configs does not run) with keystone.conf, the key directories and
  // the log directory bind-mounted. `:z` relabels for SELinux hosts and is
  // ignored elsewhere. db_sync is idempotent; the key setups are guarded so a
  // re-run keeps existing keys (tokens already issued stay valid); bootstrap
  // re-asserts the admin user and endpoints.
  private val initScript =
    s"""set -e
       |mkdir -p $dataDir/fernet-keys $dataDir/credential-keys $logDir
       |# keystone-manage creates key files as the image's keystone user (it switches
       |# its effective uid), so the host directories must belong to that uid.
       |KUID=$$(podman run --rm --entrypoint id $image -u keystone)
       |KGID=$$(podman run --rm --entrypoint id $image -g keystone)
       |chown -R $$KUID:$$KGID $dataDir/fernet-keys $dataDir/credential-keys
       |chmod 700 $dataDir/fernet-keys $dataDir/credential-keys
       |ks() {
       |  podman run --rm --network host --user root --entrypoint keystone-manage -v $confDir/keystone.conf:/etc/keystone/keystone.conf:ro,z -v $dataDir/fernet-keys:/etc/keystone/fernet-keys:z -v $dataDir/credential-keys:/etc/keystone/credential-keys:z -v $logDir:/var/log/kolla/keystone:z $image "$$@"
       |}
       |ks db_sync
       |[ -f $dataDir/fernet-keys/0 ] || ks fernet_setup --keystone-user keystone --keystone-group keystone
       |[ -f $dataDir/credential-keys/0 ] || ks credential_setup --keystone-user keystone --keystone-group keystone
       |ks bootstrap --bootstrap-password '${adminPassword.reveal}' --bootstrap-admin-url $endpoint --bootstrap-internal-url $endpoint --bootstrap-public-url $endpoint --bootstrap-region-id RegionOne
       |echo "keystone database, keys and bootstrap done"""".stripMargin

  // Prints a scoped admin token on stdout. Used by the confirm stage and by
  // test_keystone.sh; root only, since it embeds the admin password.
  private val tokenHelperScript =
    s"""cat > /usr/local/sbin/keystone-admin-token <<'HELPER'
       |#!/bin/sh
       |curl -s --cacert $caCert -D - -o /dev/null -H 'Content-Type: application/json' -d '{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"admin","domain":{"name":"Default"},"password":"${adminPassword.reveal}"}}},"scope":{"project":{"name":"admin","domain":{"name":"Default"}}}}}' https://$fqdn:5000/v3/auth/tokens | tr -d '\\r' | awk -F': ' 'tolower($$1)=="x-subject-token"{print $$2}'
       |HELPER
       |chmod 700 /usr/local/sbin/keystone-admin-token
       |echo "wrote /usr/local/sbin/keystone-admin-token"""".stripMargin

  // Over TLS with the test CA, and the body must look like the v3 version
  // document, so a 200 from some unrelated listener does not pass.
  private val healthScript =
    s"""CODE=$$(curl -s --cacert $caCert -o /tmp/ks-check.json -w '%{http_code}' https://$fqdn:5000/v3/)
       |if [ "$$CODE" = "200" ] && grep -q '"version"' /tmp/ks-check.json; then
       |  exit 0
       |else
       |  exit 1
       |fi""".stripMargin

  // A real authentication against the database, not just a reachable port.
  // Prints only the token length, never the token.
  private val tokenScript =
    """T=$(/usr/local/sbin/keystone-admin-token)
      |if [ -z "$T" ]; then
      |  echo "no token returned by keystone" >&2
      |  exit 1
      |fi
      |echo "admin token issued (${#T} characters)"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("keystone-single-node")(
      stage("create-keystone-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-keystone", node)
        .task("install podman and the TLS tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Kolla keystone image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1200)
        )
        .task("create the test CA and server certificate")(
          Task.RunCommand(List("sh", "-c", tlsScript), timeoutSeconds = 120)
        )
        .task("create the Keystone directories")(
          Task.RunCommand(List("sh", "-c", keystoneDirsScript))
        )
        .task("write keystone.conf")(
          Task.WriteFile(
            content = keystoneConfText,
            dest = s"$confDir/keystone.conf",
            owner = "root",
            group = "root",
            mode = 416 // 0640: it holds the database password
          )
        )
        .task("write the WebSSO callback page")(
          Task.WriteFile(
            content = ssoCallbackTemplate,
            dest = s"$confDir/sso_callback_template.html",
            owner = "root",
            group = "root",
            mode = 420 // 0644
          )
        )
        .task("write the Apache vhost and Kolla config.json")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 120
          )
        )
        .task("db_sync, fernet and credential keys, bootstrap")(
          Task.RunCommand(List("sh", "-c", initScript), timeoutSeconds = 600)
        )
        .task(s"write ${keystoneContainer.unitName}")(
          keystoneContainer.writeUnit
        )
        .task(s"start ${keystoneContainer.unitName}")(keystoneContainer.start)
        .task("write the admin token helper")(
          Task.RunCommand(List("sh", "-c", tokenHelperScript))
        )
        .build,

      stage("confirm-keystone-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 90
          )
        )
        .task("authenticate as admin")(
          Task.RunCommand(List("sh", "-c", tokenScript), timeoutSeconds = 60)
        )
        .task("keystone confirmed")(
          Task.Debug(
            s"Keystone ($image) up at $endpoint ($nodeIp), database on the Galera cluster via $vip:3306. CA certificate: $caCert on $node."
          )
        )
        .build
    )
