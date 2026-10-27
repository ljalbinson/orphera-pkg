// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Manila (shared file systems: NFS shares on demand), Kolla images under podman, with
// CephFS exported over NFS by NFS-Ganesha as the share backend. Modelled on barbican_single_node.scala and
// cinder_single_node.scala; read their headers for the conventions.
//
// Where it runs: tst22 (10.10.5.34), a new VM provisioned from config/tst22.yaml. To move it, change `node`,
// `fqdn` and `nodeIp` below (and the teardown's `node`); nothing else depends on the location.
//
// What it builds:
//   - manila-api        the REST API, port 8786, plain HTTP like the other APIs here. Behind Apache + mod_wsgi
//                       when the image has manila-wsgi, otherwise the image's own manila-api server
//   - manila-scheduler  picks the backend for each new share
//   - manila-share      the backend driver: asks Ceph (through the mgr) to make a CephFS subvolume and an NFS
//                       export for every share
// The data service (share migration and backups) is left out.
//
// What it does on the Ceph cluster (stage manila-ceph, on tst0, through `cephadm shell`; every step is
// repeatable):
//   - the CephFS filesystem `cephfs` (`ceph fs volume create`, which also starts the MDS daemons)
//   - the NFS cluster `manila`, one NFS-Ganesha daemon placed on the Ceph host with the address `nfsIp`
//     (`ceph nfs cluster create`); shares are exported from there, port 2049
//   - the cephx user `client.manila` with caps mon "allow r" and mgr "allow rw", imported with a key derived
//     from the secret `manila/ceph-key` (a valid cephx key is a fixed header plus 16 bytes; the 16 bytes are
//     taken from a SHA-256 of the secret). The cephx cipher setting is the same compromise as in
//     cinder_single_node.scala (the Kolla images carry an older Ceph client).
// Clients of a share need only NFS (v4.1) and a route to nfsIp; they need no Ceph client and no key. Access
// is by client IP (access rules of type `ip`).
//
// What it adds to Keystone (done by the helper's `register`, repeatable): user `manila` in project `service`
// with the admin role; the service `manilav2` of type `sharev2` with the endpoint http://<fqdn>:8786/v2. The
// share type `default_share_type` (driver_handles_share_servers False) is made by the helper's `share-type`.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - Ceph healthy with OSDs and the mgr (cephadm_*.scala); tst0 has cephadm
//   - the agent on tst22; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Manila images (manila-api, manila-scheduler, manila-share) are in tst21's build list, but need
//     `orphera-kolla-build sources refresh` and a build of those three the first time
//
// Stages:
//   - manila-database (tst3, a Galera node): the `manila` database + user.
//   - manila-rabbitmq (tst10): vhost and user, idempotent.
//   - manila-ceph (tst0): CephFS, the NFS cluster and client.manila, as above.
//   - install-manila (tst22): packages, cache mirror, image pulls, a check that the share image has the Ceph
//     Python bindings, Keystone's CA, the helper and the registration, manila.conf, Ceph client files, Kolla
//     config, `manila-manage db sync`, then the units.
//   - confirm-manila-healthy (tst22): the API answers, the share type exists, and a share is made, mounted
//     over NFS, written and read back, and deleted.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/manila_single_node.scala` changes nothing
// and reports whether the units on the node match what this playbook writes.
//
// Facts about Manila this was written from, to check if the first run fails: the CephFS driver is
// manila.share.drivers.cephfs.driver.CephFSDriver with cephfs_protocol_helper_type = NFS and
// cephfs_nfs_cluster_id (it drives `ceph nfs export` through the mgr, so the cephx user needs mgr caps);
// cephfs_ganesha_server_ip and cephfs_ganesha_export_ips are given too and ignored if unknown; the API
// microversion used by the helper is 2.45 (access rules list); the share image has python3 `rados` and
// `cephfs` (the run stops with a message if not); the Kolla images are the 2025.1 ubuntu-noble ones;
// manila.conf is plain text, a typed version needs a catalogue of the image's options
// (manifests/dump_oslo_options.sh manila-api).
//
// Test-only credentials come from the secret store (manila/db, manila/service, manila/rabbit,
// manila/ceph-key), made on first use. Not compiled where this was written; the first run on scala0 is the
// first compile.
object manila_single_node extends OrpheraClusterPlaybook:

  private val node = "tst22"
  private val fqdn = "tst22.ljalbinson.com"
  private val nodeIp = "10.10.5.34"
  private val apiPort = 8786

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "manila"
  private val rabbitPassword = Secrets.secret("manila/rabbit")
  private val rabbitVhost = "manila"

  // The Ceph host that runs NFS-Ganesha; its address is what clients mount. Keep in step with inventory.yaml.
  private val cephNode = "tst0"
  private val nfsNode = "tst0"
  private val nfsIp = "10.10.5.12"
  private val cephFs = "cephfs"
  private val nfsCluster = "manila"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "manila"
  private val dbUser = "manila"
  private val dbPassword = Secrets.secret("manila/db")
  private val manilaPassword = Secrets.secret("manila/service")

  // A valid cephx key: base64 of a 12 byte header (type 1 = AES as a little-endian u16, a creation time as
  // two u32, the key length 16 as a u16) and 16 key bytes. The 16 bytes come from a SHA-256 of a stored
  // secret, so the key is the same on every run. Test infrastructure only.
  private val cephKey: String =
    val keyBytes = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        Secrets
          .secret("manila/ceph-key")
          .reveal
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
      .take(16)
    val header = java.nio.ByteBuffer
      .allocate(12)
      .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      .putShort(1.toShort)
      .putInt(1700000000)
      .putInt(0)
      .putShort(16.toShort)
      .array()
    java.util.Base64.getEncoder.encodeToString(header ++ keyBytes)

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/manila"
  private val logDir = "/var/log/kolla/manila"
  private val stateDir = "/var/lib/orphera/manila"
  private val caDir = "/etc/kolla/manila-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The three containers as data: the unit file text and the start task come from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. Defined after confRoot, logDir and
  // stateDir, which they read; a val used before its definition is null in an object.
  private def manilaContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/manila", Access.ReadWrite),
        Mount(stateDir, "/var/lib/manila", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The API image may set a non-root USER, which cannot open Apache's log files; start it as root.
      user = if service == "manila-api" then Some("root") else None
    )
  private val manilaServices: List[Container] =
    List("manila-scheduler", "manila-share", "manila-api").map(manilaContainer)

  private val apiImage =
    Image("manila-api", Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val shareImage =
    Image("manila-share", Release.R2025_1, Base.UbuntuNoble, registry).ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "nfs-common"
    )

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

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

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

  // ---- tst0 (Ceph) -----------------------------------------------------

  // Every command can be repeated: the filesystem and the NFS cluster are made only if they are not listed,
  // and `auth import` replaces the entity with the same key and caps. `cephadm shell --mount FILE` makes
  // FILE visible at /mnt/<name> inside the shell container.
  private val cephScript =
    s"""set -e
       |# Same cephx cipher compromise as cinder_single_node.scala: the Kolla images carry an older Ceph client.
       |cephadm shell -- ceph mon set auth_allowed_ciphers aes,aes256k
       |if ! cephadm shell -- ceph fs ls 2>/dev/null | grep -q '^name: $cephFs,'; then
       |  cephadm shell -- ceph fs volume create $cephFs
       |fi
       |for i in $$(seq 1 60); do
       |  if cephadm shell -- ceph mds stat 2>/dev/null | grep -q 'up:active'; then break; fi
       |  sleep 5
       |done
       |if ! cephadm shell -- ceph mds stat 2>/dev/null | grep -q 'up:active'; then
       |  echo "no active MDS for $cephFs after 5 minutes - check 'ceph fs status'" >&2
       |  exit 1
       |fi
       |HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
       |for h in json.load(sys.stdin):
       |    if h.get('addr') == '$nfsIp' or h.get('hostname', '').split('.')[0] == '$nfsNode':
       |        print(h['hostname'])
       |        break")
       |if [ -z "$$HOST" ]; then
       |  echo "no Ceph orchestrator host with address $nfsIp or name $nfsNode (ceph orch host ls)" >&2
       |  exit 1
       |fi
       |if ! cephadm shell -- ceph nfs cluster ls 2>/dev/null | grep -qx '$nfsCluster'; then
       |  cephadm shell -- ceph nfs cluster create $nfsCluster "$$HOST"
       |fi
       |nfs_up() { python3 -c "import socket; socket.create_connection(('$nfsIp', 2049), 3).close()" 2>/dev/null; }
       |for i in $$(seq 1 60); do
       |  if nfs_up; then break; fi
       |  sleep 5
       |done
       |if ! nfs_up; then
       |  echo "NFS-Ganesha does not answer on $nfsIp:2049 after 5 minutes - check 'ceph nfs cluster info $nfsCluster'" >&2
       |  exit 1
       |fi
       |cat > /tmp/manila.keyring <<'KEYRING'
       |[client.manila]
       |    key = $cephKey
       |    caps mon = "allow r"
       |    caps mgr = "allow rw"
       |KEYRING
       |cephadm shell --mount /tmp/manila.keyring -- ceph auth import -i /mnt/manila.keyring
       |rm -f /tmp/manila.keyring
       |cephadm shell -- ceph auth get client.manila >/dev/null
       |echo "filesystem $cephFs, NFS cluster $nfsCluster ($$HOST, $nfsIp:2049) and client.manila ready"""".stripMargin

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
        "the Image(...) values in manila_single_node.scala"
      )}
       |for img in ${manilaServices.map(_.image.ref).mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  // The share service imports rados and cephfs; without them it starts and then fails with an import error.
  private val shareImageCheckScript =
    s"""if ! podman run --rm --entrypoint python3 $shareImage -c 'import rados, cephfs' 2>/tmp/manila-import.err; then
       |  cat /tmp/manila-import.err >&2
       |  echo "$shareImage has no python3 rados/cephfs bindings - the CephFS driver cannot run" >&2
       |  exit 1
       |fi
       |echo "the share image has the Ceph Python bindings"""".stripMargin

  // Same method as placement_single_node.scala: the last certificate Keystone presents is its CA; trust on
  // first use.
  private val fetchCaScript =
    s"""set -e
       |mkdir -p $caDir
       |rm -f /tmp/ks-chain-*.pem
       |openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk '/-----BEGIN CERTIFICATE-----/{n++} n>0{print > ("/tmp/ks-chain-" n ".pem")}'
       |COUNT=$$(ls /tmp/ks-chain-*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - re-run keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp /tmp/ks-chain-$$COUNT.pem $caDir/ca.crt
       |openssl verify -CAfile $caDir/ca.crt /tmp/ks-chain-1.pem
       |SUBJ=$$(openssl x509 -in $caDir/ca.crt -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caDir/ca.crt -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |rm -f /tmp/ks-chain-*.pem
       |if command -v update-ca-certificates >/dev/null 2>&1; then
       |  cp $caDir/ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |  update-ca-certificates
       |else
       |  cp $caDir/ca.crt /etc/pki/ca-trust/source/anchors/orphera-test-ca.crt
       |  update-ca-trust
       |fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  private val dirsScript =
    s"""mkdir -p $srcDir $logDir $stateDir $stateDir/tmp
       |echo "directories ready"""".stripMargin

  // manila.conf, plain text (see the header). The same file goes to all three containers.
  private val manilaConf: String =
    s"""# Managed by Orphera (manila_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/manila
       |use_stderr = true
       |auth_strategy = keystone
       |my_ip = $nodeIp
       |osapi_share_listen = 0.0.0.0
       |osapi_share_listen_port = $apiPort
       |state_path = /var/lib/manila
       |api_paste_config = /etc/manila/api-paste.ini
       |rootwrap_config = /etc/manila/rootwrap.conf
       |enabled_share_protocols = NFS
       |enabled_share_backends = cephfsnfs
       |default_share_type = default_share_type
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
       |username = manila
       |password = ${manilaPassword.reveal}
       |cafile = /etc/manila/ssl/ca.crt
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/manila/tmp
       |
       |[cephfsnfs]
       |driver_handles_share_servers = False
       |share_backend_name = CEPHFSNFS
       |share_driver = manila.share.drivers.cephfs.driver.CephFSDriver
       |cephfs_protocol_helper_type = NFS
       |cephfs_conf_path = /etc/ceph/ceph.conf
       |cephfs_auth_id = manila
       |cephfs_cluster_name = ceph
       |cephfs_filesystem_name = $cephFs
       |cephfs_nfs_cluster_id = $nfsCluster
       |cephfs_ganesha_server_ip = $nfsIp
       |cephfs_ganesha_export_ips = $nfsIp
       |""".stripMargin

  // ceph.conf needs only the monitors for a client. The only task whose script contains template
  // expressions, kept separate on purpose so the runner's template pass does not touch the large scripts
  // elsewhere.
  private val cephClientScript =
    s"""mkdir -p $srcDir
       |cat > $srcDir/ceph.conf <<'CEPHCONF'
       |[global]
       |mon_host = {{nodes.tst0.cluster_ip}},{{nodes.tst1.cluster_ip}},{{nodes.tst2.cluster_ip}}
       |CEPHCONF
       |cat > $srcDir/ceph.client.manila.keyring <<'KEYRING'
       |[client.manila]
       |    key = $cephKey
       |KEYRING
       |chmod 640 $srcDir/ceph.client.manila.keyring
       |echo "ceph client config written"""".stripMargin

  // One config directory per container (Kolla mounts it at /var/lib/kolla/config_files). The API runs behind
  // Apache + mod_wsgi when the image has manila-wsgi (the WSGI script path and the Apache flavour are read
  // from the image); an older image without it runs its own manila-api server.
  private val kollaConfigScript =
    s"""set -e
       |for svc in api scheduler share; do
       |  mkdir -p $confRoot/manila-$$svc
       |  cp $srcDir/manila.conf $confRoot/manila-$$svc/manila.conf
       |  cp $caDir/ca.crt $confRoot/manila-$$svc/ca.crt
       |done
       |cp $srcDir/ceph.conf $srcDir/ceph.client.manila.keyring $confRoot/manila-share/
       |COMMON='{"source": "/var/lib/kolla/config_files/manila.conf", "dest": "/etc/manila/manila.conf", "owner": "manila", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/manila/ssl/ca.crt", "owner": "manila", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/manila", "owner": "manila:kolla", "recurse": true}, {"path": "/var/lib/manila", "owner": "manila:manila", "recurse": true}]'
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v manila-wsgi' || true)
       |if [ -n "$$WSGI" ]; then
       |  if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |    APACHE_CONF=/etc/apache2/conf-enabled/manila-api.conf
       |    APACHE_CMD="apache2 -DFOREGROUND"
       |    # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |    if podman run --rm --entrypoint sh $apiImage -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |  else
       |    APACHE_CONF=/etc/httpd/conf.d/manila-api.conf
       |    APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |  fi
       |  echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |  cat > $confRoot/manila-api/manila-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/manila-apache-error.log"
       |    CustomLog "$logDir/manila-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess manila-api group=manila processes=2 threads=1 user=manila
       |    WSGIProcessGroup manila-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |  API_CMD="$$APACHE_CMD"
       |  API_EXTRA=", {\\"source\\": \\"/var/lib/kolla/config_files/manila-api.conf\\", \\"dest\\": \\"$$APACHE_CONF\\", \\"owner\\": \\"manila\\", \\"perm\\": \\"0600\\"}"
       |else
       |  echo "manila-wsgi not found in $apiImage: running the image's manila-api server"
       |  API_CMD="manila-api --config-file /etc/manila/manila.conf"
       |  API_EXTRA=""
       |fi
       |cat > $confRoot/manila-api/config.json <<JSON
       |{"command": "$$API_CMD", "config_files": [$$COMMON$$API_EXTRA], $$PERMS}
       |JSON
       |cat > $confRoot/manila-scheduler/config.json <<JSON
       |{"command": "manila-scheduler --config-file /etc/manila/manila.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |cat > $confRoot/manila-share/config.json <<JSON
       |{"command": "manila-share --config-file /etc/manila/manila.conf", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/ceph.conf", "dest": "/etc/ceph/ceph.conf", "owner": "manila", "perm": "0644"}, {"source": "/var/lib/kolla/config_files/ceph.client.manila.keyring", "dest": "/etc/ceph/ceph.client.manila.keyring", "owner": "manila", "perm": "0600"}], $$PERMS}
       |JSON
       |python3 -c "import json; [json.load(open('$confRoot/manila-' + s + '/config.json')) for s in ('api', 'scheduler', 'share')]; print('config.json files are valid JSON')"
       |echo "kolla config written under $confRoot"""".stripMargin

  // manila-manage runs in a one-off container with manila.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint manila-manage -v $srcDir/manila.conf:/etc/manila/manila.conf:ro,z -v $logDir:/var/log/kolla/manila:z $apiImage --config-file /etc/manila/manila.conf db sync
       |echo "manila database synced"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | catalog | services | share-type | share-cycle
  // share-cycle makes a 1 GB NFS share on the CephFS backend, allows this node's address, reads the export
  // location, mounts it over NFSv4.1, writes a file and reads it back, unmounts, removes the access rule
  // and the share, and checks the share is gone.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, os, ssl, subprocess, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
MANILA = '@@MANILA@@'
NODE_IP = '@@NODEIP@@'
ADMIN_PW = '@@ADMINPW@@'
MANILA_PW = '@@MANILAPW@@'
CTX = ssl.create_default_context(cafile='@@CA@@')
# Manila's own header is the one older releases read; the generic one is for newer releases. Without a
# version the API serves 2.0, which has no /services and a different access call (404).
VERSION = {'X-OpenStack-Manila-API-Version': '2.45', 'X-OpenStack-API-Version': 'shared-file-system 2.45'}
TYPE_NAME = 'default_share_type'
MOUNT_DIR = '/mnt/orphera-manila-test'


def call(method, url, body=None, token=None, extra=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    for k, v in (extra or {}).items():
        req.add_header(k, v)
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


def mcall(method, path, token, body=None):
    return call(method, MANILA + path, body, token, VERSION)


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': 'Default'}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token'], parsed


def admin_token():
    return get_token('admin', ADMIN_PW, 'admin')[0]


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def ensure(token, what, path, key, body, create_key):
    # the first object that matches `path`, or the one made from `body`
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=manila', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'manila', 'domain_id': 'default', 'password': MANILA_PW}}, token)
        if s != 201:
            die('creating user manila failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': MANILA_PW}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to manila failed: HTTP %s %s' % (s, p))
    service = ensure(token, 'services', '/services?type=sharev2', 'services', {'service': {'name': 'manilav2', 'type': 'sharev2'}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': MANILA, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != MANILA:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': MANILA}}, token)
    print('registered manila in keystone (user manila, service type sharev2, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=sharev2', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=manila', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed manila from keystone (service sharev2, its endpoints, user manila)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def share_services(token):
    # /services is the current path; some releases serve only the older /os-services
    for path in ('/services', '/os-services'):
        s, p, _ = mcall('GET', path, token)
        if s == 200:
            return p.get('services', [])
        if s != 404:
            die('service list (%s) failed: HTTP %s %s' % (path, s, p))
    die('service list failed: HTTP 404 on both /services and /os-services')


def services():
    token = admin_token()
    svcs = share_services(token)
    print(' '.join('%s@%s=%s' % (x.get('binary'), x.get('host'), x.get('state')) for x in svcs))
    print('services: %d' % len(svcs))


def wait_services(token):
    end = time.time() + 180
    last = []
    while time.time() < end:
        last = share_services(token)
        up = set(x.get('binary') for x in last if x.get('state') == 'up')
        if 'manila-scheduler' in up and 'manila-share' in up:
            return
        time.sleep(5)
    die('manila-scheduler and manila-share are not both up after 180 s: %s' % ['%s=%s' % (x.get('binary'), x.get('state')) for x in last])


def ensure_type(token):
    s, p, _ = mcall('GET', '/types', token)
    if s != 200:
        die('share type list failed: HTTP %s %s' % (s, p))
    for t in p.get('share_types', []):
        if t.get('name') == TYPE_NAME:
            return t['id']
    body = {'share_type': {'name': TYPE_NAME, 'share_type_access:is_public': True, 'extra_specs': {'driver_handles_share_servers': 'False'}}}
    s, p, _ = mcall('POST', '/types', token, body)
    if s not in (200, 201):
        die('creating share type failed: HTTP %s %s' % (s, p))
    return p['share_type']['id']


def share_type():
    token = admin_token()
    tid = ensure_type(token)
    print('share type %s ready (%s)' % (TYPE_NAME, tid))


def wait_gone(token, path, what):
    end = time.time() + 180
    while time.time() < end:
        s, _, _ = mcall('GET', path, token)
        if s == 404:
            return
        time.sleep(3)
    die('%s is still there 180 s after the delete' % what)


def share_cycle():
    token = admin_token()
    wait_services(token)
    tid = ensure_type(token)
    name = 'orphera-test-%d' % int(time.time())
    s, p, _ = mcall('POST', '/shares', token, {'share': {'share_proto': 'NFS', 'size': 1, 'name': name, 'share_type': tid}})
    if s not in (200, 202):
        die('share create failed: HTTP %s %s' % (s, p))
    sid = p['share']['id']
    access_id = None
    mounted = False
    try:
        end = time.time() + 240
        status = None
        while time.time() < end:
            s, p, _ = mcall('GET', '/shares/' + sid, token)
            if s == 200:
                status = p['share'].get('status')
                if status == 'available':
                    break
                if status and status.startswith('error'):
                    die('share %s went to %s; see manila-share on this node (journalctl -u orphera-manila-share)' % (sid, status))
            time.sleep(3)
        if status != 'available':
            die('share %s did not become available within 240 s (last status %s)' % (sid, status))
        s, p, _ = mcall('POST', '/shares/%s/action' % sid, token, {'allow_access': {'access_type': 'ip', 'access_to': NODE_IP, 'access_level': 'rw'}})
        if s not in (200, 202):
            die('allow_access failed: HTTP %s %s' % (s, p))
        access_id = p['access']['id']
        end = time.time() + 120
        state = None
        while time.time() < end:
            s, p, _ = mcall('GET', '/share-access-rules?share_id=' + sid, token)
            if s == 200:
                for rule in p.get('access_list', []):
                    if rule.get('id') == access_id:
                        state = rule.get('state')
                if state == 'active':
                    break
                if state == 'error':
                    die('access rule %s went to error' % access_id)
            time.sleep(3)
        if state != 'active':
            die('access rule %s is not active after 120 s (state %s)' % (access_id, state))
        s, p, _ = mcall('GET', '/shares/%s/export_locations' % sid, token)
        locs = p.get('export_locations', []) if s == 200 else []
        if not locs:
            die('share %s has no export location: HTTP %s %s' % (sid, s, p))
        path = locs[0]['path']
        os.makedirs(MOUNT_DIR, exist_ok=True)
        r = subprocess.run(['mount', '-t', 'nfs', '-o', 'nfsvers=4.1,proto=tcp,timeo=50,retrans=2', path, MOUNT_DIR], capture_output=True, text=True, timeout=90)
        if r.returncode != 0:
            die('mount of %s failed: %s %s' % (path, r.stdout.strip(), r.stderr.strip()))
        mounted = True
        payload = 'orphera-manila-%d' % int(time.time())
        f = os.path.join(MOUNT_DIR, 'orphera-test.txt')
        with open(f, 'w') as fh:
            fh.write(payload)
        subprocess.run(['sync'], timeout=60)
        with open(f) as fh:
            got = fh.read()
        if got != payload:
            die('file read back wrongly from %s: %r' % (path, got))
        print('share cycle ok: share %s exported as %s, mounted, file written and read back' % (sid, path))
    finally:
        if mounted:
            subprocess.run(['umount', MOUNT_DIR], timeout=60)
        if access_id:
            mcall('POST', '/shares/%s/action' % sid, token, {'deny_access': {'access_id': access_id}})
            time.sleep(3)
        mcall('DELETE', '/shares/' + sid, token)
    wait_gone(token, '/shares/' + sid, 'share ' + sid)
    print('share %s deleted and gone' % sid)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'services':
        services()
    elif cmd == 'share-type':
        share_type()
    elif cmd == 'share-cycle':
        share_cycle()
    else:
        die('usage: orphera-manila-api register | unregister | catalog | services | share-type | share-cycle')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-manila-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@MANILA@@", s"http://$fqdn:$apiPort/v2")
        .replace("@@NODEIP@@", nodeIp)
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@MANILAPW@@", manilaPassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-manila-api\necho wrote /usr/local/sbin/orphera-manila-api\n"

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if
  // a unit differs from what this playbook would write. The check task comes from the same Container
  // value as the write task (see Resource.scala).
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  // Adds one "write the unit" task per container, then one "start" task per container (the scheduler and
  // the share service first, the API last).
  private def withServices(b: StageBuilder): StageBuilder =
    val written = manilaServices.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    manilaServices.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("manila-single-node-check")(
        manilaServices
          .foldLeft(stage("check-manila", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          )
          .build
      )
    else applyPlaybook

  // The whole install stage, ending with every container started.
  private def installManila: StageBuilder =
    withServices(
      stage("install-manila", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install podman, the NFS client and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("use the Kolla cache as a mirror for quay.io")(
          Task.RunCommand(List("sh", "-c", useCacheScript))
        )
        .task("pull the Manila images")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 1800)
        )
        .task("check that the share image has the Ceph Python bindings")(
          Task.RunCommand(
            List("sh", "-c", shareImageCheckScript),
            timeoutSeconds = 120
          )
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the Manila directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the Manila API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Manila in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-manila-api register"),
            timeoutSeconds = 120
          )
        )
        .task("write manila.conf")(
          Task.WriteFile(
            content = manilaConf,
            dest = s"$srcDir/manila.conf",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("write the Ceph client files")(
          Task.RunCommand(List("sh", "-c", cephClientScript))
        )
        .task("write the Kolla config and the Apache vhost")(
          Task.RunCommand(
            List("sh", "-c", kollaConfigScript),
            timeoutSeconds = 240
          )
        )
        .task("manila-manage db sync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        )
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("manila-single-node")(
      stage("manila-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("manila-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      stage("manila-ceph", cephNode)
        .task(
          s"create CephFS $cephFs, the NFS cluster $nfsCluster and client.manila"
        )(
          Task.RunCommand(List("sh", "-c", cephScript), timeoutSeconds = 900)
        )
        .build,

      installManila.build,

      stage("confirm-manila-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("make sure the default share type exists")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-manila-api share-type"
            ),
            timeoutSeconds = 120
          )
        )
        .task(
          "make a share, mount it over NFS, write and read a file, delete it"
        )(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-manila-api share-cycle"
            ),
            timeoutSeconds = 900
          )
        )
        .task("manila confirmed")(
          Task.Debug(
            s"Manila up at http://$fqdn:$apiPort/v2 ($nodeIp): manila-api, manila-scheduler and manila-share on CephFS $cephFs, NFS cluster $nfsCluster at $nfsIp:2049, RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_manila.sh"
          )
        )
        .build
    )
