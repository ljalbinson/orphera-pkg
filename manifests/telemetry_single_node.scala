// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. OpenStack telemetry: Ceilometer (measuring) and Aodh (alarms), Kolla images under podman, with the
// existing Prometheus on tst7 as the metrics store. Gnocchi is not used. Modelled on manila_single_node.scala
// and nova_compute.scala; read their headers for the conventions.
//
// Where it runs: tst24 (10.10.5.36), a new VM provisioned from config/tst24.yaml; the compute agents run on
// tst17 and tst18 next to nova-compute. To move the main node, change `node`, `fqdn` and `nodeIp` below (and
// the teardown's `node`); nothing else depends on the location.
//
// How the data flows:
//   - ceilometer-central (tst24) polls the Glance, Cinder and Neutron APIs; ceilometer-compute (tst17, tst18)
//     polls libvirt for the instances on its node. Each agent publishes its samples with the `prometheus://`
//     publisher to the Pushgateway on tst24 (port 9091), in its own group (job=ceilometer, instance=<node>),
//     so one agent's push never replaces another's.
//   - Prometheus on tst7 scrapes the Pushgateway every 15 s (honor_labels, so the pushed job and instance
//     labels are kept). The scrape job goes in /etc/prometheus/scrape.d/openstack-telemetry.yml, named by a
//     `scrape_config_files` line in prometheus.yml. observability_stack.scala and observability_extended.scala
//     rewrite prometheus.yml whole and both carry that line, so a re-run of either keeps the job.
//   - aodh-evaluator (tst24) runs PromQL alarms against the Prometheus it finds in the Keystone catalog (service
//     type `prometheus`, registered here as http://tst7.ljalbinson.com:9090); aodh-notifier carries out the
//     alarm actions; aodh-api (port 8042, Apache + mod_wsgi) is the REST API.
// Ceilometer's Prometheus publisher sends no timestamps and only the label resource_id, so a series is named
// after the meter (cpu, memory_usage, image_size ...) with {resource_id="..."}; the pollers run every 60 s.
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
//   - Galera + VIP healthy, Keystone up on tst6, RabbitMQ running on tst10 (cinder_single_node.scala)
//   - Prometheus running on tst7 (observability_stack.scala), version 2.43 or later (scrape_config_files)
//   - Nova compute running on tst17 and tst18 (nova_compute.scala): libvirtd with its socket open to all
//   - the agent on tst24; tst21's built images or tst9's Kolla cache are optional (quay.io works)
//   - the Ceilometer and Aodh images (ceilometer-central, ceilometer-compute, aodh-api, aodh-evaluator,
//     aodh-notifier) are in tst21's build list, but need `orphera-kolla-build sources refresh` and a build of
//     those the first time
//
// Stages:
//   - telemetry-database (tst3): the `aodh` database + user.
//   - telemetry-rabbitmq (tst10): vhosts and users `aodh` and `ceilometer`, idempotent.
//   - install-telemetry (tst24): packages, cache mirror, image pulls, Keystone's CA, the helper and the
//     registration, configuration, Kolla config, `aodh-dbsync`, then the units.
//   - install-ceilometer-compute (tst17, tst18): image pull, CA, configuration (host name filled in per
//     node), Kolla config, unit.
//   - telemetry-prometheus (tst7): the scrape job for the Pushgateway.
//   - confirm-telemetry-healthy (tst24): the Aodh API answers, ceilometer series appear in Prometheus, and two
//     PromQL alarms reach the states they should.
//
// Check mode: `ORPHERA_CHECK=1 orphera cluster-playbook manifests/telemetry_single_node.scala` changes nothing
// and reports whether the units on tst24 match what this playbook writes.
//
// Facts this was written from, to check if the first run fails: the Prometheus publisher is
// prometheus://host:9091/metrics/job/<job>[/instance/<name>]; the Aodh Prometheus alarm rule is
// {comparison_operator, threshold, query}; the Aodh evaluator finds Prometheus by service type `prometheus`
// (the install stage prints the lines of the image's evaluator that name it); the aodh-api WSGI script is
// found in the image as app.wsgi; the Kolla user names are ceilometer and aodh; ceilometer-polling takes
// --polling-namespaces central|compute; instance_discovery_method = libvirt_metadata reads the instance
// details from the libvirt domain XML, so the compute agent needs no Nova API; the Pushgateway image is
// quay.io/prometheus/pushgateway.
//
// Test-only credentials come from the secret store (aodh/db, aodh/service, aodh/rabbit, ceilometer/service,
// ceilometer/rabbit), made on first use. Not compiled where this was written; the first run on scala0 is the
// first compile.
object telemetry_single_node extends OrpheraClusterPlaybook:

  private val node = "tst24"
  private val fqdn = "tst24.ljalbinson.com"
  private val nodeIp = "10.10.5.36"
  private val aodhPort = 8042
  private val pushPort = 9091
  private val computeNodes = List("tst17", "tst18")

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

  private val promNode = "tst7"
  private val promUrl = "http://tst7.ljalbinson.com:9090"

  private val rabbitNode = "tst10"
  private val rabbitIp = "10.10.5.22"
  private val aodhRabbitPassword = Secrets.secret("aodh/rabbit")
  private val ceilometerRabbitPassword = Secrets.secret("ceilometer/rabbit")

  // Keep in step with keystone_single_node.scala / kolla_cache.scala.
  private val registry = "quay.io/openstack.kolla"
  private val cacheHostPort = "tst9.ljalbinson.com:5000"
  private val pushgatewayImage =
    ExternalImage("quay.io/prometheus/pushgateway:v1.9.0")

  private val adminPassword = Secrets.secret("keystone/admin")
  private val dbName = "aodh"
  private val dbUser = "aodh"
  private val dbPassword = Secrets.secret("aodh/db")
  private val aodhPassword = Secrets.secret("aodh/service")
  private val ceilometerPassword = Secrets.secret("ceilometer/service")

  private val confRoot = "/etc/kolla"
  private val srcDir = "/etc/kolla/telemetry"
  private val caDir = "/etc/kolla/telemetry-ca"
  private val aodhLogDir = "/var/log/kolla/aodh"
  private val ceilometerLogDir = "/var/log/kolla/ceilometer"
  private val stateDir = "/var/lib/orphera/telemetry"
  private val ksUrl = s"https://$ksFqdn:5000"

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
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
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
  private val pushgateway = Container(
    name = ContainerName("pushgateway"),
    image = pushgatewayImage,
    description = Some("Prometheus Pushgateway for Ceilometer")
  )
  private val nodeContainers: List[Container] =
    List(pushgateway, centralContainer) ++
      List("aodh-evaluator", "aodh-notifier", "aodh-api").map(aodhContainer)

  // The compute agent reads libvirt through the host's socket (nova_compute.scala opens it to all users) and
  // runs as root in the container, as the nova-compute container does.
  private val computeContainer = Container(
    name = ContainerName("ceilometer-compute"),
    image = kolla("ceilometer-compute"),
    mounts = List(
      Mount(
        s"$confRoot/ceilometer-compute",
        "/var/lib/kolla/config_files"
      ),
      Mount(ceilometerLogDir, "/var/log/kolla/ceilometer", Access.ReadWrite),
      Mount(s"$stateDir/ceilometer", "/var/lib/ceilometer", Access.ReadWrite),
      Mount("/run/libvirt", "/run/libvirt", Access.Unlabelled)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
    user = Some("root"),
    after = List("libvirtd.service"),
    wants = List("libvirtd.service")
  )

  private val aodhApiImage = kolla("aodh-api").ref

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

  // ---- tst3 (Galera) ---------------------------------------------------

  private val createDatabaseScript =
    s"""mariadb -N -e "CREATE DATABASE IF NOT EXISTS $dbName; CREATE USER IF NOT EXISTS '$dbUser'@'%' IDENTIFIED BY '${dbPassword.reveal}'; GRANT ALL PRIVILEGES ON $dbName.* TO '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  // ---- tst10 (RabbitMQ) ------------------------------------------------

  // One vhost and one user of the same name, with the user's password set every time.
  private def rabbitPart(name: String, password: String): String =
    s"""if ! podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx '$name'; then
       |  podman exec rabbitmq rabbitmqctl add_vhost '$name'
       |fi
       |if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx '$name'; then
       |  podman exec rabbitmq rabbitmqctl change_password '$name' '$password'
       |else
       |  podman exec rabbitmq rabbitmqctl add_user '$name' '$password'
       |fi
       |podman exec rabbitmq rabbitmqctl set_permissions -p '$name' '$name' '.*' '.*' '.*'
       |echo "rabbitmq vhost $name and user $name ready"""".stripMargin

  private val rabbitScript =
    s"""set -e
       |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
       |  echo "no running 'rabbitmq' container on this node - run cinder_single_node.scala first" >&2
       |  exit 1
       |fi
       |${rabbitPart("aodh", aodhRabbitPassword.reveal)}
       |${rabbitPart("ceilometer", ceilometerRabbitPassword.reveal)}""".stripMargin

  // ---- tst7 (Prometheus) -----------------------------------------------

  // The scrape job goes in its own file, named by a scrape_config_files line in prometheus.yml. The two
  // observability playbooks rewrite prometheus.yml and carry the same line. The old file is put back if
  // promtool does not accept the new one.
  private val prometheusScript =
    s"""set -e
       |mkdir -p /etc/prometheus/scrape.d
       |cat > /etc/prometheus/scrape.d/openstack-telemetry.yml <<'SCRAPE'
       |scrape_configs:
       |  - job_name: 'pushgateway'
       |    honor_labels: true
       |    static_configs:
       |      - targets: ['$fqdn:$pushPort']
       |SCRAPE
       |cp /etc/prometheus/prometheus.yml /etc/prometheus/prometheus.yml.orphera-bak
       |if ! grep -q '^scrape_config_files:' /etc/prometheus/prometheus.yml; then
       |  sed -i 's|^scrape_configs:|scrape_config_files:\\n  - /etc/prometheus/scrape.d/*.yml\\n\\nscrape_configs:|' /etc/prometheus/prometheus.yml
       |fi
       |if command -v promtool >/dev/null 2>&1 && ! promtool check config /etc/prometheus/prometheus.yml; then
       |  cp /etc/prometheus/prometheus.yml.orphera-bak /etc/prometheus/prometheus.yml
       |  echo "promtool rejected the configuration (Prometheus older than 2.43 has no scrape_config_files?); prometheus.yml put back" >&2
       |  exit 1
       |fi
       |systemctl restart prometheus
       |sleep 2
       |if ! curl -sf http://localhost:9090/-/healthy >/dev/null; then
       |  echo "prometheus did not come back healthy - see 'journalctl -xeu prometheus'" >&2
       |  exit 1
       |fi
       |echo "prometheus scrapes the pushgateway at $fqdn:$pushPort""""

  // ---- the nodes -------------------------------------------------------

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

  private def pullScript(images: List[String]): String =
    s"""${Pull.shellFunction(
        "the Image(...) values in telemetry_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin

  private val nodePullScript =
    pullScript(nodeContainers.map(_.image.ref))
  private val computePullScript = pullScript(List(computeContainer.image.ref))

  // Same method as manila_single_node.scala: the last certificate Keystone presents is its CA; trust on first
  // use.
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
    s"""mkdir -p $srcDir $aodhLogDir $ceilometerLogDir $stateDir/aodh/tmp $stateDir/ceilometer/tmp
       |echo "directories ready"""".stripMargin

  // Which lines of the image's evaluator say where it looks for Prometheus; printed, not checked.
  private val evaluatorHintScript =
    s"""podman run --rm --entrypoint sh $aodhApiImage -c 'f=$$(find / -xdev -path "*aodh/evaluator/prometheus.py" 2>/dev/null | head -1); if [ -n "$$f" ]; then echo "$$f:"; grep -n -i "service_type\\|catalog\\|endpoint" "$$f" | head -20; else echo "no prometheus evaluator in the image"; fi' || true"""

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

  // @@HOST@@ is the node's short name, put in by sed on the node. The central agent's file is also written
  // on tst24, where it is tst24.
  private def ceilometerConf: String =
    s"""# Managed by Orphera (telemetry_single_node.scala)
       |[DEFAULT]
       |transport_url = rabbit://ceilometer:${ceilometerRabbitPassword.reveal}@$rabbitIp:5672/ceilometer
       |log_dir = /var/log/kolla/ceilometer
       |use_stderr = true
       |host = @@HOST@@
       |
       |[service_credentials]
       |${credentials(
        "ceilometer",
        ceilometerPassword.reveal,
        "/etc/ceilometer/ssl/ca.crt"
      )}
       |
       |[compute]
       |instance_discovery_method = libvirt_metadata
       |
       |[oslo_concurrency]
       |lock_path = /var/lib/ceilometer/tmp
       |""".stripMargin

  private val pipelineYaml: String =
    s"""---
       |sources:
       |  - name: meter_source
       |    meters:
       |      - "*"
       |    sinks:
       |      - meter_sink
       |sinks:
       |  - name: meter_sink
       |    publishers:
       |      - prometheus://$fqdn:$pushPort/metrics/job/ceilometer/instance/@@HOST@@
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

  private val computePolling: String =
    """---
      |sources:
      |  - name: compute_pollsters
      |    interval: 60
      |    meters:
      |      - cpu
      |      - vcpus
      |      - memory.usage
      |      - network.incoming.bytes
      |      - network.outgoing.bytes
      |      - disk.device.read.bytes
      |      - disk.device.write.bytes
      |""".stripMargin

  // Writes one file with a quoted heredoc.
  private def put(content: String, dest: String): String =
    s"cat > $dest <<'ORPHERA_EOF'\n$content\nORPHERA_EOF\n"

  // The ceilometer files for one agent: configuration, pipeline, polling, written under srcDir and then
  // copied into the Kolla config directory with the CA and the config.json.
  private def ceilometerFilesScript(
      service: String,
      namespace: String,
      polling: String
  ): String =
    put(ceilometerConf, s"$srcDir/ceilometer.conf") +
      put(pipelineYaml, s"$srcDir/pipeline.yaml") +
      put(polling, s"$srcDir/polling.yaml") +
      s"""set -e
         |HOST=$$(hostname -s)
         |sed -i "s/@@HOST@@/$$HOST/" $srcDir/ceilometer.conf $srcDir/pipeline.yaml
         |mkdir -p $confRoot/$service
         |cp $srcDir/ceilometer.conf $srcDir/pipeline.yaml $srcDir/polling.yaml $confRoot/$service/
         |cp $caDir/ca.crt $confRoot/$service/ca.crt
         |cat > $confRoot/$service/config.json <<JSON
         |{"command": "ceilometer-polling --polling-namespaces $namespace --config-file /etc/ceilometer/ceilometer.conf", "config_files": [{"source": "/var/lib/kolla/config_files/ceilometer.conf", "dest": "/etc/ceilometer/ceilometer.conf", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/pipeline.yaml", "dest": "/etc/ceilometer/pipeline.yaml", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/polling.yaml", "dest": "/etc/ceilometer/polling.yaml", "owner": "ceilometer", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/ceilometer/ssl/ca.crt", "owner": "ceilometer", "perm": "0644"}], "permissions": [{"path": "/var/log/kolla/ceilometer", "owner": "ceilometer:kolla", "recurse": true}, {"path": "/var/lib/ceilometer", "owner": "ceilometer:ceilometer", "recurse": true}]}
         |JSON
         |python3 -c "import json; json.load(open('$confRoot/$service/config.json')); print('config.json is valid JSON')"
         |echo "kolla config written for $service on $$HOST"
         |""".stripMargin

  // The Aodh Kolla config: one directory per container. The API runs behind Apache + mod_wsgi; the WSGI
  // script and the Apache flavour are read from the image.
  private val aodhKollaScript =
    put(aodhConf, s"$srcDir/aodh.conf") +
      s"""set -e
         |chmod 600 $srcDir/aodh.conf
         |for svc in api evaluator notifier; do
         |  mkdir -p $confRoot/aodh-$$svc
         |  cp $srcDir/aodh.conf $confRoot/aodh-$$svc/aodh.conf
         |  cp $caDir/ca.crt $confRoot/aodh-$$svc/ca.crt
         |done
         |COMMON='{"source": "/var/lib/kolla/config_files/aodh.conf", "dest": "/etc/aodh/aodh.conf", "owner": "aodh", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/aodh/ssl/ca.crt", "owner": "aodh", "perm": "0644"}'
         |PERMS='"permissions": [{"path": "/var/log/kolla/aodh", "owner": "aodh:kolla", "recurse": true}, {"path": "/var/lib/aodh", "owner": "aodh:aodh", "recurse": true}]'
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
         |cat > $confRoot/aodh-api/aodh-api.conf <<WSGICONF
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
         |API_EXTRA=", {\\"source\\": \\"/var/lib/kolla/config_files/aodh-api.conf\\", \\"dest\\": \\"$$APACHE_CONF\\", \\"owner\\": \\"aodh\\", \\"perm\\": \\"0600\\"}"
         |cat > $confRoot/aodh-api/config.json <<JSON
         |{"command": "$$APACHE_CMD", "config_files": [$$COMMON$$API_EXTRA], $$PERMS}
         |JSON
         |cat > $confRoot/aodh-evaluator/config.json <<JSON
         |{"command": "aodh-evaluator --config-file /etc/aodh/aodh.conf", "config_files": [$$COMMON], $$PERMS}
         |JSON
         |cat > $confRoot/aodh-notifier/config.json <<JSON
         |{"command": "aodh-notifier --config-file /etc/aodh/aodh.conf", "config_files": [$$COMMON], $$PERMS}
         |JSON
         |python3 -c "import json; [json.load(open('$confRoot/aodh-' + s + '/config.json')) for s in ('api', 'evaluator', 'notifier')]; print('config.json files are valid JSON')"
         |echo "kolla config written under $confRoot"
         |""".stripMargin

  // aodh-dbsync runs in a one-off container with aodh.conf bind-mounted (entrypoint overridden, so
  // kolla_set_configs does not run).
  private val dbSyncScript =
    s"""set -e
       |podman run --rm --network host --user root --entrypoint aodh-dbsync -v $srcDir/aodh.conf:/etc/aodh/aodh.conf:ro,z -v $aodhLogDir:/var/log/kolla/aodh:z $aodhApiImage --config-file /etc/aodh/aodh.conf
       |echo "aodh database synced"""".stripMargin

  // ---- the helper ------------------------------------------------------

  // Standard-library Python, as in the other service playbooks. Plain string filled in with replace().
  // Subcommands:
  //   register | unregister | catalog | metrics | alarm-cycle
  // metrics waits (up to 5 minutes) for series of job=ceilometer in Prometheus and prints their names.
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
    deadline = time.time() + 300
    names = []
    while time.time() < deadline:
        p = prom('/api/v1/label/__name__/values', {'match[]': '{job="ceilometer"}'})
        names = p.get('data', [])
        if names:
            break
        time.sleep(10)
    if not names:
        die('no series with job="ceilometer" in prometheus after 5 minutes - check the pushgateway (curl %s:9091/metrics), the scrape job and the ceilometer-central log' % '@@PUSH@@')
    q = prom('/api/v1/query', {'query': 'count({job="ceilometer"})'})
    count = q['data']['result'][0]['value'][1] if q['data']['result'] else '0'
    print('ceilometer series in prometheus: %s; names: %s' % (count, ' '.join(sorted(names))))


def acall(method, path, token, body=None):
    return call(method, AODH + path, body, token)


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
    elif cmd == 'unregister':
        unregister()
    elif cmd == 'catalog':
        catalog()
    elif cmd == 'metrics':
        metrics()
    elif cmd == 'alarm-cycle':
        alarm_cycle()
    else:
        die('usage: orphera-telemetry-api register | unregister | catalog | metrics | alarm-cycle')
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-telemetry-api <<'PYEOF'\n" +
      apiHelperPython
        .replace("@@KS@@", ksFqdn)
        .replace("@@AODH@@", s"http://$fqdn:$aodhPort")
        .replace("@@PROM@@", promUrl)
        .replace("@@PUSH@@", fqdn)
        .replace("@@ADMINPW@@", adminPassword.reveal)
        .replace("@@AODHPW@@", aodhPassword.reveal)
        .replace("@@CEILOMETERPW@@", ceilometerPassword.reveal)
        .replace("@@CA@@", s"$caDir/ca.crt") +
      "\nPYEOF\nchmod 700 /usr/local/sbin/orphera-telemetry-api\necho wrote /usr/local/sbin/orphera-telemetry-api\n"

  // The API answers its version document without a token (200, or 300 for a list of versions).
  private val apiHealthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$aodhPort/)
       |[ "$$CODE" = "200" ] || [ "$$CODE" = "300" ]""".stripMargin

  private val pushgatewayHealthScript =
    s"""curl -sf http://localhost:$pushPort/-/healthy >/dev/null"""

  // ORPHERA_CHECK=1 turns the run into a check: it changes nothing on the node and fails, with a diff, if a
  // unit differs from what this playbook would write.
  private val checkMode = sys.env.get("ORPHERA_CHECK").contains("1")

  private val unitResource = summon[Resource[Container]]

  private def withUnits(
      b: StageBuilder,
      containers: List[Container]
  ): StageBuilder =
    val written = containers.foldLeft(b)((acc, c) =>
      acc.task(s"write ${c.unitName}")(c.writeUnit)
    )
    containers.foldLeft(written)((acc, c) =>
      acc.task(s"start ${c.unitName}")(c.start)
    )

  val playbook: ClusterPlaybook =
    if checkMode then
      clusterPlaybook("telemetry-single-node-check")(
        (nodeContainers
          .foldLeft(stage("check-telemetry", node))((acc, c) =>
            acc.task(s"check ${c.unitName}")(unitResource.check(c))
          ))
          .build,
        stage("check-ceilometer-compute", computeNodes*)
          .task(s"check ${computeContainer.unitName}")(
            unitResource.check(computeContainer)
          )
          .build
      )
    else applyPlaybook

  private def installTelemetry: StageBuilder =
    withUnits(
      stage("install-telemetry", node)
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
        .task("pull the Pushgateway, Ceilometer and Aodh images")(
          Task.RunCommand(List("sh", "-c", nodePullScript), timeoutSeconds = 1800)
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the telemetry directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the telemetry API helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .task("register Aodh, Ceilometer and Prometheus in Keystone")(
          Task.RunCommand(
            List("sh", "-c", "/usr/local/sbin/orphera-telemetry-api register"),
            timeoutSeconds = 120
          )
        )
        .task("show where the Aodh evaluator looks for Prometheus")(
          Task.RunCommand(
            List("sh", "-c", evaluatorHintScript),
            timeoutSeconds = 120
          )
        )
        .task("write the Ceilometer central agent's Kolla config")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              ceilometerFilesScript(
                "ceilometer-central",
                "central",
                centralPolling
              )
            ),
            timeoutSeconds = 120
          )
        )
        .task("write the Aodh Kolla config and the Apache vhost")(
          Task.RunCommand(
            List("sh", "-c", aodhKollaScript),
            timeoutSeconds = 240
          )
        )
        .task("aodh-dbsync")(
          Task.RunCommand(List("sh", "-c", dbSyncScript), timeoutSeconds = 600)
        ),
      nodeContainers
    )

  private def installCompute: StageBuilder =
    withUnits(
      stage("install-ceilometer-compute", computeNodes*)
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
        .task("pull the Ceilometer compute image")(
          Task.RunCommand(
            List("sh", "-c", computePullScript),
            timeoutSeconds = 1800
          )
        )
        .task("fetch Keystone's CA from its TLS handshake")(
          Task.RunCommand(List("sh", "-c", fetchCaScript), timeoutSeconds = 60)
        )
        .task("create the Ceilometer directories")(
          Task.RunCommand(List("sh", "-c", dirsScript))
        )
        .task("write the Ceilometer compute agent's Kolla config")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              ceilometerFilesScript(
                "ceilometer-compute",
                "compute",
                computePolling
              )
            ),
            timeoutSeconds = 120
          )
        ),
      List(computeContainer)
    )

  private def applyPlaybook: ClusterPlaybook =
    clusterPlaybook("telemetry-single-node")(
      stage("telemetry-database", "tst3")
        .task(s"create $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", createDatabaseScript))
        )
        .build,

      stage("telemetry-rabbitmq", rabbitNode)
        .task("create the aodh and ceilometer vhosts and users on RabbitMQ")(
          Task.RunCommand(List("sh", "-c", rabbitScript), timeoutSeconds = 120)
        )
        .build,

      installTelemetry.build,

      installCompute.build,

      stage("telemetry-prometheus", promNode)
        .task("add the Pushgateway scrape job to Prometheus")(
          Task.RunCommand(
            List("sh", "-c", prometheusScript),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("confirm-telemetry-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", pushgatewayHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", apiHealthScript),
            pollIntervalSeconds = 5,
            timeoutSeconds = 120
          )
        )
        .task("wait for Ceilometer series in Prometheus")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-telemetry-api metrics"
            ),
            timeoutSeconds = 420
          )
        )
        .task("make two Prometheus alarms and wait for their states")(
          Task.RunCommand(
            List(
              "sh",
              "-c",
              "/usr/local/sbin/orphera-telemetry-api alarm-cycle"
            ),
            timeoutSeconds = 480
          )
        )
        .task("telemetry confirmed")(
          Task.Debug(
            s"Telemetry up: Aodh at http://$fqdn:$aodhPort ($nodeIp), Pushgateway at $fqdn:$pushPort, ceilometer-central on $node and ceilometer-compute on ${computeNodes.mkString(", ")}, series in Prometheus at $promUrl (job=ceilometer), RabbitMQ vhosts aodh and ceilometer on $rabbitIp:5672, database $dbName via $vip:3306, identity from $ksUrl. Test: manifests/test_telemetry.sh"
          )
        )
        .build
    )
