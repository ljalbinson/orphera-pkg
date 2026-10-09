// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// OpenStack Keystone (identity service), one node, TLS from the start.
//
// Same shape as wordpress_site.scala: an ordinary application on top of the
// already-working Galera cluster behind the 10.10.5.100 VIP. Prerequisite,
// not checked here: mariadb_galera_cluster.scala AND
// mariadb_haproxy_keepalived.scala are applied and healthy on tst0-tst2.
//
// Stages:
//   - create-keystone-database (tst0): `keystone` database and user, via the
//     unix-socket root login, same as wordpress_site.scala. Galera replicates
//     it to the other two nodes.
//   - install-keystone (keystoneNode): packages, keystone.conf pointing at the
//     VIP write listener (3306), a private test CA and a server certificate
//     for the node, db_sync, fernet and credential key setup, `bootstrap`
//     (creates the admin user, project, role and the v3 endpoints), an Apache
//     vhost that terminates TLS on 5000 and runs keystone-wsgi-public, and an
//     admin-openrc file.
//   - confirm-keystone-healthy (keystoneNode): a separate stage because a
//     stage's waitFor is checked BEFORE its own tasks run (see the long note
//     in wordpress_site.scala). Polls GET /v3/ over TLS, then issues a real
//     admin token with the openstack client.
//
// Scope, deliberately small: one node, no memcached (fernet tokens need no
// shared cache and the default cache backend is fine for one node), no
// HAProxy frontend for 5000. Fernet keys live only on this node; a second
// Keystone node would need the keys copied out and a rotation manifest.
//
// TLS: a self-signed test CA is created on the node under /etc/keystone/ssl
// (ca.crt, ca.key, server.crt, server.key) the first time, then reused on
// re-runs. ca.crt is also added to the node's system trust store so the
// openstack client and curl work there without flags. Other hosts that talk
// to Keystone must be given ca.crt. Ubuntu only (apt, a2enmod,
// update-ca-certificates).
//
// Test-only hardcoded credentials, same convention and caveat as
// wordpress_site.scala: fine for disposable infrastructure, not for anything
// real.
//
// Not compiled where this was written (no Scala toolchain there); the first
// run on scala0 is the first compile.
object keystone_single_node extends OrpheraClusterPlaybook:

  // Change these to move Keystone to another node. The node must be in
  // inventory.yaml and have the agent installed. Currently tst7 (Ubuntu
  // 24.04, otherwise unused).
  private val node = "tst7"
  private val fqdn = "tst7.ljalbinson.com"
  private val nodeIp = "10.10.5.19"

  private val dbName = "keystone"
  private val dbUser = "keystone"
  private val dbPassword = "orphera-test-keystone-db-password"
  private val adminPassword = "orphera-test-keystone-admin-password"

  // Floating IP held by keepalived on whichever of tst0-tst2 is current.
  private val vip = "10.10.5.100"

  private val endpoint = s"https://$fqdn:5000/v3/"

  private val keystonePackages = List(
    "keystone",
    "apache2",
    "libapache2-mod-wsgi-py3",
    "python3-pymysql",
    "python3-openstackclient",
    "openssl",
    "curl",
    "ca-certificates"
  )

  // tst0 only. IF NOT EXISTS / re-GRANT make a re-run a no-op. `@'%'` for the
  // same reason as wordpress_site.scala: haproxy's tcp mode hides the real
  // client address, so the connection arrives from a Galera node's address.
  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // Run BEFORE the package install. Debian's keystone package can ask
  // dbconfig-common questions in postinst; a noninteractive install would
  // then pick a default (sqlite) or hang. Preseeding "no" leaves
  // /etc/keystone/keystone.conf to this manifest. Harmless if the question
  // does not exist.
  private val preseedScript =
    """echo "keystone keystone/configure_db boolean false" | debconf-set-selections
      |echo "debconf preseeded for keystone"""".stripMargin

  private val writeConfigScript =
    s"""mkdir -p /etc/keystone
       |cat > /etc/keystone/keystone.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/keystone
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/$dbName
       |
       |[token]
       |provider = fernet
       |CONF
       |chown root:keystone /etc/keystone/keystone.conf
       |chmod 640 /etc/keystone/keystone.conf
       |echo "keystone.conf written, database at $vip:3306"""".stripMargin

  // Creates the CA and the server certificate once; later runs reuse them so
  // the CA clients already trust does not change. SAN covers the FQDN, the
  // short name and the IP.
  private val tlsScript =
    s"""set -e
       |mkdir -p /etc/keystone/ssl
       |cd /etc/keystone/ssl
       |if [ ! -f ca.key ] || [ ! -f ca.crt ]; then
       |  openssl req -x509 -newkey rsa:4096 -nodes -keyout ca.key -out ca.crt -days 3650 -subj "/CN=Orphera Test CA"
       |  chmod 600 ca.key
       |fi
       |if [ ! -f server.key ] || [ ! -f server.crt ]; then
       |  cat > server.ext <<EXT
       |subjectAltName=DNS:$fqdn,DNS:$node,IP:$nodeIp
       |basicConstraints=CA:FALSE
       |keyUsage=digitalSignature,keyEncipherment
       |extendedKeyUsage=serverAuth
       |EXT
       |  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 825 -extfile server.ext
       |  chmod 600 server.key
       |fi
       |openssl verify -CAfile ca.crt server.crt
       |cp ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |update-ca-certificates
       |echo "TLS material ready in /etc/keystone/ssl"""".stripMargin

  // db_sync is idempotent. fernet_setup / credential_setup are guarded so a
  // re-run does not touch existing keys (tokens already issued stay valid).
  // bootstrap is idempotent too: it re-asserts the admin user and endpoints.
  private val initScript =
    s"""set -e
       |su -s /bin/sh -c "keystone-manage db_sync" keystone
       |[ -f /etc/keystone/fernet-keys/0 ] || keystone-manage fernet_setup --keystone-user keystone --keystone-group keystone
       |[ -f /etc/keystone/credential-keys/0 ] || keystone-manage credential_setup --keystone-user keystone --keystone-group keystone
       |keystone-manage bootstrap --bootstrap-password '$adminPassword' --bootstrap-admin-url $endpoint --bootstrap-internal-url $endpoint --bootstrap-public-url $endpoint --bootstrap-region-id RegionOne
       |echo "keystone database, keys and bootstrap done"""".stripMargin

  // Our own vhost; the package's own site (if it enabled one) is disabled so
  // two vhosts do not both listen on 5000. Quoted heredoc: Apache's %{...}
  // must reach the file untouched. configtest and the final is-active check
  // follow the "prove it started" convention from wordpress_site.scala.
  private val apacheScript =
    """set -e
      |a2dissite keystone 2>/dev/null || true
      |a2dissite wsgi-keystone 2>/dev/null || true
      |a2disconf keystone 2>/dev/null || true
      |a2enmod ssl wsgi
      |cat > /etc/apache2/sites-available/orphera-keystone.conf <<'APACHE'
      |Listen 5000
      |<VirtualHost *:5000>
      |    WSGIDaemonProcess keystone-public processes=2 threads=1 user=keystone group=keystone display-name=%{GROUP}
      |    WSGIProcessGroup keystone-public
      |    WSGIScriptAlias / /usr/bin/keystone-wsgi-public
      |    WSGIApplicationGroup %{GLOBAL}
      |    WSGIPassAuthorization On
      |    LimitRequestBody 114688
      |    ErrorLogFormat "%{cu}t %M"
      |    ErrorLog /var/log/apache2/keystone.log
      |    CustomLog /var/log/apache2/keystone_access.log combined
      |
      |    SSLEngine on
      |    SSLCertificateFile /etc/keystone/ssl/server.crt
      |    SSLCertificateKeyFile /etc/keystone/ssl/server.key
      |
      |    <Directory /usr/bin>
      |        Require all granted
      |    </Directory>
      |</VirtualHost>
      |APACHE
      |a2ensite orphera-keystone
      |apache2ctl configtest
      |systemctl enable apache2
      |systemctl restart apache2
      |if ! systemctl is-active --quiet apache2; then
      |  echo "apache2 failed to start — see 'journalctl -xeu apache2' and /var/log/apache2/error.log" >&2
      |  exit 1
      |fi
      |echo "apache2 serving keystone on 5000 (TLS)"""".stripMargin

  private val openrcScript =
    s"""cat > /etc/keystone/admin-openrc <<'RC'
       |export OS_USERNAME=admin
       |export OS_PASSWORD=$adminPassword
       |export OS_PROJECT_NAME=admin
       |export OS_USER_DOMAIN_NAME=Default
       |export OS_PROJECT_DOMAIN_NAME=Default
       |export OS_AUTH_URL=https://$fqdn:5000/v3
       |export OS_IDENTITY_API_VERSION=3
       |export OS_REGION_NAME=RegionOne
       |export OS_CACERT=/etc/keystone/ssl/ca.crt
       |RC
       |chmod 600 /etc/keystone/admin-openrc
       |echo "wrote /etc/keystone/admin-openrc"""".stripMargin

  // Over TLS with the test CA, and the body must look like the v3 version
  // document, so a 200 from some unrelated listener does not pass.
  private val healthScript =
    s"""CODE=$$(curl -s --cacert /etc/keystone/ssl/ca.crt -o /tmp/ks-check.json -w '%{http_code}' https://$fqdn:5000/v3/)
       |if [ "$$CODE" = "200" ] && grep -q '"version"' /tmp/ks-check.json; then
       |  exit 0
       |else
       |  exit 1
       |fi""".stripMargin

  // A real authentication against the database, not just a reachable port.
  // Prints only the expiry, never the token.
  private val tokenScript =
    """set -e
      |. /etc/keystone/admin-openrc
      |EXPIRES=$(openstack token issue -f value -c expires)
      |echo "admin token issued, expires $EXPIRES"
      |openstack endpoint list -f value -c "Service Name" -c Interface -c URL""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("keystone-single-node")(
      stage("create-keystone-database", "tst0")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("install-keystone", node)
        .task("preseed debconf for keystone")(
          Task.RunCommand(List("sh", "-c", preseedScript))
        )
        .task("install keystone, apache2, mod_wsgi and the openstack client")(
          Task.Install(packages = keystonePackages, updateCache = true)
        )
        .task("write keystone.conf")(
          Task.RunCommand(List("sh", "-c", writeConfigScript))
        )
        .task("create the test CA and server certificate")(
          Task.RunCommand(List("sh", "-c", tlsScript), timeoutSeconds = 120)
        )
        .task("db_sync, fernet and credential keys, bootstrap")(
          Task.RunCommand(List("sh", "-c", initScript), timeoutSeconds = 300)
        )
        .task("configure and start apache with TLS on 5000")(
          Task.RunCommand(List("sh", "-c", apacheScript))
        )
        .task("write admin-openrc")(
          Task.RunCommand(List("sh", "-c", openrcScript))
        )
        .build,

      stage("confirm-keystone-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("issue an admin token and list endpoints")(
          Task.RunCommand(List("sh", "-c", tokenScript), timeoutSeconds = 60)
        )
        .task("keystone confirmed")(
          Task.Debug(
            s"Keystone up at $endpoint ($nodeIp), database on the Galera cluster via $vip:3306. Credentials: /etc/keystone/admin-openrc on $node. CA certificate: /etc/keystone/ssl/ca.crt."
          )
        )
        .build
    )
