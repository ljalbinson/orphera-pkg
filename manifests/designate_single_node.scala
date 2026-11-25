// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// OpenStack Designate (DNS as a service) with a BIND9 name server on the same node, Kolla images under
// podman, as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the cluster
// playbook of the same name; a second run changes nothing and restarts nothing, and `--check` shows what
// would change. Modelled on heat_single_node.scala and barbican_single_node.scala, which explain the
// conventions.
//
// Three descriptions, since one description cannot order its nodes. Apply them in this order:
//
//   orphera apply manifests/designate_database.scala     # the designate database and user (tst3)
//   orphera apply manifests/designate_rabbitmq.scala     # the `designate` vhost and user on RabbitMQ (tst10)
//   orphera apply manifests/designate_single_node.scala  # this one (tst16)
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
//   - RabbitMQ running on tst10 (rabbitmq_single_node.scala)
//   - agent on the node; tst9's Kolla cache is optional
//   Ceph is not involved.
//
// How it stays idempotent (as for Heat and Barbican):
//   - Keystone's CA is compared as a certificate with the installed copy each run; each service's copy is
//     refreshed (and the service restarted) when it changes.
//   - designate.conf holds secrets, so it is a secret file, one per service directory. The API's Apache
//     vhost and config.json, and BIND9's config.json, depend on what the image has (the WSGI script, the
//     Apache flavour, the path of `named` and the name of its user), so they are generated into a
//     temporary directory and compared with the installed files.
//   - `designate-manage database sync` is skipped while its stamp matches and the `pools` table exists (a
//     query as the designate user, through the VIP).
//   - The rndc key (shared by BIND9 and the worker) is made once and kept: a new key on a re-run would
//     stop the worker's rndc calls until both sides were restarted. It lives in the BIND9 config directory;
//     the worker's copy is refreshed when it differs.
//   - The default pool is loaded with `designate-manage pool update`, skipped while its stamp (the hash of
//     pools.yaml) matches and the pool `default` is in the database. It runs once central is up, because
//     it talks to central over RabbitMQ, and the worker and mdns start after it (they read the pool when
//     they start). When pools.yaml changes later, the pool is loaded again and the worker and mdns are
//     restarted.
//   - Designate is registered in Keystone before the units start, skipped while the helper's `registered`
//     check passes.
//
// Not done here, on purpose (later): Neutron's DNS integration and the notification sink, which
// need changes to neutron_single_node.scala and nova_single_node.scala.
//
// Facts about Designate this was written from, to check if a run fails: `designate-manage pool update`
// talks to designate-central over RabbitMQ; the keys of pools.yaml are the documented ones (name,
// ns_records, nameservers, targets with masters and options); the Kolla images are the 2025.1 ubuntu-noble
// ones, as for the other services. designate.conf is written as plain text: a typed version needs a
// catalogue of the image's options (manifests/dump_oslo_options.sh designate-central).
//
// Test-only credentials come from the secret store (designate/db, designate/service, designate/rabbit),
// as before.
object designate_single_node extends OrpheraApply:
  import Resources.*

  private val node = "tst16"
  private val fqdn = "tst16.ljalbinson.com"
  private val nodeIp = "10.10.5.28"
  private val apiPort = 9001
  private val mdnsPort = 5354
  private val dnsPort = 53
  private val rndcPort = 953

  override def nodes: List[String] = List(node)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val vip = "10.10.5.100"

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
  private val logDir = "/var/log/kolla/designate"
  private val stateDir = "/var/lib/orphera/designate"
  private val caDir = "/etc/kolla/designate-ca"
  private val caCert = s"$caDir/ca.crt"
  private val ksUrl = s"https://$ksFqdn:5000"
  private val centralDir = s"$confRoot/designate-central"
  private val bindDir = s"$confRoot/designate-backend-bind9"
  private val rndcKey = s"$bindDir/rndc.key"

  // The name of the nameserver in the pool's NS and SOA records: the node itself, with the final dot
  // DNS names need.
  private val nsHostname = s"$fqdn."

  // The six containers as data: the unit file text comes from Container
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
      env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS"),
      // The image's own user cannot open Apache's log files; the API container starts as root.
      user = if service == "designate-api" then Some("root") else None
    )
  private val designateNames = List(
    "designate-central",
    "designate-mdns",
    "designate-api",
    "designate-producer",
    "designate-worker"
  )
  private val designateServices: List[Container] =
    designateNames.map(designateContainer)

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
      Mount(bindDir, "/var/lib/kolla/config_files"),
      Mount(s"$stateDir/bind", "/var/lib/named", Access.ReadWrite)
    ),
    env = List("KOLLA_CONFIG_STRATEGY" -> "COPY_ALWAYS")
  )

  private val allContainers: List[Container] = bind9 :: designateServices
  private def imageRef(service: String): String =
    Image(service, Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val apiImage = imageRef("designate-api")
  private val centralImage = imageRef("designate-central")
  private val workerImage = imageRef("designate-worker")
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

  // The rndc key is made once and kept (see the header).
  private val rndcKeyScript =
    s"""set -e
       |umask 077
       |SECRET=$$(openssl rand -base64 32)
       |cat > $rndcKey <<KEY
       |key "designate" {
       |    algorithm hmac-sha256;
       |    secret "$$SECRET";
       |};
       |KEY
       |echo "rndc key made"""".stripMargin

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

  private val images = allContainers.map(_.image.ref)
  private val pullScript =
    s"""${Pull.shellFunction(
        "the Image(...) values in designate_single_node.scala"
      )}
       |for img in ${images.mkString(" ")}; do
       |  pull_retry $$img || exit 1
       |done
       |echo "images ready"""".stripMargin
  private val imagesPresent =
    s"""for img in ${images.mkString(
        " "
      )}; do podman image exists $$img || exit 1; done"""

  // Keystone's CA: the last certificate it presents (trust on first use),
  // which must be self-signed. `chainTo DIR` leaves DIR/c1.pem ... DIR/cN.pem,
  // each holding the certificate only: the text s_client prints after the
  // last one (bytes read and written, session ticket) differs on every
  // handshake, and would make the comparison with the installed copy fail.
  private val chainTo =
    s"""chainTo() { openssl s_client -connect $ksFqdn:5000 -servername $ksFqdn -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""

  private val trustTarget =
    """if command -v update-ca-certificates >/dev/null 2>&1; then t=/usr/local/share/ca-certificates/orphera-test-ca.crt; else t=/etc/pki/ca-trust/source/anchors/orphera-test-ca.crt; fi"""

  // Current when the installed CA is the one Keystone presents now, and is in
  // the system trust store.
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

  // The files every Designate service's config.json copies in, and the ownership it sets.
  private val commonFiles =
    """{"source": "/var/lib/kolla/config_files/designate.conf", "dest": "/etc/designate/designate.conf", "owner": "designate", "perm": "0600"}, {"source": "/var/lib/kolla/config_files/ca.crt", "dest": "/etc/designate/ssl/ca.crt", "owner": "designate", "perm": "0644"}"""
  private val permissions =
    """"permissions": [{"path": "/var/log/kolla/designate", "owner": "designate:kolla", "recurse": true}, {"path": "/var/lib/designate", "owner": "designate:designate", "recurse": true}]"""
  // central, mdns and producer: the program of the same name.
  private def plainConfigJson(name: String): String =
    s"""{"command": "$name --config-file /etc/designate/designate.conf", "config_files": [$commonFiles], $permissions}
       |""".stripMargin
  private val workerConfigJson =
    s"""{"command": "designate-worker --config-file /etc/designate/designate.conf", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/rndc.key", "dest": "/etc/designate/rndc.key", "owner": "designate", "perm": "0600"}], $permissions}
       |""".stripMargin

  // The API runs behind Apache + mod_wsgi like Placement and Nova when the image has the WSGI script, else
  // as the standalone designate-api. Which one is read from the image. The vhost (WSGI case only) and
  // config.json are generated into `out` (the real directory, or a temporary one for the guard to compare
  // with).
  private def kollaApiScript(out: String): String =
    s"""set -e
       |WSGI=$$(podman run --rm --entrypoint sh $apiImage -c 'command -v designate-api-wsgi' || true)
       |if [ -n "$$WSGI" ]; then
       |  if podman run --rm --entrypoint sh $apiImage -c 'test -d /etc/apache2/conf-enabled'; then
       |    APACHE_CONF=/etc/apache2/conf-enabled/designate-api.conf
       |    APACHE_CMD="apache2 -DFOREGROUND"
       |    # apache2ctl loads /etc/apache2/envvars (APACHE_RUN_DIR and the rest); plain apache2 does not
       |    if podman run --rm --entrypoint sh $apiImage -c 'command -v apache2ctl' >/dev/null 2>&1; then APACHE_CMD="apache2ctl -DFOREGROUND"; fi
       |  else
       |    APACHE_CONF=/etc/httpd/conf.d/designate-api.conf
       |    APACHE_CMD="/usr/sbin/httpd -DFOREGROUND"
       |  fi
       |  echo "api: wsgi script $$WSGI; apache config $$APACHE_CONF"
       |cat > $out/designate-api.conf <<WSGICONF
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
       |cat > $out/config.json <<JSON
       |{"command": "$$APACHE_CMD", "config_files": [$commonFiles, {"source": "/var/lib/kolla/config_files/designate-api.conf", "dest": "$$APACHE_CONF", "owner": "designate", "perm": "0600"}], $permissions}
       |JSON
       |else
       |  echo "api: no designate-api-wsgi in $apiImage; running the standalone designate-api"
       |cat > $out/config.json <<JSON
       |{"command": "designate-api --config-file /etc/designate/designate.conf", "config_files": [$commonFiles], $permissions}
       |JSON
       |fi
       |echo "kolla config written under $out"""".stripMargin

  // BIND9's config.json: the path of `named` and the name of its user (`bind` or `named`) are read from the
  // image.
  private def kollaBindScript(out: String): String =
    s"""set -e
       |NAMED=$$(podman run --rm --entrypoint sh $bindImage -c 'command -v named' || true)
       |if [ -z "$$NAMED" ]; then echo "named not found in $bindImage" >&2; exit 1; fi
       |BINDUSER=$$(podman run --rm --entrypoint sh $bindImage -c 'id -u bind >/dev/null 2>&1 && echo bind || echo named')
       |cat > $out/config.json <<JSON
       |{"command": "$$NAMED -f -u $$BINDUSER -c /etc/bind/named.conf", "config_files": [{"source": "/var/lib/kolla/config_files/named.conf", "dest": "/etc/bind/named.conf", "owner": "$$BINDUSER", "perm": "0640"}, {"source": "/var/lib/kolla/config_files/rndc.key", "dest": "/etc/bind/rndc.key", "owner": "$$BINDUSER", "perm": "0640"}], "permissions": [{"path": "/var/lib/named", "owner": "$$BINDUSER:$$BINDUSER", "recurse": true}]}
       |JSON
       |echo "kolla config written under $out (bind user $$BINDUSER)"""".stripMargin

  // Current when every file the script generates is installed with the same content.
  private def kollaCurrent(generate: String, dir: String): String =
    s"""T=$$(mktemp -d)
       |$generate >/dev/null
       |R=0
       |for f in $$T/*; do cmp -s $$f $dir/$$(basename $$f) || R=1; done
       |rm -rf $$T
       |exit $$R""".stripMargin

  // The worker cannot manage BIND9 without rndc in its image.
  private val workerHasRndc =
    s"""podman run --rm --entrypoint sh $workerImage -c 'command -v rndc' >/dev/null 2>&1"""
  private val workerRndcMissing =
    s"""echo "rndc is not in $workerImage; the worker cannot manage BIND9 without it" >&2
       |exit 1""".stripMargin

  // designate-manage runs in a one-off container with designate.conf and pools.yaml bind-mounted (entrypoint
  // overridden, so kolla_set_configs does not run). Both live in central's config directory.
  private def manage(args: String): String =
    s"podman run --rm --network host --user root --entrypoint designate-manage -v $centralDir/designate.conf:/etc/designate/designate.conf:ro,z -v $centralDir/pools.yaml:/etc/designate/pools.yaml:ro,z -v $logDir:/var/log/kolla/designate:z $centralImage $args"

  // Runs a Python snippet in the central image, with its own interpreter (the one named on the first line
  // of designate-manage), which has pymysql. `body` is indented lines inside a `try`; the connection is `c`.
  // Exit 0 when the snippet ends normally, 1 on any error.
  private def dbProbe(body: String): String =
    s"""podman run --rm -i --network host --entrypoint sh $centralImage -c 'exec $$(head -1 "$$(command -v designate-manage)" | sed "s/^#!//") -' >/dev/null 2>&1 <<'PYEOF'
       |import sys, pymysql
       |try:
       |    c = pymysql.connect(host='$vip', user='$dbUser', password='${dbPassword.reveal}', database='$dbName')
       |$body
       |except Exception:
       |    sys.exit(1)
       |PYEOF""".stripMargin

  private val dbSyncScript =
    s"""set -e
       |${manage("database sync")}
       |echo "designate database synced"""".stripMargin
  private val dbStamp = Resource.sha256(dbSyncScript + designateConf)
  // True when the designate database, queried as the designate user through the VIP, has the `pools` table.
  private val dbInState =
    dbProbe("    c.cursor().execute('SELECT COUNT(*) FROM pools')")
  // Current when the stamp matches AND the database is in that state.
  private val dbCurrent =
    s"""[ "$$(cat $stateDir/db.stamp 2>/dev/null)" = "$dbStamp" ] &&
       |$dbInState""".stripMargin
  private val dbSyncWithStamp =
    s"""$dbSyncScript
       |echo $dbStamp > $stateDir/db.stamp""".stripMargin

  // `pool update` calls designate-central over RabbitMQ, so central must be up; it can take a few seconds
  // after the unit starts.
  private val poolRun =
    s"""set -e
       |ok=""
       |for i in $$(seq 1 24); do
       |  if ${manage("pool update --file /etc/designate/pools.yaml")}; then ok=1; break; fi
       |  echo "pool update not accepted yet (attempt $$i of 24); central may still be starting"
       |  sleep 5
       |done
       |if [ -z "$$ok" ]; then echo "designate-manage pool update failed; see the output above and: journalctl -u orphera-designate-central" >&2; exit 1; fi
       |echo "pool loaded"""".stripMargin
  private val poolStamp = Resource.sha256(poolRun + poolsYaml)
  // True when the pool `default` is in the database.
  private val poolInDatabase =
    dbProbe(
      "    cur = c.cursor()\n    cur.execute(\"SELECT 1 FROM pools WHERE name = 'default'\")\n    sys.exit(0 if cur.fetchone() else 1)"
    )
  private val poolCurrent =
    s"""[ "$$(cat $stateDir/pool.stamp 2>/dev/null)" = "$poolStamp" ] &&
       |$poolInDatabase""".stripMargin
  private val poolWithStamp =
    s"""$poolRun
       |echo $poolStamp > $stateDir/pool.stamp""".stripMargin

  // Standard-library Python, as in the other services. Plain string filled in with replace().
  // Subcommands:
  //   register | registered | unregister | catalog | zone-list | zone-create <name> <email> | zone-wait <id> |
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


def registered():
    # Exit 0 only when everything register() makes is in place: the designate user can get a token in project
    # service (so it holds a role there), and the dns service has its three endpoints at the right URL.
    # Makes no change.
    get_token('designate', DESIGNATE_PW, 'service')
    token, _ = get_token('admin', ADMIN_PW, 'admin')
    service = find(token, '/services?type=dns', 'services')
    if service is None:
        die('no service of type dns')
    for iface in ('public', 'internal', 'admin'):
        ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
        if ep is None or ep['url'] != DNS:
            die('endpoint %s of dns missing or wrong' % iface)
    print('designate is registered in keystone')


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
    elif cmd == 'registered':
        registered()
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
        die('usage: orphera-designate-api register | registered | unregister | catalog | zone-list | zone-create <name> <email> | zone-wait <id> | record-create <zone-id> <name> <ipv4> | record-wait <zone-id> <id> | zone-delete <id> | zone-cycle')
"""

  private val helper =
    apiHelperPython
      .replace("@@KS@@", ksFqdn)
      .replace("@@DNS@@", s"http://$fqdn:$apiPort")
      .replace("@@NSIP@@", nodeIp)
      .replace("@@ADMINPW@@", adminPassword.reveal)
      .replace("@@DESIGNATEPW@@", designatePassword.reveal)
      .replace("@@CA@@", caCert)
  private val helperPath = "/usr/local/sbin/orphera-designate-api"

  // The API accepts an admin token from Keystone and reaches its database.
  private val acceptsToken = s"$helperPath zone-list >/dev/null 2>&1"
  private val waitAcceptsToken =
    s"""for i in $$(seq 1 24); do $helperPath zone-list && exit 0; sleep 5; done
       |$helperPath zone-list
       |exit 1""".stripMargin

  // One Designate service: its resources (written before it starts) and the ones that decide its order.
  private final case class Svc(
      name: String,
      unit: ApplyResource,
      resources: List[ApplyResource],
      deps: List[ApplyResource],
      conf: ApplyResource
  )

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
      "pull the Designate and BIND9 images",
      pullScript,
      unless = imagesPresent,
      timeout = 2400
    ).after(podman, mirror)

    val caDirectory = directory(caDir, mode = "0755").after(podman)
    val ca = command(
      "fetch Keystone's CA from its TLS handshake",
      caScript,
      unless = caCurrent,
      timeout = 60
    ).after(caDirectory, tools(1))

    val logDirectory = directory(logDir)
    val stateDirectory = directory(stateDir)
    val bindState = directory(s"$stateDir/bind").after(stateDirectory)

    // The units come first so that what they depend on can name them in `notifies`.
    val bindUnit = service(bind9.unitName)
    val unitOf: Map[String, ApplyResource] =
      designateNames
        .zip(designateServices)
        .map((n, c) => n -> service(c.unitName))
        .toMap

    // ---- BIND9: its config directory, named.conf, the rndc key (made once) and config.json ----
    val bindDirRes = directory(bindDir, mode = "0755")
    val namedFile = file(
      s"$bindDir/named.conf",
      namedConf,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(bindDirRes).notifies(bindUnit)
    val rndcMade = command(
      "make the rndc key, once",
      rndcKeyScript,
      unless = s"[ -s $rndcKey ]"
    ).after(bindDirRes, tools(1)).notifies(bindUnit)
    val bindJson = command(
      "write the Kolla config.json for designate-backend-bind9",
      kollaBindScript(bindDir),
      unless = kollaCurrent(kollaBindScript("$T"), bindDir),
      timeout = 180
    ).after(pulled, bindDirRes).notifies(bindUnit)
    val bindUnitFile = file(
      bind9.unitPath,
      bind9.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(bindUnit)
    val bindResources =
      List(bindState, bindDirRes, namedFile, rndcMade, bindJson, bindUnitFile)

    // ---- the five Designate services: a config directory each, mounted by Kolla at
    // /var/lib/kolla/config_files ----
    val svcs = designateNames.zip(designateServices).map { (name, c) =>
      val dir = s"$confRoot/$name"
      val unitService = unitOf(name)
      val dirRes = directory(dir, mode = "0755")
      val conf = secretFile(
        s"$dir/designate.conf",
        Secret(
          designateConf
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
      val kollaJson = name match
        case "designate-api" =>
          command(
            s"write the Apache vhost and the Kolla config.json for $name",
            kollaApiScript(dir),
            unless = kollaCurrent(kollaApiScript("$T"), dir),
            timeout = 180
          ).after(pulled, dirRes, logDirectory).notifies(unitService)
        case "designate-worker" =>
          file(
            s"$dir/config.json",
            workerConfigJson,
            mode = "0644",
            owner = "root",
            group = "root"
          ).after(dirRes).notifies(unitService)
        case _ =>
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
      // Central keeps pools.yaml in its directory (the pool update mounts it from there); the worker gets
      // the rndc key.
      val extras: List[ApplyResource] = name match
        case "designate-central" =>
          List(
            file(
              s"$dir/pools.yaml",
              poolsYaml,
              mode = "0644",
              owner = "root",
              group = "root"
            ).after(dirRes)
          )
        case "designate-worker" =>
          val rndcHere = fileCopy(s"$dir/rndc.key", rndcKey, mode = "0600")
            .after(rndcMade, dirRes)
            .notifies(unitService)
          val rndcInImage = command(
            "the worker image has rndc",
            workerRndcMissing,
            unless = workerHasRndc
          ).after(pulled)
          List(rndcHere, rndcInImage)
        case _ => Nil
      Svc(
        name,
        unitService,
        List(dirRes, conf, kollaCa, kollaJson, unitFile) ++ extras,
        List(conf, kollaCa, kollaJson, unitFile) ++ extras,
        conf
      )
    }
    val byName = svcs.map(s => s.name -> s).toMap

    // The one-off container mounts central's designate.conf and pools.yaml, so both must exist first.
    val dbSync = command(
      "designate-manage database sync",
      dbSyncWithStamp,
      unless = dbCurrent,
      timeout = 600
    ).after(
      (pulled :: logDirectory :: stateDirectory :: byName(
        "designate-central"
      ).deps)*
    )

    val helperFile = secretFile(
      helperPath,
      Secret(helper), // it holds the admin and designate passwords
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // Registration comes before the services start.
    val registered = command(
      "register Designate in Keystone",
      s"$helperPath register",
      unless = s"$helperPath registered >/dev/null 2>&1",
      timeout = 120
    ).after(helperFile, ca)

    def start(name: String, more: ApplyResource*): ApplyResource =
      val s = byName(name)
      s.unit.after((dbSync :: registered :: (s.deps ++ more))*)

    val bindRunning = bindUnit.after(
      (pulled :: bindResources)*
    )
    val centralRunning = start("designate-central")
    // The pool is loaded once central answers; the worker and mdns read it when they start, and are
    // restarted when a later change of pools.yaml loads it again.
    val pool = command(
      "load the default pool (designate-manage pool update)",
      poolWithStamp,
      unless = poolCurrent,
      timeout = 300
    ).after(
      (centralRunning :: stateDirectory :: byName("designate-central").deps)*
    ).notifies(unitOf("designate-worker"), unitOf("designate-mdns"))
    val running = List(
      bindRunning,
      centralRunning,
      start("designate-mdns", pool),
      start("designate-api"),
      start("designate-producer"),
      start("designate-worker", pool, bindRunning)
    )

    // The API answers its version document without a token (200, or 300 for a list of versions), and BIND9
    // accepts connections on port 53 of the node's address.
    val apiAnswers = waitForHttp(
      "designate-api answers on its port",
      s"http://localhost:$apiPort/",
      status = List(200, 300),
      timeout = 120,
      hint = s"see 'journalctl -xeu orphera-designate-api' and $logDir"
    ).after(running*)
    val bindAnswers = waitForPort(
      "BIND9 answers on its port",
      nodeIp,
      dnsPort,
      timeout = 120,
      hint = s"see 'journalctl -xeu ${bind9.unitName}'"
    ).after(running*)
    val authenticating = command(
      "the API accepts an admin token and reaches its database",
      waitAcceptsToken,
      unless = acceptsToken,
      timeout = 180
    ).after((apiAnswers :: bindAnswers :: running)*)

    tools ++ List(
      mirrorDir,
      mirror,
      pulled,
      caDirectory,
      ca,
      logDirectory,
      stateDirectory
    ) ++ bindResources ++ svcs.flatMap(_.resources) ++
      List(dbSync, helperFile, registered, pool) ++ running ++
      List(apiAnswers, bindAnswers, authenticating)
