// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack Designate (DNS as a service) with a BIND9 name server on the same node, Kolla images
// under podman. Modelled on nova_single_node.scala and placement_single_node.scala; read their headers
// for the conventions.
//
// Where it runs: tst16, which had no role. Designate is five small Python services plus BIND9, so one
// 4 GB VM holds all of it. To move it, change `node`, `fqdn` and `nodeIp` below (and the teardown's
// `node`); nothing else depends on the location.
//
// What it builds:
//   - designate-api      the REST API (Apache + mod_wsgi when the image has designate-api-wsgi, else the
//                        standalone designate-api), port 9001, plain HTTP like the other APIs here
//   - designate-central  the logic and the database
//   - designate-worker   pushes zones to BIND9 (rndc addzone, notify) and checks they are served
//   - designate-producer periodic tasks
//   - designate-mdns     the "hidden master" that BIND9 transfers zones from (AXFR), port 5354
//   - designate-backend-bind9  the name server that answers queries, port 53 on the node's address
//     (not on 127.0.0.53, which systemd-resolved holds), and rndc on 127.0.0.1:953
// One pool, `default`, with one BIND9 target, is loaded with `designate-manage pool update`.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy (mariadb_galera_cluster.scala, mariadb_haproxy_keepalived.scala)
//   - Keystone up on tst6 (keystone_single_node.scala), serving the CA chain
//   - RabbitMQ running on tst10 (cinder_single_node.scala)
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// Stages:
//   - designate-database (tst3, a Galera node): the `designate` database + user.
//   - designate-rabbitmq (tst10): vhost and user, idempotent.
//   - install-designate (the node): packages, cache mirror, image pulls, Keystone's CA from its TLS
//     handshake, designate.conf, pools.yaml, named.conf and the rndc key, Kolla config for each
//     container, `designate-manage database sync`, the units, registration in Keystone (user `designate`
//     in project `service`, service type `dns`, three endpoints), then the pool is loaded and the worker
//     and mdns are restarted so they read it.
//   - confirm-designate-healthy (the node): the API answers, BIND9 answers on port 53, and the zone
//     list is readable with an admin token.
//
// Not done here, on purpose (later): Neutron's DNS integration and the notification sink, which
// need changes to neutron_single_node.scala and nova_single_node.scala.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/designate_single_node.scala` changes
// nothing and reports whether the units on the node match what this playbook writes.
//
// Facts about Designate this was written from, to check if the first run fails: `designate-manage
// pool update` talks to designate-central over RabbitMQ, so it runs after the services start; the worker
// reads the pool when it starts, so it is restarted afterwards; the keys of pools.yaml are the
// documented ones (name, ns_records, nameservers, targets with masters and options); the Kolla
// images are the 2025.1 ubuntu-noble ones, as for the other services. designate.conf is written as
// plain text: a typed version needs a catalogue of the image's options
// (manifests/dump_oslo_options.sh designate-central).
//
// Test-only credentials come from the secret store (designate/db, designate/service, designate/rabbit),
// made on first use. Not compiled where this was written; the first run on scala0 is the first compile.
object designate_single_node extends OrpheraClusterPlaybook:

  private val node = "tst16"
  private val fqdn = "tst16.ljalbinson.com"
  private val nodeIp = "10.10.5.28"
  private val apiPort = 9001
  private val mdnsPort = 5354
  private val dnsPort = 53
  private val rndcPort = 953

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val rabbitUser = "designate"
  private val rabbitPassword = Secrets.secret("designate/rabbit")
  private val rabbitVhost = "designate"

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "designate"
  private val dbUser = "designate"
  private val dbPassword = Secrets.secret("designate/db")
  private val designatePassword = Secrets.secret("designate/service")

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/designate"
  private val logDir = "/var/log/kolla/designate"
  private val stateDir = "/var/lib/orphera/designate"
  private val caDir = "/etc/kolla/designate-ca"
  private val ksUrl = s"https://$ksFqdn:5000"

  // The name of the nameserver in the pool's NS and SOA records: the node itself, with the final dot
  // DNS names need.
  private val nsHostname = s"$fqdn."

  // The six containers as data: the unit file text and the start task come from Container
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. The five Designate services
  // share their mounts apart from their own config directory. Defined after confRoot, logDir and
  // stateDir, which they read; a val used before its definition is null in an object.
  private def designateContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = Image(service, Release.R2025_1, Base.UbuntuNoble, registry),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(logDir, "/var/log/kolla/designate", Access.ReadWrite),
        Mount(stateDir, "/var/lib/designate", Access.ReadWrite)
      ),
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
    )
  private val designateServices: List[Container] =
    List(
      "designate-central",
      "designate-mdns",
      "designate-api",
      "designate-producer",
      "designate-worker"
    ).map(designateContainer)

  // BIND9 keeps the zones Designate adds with rndc (allow-new-zones) in /var/lib/named.
  private val bind9 = Container(
    name = ContainerName("designate-backend-bind9"),
    image = Image(
      "designate-backend-bind9",
      Release.R2025_1,
      Base.UbuntuNoble,
      registry
    ),
    mounts = List(
      Mount(
        s"$confRoot/designate-backend-bind9",
        "/var/lib/kolla/config_files"
      ),
      Mount(s"$stateDir/bind", "/var/lib/named", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )

  private val allContainers: List[Container] = bind9 :: designateServices
  private val apiImage = Image(
    "designate-api",
    Release.R2025_1,
    Base.UbuntuNoble,
    registry
  ).ref
  private val centralImage = Image(
    "designate-central",
    Release.R2025_1,
    Base.UbuntuNoble,
    registry
  ).ref
  private val workerImage = Image(
    "designate-worker",
    Release.R2025_1,
    Base.UbuntuNoble,
    registry
  ).ref
  private val bindImage = bind9.image.ref

  private val hostPackages =
    List(
      "podman",
      "openssl",
      "curl",
      "ca-certificates",
      "python3",
      "bind9-dnsutils"
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

  // ---- the node --------------------------------------------------------

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

  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in designate_single_node.scala"
      )}
       |for img in ${allContainers.map(_.image.ref).mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

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
    s"""mkdir -p $srcDir $logDir $stateDir $stateDir/bind
       |echo "directories ready"""".stripMargin

  // designate.conf, plain text (see the header). The same file goes to all five services.
  private val designateConf: String =
    s"""# Managed by Orphera (designate_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://$rabbitUser:${rabbitPassword.reveal}@$rabbitIp:5672/$rabbitVhost
       |log_dir = /var/log/kolla/designate
       |use_stderr = true
       |state_path = /var/lib/designate
       |
       |[service:api]
       |listen = 0.0.0.0:$apiPort
       |auth_strategy = keystone
       |
       |[service:mdns]
       |listen = 0.0.0.0:$mdnsPort
       |
       |[service:worker]
       |enabled = true
       |
       |[storage:sqlalchemy]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = designate
       |password = ${designatePassword.reveal}
       |cafile = /etc/designate/ssl/ca.crt
       |""".stripMargin

  // The default pool: one BIND9 target on this node. mdns is the master BIND9 transfers from; the worker
  // adds and removes zones through rndc on 127.0.0.1.
  private val poolsYaml: String =
    s"""# Managed by Orphera (designate_single_node.scala)
       |- name: default
       |  description: Default pool, one BIND9 server on $node
       |  attributes: {}
       |  ns_records:
       |    - hostname: $nsHostname
       |      priority: 1
       |  nameservers:
       |    - host: $nodeIp
       |      port: $dnsPort
       |  targets:
       |    - type: bind9
       |      description: BIND9 on $node
       |      masters:
       |        - host: $nodeIp
       |          port: $mdnsPort
       |      options:
       |        host: $nodeIp
       |        port: $dnsPort
       |        rndc_host: 127.0.0.1
       |        rndc_port: $rndcPort
       |        rndc_key_file: /etc/designate/rndc.key
       |""".stripMargin

  // BIND9 answers on the node's address only (systemd-resolved holds 127.0.0.53:53), takes no recursion,
  // learns zones from rndc, and fetches them from mdns. The rndc key is shared with the worker.
  private val namedConf: String =
    s"""// Managed by Orphera (designate_single_node.scala)
       |include "/etc/bind/rndc.key";
       |
       |controls {
       |    inet 127.0.0.1 port $rndcPort allow { 127.0.0.1; } keys { "designate"; };
       |};
       |
       |options {
       |    directory "/var/lib/named";
       |    pid-file "/var/lib/named/named.pid";
       |    listen-on port $dnsPort { $nodeIp; };
       |    listen-on-v6 { none; };
       |    allow-new-zones yes;
       |    allow-notify { $nodeIp; 127.0.0.1; };
       |    allow-query { any; };
       |    allow-transfer { none; };
       |    recursion no;
       |    dnssec-validation no;
       |    version "none";
       |    auth-nxdomain no;
       |};
       |""".stripMargin

  // The rndc key is made once and kept: a new key on a re-run would stop the worker's rndc calls until
  // both sides were restarted.
  private val rndcKeyScript =
    s"""set -e
       |if [ ! -s $srcDir/rndc.key ]; then
       |  umask 077
       |  SECRET=$$(openssl rand -base64 32)
       |  cat > $srcDir/rndc.key <<KEY
       |key "designate" {
       |    algorithm hmac-sha256;
       |    secret "$$SECRET";
       |};
       |KEY
       |  echo "rndc key made"
       |else
       |  echo "rndc key already there"
       |fi""".stripMargin

  // One config directory per container (Kolla mounts it at /var/lib/kolla/config_files). The API runs
  // behind Apache + mod_wsgi like Placement and Nova when the image has the WSGI script, else as the
  // standalone designate-api. The command, the user of BIND9 and the presence of rndc in the worker
  // image are read from the images.
  private val kollaConfigScript =
    s"""set -e
       |for svc in central mdns api producer worker; do
       |  mkdir -p $confRoot/designate-$$svc
       |  cp $srcDir/designate.conf $confRoot/designate-$$svc/designate.conf
       |  cp $caDir/ca.crt $confRoot/designate-$$svc/ca.crt
       |done
       |cp $srcDir/rndc.key $confRoot/designate-worker/rndc.key
       |mkdir -p $confRoot/designate-backend-bind9
       |cp $srcDir/named.conf $confRoot/designate-backend-bind9/named.conf
       |cp $srcDir/rndc.key $confRoot/designate-backend-bind9/rndc.key
       |if ! podman run --rm --entrypoint sh $workerImage -c 'command -v rndc' >/dev/null; then
       |  echo "rndc is not in $workerImage; the worker cannot manage BIND9 without it" >&2
       |  exit 1
       |fi
       |NAMED=$$(podman run --rm --entrypoint sh $bindImage -c 'command -v named' || true)
       |if [ -z "$$NAMED" ]; then echo "named not found in $bindImage" >&2; exit 1; fi
       |BINDUSER=$$(podman run --rm --entrypoint sh $bindImage -c 'id -u bind >/dev/null 2>&1 && echo bind || echo named')
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v designate-api-wsgi' || true)
       |COMMON='{"source": "/var/lib/kolla/config_files/designate.conf", "dest": "/etc/designate/designate.conf", "owner": "designate", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/designate/ssl/ca.crt", "owner": "designate", "perm": "0644"}'
       |PERMS='"permissions": [{"path": "/var/log/kolla/designate", "owner": "designate:kolla", "recurse": true}, {"path": "/var/lib/designate", "owner": "designate:designate", "recurse": true}]'
       |for svc in central mdns producer; do
       |  cat > $confRoot/designate-$$svc/config.json <<JSON
       |{"command": "designate-$$svc --config-file /etc/designate/designate.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |done
       |cat > $confRoot/designate-worker/config.json <<JSON
       |{"command": "designate-worker --config-file /etc/designate/designate.conf", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/rndc.key", "dest": "/etc/designate/rndc.key", "owner": "designate", "perm": "0600"}], $$PERMS}
       |JSON
       |if [ -n "$$WSGI" ]; then
       |  if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |    APACHE_CONF=/etc/apache2/conf-enabled/designate-api.conf
       |    APACHE_CMD="apache2 -DFOREGROUND"
       |  else
       |    APACHE_CONF=/etc/httpd/conf.d/designate-api.conf
       |    APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |  fi
       |  echo "api: wsgi script $$WSGI; apache config $$APACHE_CONF"
       |  cat > $confRoot/designate-api/designate-api.conf <<WSGICONF
       |Listen 0.0.0.0:$apiPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$apiPort>
       |    ErrorLog "$logDir/designate-apache-error.log"
       |    CustomLog "$logDir/designate-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess designate-api group=designate processes=2 threads=1 user=designate
       |    WSGIProcessGroup designate-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |  cat > $confRoot/designate-api/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$$COMMON, {"source": "/var/lib/kolla/config_files/designate-api.conf", "dest": "$$APACHE_CONF", "owner": "designate", "perm": "0600"}], $$PERMS}
       |JSON
       |else
       |  echo "api: no designate-api-wsgi in $apiImage; running the standalone designate-api"
       |  cat > $confRoot/designate-api/config.json <<JSON
       |{"command": "designate-api --config-file /etc/designate/designate.conf", "config_files": [$$COMMON], $$PERMS}
       |JSON
       |fi
       |cat > $confRoot/designate-backend-bind9/config.json <<JSON
       |{"command": "$$NAMED -f -u $$BINDUSER -c /etc/bind/named.conf", "config_files": [{"source": "/var/lib/kolla/config_files/named.conf", "dest": "/etc/bind/named.conf", "owner": "$$BINDUSER", "perm": "0640"}, {"source": "/var/lib/kolla/config_files/rndc.key", "dest": "/etc/bind/rndc.key", "owner": "$$BINDUSER", "perm": "0640"}], "permissions": [{"path": "/var/lib/named", "owner": "$$BINDUSER:$$BINDUSER", "recurse": true}]}
       |JSON
       |echo "kolla config written under $confRoot (bind user $$BINDUSER)"""".stripMargin

  // designate-manage runs in a one-off container with designate.conf bind-mounted (entrypoint overridden,
  // so kolla_set_configs does not run).
  private def manage(args: String): String =
    s"podman run --rm --network host --user root --entrypoint designate-manage -v $srcDir/designate.conf:/etc/designate/designate.conf:ro,z -v $srcDir/pools.yaml:/etc/designate/pools.yaml:ro,z -v $logDir:/var/log/kolla/designate:z $centralImage $args"

  private val dbSyncScript =
    s"""set -e
       |${manage("database sync")}
       |echo "designate database synced"""".stripMargin

  // `pool update` calls designate-central over RabbitMQ, so central must be up; it can take a few seconds
  // after the unit starts. The worker reads the pool when it starts, so it and mdns are restarted after.
  private val poolScript =
    s"""set -e
       |ok=""
       |for i in $$(seq 1 24); do
       |  if ${manage("pool update --file /etc/designate/pools.yaml")}; then ok=1; break; fi
       |  echo "pool update not accepted yet (attempt $$i of 24); central may still be starting"
       |  sleep 5
       |done
       |if [ -z "$$ok" ]; then echo "designate-manage pool update failed; see the output above and: journalctl -u orphera-designate-central" >&2; exit 1; fi
       |systemctl restart orphera-designate-worker orphera-designate-mdns
       |sleep 5
       |for u in worker mdns; do
       |  systemctl is-active --quiet orphera-designate-$$u || { echo "orphera-designate-$$u is not running after the pool update" >&2; exit 1; }
       |done
       |echo "pool loaded; worker and mdns restarted"""".stripMargin

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | catalog | zone-list | zone-create <name> <email> | zone-wait <id> |
  //   record-create <zone-id> <name> <ipv4> | record-wait <zone-id> <id> | zone-delete <id> | zone-cycle
  // zone-cycle is the whole path: a zone and an A record made through the API, BIND9 asked for them on
  // port 53, then the zone deleted.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, subprocess, sys, time, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
DNS = '@@DNS@@'
NS_IP = '@@NSIP@@'
ADMIN_PW = '@@ADMINPW@@'
DESIGNATE_PW = '@@DESIGNATEPW@@'
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
    return headers['X-Subject-Token'], parsed


def admin_token():
    return get_token('admin', ADMIN_PW, 'admin')[0]


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
    user = find(token, '/users?name=designate', 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': 'designate', 'domain_id': 'default', 'password': DESIGNATE_PW}}, token)
        if s != 201:
            die('creating user designate failed: HTTP %s %s' % (s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': DESIGNATE_PW}}, token)
    role = find(token, '/roles?name=admin', 'roles')
    if role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to designate failed: HTTP %s %s' % (s, p))
    service = find(token, '/services?type=dns', 'services')
    if service is None:
        s, p, _ = call('POST', KS + '/services', {'service': {'name': 'designate', 'type': 'dns'}}, token)
        if s != 201:
            die('creating service designate failed: HTTP %s %s' % (s, p))
        service = p['service']
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': DNS, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint failed: HTTP %s %s' % (iface, s, p))
        elif ep['url'] != DNS:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': DNS}}, token)
    print('registered designate in keystone (project service, user designate, service type dns, 3 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=dns', 'services')
    if service is not None:
        call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=designate', 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed designate from keystone (service dns, its endpoints, user designate)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def zone_list():
    token = admin_token()
    s, p, _ = call('GET', DNS + '/v2/zones', None, token)
    if s != 200:
        die('zone list failed: HTTP %s %s' % (s, p))
    for z in p.get('zones', []):
        print('zone %s name=%s status=%s' % (z.get('id'), z.get('name'), z.get('status')))
    print('zones: %d' % len(p.get('zones', [])))


def wait_status(token, url, want, what, seconds=180):
    # want None means: wait until the object is gone (404)
    end = time.time() + seconds
    last = None
    while time.time() < end:
        s, p, _ = call('GET', url, None, token)
        if want is None:
            if s == 404:
                return
        elif s == 200:
            last = p.get('status')
            if last == want:
                return
            if last == 'ERROR':
                die('%s went to ERROR: %s' % (what, p))
        time.sleep(2)
    die('%s did not reach %s within %d s (last status %s)' % (what, want or 'deleted', seconds, last))


def make_zone(token, name, email):
    s, p, _ = call('POST', DNS + '/v2/zones', {'name': name, 'email': email, 'ttl': 3600}, token)
    if s not in (201, 202):
        die('zone create failed: HTTP %s %s' % (s, p))
    return p['id']


def make_record(token, zone_id, name, ip):
    s, p, _ = call('POST', DNS + '/v2/zones/%s/recordsets' % zone_id, {'name': name, 'type': 'A', 'ttl': 60, 'records': [ip]}, token)
    if s not in (201, 202):
        die('record create failed: HTTP %s %s' % (s, p))
    return p['id']


def zone_create(name, email):
    print(make_zone(admin_token(), name, email))


def zone_wait(zone_id):
    wait_status(admin_token(), DNS + '/v2/zones/' + zone_id, 'ACTIVE', 'zone ' + zone_id)
    print('zone %s ACTIVE' % zone_id)


def record_create(zone_id, name, ip):
    print(make_record(admin_token(), zone_id, name, ip))


def record_wait(zone_id, record_id):
    wait_status(admin_token(), DNS + '/v2/zones/%s/recordsets/%s' % (zone_id, record_id), 'ACTIVE', 'record ' + record_id)
    print('record %s ACTIVE' % record_id)


def zone_delete(zone_id):
    token = admin_token()
    s, p, _ = call('DELETE', DNS + '/v2/zones/' + zone_id, None, token)
    if s not in (200, 202, 204):
        die('zone delete failed: HTTP %s %s' % (s, p))
    wait_status(token, DNS + '/v2/zones/' + zone_id, None, 'zone ' + zone_id)
    print('deleted ' + zone_id)


def dig(name, rtype):
    r = subprocess.run(['dig', '+short', '+time=3', '+tries=2', '@' + NS_IP, name, rtype], capture_output=True, text=True)
    return r.stdout.strip()


def zone_cycle():
    token = admin_token()
    zname = 'orphera-test-%d.example.org.' % int(time.time())
    zone_id = make_zone(token, zname, 'hostmaster@example.org')
    try:
        wait_status(token, DNS + '/v2/zones/' + zone_id, 'ACTIVE', 'zone ' + zone_id)
        rname = 'www.' + zname
        record_id = make_record(token, zone_id, rname, '192.0.2.10')
        wait_status(token, DNS + '/v2/zones/%s/recordsets/%s' % (zone_id, record_id), 'ACTIVE', 'record ' + record_id)
        end = time.time() + 60
        answer = ''
        while time.time() < end:
            answer = dig(rname, 'A')
            if answer == '192.0.2.10':
                break
            time.sleep(2)
        if answer != '192.0.2.10':
            die('BIND9 at %s does not serve %s (answer: %r)' % (NS_IP, rname, answer))
        soa = dig(zname, 'SOA')
        if not soa:
            die('BIND9 at %s has no SOA for %s' % (NS_IP, zname))
    finally:
        call('DELETE', DNS + '/v2/zones/' + zone_id, None, token)
    wait_status(token, DNS + '/v2/zones/' + zone_id, None, 'zone ' + zone_id)
    print('zone cycle ok: %s created, %s answered 192.0.2.10 from %s, zone deleted' % (zname, rname, NS_IP))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    args = sys.argv[2:]
    if cmd == 'register':
        register()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'zone-list':
        zone_list()
    elif cmd == 'zone-create' and len(args) == 2:
        zone_create(args[0], args[1])
    elif cmd == 'zone-wait' and len(args) == 1:
        zone_wait(args[0])
    elif cmd == 'record-create' and len(args) == 3:
        record_create(args[0], args[1], args[2])
    elif cmd == 'record-wait' and len(args) == 2:
        record_wait(args[0], args[1])
    elif cmd == 'zone-delete' and len(args) == 1:
        zone_delete(args[0])
    elif cmd == 'zone-cycle':
        zone_cycle()
    else:
        die('usage: orphera-designate-api register | unregister | catalog | zone-list | zone-create <name> <email> | zone-wait <id> | record-create <zone-id> <name> <ipv4> | record-wait <zone-id> <id> | zone-delete <id> | zone-cycle')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-designate-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@DNS@@", s"http://$fqdn:$apiPort")
        .replace("@@NSIP@@", nodeIp)
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@DESIGNATEPW@@", designatePassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-designate-api\necho wrote /usr/local/sbin/orphera-designate-api\n"

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$apiPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  // BIND9 answers on port 53 of the node's address (any reply, even REFUSED, ends dig with status 0; a
  // timeout does not). One health check covers both, because a stage takes only one.
  private val healthScript =
    s"""set -e
       |$apiHealthScript
       |dig +time=3 +tries=1 @$nodeIp version.bind txt chaos >/dev/null""".stripMargin

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if
  // a unit differs from what this playbook would write. The check task comes from the same Container
  // value as the write task (see Resource.scala).
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  // Adds one "write the unit" task per container, then one "start" task per container (BIND9 first, then
  // central, mdns, api, producer, worker).
  private def withServices(b: StageBuilder): StageBuilder =
    val written = allContainers.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    allContainers.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("designate-single-node-check")(
        allContainers
          .foldLeft(stage("check-designate", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          )
          .build
      )
    else applyPlaybook

  // The install stage up to and including starting every container; registering in Keystone and loading
  // the pool come after, because they need the services running.
  private def installDesignate: StageBuilder =
    withServices(
      stage("install-designate", node)
            .task("wait for apt and dpkg to be idle")(
              Task.RunCommand(
                List("sh", "-c", waitAptIdleScript),
                timeoutSeconds = 960
              )
            )
            .task("install podman and tools")(
              Task.Install(packages = hostPackages, updateCache = true)
            )
            .task("use the Kolla cache as a mirror for quay.io")(
              Task.RunCommand(List("sh", "-c", useCacheScript))
            )
            .task("pull the Designate and BIND9 images")(
              Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 2400)
            )
            .task("fetch Keystone's CA from its TLS handshake")(
              Task.RunCommand(
                List("sh", "-c", fetchCaScript),
                timeoutSeconds = 60
              )
            )
            .task("create the Designate directories")(
              Task.RunCommand(List("sh", "-c", dirsScript))
            )
            .task("write designate.conf")(
              Task.WriteFile(
                content = designateConf,
                dest = s"$srcDir/designate.conf",
                owner = "root",
                group = "root",
                mode = 384 // 0600
              )
            )
            .task("write pools.yaml")(
              Task.WriteFile(
                content = poolsYaml,
                dest = s"$srcDir/pools.yaml",
                owner = "root",
                group = "root",
                mode = 420 // 0644
              )
            )
            .task("write named.conf")(
              Task.WriteFile(
                content = namedConf,
                dest = s"$srcDir/named.conf",
                owner = "root",
                group = "root",
                mode = 420 // 0644
              )
            )
            .task("make the rndc key, once")(
              Task.RunCommand(List("sh", "-c", rndcKeyScript))
            )
            .task("write the Kolla config for each container")(
              Task.RunCommand(
                List("sh", "-c", kollaConfigScript),
                timeoutSeconds = 300
              )
            )
            .task("designate-manage database sync")(
              Task.RunCommand(
                List("sh", "-c", dbSyncScript),
                timeoutSeconds = 600
              )
            )
            .task("write the Designate API helper")(
              Task.RunCommand(List("sh", "-c", helperScript))
            )
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("designate-single-node")(
      stage("designate-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("designate-rabbitmq", rabbitNode)
        .task(
          s"create the $rabbitVhost vhost and the $rabbitUser user on RabbitMQ"
        )(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      installDesignate
        .task("register Designate in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-designate-api register"),
            timeoutSeconds = 120
          )
        )
        .task("load the default pool, then restart the worker and mdns")(
          Task.RunCommand(List("sh", "-c", poolScript), timeoutSeconds = 300)
        )
        .build,

      stage("confirm-designate-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("list zones with an admin token")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-designate-api zone-list"),
            timeoutSeconds = 60
          )
        )
        .task("designate confirmed")(
          Task.Debug(
            s"Designate up at http://$fqdn:$apiPort ($nodeIp): api, central, worker, producer, mdns (:$mdnsPort) and BIND9 (:$dnsPort), RabbitMQ vhost $rabbitVhost on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_designate.sh"
          )
        )
        .build
    )
