// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

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

  // Kolla image: <registry>/keystone:<release>-<distro>-<version>. Confirm the
  // tag exists (`podman pull`) before changing it; the pull task prints a hint
  // if it does not. baseTag must match the node's distro family so the image
  // and host agree on Ubuntu/Rocky conventions (the config files are detected
  // from the image itself, so the host can be either).
  private val registry = "quay.io/openstack.kolla"
  private val release = "2025.1"
  private val baseTag = "ubuntu-noble"
  private val image = s"$registry/keystone:$release-$baseTag"

  private val dbName = "keystone"
  private val dbUser = "keystone"
  private val dbPassword = "orphera-test-keystone-db-password"
  private val adminPassword = "orphera-test-keystone-admin-password"

  // Floating IP held by keepalived on whichever of tst3-tst5 is current.
  private val vip = "10.10.5.100"

  private val endpoint = s"https://$fqdn:5000/v3/"

  private val confDir = "/etc/kolla/keystone"
  private val sslDir = s"$confDir/ssl"
  private val caDir = "/etc/kolla/keystone-ca"
  private val dataDir = "/var/lib/orphera/keystone"
  private val logDir = "/var/log/kolla/keystone"
  private val caCert = s"$caDir/ca.crt"

  private val hostPackages = List("podman", "openssl", "curl", "ca-certificates")

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
       |location = "$cacheHostPort"
       |insecure = true
       |REG
       |echo "podman will try $cacheHostPort before quay.io"""".stripMargin

  // tst3 only. IF NOT EXISTS / re-GRANT make a re-run a no-op. `@'%'` for the
  // same reason as wordpress_site.scala: haproxy's tcp mode hides the real
  // client address.
  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  private val pullScript =
    s"""podman pull $image || {
       |  echo "could not pull $image - check the registry path and tag (registry/release/baseTag at the top of keystone_single_node.scala)" >&2
       |  exit 1
       |}
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

  // keystone.conf as the container sees it (copied to /etc/keystone by
  // kolla_set_configs). Quoted heredoc: nothing for the shell to expand.
  private val writeConfigScript =
    s"""mkdir -p $confDir $logDir
       |cat > $confDir/keystone.conf <<'CONF'
       |[DEFAULT]
       |log_dir = /var/log/kolla/keystone
       |use_stderr = true
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:$dbPassword@$vip:3306/$dbName
       |
       |[token]
       |provider = fernet
       |CONF
       |chmod 640 $confDir/keystone.conf
       |echo "keystone.conf written, database at $vip:3306"""".stripMargin

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
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/wsgi-keystone.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $confDir/wsgi-keystone.conf <<WSGICONF
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
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $confDir/config.json <<JSON
       |{
       |  "command": "$$APACHE_CMD",
       |  "config_files": [
       |    {"source": "/var/lib/kolla/config_files/keystone.conf", "dest": "/etc/keystone/keystone.conf", "owner": "keystone", "perm": "0600"},
       |    {"source": "/var/lib/kolla/config_files/wsgi-keystone.conf", "dest": "$$APACHE_CONF", "owner": "keystone", "perm": "0600"},
       |    {"source": "/var/lib/kolla/config_files/ssl/server-chain.crt", "dest": "/etc/keystone/ssl/server.crt", "owner": "keystone", "perm": "0644"},
       |    {"source": "/var/lib/kolla/config_files/ssl/server.key", "dest": "/etc/keystone/ssl/server.key", "owner": "keystone", "perm": "0600"}
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
       |ks() {
       |  podman run --rm --network host --entrypoint keystone-manage -v $confDir/keystone.conf:/etc/keystone/keystone.conf:ro,z -v $dataDir/fernet-keys:/etc/keystone/fernet-keys:z -v $dataDir/credential-keys:/etc/keystone/credential-keys:z -v $logDir:/var/log/kolla/keystone:z $image "$$@"
       |}
       |ks db_sync
       |[ -f $dataDir/fernet-keys/0 ] || ks fernet_setup --keystone-user keystone --keystone-group keystone
       |[ -f $dataDir/credential-keys/0 ] || ks credential_setup --keystone-user keystone --keystone-group keystone
       |ks bootstrap --bootstrap-password '$adminPassword' --bootstrap-admin-url $endpoint --bootstrap-internal-url $endpoint --bootstrap-public-url $endpoint --bootstrap-region-id RegionOne
       |echo "keystone database, keys and bootstrap done"""".stripMargin

  // The long-lived container, run by systemd so it restarts and survives a
  // reboot. `--rm` plus ExecStartPre cleanup keeps the name free. The unit is
  // written on one ExecStart line on purpose (no line continuations).
  private val unitScript =
    s"""cat > /etc/systemd/system/orphera-keystone.service <<'UNIT'
       |[Unit]
       |Description=Keystone (Kolla container) managed by Orphera
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f keystone
       |ExecStart=/usr/bin/podman run --rm --name keystone --network host -e KOLLA_CONFIG_STRATEGY=COPY_ALWAYS -v $confDir:/var/lib/kolla/config_files:ro,z -v $dataDir/fernet-keys:/etc/keystone/fernet-keys:z -v $dataDir/credential-keys:/etc/keystone/credential-keys:z -v $logDir:/var/log/kolla/keystone:z $image
       |ExecStop=/usr/bin/podman stop -t 30 keystone
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |systemctl enable orphera-keystone
       |systemctl restart orphera-keystone
       |sleep 5
       |if ! systemctl is-active --quiet orphera-keystone; then
       |  echo "orphera-keystone failed to start - see 'journalctl -xeu orphera-keystone' and $logDir" >&2
       |  exit 1
       |fi
       |echo "orphera-keystone running"""".stripMargin

  // Prints a scoped admin token on stdout. Used by the confirm stage and by
  // test_keystone.sh; root only, since it embeds the admin password.
  private val tokenHelperScript =
    s"""cat > /usr/local/sbin/keystone-admin-token <<'HELPER'
       |#!/bin/sh
       |curl -s --cacert $caCert -D - -o /dev/null -H 'Content-Type: application/json' -d '{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"admin","domain":{"name":"Default"},"password":"$adminPassword"}}},"scope":{"project":{"name":"admin","domain":{"name":"Default"}}}}}' https://$fqdn:5000/v3/auth/tokens | tr -d '\\r' | awk -F': ' 'tolower($$1)=="x-subject-token"{print $$2}'
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
        .task("write keystone.conf")(
          Task.RunCommand(List("sh", "-c", writeConfigScript))
        )
        .task("write the Apache vhost and Kolla config.json")(
          Task.RunCommand(List("sh", "-c", kollaConfigScript), timeoutSeconds = 120)
        )
        .task("db_sync, fernet and credential keys, bootstrap")(
          Task.RunCommand(List("sh", "-c", initScript), timeoutSeconds = 600)
        )
        .task("run keystone as a systemd-managed container")(
          Task.RunCommand(List("sh", "-c", unitScript), timeoutSeconds = 120)
        )
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
