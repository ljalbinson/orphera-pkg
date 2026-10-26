// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// DRAFT. Builds the Kolla images from source on tst21 (10.10.5.33), with caches so that a rebuild downloads
// next to nothing, and serves them from a writable registry that the other nodes pull from.
//
// What runs on tst21:
//   - kolla-registry  registry:2 on port 5000 (writable; kolla_cache.scala on tst9 is a read-only proxy of
//                     quay.io and cannot take pushes). Built images are pushed as
//                     tst21.ljalbinson.com:5000/openstack.kolla/<name>:2025.1-ubuntu-noble, the same
//                     repository and tag the playbooks pull from quay.io.
//   - nginx           port 8081, serves /srv/kolla-sources: one tarball per OpenStack project
//                     (<project>-stable-2025.1.tar.gz from tarballs.opendev.org), fetched once.
//   - devpi           port 3141, a caching mirror of PyPI; the builds install their Python packages from it.
//   - kolla-build     in /opt/kolla-build (a venv), run with podman through its socket.
//   - the apt cache on tst11 is used by the builds as an HTTP proxy (build argument http_proxy).
//   - the Ubuntu base image is copied once into the local registry (library/ubuntu:24.04) and the builds
//     start from that copy.
//
// Use, on tst21 (the helper is /usr/local/sbin/orphera-kolla-build):
//   orphera-kolla-build status               what is cached, what is built, the source checksums
//   orphera-kolla-build sources              fetch the source tarballs that are missing
//   orphera-kolla-build sources refresh      fetch newer ones (only changed tarballs are downloaded)
//   orphera-kolla-build build [--refresh] [image ...]   build, push; no names = every image in
//                                            /etc/kolla-build/images.txt (edit that file to change the set)
//   Run from scala0:  orphera run sudo orphera-kolla-build build --nodes tst21
// The build pins nothing by commit: it builds the tarballs in the cache, and they change only on
// `sources refresh` or `build --refresh`. /srv/kolla-sources/MANIFEST lists their sha256 sums and
// /var/lib/orphera/kolla-build/last-build.txt records the sums of each build.
//
// How the nodes use it: the playbooks that pull Kolla images (keystone_single_node.scala and the other
// OpenStack ones) point podman at tst21's registry first, then tst9, then quay.io. An image that has not
// been built is simply not found there, and podman goes on to the next, so the nodes work before the
// first build and with images built only in part.
//
// Disk and time: 200 GB disk (config/tst21.yaml); a full set is large, and the first build takes hours
// on 4 CPUs. Later builds reuse the layers and the caches.
//
// Facts this was written from, to check when the first run fails: kolla 2025.1 is version 20.x and has a
// podman engine (`engine = podman`, needs the `podman` Python package and the podman socket); the
// kolla-build.conf option names (base_image, tag, namespace, registry, push, build_args); the section
// names for source locations ([<project>-base], [horizon], [openstack-base] for the requirements); the
// opendev branch tarball URLs; that build_args is a list of key:value pairs. Not compiled where this
// was written; the first run on scala0 is the first compile.
object kolla_build extends OrpheraClusterPlaybook:

  private val node = "tst21"
  private val fqdn = "tst21.ljalbinson.com"
  private val registryPort = 5000
  private val sourcesPort = 8081
  private val devpiPort = 3141
  private val aptProxy = "http://tst11.ljalbinson.com:3142"

  private val dataDir = "/var/lib/orphera/kolla-registry"

  private val registryContainer = Container(
    name = ContainerName("kolla-registry"),
    image = ExternalImage("docker.io/library/registry:2"),
    mounts = List(Mount(dataDir, "/var/lib/registry", Access.ReadWrite)),
    env = List("REGISTRY_STORAGE_DELETE_ENABLED" -> "true"),
    network = Network.Published,
    ports = List(Port(registryPort, 5000)),
    description =
      Some("Writable Kolla image registry (built images) managed by Orphera"),
    settleSeconds = 5
  )

  // Images built by default; written to /etc/kolla-build/images.txt once, then left to the user.
  private val images = List(
    "keystone",
    "glance-api",
    "placement-api",
    "nova-api",
    "nova-scheduler",
    "nova-conductor",
    "nova-novncproxy",
    "nova-compute",
    "nova-libvirt",
    "neutron-server",
    "neutron-openvswitch-agent",
    "neutron-dhcp-agent",
    "openvswitch-db-server",
    "openvswitch-vswitchd",
    "cinder-api",
    "cinder-scheduler",
    "cinder-volume",
    "horizon",
    "designate-api",
    "designate-central",
    "designate-worker",
    "designate-producer",
    "designate-mdns",
    "designate-backend-bind9",
    "heat-api",
    "heat-api-cfn",
    "heat-engine",
    "barbican-api",
    "barbican-worker",
    "manila-api",
    "manila-scheduler",
    "manila-share"
  )

  private val projects =
    List(
      "requirements",
      "keystone",
      "glance",
      "placement",
      "nova",
      "neutron",
      "cinder",
      "horizon",
      "designate",
      "heat",
      "barbican",
      "manila"
    )

  private val hostPackages = List(
    "podman",
    "skopeo",
    "python3",
    "python3-venv",
    "python3-pip",
    "git",
    "curl",
    "nginx",
    "ca-certificates"
  )

  private val waitAptIdleScript =
    """end=$(( $(date +%s) + 900 ))
      |while fuser /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/lib/apt/lists/lock >/dev/null 2>&1; do
      |  [ "$(date +%s)" -lt "$end" ] || { echo "apt/dpkg still busy after 15 minutes" >&2; exit 1; }
      |  sleep 5
      |done
      |echo "apt and dpkg idle"""".stripMargin

  private val pullRegistryScript =
    s"""${Pull.shellFunction(
        "the registry image name in kolla_build.scala; the pull is from Docker Hub"
      )}
       |pull_retry ${registryContainer.image.ref} || exit 1
       |echo "registry image ready"""".stripMargin

  // podman may talk to the local registry over plain HTTP (the registry is on this LAN, as tst9's is).
  private val registriesConf =
    s"""# Managed by Orphera (kolla_build.scala)
       |[[registry]]
       |location = "$fqdn:$registryPort"
       |insecure = true
       |
       |[[registry]]
       |location = "localhost:$registryPort"
       |insecure = true
       |""".stripMargin

  private val nginxConf =
    s"""# Managed by Orphera (kolla_build.scala)
       |server {
       |    listen $sourcesPort;
       |    server_name _;
       |    root /srv/kolla-sources;
       |    autoindex on;
       |}
       |""".stripMargin

  private val devpiUnit =
    s"""[Unit]
       |Description=devpi PyPI cache (managed by Orphera)
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStart=/opt/devpi/bin/devpi-server --serverdir /var/lib/orphera/devpi --host 0.0.0.0 --port $devpiPort
       |Restart=always
       |
       |[Install]
       |WantedBy=multi-user.target
       |""".stripMargin

  private val setupScript =
    s"""set -e
       |mkdir -p /srv/kolla-sources /etc/kolla-build /var/lib/orphera/kolla-build /var/lib/orphera/devpi $dataDir
       |if [ ! -x /opt/devpi/bin/devpi-server ]; then
       |  python3 -m venv /opt/devpi
       |  /opt/devpi/bin/pip install --quiet devpi-server
       |fi
       |if [ ! -f /var/lib/orphera/devpi/.serverversion ]; then
       |  /opt/devpi/bin/devpi-init --serverdir /var/lib/orphera/devpi
       |fi
       |if [ ! -f /etc/kolla-build/images.txt ]; then
       |  printf '%s\\n' ${images.mkString(" ")} > /etc/kolla-build/images.txt
       |fi
       |rm -f /etc/nginx/sites-enabled/default
       |echo "directories and devpi ready"""".stripMargin

  private val startServicesScript =
    s"""set -e
       |systemctl daemon-reload
       |systemctl enable --now orphera-devpi
       |systemctl enable --now podman.socket
       |nginx -t
       |systemctl enable nginx
       |systemctl restart nginx
       |echo "devpi, podman.socket and nginx running"""".stripMargin

  // devpi is started before the kolla-build install above would need it, so that part is its own task
  // after the services are up.
  private val installKollaScript =
    s"""set -e
       |if [ ! -x /opt/kolla-build/bin/kolla-build ]; then
       |  for i in 1 2 3 4 5 6 7 8 9 10; do
       |    curl -sf -o /dev/null http://localhost:$devpiPort/ && break
       |    sleep 3
       |  done
       |  python3 -m venv /opt/kolla-build
       |  /opt/kolla-build/bin/pip install --quiet --index-url http://localhost:$devpiPort/root/pypi/+simple/ --trusted-host localhost 'kolla>=20,<21' podman
       |fi
       |/opt/kolla-build/bin/kolla-build --version
       |echo "kolla-build installed"""".stripMargin

  private val helper: String =
    """#!/usr/bin/env bash
set -euo pipefail

FQDN='@@FQDN@@'
REG_PORT=@@REGPORT@@
SRC_PORT=@@SRCPORT@@
DEVPI_PORT=@@DEVPIPORT@@
APT_PROXY='@@APTPROXY@@'
BRANCH='stable-2025.1'
TAG='2025.1-ubuntu-noble'
PROJECTS='@@PROJECTS@@'
SRC=/srv/kolla-sources
CONF=/etc/kolla-build/kolla-build.conf
STATE=/var/lib/orphera/kolla-build
KB=/opt/kolla-build/bin/kolla-build

url_of() { echo "https://tarballs.opendev.org/openstack/$1/$1-$BRANCH.tar.gz"; }
file_of() { echo "$SRC/$1-$BRANCH.tar.gz"; }

fetch_one() {
  local p="$1" mode="$2" f
  f=$(file_of "$p")
  if [ -s "$f" ] && [ "$mode" != refresh ]; then return 0; fi
  rm -f "$f.new"
  if [ -s "$f" ]; then
    curl -fsSL -R -z "$f" -o "$f.new" "$(url_of "$p")"
  else
    curl -fsSL -R -o "$f.new" "$(url_of "$p")"
  fi
  if [ -s "$f.new" ]; then mv "$f.new" "$f"; echo "fetched $p"; else rm -f "$f.new"; echo "unchanged $p"; fi
}

sources() {
  local mode="${1:-missing}" p
  for p in $PROJECTS; do fetch_one "$p" "$mode"; done
  (cd "$SRC" && sha256sum ./*.tar.gz | sed 's# \./# #' > MANIFEST)
  echo "source tarballs in $SRC: $(ls "$SRC"/*.tar.gz | wc -l)"
}

base_image() {
  if ! curl -sf "http://localhost:$REG_PORT/v2/library/ubuntu/tags/list" | grep -q '"24.04"'; then
    skopeo copy --dest-tls-verify=false docker://docker.io/library/ubuntu:24.04 "docker://localhost:$REG_PORT/library/ubuntu:24.04"
  fi
  echo "base image in the local registry"
}

write_conf() {
  {
    echo "{% extends parent_template %}"
    echo "{% block base_pip_conf %}"
    echo "ENV PIP_INDEX_URL=http://$FQDN:$DEVPI_PORT/root/pypi/+simple/"
    echo "ENV PIP_TRUSTED_HOST=$FQDN"
    echo "{% endblock %}"
  } > /etc/kolla-build/template-override.j2
  {
    echo "# Managed by Orphera (kolla_build.scala): regenerated by every build"
    echo "[DEFAULT]"
    echo "engine = podman"
    echo "base = ubuntu"
    echo "base_image = $FQDN:$REG_PORT/library/ubuntu"
    echo "base_tag = 24.04"
    echo "namespace = openstack.kolla"
    echo "registry = $FQDN:$REG_PORT"
    echo "tag = $TAG"
    echo "push = true"
    echo "build_args = http_proxy:$APT_PROXY,https_proxy:,no_proxy:$FQDN"
    echo "template_override = /etc/kolla-build/template-override.j2"
    local p section
    for p in $PROJECTS; do
      case "$p" in
        requirements) section=openstack-base ;;
        horizon) section=horizon ;;
        *) section="$p-base" ;;
      esac
      echo
      echo "[$section]"
      echo "type = url"
      echo "location = http://$FQDN:$SRC_PORT/$p-$BRANCH.tar.gz"
    done
  } > "$CONF"
}

build() {
  local refresh=missing
  if [ "${1:-}" = "--refresh" ]; then refresh=refresh; shift; fi
  sources "$refresh"
  base_image
  write_conf
  local list
  if [ "$#" -gt 0 ]; then list="$*"; else list=$(tr '\n' ' ' < /etc/kolla-build/images.txt); fi
  echo "building: $list"
  export CONTAINER_HOST=unix:///run/podman/podman.sock
  "$KB" --config-file "$CONF" $list
  {
    echo "built $(date -u +%FT%TZ): $list"
    cat "$SRC/MANIFEST"
  } > "$STATE/last-build.txt"
  echo "build finished; $STATE/last-build.txt has the source checksums"
}

status() {
  echo "== source tarballs ($SRC)"; ls -l --time-style=long-iso "$SRC"/*.tar.gz 2>/dev/null | awk '{print $6, $7, $5, $8}' || true
  echo "== registry catalog"; curl -s "http://localhost:$REG_PORT/v2/_catalog"; echo
  echo "== last build"; head -1 "$STATE/last-build.txt" 2>/dev/null || echo "none yet"
  echo "== caches"; du -sh "$SRC" /var/lib/orphera/devpi /var/lib/orphera/kolla-registry 2>/dev/null
}

case "${1:-}" in
  sources) shift; sources "${1:-missing}" ;;
  build) shift; build "$@" ;;
  status) status ;;
  *) echo "usage: orphera-kolla-build status | sources [refresh] | build [--refresh] [image ...]" >&2; exit 2 ;;
esac
"""

  private val helperScript =
    "cat > /usr/local/sbin/orphera-kolla-build <<'HELPEREOF'\n" +
      helper
        .replace("@@FQDN@@", fqdn)
        .replace("@@REGPORT@@", registryPort.toString)
        .replace("@@SRCPORT@@", sourcesPort.toString)
        .replace("@@DEVPIPORT@@", devpiPort.toString)
        .replace("@@APTPROXY@@", aptProxy)
        .replace("@@PROJECTS@@", projects.mkString(" ")) +
      "HELPEREOF\nchmod 755 /usr/local/sbin/orphera-kolla-build\necho wrote /usr/local/sbin/orphera-kolla-build\n"

  private val healthScript =
    s"""curl -sf http://localhost:$registryPort/v2/ >/dev/null &&
       |curl -sf http://localhost:$sourcesPort/ >/dev/null &&
       |curl -sf http://localhost:$devpiPort/ >/dev/null""".stripMargin

  // Fetches the source tarballs and copies the base image, so the first build starts with warm caches.
  private val warmScript =
    """set -e
      |/usr/local/sbin/orphera-kolla-build sources
      |mkdir -p /var/lib/orphera/kolla-build
      |if ! curl -sf http://localhost:5000/v2/library/ubuntu/tags/list | grep -q '"24.04"'; then
      |  skopeo copy --dest-tls-verify=false docker://docker.io/library/ubuntu:24.04 docker://localhost:5000/library/ubuntu:24.04
      |fi
      |/usr/local/sbin/orphera-kolla-build status""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kolla-build")(
      stage("install-kolla-build", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install podman, nginx and tools")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("pull the registry image")(
          Task.RunCommand(
            List("sh", "-c", pullRegistryScript),
            timeoutSeconds = 600
          )
        )
        .task("let podman use the local registry over HTTP")(
          Task.WriteFile(
            content = registriesConf,
            dest =
              "/etc/containers/registries.conf.d/orphera-kolla-registry.conf",
            owner = "root",
            group = "root",
            mode = 420
          )
        )
        .task("nginx site for the source tarballs")(
          Task.WriteFile(
            content = nginxConf,
            dest = "/etc/nginx/conf.d/orphera-kolla-sources.conf",
            owner = "root",
            group = "root",
            mode = 420
          )
        )
        .task("devpi unit")(
          Task.WriteFile(
            content = devpiUnit,
            dest = "/etc/systemd/system/orphera-devpi.service",
            owner = "root",
            group = "root",
            mode = 420
          )
        )
        .task("create directories, install devpi, write the image list")(
          Task.RunCommand(List("sh", "-c", setupScript), timeoutSeconds = 900)
        )
        .task(s"write ${registryContainer.unitName}")(
          registryContainer.writeUnit
        )
        .task(s"start ${registryContainer.unitName}")(registryContainer.start)
        .task("start devpi, podman.socket and nginx")(
          Task.RunCommand(
            List("sh", "-c", startServicesScript),
            timeoutSeconds = 120
          )
        )
        .task("install kolla-build through the devpi cache")(
          Task.RunCommand(
            List("sh", "-c", installKollaScript),
            timeoutSeconds = 900
          )
        )
        .task("write the kolla-build helper")(
          Task.RunCommand(List("sh", "-c", helperScript))
        )
        .build,

      stage("confirm-kolla-build-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 3,
            timeoutSeconds = 120
          )
        )
        .task("fetch the source tarballs and the base image")(
          Task.RunCommand(List("sh", "-c", warmScript), timeoutSeconds = 1800)
        )
        .task("kolla builder confirmed")(
          Task.Debug(
            s"Kolla builder up on $fqdn: registry :$registryPort, sources :$sourcesPort, pip cache :$devpiPort. Build with: orphera run sudo orphera-kolla-build build --nodes $node"
          )
        )
        .build
    )
