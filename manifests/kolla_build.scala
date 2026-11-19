// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// Sets up the Kolla image builder on tst21 (10.10.5.33) as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md; it replaced the cluster playbook of the same name): kolla-build, with caches so that a
// rebuild downloads next to nothing, and a writable registry that the other nodes pull from. A second run
// changes nothing and `--check` shows what would change.
//
//   orphera apply manifests/kolla_build.scala --check
//   orphera apply manifests/kolla_build.scala
//
// Each step that changes the node is a command with a guard that tests what it makes (a file it creates, the
// tarballs and the base image being cached, the three services answering), so a second run does nothing.
// /etc/kolla-build/images.txt is written once and never again: it is the user's list afterwards.
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
// was written. A build of keystone from the cached sources has been run on the lab and pushed to the
// registry, so the options in the helper's kolla-build.conf work for it; the other images are not built yet.
object kolla_build extends OrpheraApply:
  import Resources.*

  private val node = "tst21"
  private val fqdn = "tst21.ljalbinson.com"
  private val registryPort = 5000
  private val sourcesPort = 8081
  private val devpiPort = 3141
  private val aptProxy = "http://tst11.ljalbinson.com:3142"

  private val dataDir = "/var/lib/orphera/kolla-registry"
  // Keep in step with BRANCH in the helper below.
  private val branch = "stable-2025.1"

  override def nodes: List[String] = List(node)

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
  private val registryImage = registryContainer.image.ref

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
    "neutron-l3-agent",
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
    "manila-share",
    "octavia-api",
    "octavia-worker",
    "octavia-health-manager",
    "octavia-housekeeping",
    "magnum-api",
    "magnum-conductor",
    "ceilometer-central",
    "ceilometer-compute",
    "aodh-api",
    "aodh-evaluator",
    "aodh-notifier",
    "trove-api",
    "trove-conductor",
    "trove-taskmanager"
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
      "manila",
      "octavia",
      "magnum",
      "ceilometer",
      "aodh",
      "trove"
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

  // A fresh VM may still be running unattended-upgrades; wait for it before touching packages.
  private val aptIdle =
    """! pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null && ! pgrep -x apt-get >/dev/null && ! pgrep -x apt >/dev/null && ! pgrep -x dpkg >/dev/null"""
  private val waitAptIdleScript =
    s"""for i in $$(seq 1 180); do
       |  if $aptIdle; then
       |    echo "apt and dpkg are idle"
       |    exit 0
       |  fi
       |  sleep 5
       |done
       |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
       |exit 1""".stripMargin

  private val pullRegistryScript =
    s"""${Pull.shellFunction(
        "the registry image name in kolla_build.scala; the pull is from Docker Hub"
      )}
       |pull_retry $registryImage || exit 1
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

  private val installDevpiScript =
    """set -e
      |python3 -m venv /opt/devpi
      |/opt/devpi/bin/pip install --quiet devpi-server
      |echo "devpi installed"""".stripMargin
  private val initDevpiScript =
    """set -e
      |/opt/devpi/bin/devpi-init --serverdir /var/lib/orphera/devpi
      |echo "devpi initialised"""".stripMargin
  // The image list is written once; after that it is the user's to edit.
  private val imageListScript =
    s"""printf '%s\\n' ${images.mkString(" ")} > /etc/kolla-build/images.txt
       |echo "wrote /etc/kolla-build/images.txt"""".stripMargin
  private val removeDefaultSiteScript =
    """rm -f /etc/nginx/sites-enabled/default
      |echo "default nginx site removed"""".stripMargin

  // kolla-build is installed through devpi, so wait for it first.
  private val installKollaScript =
    s"""set -e
       |for i in $$(seq 1 20); do
       |  curl -sf -o /dev/null http://localhost:$devpiPort/ && break
       |  sleep 3
       |done
       |python3 -m venv /opt/kolla-build
       |/opt/kolla-build/bin/pip install --quiet --index-url http://localhost:$devpiPort/root/pypi/+simple/ --trusted-host localhost 'kolla>=20,<21' podman
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

  private val helperText: String =
    helper
      .replace("@@FQDN@@", fqdn)
      .replace("@@REGPORT@@", registryPort.toString)
      .replace("@@SRCPORT@@", sourcesPort.toString)
      .replace("@@DEVPIPORT@@", devpiPort.toString)
      .replace("@@APTPROXY@@", aptProxy)
      .replace("@@PROJECTS@@", projects.mkString(" "))
  private val helperPath = "/usr/local/sbin/orphera-kolla-build"

  private val servicesAnswer =
    s"""curl -sf http://localhost:$registryPort/v2/ >/dev/null &&
       |curl -sf http://localhost:$sourcesPort/ >/dev/null &&
       |curl -sf http://localhost:$devpiPort/ >/dev/null""".stripMargin
  private val waitServicesScript =
    s"""for i in $$(seq 1 40); do
       |  if $servicesAnswer; then echo "registry, sources and devpi answer"; exit 0; fi
       |  sleep 3
       |done
       |curl -s -o /dev/null -w 'registry http=%{http_code}\\n' http://localhost:$registryPort/v2/ || true
       |curl -s -o /dev/null -w 'sources http=%{http_code}\\n' http://localhost:$sourcesPort/ || true
       |curl -s -o /dev/null -w 'devpi http=%{http_code}\\n' http://localhost:$devpiPort/ || true
       |exit 1""".stripMargin

  // True when every project's tarball and the checksum list are there and the base image is in the registry.
  private val warm =
    s"""for p in ${projects.mkString(" ")}; do [ -s /srv/kolla-sources/$$p-$branch.tar.gz ] || exit 1; done
       |[ -s /srv/kolla-sources/MANIFEST ] && curl -sf http://localhost:$registryPort/v2/library/ubuntu/tags/list | grep -q '"24.04"'""".stripMargin
  // Fetches the source tarballs and copies the base image, so the first build starts with warm caches.
  private val warmScript =
    s"""set -e
       |$helperPath sources
       |if ! curl -sf http://localhost:$registryPort/v2/library/ubuntu/tags/list | grep -q '"24.04"'; then
       |  skopeo copy --dest-tls-verify=false docker://docker.io/library/ubuntu:24.04 docker://localhost:$registryPort/library/ubuntu:24.04
       |fi
       |$helperPath status""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val aptWait = command(
      "wait for apt and dpkg to be idle",
      waitAptIdleScript,
      unless = aptIdle,
      timeout = 960
    )
    val tools = hostPackages.map(p => pkg(p, updateCache = true).after(aptWait))
    val byName = hostPackages.zip(tools).toMap
    val podman = byName("podman")
    val nginxPkg = byName("nginx")
    val venvTools = List(byName("python3"), byName("python3-venv"), byName("python3-pip"))

    val pulled = command(
      "pull the registry image",
      pullRegistryScript,
      unless = s"podman image exists $registryImage",
      timeout = 600
    ).after(podman)
    val registriesDir = directory("/etc/containers/registries.conf.d").after(podman)
    val registries = file(
      "/etc/containers/registries.conf.d/orphera-kolla-registry.conf",
      registriesConf,
      mode = "0644"
    ).after(registriesDir)

    val sourcesDir = directory("/srv/kolla-sources")
    val kollaBuildDir = directory("/etc/kolla-build")
    val stateDir = directory("/var/lib/orphera/kolla-build")
    val devpiDir = directory("/var/lib/orphera/devpi")
    val registryData = directory(dataDir)

    val imageList = command(
      "write the list of images to build (once)",
      imageListScript,
      creates = "/etc/kolla-build/images.txt"
    ).after(kollaBuildDir)

    // nginx serves the source tarballs.
    val nginxUnit = service("nginx")
    val nginxConfFile = file(
      "/etc/nginx/conf.d/orphera-kolla-sources.conf",
      nginxConf,
      mode = "0644",
      owner = "root",
      group = "root"
    ).after(nginxPkg).notifies(nginxUnit)
    val defaultSite = command(
      "remove nginx's default site",
      removeDefaultSiteScript,
      onlyIf = "[ -e /etc/nginx/sites-enabled/default ] || [ -L /etc/nginx/sites-enabled/default ]"
    ).after(nginxPkg).notifies(nginxUnit)
    val nginxRunning = nginxUnit.after(nginxPkg, nginxConfFile, defaultSite, sourcesDir)

    // devpi, the PyPI cache.
    val devpiUnit0 = service("orphera-devpi.service")
    val devpiInstalled = command(
      "install devpi",
      installDevpiScript,
      creates = "/opt/devpi/bin/devpi-server",
      timeout = 900
    ).after((devpiDir :: venvTools)*)
    val devpiInit = command(
      "initialise the devpi store",
      initDevpiScript,
      creates = "/var/lib/orphera/devpi/.serverversion"
    ).after(devpiInstalled, devpiDir)
    val devpiUnitFile = file(
      "/etc/systemd/system/orphera-devpi.service",
      devpiUnit,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(devpiUnit0)
    val devpiRunning = devpiUnit0.after(devpiInit, devpiUnitFile)

    // The registry container.
    val registryUnit = service(registryContainer.unitName)
    val registryUnitFile = file(
      registryContainer.unitPath,
      registryContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(registryUnit)
    val registryRunning = registryUnit.after(pulled, registryData, registryUnitFile, registries)

    val socket = service("podman.socket").after(podman)

    val kollaInstalled = command(
      "install kolla-build through the devpi cache",
      installKollaScript,
      creates = "/opt/kolla-build/bin/kolla-build",
      timeout = 900
    ).after((devpiRunning :: podman :: venvTools)*)

    val helperFile = file(
      helperPath,
      helperText,
      mode = "0755",
      owner = "root",
      group = "root"
    ).after(sourcesDir, kollaBuildDir, stateDir)

    val warmed = command(
      "fetch the source tarballs and copy the base image into the registry",
      warmScript,
      unless = warm,
      timeout = 1800
    ).after(helperFile, registryRunning, nginxRunning, sourcesDir, byName("skopeo"), byName("curl"))
    val answering = command(
      "the registry, the source server and devpi answer",
      waitServicesScript,
      unless = servicesAnswer,
      timeout = 150
    ).after(registryRunning, nginxRunning, devpiRunning)

    tools ++ List(
      aptWait,
      pulled,
      registriesDir,
      registries,
      sourcesDir,
      kollaBuildDir,
      stateDir,
      devpiDir,
      registryData,
      imageList,
      nginxConfFile,
      defaultSite,
      nginxRunning,
      devpiInstalled,
      devpiInit,
      devpiUnitFile,
      devpiRunning,
      registryUnitFile,
      registryRunning,
      socket,
      kollaInstalled,
      helperFile,
      warmed,
      answering
    )
