// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
import orphera.orchestrator.conf.*

// OpenStack Keystone (identity service), one node, TLS from the start, run
// from the Kolla container image rather than distro packages, as a declarative
// description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run now changes nothing and restarts
// nothing, and `--check` shows what would change.
//
//   orphera apply manifests/keystone_database.scala     # first: the database and user (tst3)
//   orphera apply manifests/keystone_single_node.scala --check
//   orphera apply manifests/keystone_single_node.scala
//
// Prerequisite, not checked here: mariadb_galera_cluster.scala AND
// mariadb_haproxy_keepalived.scala are applied and healthy on tst3-tst5, and
// keystone_database.scala has run (one description cannot order its nodes, and
// db_sync needs the database).
//
// Why Kolla: a pinned, tested image replaces the distro package (no
// package-owned Apache site or debconf questions to fight), and the same image
// runs on Ubuntu and Rocky. Orphera does the part kolla-ansible normally does:
// it writes the config files the image's `kolla_start` expects and starts the
// container. See https://docs.openstack.org/kolla/latest/admin/kolla_api.html
// (config.json at /var/lib/kolla/config_files, KOLLA_CONFIG_STRATEGY).
//
// Resources, in order: podman and the TLS tools; the Kolla cache as a mirror
// for quay.io; the image (pulled once); the test CA and server certificate
// (made once and then reused); the directories; keystone.conf and the WebSSO
// page; the Apache vhost and Kolla config.json; db_sync, the fernet and
// credential keys and bootstrap; the container's unit and service; the admin
// token helper; and two checks (Keystone answers over TLS, the admin can
// authenticate).
//
// What is a command, and why. The certificates, the vhost and config.json, and
// the init step have no declarative kind: each is a command with a guard that
// says whether it is already done, so it runs only when it is needed.
//   - The vhost and config.json are worked out from the image (where
//     keystone-wsgi-public lives, the Apache flavour, where mod_ssl and
//     mod_auth_openidc are), so the guard generates them into a temporary
//     directory and compares: they are rewritten, and Keystone restarted, only
//     when the image or a setting changed them.
//   - db_sync, key setup and bootstrap run again when the image, keystone.conf
//     or the admin password changed (a stamp on the node holds the hash of
//     what they last ran with); the key setups keep existing keys.
//   - Anything that changes the container's files restarts it, once.
//
// Scope, deliberately small: one node, no memcached, no HAProxy frontend.
// Fernet keys live on this node only (/var/lib/orphera/keystone/fernet-keys).
//
// Layout on the node:
//   /etc/kolla/keystone/        mounted read-only into the container
//       config.json keystone.conf wsgi-keystone.conf ssl/{server.crt,server.key,ca.crt}
//   /etc/kolla/keystone-ca/     CA key and certificate (NOT mounted)
//   /var/lib/orphera/keystone/  fernet-keys, credential-keys (bind-mounted rw), init.stamp
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
object keystone_single_node extends OrpheraApply:
  import Resources.*

  override def nodes: List[String] = List(node)

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

  // The option lists of the image (keystone), from catalogues/. Every section and key of the
  // files below is checked against them when this manifest is built.
  private val keystoneCatalogue: Catalogue =
    Catalogues.load("keystone", Set.empty[String])

  // Federation with Keycloak on tst26 (keycloak_single_node.scala). For the CLI,
  // Keystone checks the bearer token Keycloak issued: mod_auth_openidc, in the vhost
  // below, verifies its signature against the realm's published keys (OAuth 2.0
  // resource server mode) and passes its claims on with the prefix OIDC- (groups
  // with ";" between values). For the dashboards it is also an OpenID Connect client
  // of Keycloak (WebSSO, below), which needs the client secret. The identity provider `keycloak`, its protocol `openid` and the mapping
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
  private def kollaConfigScript(out: String): String =
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
       |cat > $out/wsgi-keystone.conf <<WSGICONF
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
       |chmod 600 $out/wsgi-keystone.conf
       |cat > $out/config.json <<JSON
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
       |echo "kolla config written to $out"""".stripMargin

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

  private val pullScript =
    s"""${Pull.shellFunction(
        "the registry path and tag, the Image(...) in keystoneContainer in keystone_single_node.scala"
      )}
       |pull_retry $image || exit 1
       |echo "image ready: $image"""".stripMargin

  // CA and server certificate are created once and then reused, so the CA
  // clients already trust never changes under them. SAN: FQDN, short name, IP.
  private val caExists = s"[ -f $caDir/ca.key ] && [ -f $caDir/ca.crt ]"
  private val caScript =
    s"""set -e
       |cd $caDir
       |openssl req -x509 -newkey rsa:4096 -nodes -keyout ca.key -out ca.crt -days 3650 -subj "/CN=Orphera Test CA"
       |chmod 600 ca.key
       |echo "test CA made in $caDir"""".stripMargin

  private val serverExists =
    s"[ -f $sslDir/server.key ] && [ -f $sslDir/server.crt ]"
  private val serverScript =
    s"""set -e
       |cd $sslDir
       |cat > server.ext <<EXT
       |subjectAltName=DNS:$fqdn,DNS:$node,IP:$nodeIp
       |basicConstraints=CA:FALSE
       |keyUsage=digitalSignature,keyEncipherment
       |extendedKeyUsage=serverAuth
       |EXT
       |openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |openssl x509 -req -in server.csr -CA $caDir/ca.crt -CAkey $caDir/ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext
       |chmod 600 server.key
       |echo "server certificate made in $sslDir"""".stripMargin

  // The CA next to the key Apache serves, and the chain Apache serves (the
  // server certificate, then the CA).
  private val chainCurrent =
    s"""cmp -s $caCert $sslDir/ca.crt && [ -f $sslDir/server-chain.crt ] && cat $sslDir/server.crt $caCert | cmp -s - $sslDir/server-chain.crt"""
  private val chainScript =
    s"""set -e
       |cp $caCert $sslDir/ca.crt
       |cat $sslDir/server.crt $caCert > $sslDir/server-chain.crt
       |openssl verify -CAfile $caCert $sslDir/server.crt""".stripMargin

  // The trust-store step covers both Debian-family and Red Hat-family hosts.
  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""
  private val trustCurrent = s"""$trustTarget; cmp -s $caCert "$$t""""
  private val trustScript =
    s"""set -e
       |$trustTarget
       |cp $caCert "$$t"
       |if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; else update-ca-trust; fi""".stripMargin

  // The vhost and config.json are generated into `out` (the real directory, or
  // a temporary one for the guard to compare with).
  private val kollaFilesCurrent =
    s"""T=$$(mktemp -d)
       |${kollaConfigScript("$T")}
       |R=0
       |cmp -s $$T/wsgi-keystone.conf $confDir/wsgi-keystone.conf && cmp -s $$T/config.json $confDir/config.json || R=1
       |rm -rf $$T
       |exit $$R""".stripMargin

  private val initStamp = Resource.sha256(initScript + keystoneConfText)
  // Current when the stamp matches AND the database is at the current schema
  // (`db_sync --check` exits 0), so a database dropped or replaced since the
  // stamp was written is initialised again.
  private val initCurrent =
    s"""[ "$$(cat $dataDir/init.stamp 2>/dev/null)" = "$initStamp" ] && podman run --rm --network host --user root --entrypoint keystone-manage -v $confDir/keystone.conf:/etc/keystone/keystone.conf:ro,z -v $logDir:/var/log/kolla/keystone:z $image db_sync --check >/dev/null 2>&1"""
  private val initWithStamp =
    s"""$initScript
       |echo $initStamp > $dataDir/init.stamp""".stripMargin

  // Prints a scoped admin token on stdout. Used by the check below and by
  // test_keystone.sh; root only, since it embeds the admin password.
  private val tokenHelper =
    s"""#!/bin/sh
       |curl -s --cacert $caCert -D - -o /dev/null -H 'Content-Type: application/json' -d '{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"admin","domain":{"name":"Default"},"password":"${adminPassword.reveal}"}}},"scope":{"project":{"name":"admin","domain":{"name":"Default"}}}}}' https://$fqdn:5000/v3/auth/tokens | tr -d '\\r' | awk -F': ' 'tolower($$1)=="x-subject-token"{print $$2}'
       |""".stripMargin

  // A real authentication against the database, not just a reachable port.
  private val canAuthenticate =
    """[ -n "$(/usr/local/sbin/keystone-admin-token)" ]"""

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)
    val unit = keystoneContainer.unitName

    val unitService = service(unit)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Kolla keystone image",
      pullScript,
      unless = s"podman image exists $image",
      timeout = 1200
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0700").after(podman)
    val confDirectory = directory(confDir, mode = "0755")
    val sslDirectory = directory(sslDir, mode = "0755").after(confDirectory)
    val logDirectory = directory(logDir, mode = "0755")
    val ca = command(
      "create the test CA",
      caScript,
      unless = caExists,
      timeout = 120
    ).after(caDirectory, podman)
    val server = command(
      "create the server certificate",
      serverScript,
      unless = serverExists,
      timeout = 120
    ).after(ca, sslDirectory)
    val chain = command(
      "put the CA and the certificate chain beside the key",
      chainScript,
      unless = chainCurrent
    ).after(server).notifies(unitService)
    val trust = command(
      "add the test CA to the system trust store",
      trustScript,
      unless = trustCurrent,
      timeout = 120
    ).after(ca)

    val conf = secretFile(
      s"$confDir/keystone.conf",
      Secret(keystoneConfText), // it holds the database password
      mode = "0640",
      owner = "root",
      group = "root"
    ).after(confDirectory).notifies(unitService)
    val ssoPage = file(
      s"$confDir/sso_callback_template.html",
      ssoCallbackTemplate,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(confDirectory).notifies(unitService)
    val kollaFiles = command(
      "write the Apache vhost and Kolla config.json",
      kollaConfigScript(confDir),
      unless = kollaFilesCurrent,
      timeout = 300
    ).after(pulled, confDirectory, sslDirectory, logDirectory)
      .notifies(unitService)

    val init = command(
      "db_sync, fernet and credential keys, bootstrap",
      initWithStamp,
      unless = initCurrent,
      timeout = 600
    ).after(pulled, conf, logDirectory)

    val unitFile = file(
      keystoneContainer.unitPath,
      keystoneContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unitService)
    val running =
      unitService.after(init, unitFile, kollaFiles, chain, conf, ssoPage)

    val tokenHelperFile = secretFile(
      "/usr/local/sbin/keystone-admin-token",
      Secret(tokenHelper), // it holds the admin password
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Over TLS with the test CA, and the body must look like the v3 version document, so a 200 from some
    // unrelated listener does not pass. Up to 90 s for Keystone to answer after a (re)start.
    val answering = waitForHttp(
      "keystone answers over TLS",
      endpoint,
      ca = caCert,
      contains = "\"version\"",
      timeout = 90,
      hint = s"see 'journalctl -xeu ${keystoneContainer.unitName}' and $logDir"
    ).after(running, ca)
    val authenticating = command(
      "the admin can authenticate",
      "echo 'no token returned by keystone: see the database, keystone.conf and the logs' >&2; exit 1",
      unless = canAuthenticate
    ).after(answering, tokenHelperFile)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      confDirectory,
      sslDirectory,
      logDirectory,
      ca,
      server,
      chain,
      trust,
      conf,
      ssoPage,
      kollaFiles,
      init,
      unitFile,
      running,
      tokenHelperFile,
      answering,
      authenticating
    )
