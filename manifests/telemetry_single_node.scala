// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack telemetry: Ceilometer (measuring) and Aodh (alarms), Kolla images under podman, with the existing
// Prometheus on tst7 as the metrics store, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md).
// Gnocchi is not used. It replaced the cluster playbook of the same name; a second run changes nothing and
// restarts nothing, and `--check` shows what would change. Modelled on designate_single_node.scala, which
// explains the conventions.
//
// Five descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/telemetry_database.scala     # the aodh database and user (tst3)
//   orphera apply manifests/telemetry_rabbitmq.scala     # the `aodh` and `ceilometer` vhosts and users (tst10)
//   orphera apply manifests/telemetry_prometheus.scala   # the scrape job on Prometheus (tst7)
//   orphera apply manifests/telemetry_compute.scala      # the compute agents (tst17, tst18)
//   orphera apply manifests/telemetry_single_node.scala  # this one (tst24); it also checks the whole chain
//
// Where it runs: tst24 (10.10.5.36), provisioned from config/tst24.yaml; the compute agents run on tst17 and
// tst18 next to nova-compute. To move the main node, change `node` and `fqdn` below (and the teardown's
// `node`); nothing else depends on the location.
//
// How the data flows:
//   - ceilometer-central (tst24) polls the Glance, Cinder and Neutron APIs; ceilometer-compute (tst17, tst18)
//     polls libvirt for the instances on its node. Each polling agent exposes what it collected itself, in
//     Prometheus format, on port 9101 (`[polling] enable_prometheus_exporter`). There is no pipeline.yaml, no
//     notification agent and no message bus traffic for the metrics: `enable_notifications = false`. (The
//     polling agent does not apply pipeline.yaml: its samples go to a notification agent over the message bus,
//     and only that agent runs the pipeline's publishers.)
//   - Prometheus on tst7 scrapes the three exporters every 15 s. The scrape job goes in
//     /etc/prometheus/scrape.d/openstack-telemetry.yml, named by a `scrape_config_files` line in prometheus.yml.
//     observability_stack.scala and observability_extended.scala rewrite prometheus.yml whole and both carry
//     that line, so a re-run of either keeps the job.
//   - aodh-evaluator (tst24) runs PromQL alarms against the Prometheus named by its PROMETHEUS_HOST and
//     PROMETHEUS_PORT environment variables (python-observabilityclient; the evaluator does not load without
//     them); the helper also registers Prometheus in the Keystone catalog as service type `prometheus`;
//     aodh-notifier carries out the alarm actions; aodh-api (port 8042, Apache + mod_wsgi) is the REST API.
// The pollers run every 60 s; the series are named by Ceilometer's exporter (the helper's `metrics` prints the
// names it finds).
//
// Left out of this first version: the Ceilometer notification agent and the Aodh listener. Both read the
// notifications the services publish, and none of Nova, Cinder, Glance or Neutron here sends them yet
// ([oslo_messaging_notifications] driver = messagingv2 in each service's config). Event alarms need them.
//
// What it adds to Keystone (done by the helper's `register`, repeatable): users `ceilometer` and `aodh` in
// project `service` with the admin role; the service `aodh` of type `alarming` with the endpoint
// http://<fqdn>:8042; the service `prometheus` of type `prometheus` with the endpoint of Prometheus on tst7.
//
// Prerequisites, not checked here:
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ running on tst10 (rabbitmq_single_node.scala)
//   - Prometheus running on tst7 (observability_stack.scala), version 2.43 or later (scrape_config_files)
//   - Nova compute running on tst17 and tst18 (nova_compute.scala): libvirtd with its socket open to all
//   - the agent on tst24; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Ceilometer and Aodh images (ceilometer-central, ceilometer-compute, aodh-api, aodh-evaluator,
//     aodh-notifier) are in tst21's build list, but need `orphera-kolla-build sources refresh` and a build of
//     those the first time
//
// How it stays idempotent (as for Designate):
//   - Keystone's CA is compared as a certificate with the installed copy each run; each service's copy is
//     refreshed (and the service restarted) when it changes.
//   - The configuration files hold secrets, so they are secret files, one per service directory. The Aodh API's
//     Apache vhost and config.json depend on what the image has (the WSGI script, the Apache flavour), so they
//     are generated into a temporary directory and compared with the installed files.
//   - `aodh-dbsync` is skipped while its stamp matches and the `alarm` table exists (a query as the aodh user,
//     through the VIP).
//   - The services are registered in Keystone before the units start, skipped while the helper's `registered`
//     check passes.
//   - The last steps wait for the three exporters to be up in Prometheus and for two PromQL alarms to reach the
//     states they should; both are skipped while they already hold (the alarm round trip is run as the guard).
//
// Facts this was written from, to check if a run fails: the exporter is [polling]
// enable_prometheus_exporter and prometheus_listen_addresses (default 127.0.0.1:9101), fed by the polling task
// whether or not notifications are on; the Aodh Prometheus alarm rule is {comparison_operator, threshold,
// query}; the Aodh evaluator finds Prometheus through PROMETHEUS_HOST and PROMETHEUS_PORT (found on the lab:
// without them stevedore drops the prometheus evaluator and the alarms are skipped as an unsupported type); the
// aodh-api WSGI script is found in the image as app.wsgi; the Kolla user names are ceilometer and aodh;
// ceilometer-polling takes --polling-namespaces central|compute; instance_discovery_method = libvirt_metadata
// reads the instance details from the libvirt domain XML, so the compute agent needs no Nova API; the aodh
// database has the table `alarm`.
//
// Test-only credentials come from the secret store (aodh/db, aodh/service, aodh/rabbit, ceilometer/service,
// ceilometer/rabbit), made on first use.
object telemetry_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst24"
  private val fqdn = "tst24.ljalbinson.com"
  private val aodhPort = 8042
  private val exporterPort = 9101
  private val computeNodes = List("tst17", "tst18")

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val promFqdn = "tst7.ljalbinson.com"
  private val promUrl = s"http://$promFqdn:9090"

  private val rabbitIp = "10.10.5.22"
  private val aodhRabbitPassword = Secrets.secret("aodh/rabbit")
  private val ceilometerRabbitPassword = Secrets.secret("ceilometer/rabbit")

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "aodh"
  private val dbUser = "aodh"
  private val dbPassword = Secrets.secret("aodh/db")
  private val aodhPassword = Secrets.secret("aodh/service")
  private val ceilometerPassword = Secrets.secret("ceilometer/service")

  private val confRoot = "/etc/kolla"
  private val caDir = "/etc/kolla/telemetry-ca"
  private val caCert = s"$caDir/ca.crt"
  private val aodhLogDir = "/var/log/kolla/aodh"
  private val ceilometerLogDir = "/var/log/kolla/ceilometer"
  private val stateDir = "/var/lib/orphera/telemetry"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val helperPath = "/usr/local/sbin/orphera-telemetry-api"

  private def kolla(service: String): Image =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry)

  // Defined after the directories above, which they read; a val used before its definition is null in an
  // object.
  private def aodhContainer(service: String): Container =
    Container(
      name = ContainerName(service),
      image = kolla(service),
      mounts = List(
        Mount(s"$confRoot/$service", "/var/lib/kolla/config_files"),
        Mount(aodhLogDir, "/var/log/kolla/aodh", Access.ReadWrite),
        Mount(s"$stateDir/aodh", "/var/lib/aodh", Access.ReadWrite)
      ),
      // The evaluator's observabilityclient library takes the Prometheus address from these two variables
      // (or from ~/.config/openstack/prometheus.yaml); it does not read the Keystone catalog.
      env = List(
        "KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS",
        "PROMETHEUS_HOST" -> promFqdn,
        "PROMETHEUS_PORT" -> "9090"
      ),
      // The API image runs Apache, which cannot open its log files as a non-root user.
      user = if service == "aodh-api" then Some("root") else None
    )
  private val centralContainer = Container(
    name = ContainerName("ceilometer-central"),
    image = kolla("ceilometer-central"),
    mounts = List(
      Mount(s"$confRoot/ceilometer-central", "/var/lib/kolla/config_files"),
      Mount(ceilometerLogDir, "/var/log/kolla/ceilometer", Access.ReadWrite),
      Mount(s"$stateDir/ceilometer", "/var/lib/ceilometer", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )
  private val aodhNames = List("aodh-evaluator", "aodh-notifier", "aodh-api")
  private val aodhContainers: List[Container] = aodhNames.map(aodhContainer)
  private val nodeContainers: List[Container] =
    centralContainer :: aodhContainers

  private val aodhApiImage = kolla("aodh-api").ref

  private val hostPackages =
    List("podman", "openssl", "curl", "ca-certificates", "python3")

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

  private val images = nodeContainers.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in telemetry_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(
        " "
      )}; do podman image exists $$img || exit 1; done"""

  // Keystone's CA: the last certificate it presents (trust on first use), which must be self-signed. `chainTo
  // DIR` leaves DIR/c1.pem ... DIR/cN.pem, each holding the certificate only: the text s_client prints after
  // the last one differs on every handshake, and would make the comparison with the installed copy fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""

  // Current when the installed CA is the one Keystone presents now, and is in the system trust store.
  private val caCurrent =
    s"""$chainTo
       |D=$$(mktemp -d)
       |chainTo $$D
       |N=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |R=1
       |if [ "$$N" -ge 2 ]; then
       |  $trustTarget
       |  cmp -s $$D/c$$N.pem $caCert && cmp -s $caCert "$$t" && R=0
       |fi
       |rm -rf $$D
       |exit $$R""".stripMargin

  private val caScript =
    s"""set -e
       |$chainTo
       |mkdir -p $caDir
       |D=$$(mktemp -d)
       |trap 'rm -rf $$D' EXIT
       |chainTo $$D
       |COUNT=$$(ls $$D/c*.pem 2>/dev/null | wc -l)
       |if [ "$$COUNT" -lt 2 ]; then
       |  echo "keystone at $ksFqdn:5000 presented $$COUNT certificate(s); expected leaf + CA - apply keystone_single_node.scala (it must serve server-chain.crt)" >&2
       |  exit 1
       |fi
       |cp $$D/c$$COUNT.pem $caCert
       |openssl verify -CAfile $caCert $$D/c1.pem
       |SUBJ=$$(openssl x509 -in $caCert -noout -subject | sed 's/^subject=//')
       |ISS=$$(openssl x509 -in $caCert -noout -issuer | sed 's/^issuer=//')
       |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
       |$trustTarget
       |cp $caCert "$$t"
       |if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; else update-ca-trust; fi
       |echo "Keystone CA installed: $$SUBJ"""".stripMargin

  // ---- configuration ---------------------------------------------------

  private def credentials(user: String, password: String, ca: String): String =
    s"""auth_type = password
       |auth_url = $ksUrl
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = $user
       |password = $password
       |region_name = RegionOne
       |interface = public
       |cafile = $ca""".stripMargin

  private val aodhConf: String =
    s"""# Managed by Orphera (telemetry_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://aodh:${aodhRabbitPassword.reveal}@$rabbitIp:5672/aodh
       |log_dir = /var/log/kolla/aodh
       |use_stderr = true
       |
       |[database]
       |connection = mysql+pymysql://$dbUser:${dbPassword.reveal}@$vip:3306/$dbName
       |# haproxy on the VIP closes connections idle for 30 s; recycle older pooled ones before they are reused
       |connection_recycle_time = 20
       |
       |[keystone_authtoken]
       |www_authenticate_uri = $ksUrl
       |auth_url = $ksUrl
       |auth_type = password
       |project_domain_name = Default
       |user_domain_name = Default
       |project_name = service
       |username = aodh
       |password = ${aodhPassword.reveal}
       |cafile = /etc/aodh/ssl/ca.crt
       |
       |[service_credentials]
       |${credentials("aodh", aodhPassword.reveal, "/etc/aodh/ssl/ca.crt")}
       |
       |[evaluator]
       |evaluation_interval = 60
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/aodh/tmp
       |""".stripMargin

  // Keep in step with telemetry_compute.scala (the same file, with its own host name).
  private val ceilometerConf: String =
    s"""# Managed by Orphera (telemetry_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://ceilometer:${ceilometerRabbitPassword.reveal}@$rabbitIp:5672/ceilometer
       |log_dir = /var/log/kolla/ceilometer
       |use_stderr = true
       |host = $node
       |
       |[service_credentials]
       |${credentials(
        "ceilometer",
        ceilometerPassword.reveal,
        "/etc/ceilometer/ssl/ca.crt"
      )}
       |
       |[polling]
       |enable_prometheus_exporter = true
       |prometheus_listen_addresses = 0.0.0.0:$exporterPort
       |enable_notifications = false
       |batch_size = 500
       |
       |[compute]
       |instance_discovery_method = libvirt_metadata
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/ceilometer/tmp
       |""".stripMargin

  private val centralPolling: String =
    """---
      |sources:
      |  - name: central_pollsters
      |    interval: 60
      |    meters:
      |      - image.size
      |      - volume.size
      |      - volume.snapshot.size
      |      - ip.floating
      |""".stripMargin

  private val ceilometerJson: String =
    """{"command": "ceilometer-polling --polling-namespaces central --config-file /etc/ceilometer/ceilometer.conf", "config_files": [{"source": "/var/lib/kolla/config_files/ceilometer.conf", "dest": "/etc/ceilometer/ceilometer.conf", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/polling.yaml", "dest": "/etc/ceilometer/polling.yaml", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/ceilometer/ssl/ca.crt", "owner": "ceilometer", "perm": "0644"}], "permissions": [{"path": "/var/log/kolla/ceilometer", "owner": "ceilometer:kolla", "recurse": true}, {"path": "/var/lib/ceilometer", "owner": "ceilometer:ceilometer", "recurse": true}]}
      |""".stripMargin

  // The files every Aodh service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/aodh.conf", "dest": "/etc/aodh/aodh.conf", "owner": "aodh", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/aodh/ssl/ca.crt", "owner": "aodh", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/aodh", "owner": "aodh:kolla", "recurse": true}, {"path": "/var/lib/aodh", "owner": "aodh:aodh", "recurse": true}]"""
  private def plainConfigJson(name: String): String =
    s"""{"command": "$name --config-file /etc/aodh/aodh.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin

  // The API runs behind Apache + mod_wsgi; the WSGI script and the Apache flavour are read from the image. The
  // vhost and config.json are generated into `out` (the real directory, or a temporary one for the guard to
  // compare with).
  private def kollaApiScript(out: String): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $aodhApiImage -c 'find / -xdev -name app.wsgi -path "*aodh*" 2>/dev/null | head -1')
       |if [ -z "$$WSGI" ]; then
       |  echo "no aodh app.wsgi found in $aodhApiImage - the API cannot be run under Apache; look for the WSGI script in the image" >&2
       |  exit 1
       |fi
       |if podman run --rm --entrypoint sh $aodhApiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |  APACHE_CONF=/etc/apache2/conf-enabled/aodh-api.conf
       |  APACHE_CMD="apache2 -DFOREGROUND"
       |  # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |  if podman run --rm --entrypoint sh $aodhApiImage -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |else
       |  APACHE_CONF=/etc/httpd/conf.d/aodh-api.conf
       |  APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |fi
       |echo "wsgi script: $$WSGI; apache config: $$APACHE_CONF"
       |cat > $out/aodh-api.conf <<WSGICONF
       |Listen 0.0.0.0:$aodhPort
       |ServerSignature Off
       |ServerTokens Prod
       |TraceEnable off
       |
       |<VirtualHost *:$aodhPort>
       |    ErrorLog "$aodhLogDir/aodh-apache-error.log"
       |    CustomLog "$aodhLogDir/aodh-apache-access.log" combined
       |    WSGIApplicationGroup %{GLOBAL}
       |    WSGIDaemonProcess aodh-api group=aodh processes=2 threads=1 user=aodh
       |    WSGIProcessGroup aodh-api
       |    WSGIScriptAlias / "$$WSGI"
       |    WSGIPassAuthorization On
       |
       |    <Directory "$$(dirname "$$WSGI")">
       |        Require all granted
       |    </Directory>
       |</VirtualHost>
       |WSGICONF
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/aodh-api.conf", "dest": "$$APACHE_CONF", "owner": "aodh", "perm": "0600"}], $permissions}
       |JSON
       |echo "kolla config written under $out"""".stripMargin

  // Current when every file the script generates is installed with the same content.
  private def kollaCurrent(generate: String, dir: String): String =
    s"""T=$$(mktemp -d)
       |$generate >/dev/null
       |R=0
       |for f in $$T/*; do cmp -s $$f $dir/$$(basename $$f) || R=1; done
       |rm -rf $$T
       |exit $$R""".stripMargin

  // aodh-dbsync runs in a one-off container with aodh.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run). The file is the API's copy.
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint aodh-dbsync -v $confRoot/aodh-api/aodh.conf:/etc/aodh/aodh.conf:ro,z -v $aodhLogDir:/var/log/kolla/aodh:z $aodhApiImage --config-file /etc/aodh/aodh.conf
       |echo "aodh database synced"""".stripMargin

  // Runs a Python snippet in the aodh-api image, with its own interpreter (the one named on the first line of
  // aodh-dbsync), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`. Exit 0
  // when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $aodhApiImage -c 'exec $$(head -1 "$$(command -v aodh-dbsync)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbStamp = Resource.sha256(dbSyncScript + aodhConf)
  // True when the aodh database, queried as the aodh user through the VIP, has the `alarm` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM alarm')")
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other services. Plain string filled in with replace().
  // Subcommands:
  //   register | registered | unregister | catalog | metrics | exporters-up | alarm-cycle
  // metrics waits (up to 5 minutes) for series of job=ceilometer in Prometheus and prints their names.
  // exporters-up is the quick check that every exporter is up in Prometheus (no waiting).
  // alarm-cycle makes two Prometheus alarms on the query up{job="prometheus"} (one that must be true, one that
  // must be false), waits until they report the states alarm and ok, and deletes them.
  private val apiHelperPython =
    """#!/usr/bin/python3
import json, ssl, sys, time, urllib.parse, urllib.request, urllib.error

KS = 'https://@@KS@@:5000/v3'
AODH = '@@AODH@@'
PROM = '@@PROM@@'
ADMIN_PW = '@@ADMINPW@@'
AODH_PW = '@@AODHPW@@'
CEILOMETER_PW = '@@CEILOMETERPW@@'
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


def ensure(token, what, path, key, body, create_key):
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def ensure_user(token, project, admin_role, name, password):
    user = find(token, '/users?name=' + name, 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': name, 'domain_id': 'default', 'password': password}}, token)
        if s != 201:
            die('creating user %s failed: HTTP %s %s' % (name, s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': password}}, token)
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to %s failed: HTTP %s %s' % (name, s, p))


def ensure_endpoints(token, stype, sname, url):
    service = ensure(token, 'services', '/services?type=' + stype, 'services', {'service': {'name': sname, 'type': stype}}, 'service')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None:
            s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': url, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
            if s != 201:
                die('creating %s endpoint of %s failed: HTTP %s %s' % (iface, stype, s, p))
        elif ep['url'] != url:
            call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': url}}, token)


def register():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    project = ensure(token, 'projects', '/projects?name=service', 'projects', {'project': {'name': 'service', 'domain_id': 'default'}}, 'project')
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    ensure_user(token, project, admin_role, 'aodh', AODH_PW)
    ensure_user(token, project, admin_role, 'ceilometer', CEILOMETER_PW)
    ensure_endpoints(token, 'alarming', 'aodh', AODH)
    ensure_endpoints(token, 'prometheus', 'prometheus', PROM)
    print('registered in keystone (users aodh and ceilometer, service types alarming and prometheus, 6 endpoints)')


def unregister():
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for stype in ('alarming', 'prometheus'):
        service = find(token, '/services?type=' + stype, 'services')
        if service is not None:
            call('DELETE', KS + '/services/' + service['id'], None, token)
    for name in ('aodh', 'ceilometer'):
        user = find(token, '/users?name=' + name, 'users')
        if user is not None:
            call('DELETE', KS + '/users/' + user['id'], None, token)
    print('removed aodh, ceilometer and the prometheus entry from keystone (services, endpoints, users)')


def catalog():
    _, p = get_token('admin', ADMIN_PW, 'admin')
    print('catalog types: ' + ' '.join(sorted(c['type'] for c in p['token']['catalog'])))


def prom(path, params):
    url = PROM + path + '?' + urllib.parse.urlencode(params)
    s, p, _ = call('GET', url)
    if s != 200:
        die('prometheus %s failed: HTTP %s %s' % (path, s, p))
    return p


def metrics():
    expected = @@NTARGETS@@
    deadline = time.time() + 300
    up = 0
    while time.time() < deadline:
        q = prom('/api/v1/query', {'query': 'count(up{job="ceilometer"} == 1)'})
        up = int(float(q['data']['result'][0]['value'][1])) if q['data']['result'] else 0
        if up >= expected:
            break
        time.sleep(10)
    if up < expected:
        t = prom('/api/v1/targets', {})
        bad = ['%s: %s' % (x['scrapeUrl'], x.get('lastError') or x['health']) for x in t['data']['activeTargets'] if x['labels'].get('job') == 'ceilometer' and x['health'] != 'up']
        die('only %d of %d ceilometer exporters are up in prometheus after 5 minutes: %s - check "curl localhost:9101/metrics" on the node and the scrape job' % (up, expected, '; '.join(bad) or 'no ceilometer targets known'))
    # The pollers run every 60 s, so give the first samples time to be scraped.
    names = []
    end = time.time() + 180
    while time.time() < end:
        names = [n for n in prom('/api/v1/label/__name__/values', {'match[]': '{job="ceilometer"}'}).get('data', []) if n not in ('up', 'scrape_duration_seconds', 'scrape_samples_scraped', 'scrape_samples_post_metric_relabeling', 'scrape_series_added')]
        if names:
            break
        time.sleep(10)
    print('ceilometer exporters up: %d of %d' % (up, expected))
    if names:
        print('ceilometer metric names in prometheus: ' + ' '.join(sorted(names)))
    else:
        print('WARNING: the exporters are up but no Ceilometer metric has appeared yet (no instances, volumes or images to poll, or the pollsters are skipping): look at "podman logs ceilometer-central"')


def acall(method, path, token, body=None):
    return call(method, AODH + path, body, token)


def registered():
    # Exit 0 only when everything register() makes is in place: the users aodh and ceilometer can get a token in
    # project service, and the services alarming and prometheus have their three endpoints at the right URLs.
    # Makes no change.
    get_token('aodh', AODH_PW, 'service')
    get_token('ceilometer', CEILOMETER_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    for stype, url in (('alarming', AODH), ('prometheus', PROM)):
        service = find(token, '/services?type=' + stype, 'services')
        if service is None:
            die('no service of type ' + stype)
        for iface in ('public', 'internal', 'admin'):
            ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
            if ep is None or ep['url'] != url:
                die('endpoint %s of %s missing or wrong' % (iface, stype))
    print('telemetry is registered in keystone')


def exporters_up():
    # Exit 0 when Prometheus has every Ceilometer exporter up. Does not wait. Makes no change.
    expected = @@NTARGETS@@
    q = prom('/api/v1/query', {'query': 'count(up{job="ceilometer"} == 1)'})
    up = int(float(q['data']['result'][0]['value'][1])) if q['data']['result'] else 0
    if up < expected:
        die('%d of %d ceilometer exporters are up in prometheus' % (up, expected))
    print('ceilometer exporters up: %d of %d' % (up, expected))


def alarm_cycle():
    token = admin_token()
    s, p, _ = acall('GET', '/v2/alarms', token)
    if s != 200:
        die('listing alarms failed: HTTP %s %s' % (s, p))
    for a in p:
        if a.get('name', '').startswith('orphera-test-'):
            acall('DELETE', '/v2/alarms/' + a['alarm_id'], token)
    wants = {}
    try:
        for name, op, want in (('orphera-test-true', 'gt', 'alarm'), ('orphera-test-false', 'lt', 'ok')):
            body = {'name': name, 'type': 'prometheus', 'severity': 'low', 'enabled': True, 'repeat_actions': False,
                    'prometheus_rule': {'comparison_operator': op, 'threshold': 0.0, 'query': 'up{job="prometheus"}'}}
            s, p, _ = acall('POST', '/v2/alarms', token, body)
            if s != 201:
                die('creating alarm %s failed: HTTP %s %s' % (name, s, p))
            wants[p['alarm_id']] = (name, want)
        deadline = time.time() + 360
        states = {}
        while time.time() < deadline:
            for aid, (name, want) in wants.items():
                s, p, _ = acall('GET', '/v2/alarms/%s/state' % aid, token)
                states[aid] = p if s == 200 else 'HTTP %s' % s
            if all(states.get(aid) == want for aid, (name, want) in wants.items()):
                break
            time.sleep(10)
        bad = ['%s is %r, wanted %r' % (name, states.get(aid), want) for aid, (name, want) in wants.items() if states.get(aid) != want]
        if bad:
            die('alarm cycle failed: ' + '; '.join(bad) + ' - an alarm that stays at insufficient data means the evaluator cannot query prometheus: look at "podman logs aodh-evaluator" or /var/log/kolla/aodh/')
        print('alarm cycle ok: ' + ', '.join('%s -> %s' % (name, want) for aid, (name, want) in wants.items()))
    finally:
        for aid in wants:
            acall('DELETE', '/v2/alarms/' + aid, token)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'register':
        register()
    elif cmd == 'registered':
        registered()
    elif cmd == 'exporters-up':
        exporters_up()
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'metrics':
        metrics()
    elif cmd == 'alarm-cycle':
        alarm_cycle()
    else:
        die('usage: orphera-telemetry-api register | registered | unregister | catalog | metrics | exporters-up | alarm-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@AODH@@", s"http://$fqdn:$aodhPort")
      .replace("@@PROM@@", promUrl)
      .replace("@@NTARGETS@@", (node :: computeNodes).size.toString)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@AODHPW@@", aodhPassword.reveal)
      .replace("@@CEILOMETERPW@@", ceilometerPassword.reveal)
      .replace("@@CA@@", caCert)

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthy =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$aodhPort/); [ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]"""
  // The polling agent's exporter answers once the first poll cycle has started.
  private val exporterHealthy =
    s"""curl -sf http://localhost:$exporterPort/metrics >/dev/null"""
  private val healthy = s"( $apiHealthy ) && $exporterHealthy"
  private val waitHealthy =
    s"""for i in $$(seq 1 36); do
       |  ( $healthy ) && exit 0
       |  sleep 5
       |done
       |echo "the Aodh API on $aodhPort or the exporter on $exporterPort does not answer - see 'journalctl -xeu orphera-aodh-api' and $aodhLogDir" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools(0)

    val mirrorDir = directory("/etc/containers/registries.conf.d").after(podman)
    val mirror = file(
      "/etc/containers/registries.conf.d/orphera-kolla-cache.conf",
      registryMirror,
      mode = "0644"
    ).after(mirrorDir)
    val pulled = command(
      "pull the Ceilometer and Aodh images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)

    // The units come first so that what they depend on can name them in `notifies`.
    val unitOf: Map[String, ApplyResource] =
      ("ceilometer-central" :: aodhNames)
        .zip(nodeContainers)
        .map((n, c) => n -> service(c.unitName))
        .toMap

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val aodhLog = directory(aodhLogDir)
    val ceilometerLog = directory(ceilometerLogDir)
    val stateDirectory = directory(stateDir)
    val aodhState = directory(s"$stateDir/aodh").after(stateDirectory)
    val aodhTmp = directory(s"$stateDir/aodh/tmp").after(aodhState)
    val ceilometerState =
      directory(s"$stateDir/ceilometer").after(stateDirectory)
    val ceilometerTmp =
      directory(s"$stateDir/ceilometer/tmp").after(ceilometerState)

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin, aodh and ceilometer passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Aodh, Ceilometer and Prometheus in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    // ---- ceilometer-central ----
    val centralDir = s"$confRoot/ceilometer-central"
    val centralDirRes = directory(centralDir, mode = "0755")
    val centralUnit = unitOf("ceilometer-central")
    val centralResources: List[ApplyResource] = List(
      centralDirRes,
      secretFile(
        s"$centralDir/ceilometer.conf",
        Secret(
          ceilometerConf
        ), // it holds the message bus and service passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(centralDirRes).notifies(centralUnit),
      file(
        s"$centralDir/polling.yaml",
        centralPolling,
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(centralDirRes).notifies(centralUnit),
      fileCopy(
        s"$centralDir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, centralDirRes).notifies(centralUnit),
      file(
        s"$centralDir/config.json",
        ceilometerJson,
        mode = "0644",
        owner = "root",
        group = "root"
      ).after(centralDirRes).notifies(centralUnit),
      file(
        centralContainer.unitPath,
        centralContainer.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(centralUnit)
    )

    // ---- the three Aodh services ----
    val aodhServices = aodhNames.zip(aodhContainers).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = unitOf(name)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/aodh.conf",
        Secret(
          aodhConf
        ), // it holds the database, message bus and service passwords
        mode = "0640",
        owner = "root",
        group = "root"
      ).after(dirRes).notifies(unitService)
      val kollaCa = fileCopy(
        s"$dir/ca.crt",
        caCert,
        mode = "0644"
      ).after(ca, dirRes).notifies(unitService)
      val kollaJson =
        if name == "aodh-api" then
          command(
            s"write the Apache vhost and the Kolla config.json for $name",
            kollaApiScript(dir),
            unless = kollaCurrent(kollaApiScript("$T"), dir),
            timeout = 180
          ).after(pulled, dirRes, aodhLog).notifies(unitService)
        else
          file(
            s"$dir/config.json",
            plainConfigJson(name),
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
      val unitFile = file(
        c.unitPath,
        c.render,
        mode = "0644",
        owner = "root",
        group = "root"
      ).notifies(unitService)
      (
        name,
        List[ApplyResource](dirRes, conf, kollaCa, kollaJson, unitFile),
        conf
      )
    }
    val aodhResources = aodhServices.flatMap(_._2)
    val apiConf = aodhServices.find(_._1 == "aodh-api").get._3
    val apiDirRes = aodhServices.find(_._1 == "aodh-api").get._2.head

    val dbSync = command(
      "aodh-dbsync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(pulled, aodhLog, stateDirectory, apiConf, apiDirRes)

    val running = unitOf("ceilometer-central").after(
      (registered :: pulled :: ceilometerLog :: ceilometerTmp :: centralResources)*
    ) :: aodhServices.map { (name, resources, _) =>
      unitOf(name).after(
        (dbSync :: registered :: pulled :: aodhLog :: aodhTmp :: resources)*
      )
    }

    val answering = command(
      "the Aodh API and the exporter answer on their ports",
      waitHealthy,
      unless = healthy,
      timeout = 240
    ).after(running*)
    val series = command(
      "wait for the Ceilometer exporters to be up in Prometheus",
      s"$helperPath metrics",
      unless = s"$helperPath exporters-up >/dev/null 2>&1",
      timeout = 420
    ).after((answering :: running)*)
    val alarms = command(
      "two PromQL alarms reach the states alarm and ok",
      s"$helperPath alarm-cycle",
      unless = s"$helperPath alarm-cycle >/dev/null 2>&1",
      timeout = 480
    ).after((series :: answering :: running)*)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      aodhLog,
      ceilometerLog,
      stateDirectory,
      aodhState,
      aodhTmp,
      ceilometerState,
      ceilometerTmp,
      helperFile,
      registered
    ) ++ centralResources ++ aodhResources ++ List(dbSync) ++ running ++
      List(answering, series, alarms)
