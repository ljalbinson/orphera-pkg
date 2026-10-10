// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kolla image cache on tst9 (10.10.5.21): a pull-through registry mirror of
// quay.io, run as a podman container under systemd. Nodes configured to use it
// (see the "use the Kolla cache" task in keystone_single_node.scala) pull
// quay.io/openstack.kolla/* from here; the first pull of an image goes to
// quay.io and is stored on tst9, later pulls on any node are served locally.
// If the cache is down, podman falls back to quay.io.
//
// It is the stock `registry:2` image in proxy mode (REGISTRY_PROXY_REMOTEURL),
// which is read-only: nothing can be pushed to it. It speaks plain HTTP on
// port 5000, acceptable for a cache on this LAN; clients mark it `insecure`
// for that reason.
//
// Stages:
//   - install-kolla-cache (tst9): podman, registry image, systemd unit.
//   - confirm-kolla-cache-healthy (tst9): waits for the registry API, then
//     pre-pulls the images in `warmImages` through the cache so they are
//     stored before any node asks for them. Add services here as the
//     OpenStack work grows (glance, placement, nova-api, ...).
//
// Disk: the cache lives in /var/lib/orphera/kolla-cache. config/tst9.yaml
// gives the VM a 120 GB disk; a full Kolla set is on the order of 10 GB or
// more. Cached blobs expire after REGISTRY_PROXY_TTL (set to a year here).
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object kolla_cache extends OrpheraClusterPlaybook:

  private val node = "tst9"
  private val port = 5000
  private val remote = "https://quay.io"
  private val registryImage = "docker.io/library/registry:2"
  private val dataDir = "/var/lib/orphera/kolla-cache"

  // Repository paths under quay.io/openstack.kolla and the tag to warm.
  // Keep the tag in step with keystone_single_node.scala.
  private val tag = "2025.1-ubuntu-noble"
  private val warmImages = List("keystone")

  private val hostPackages = List("podman", "curl")

  private val pullRegistryScript =
    s"""podman pull $registryImage || {
       |  echo "could not pull $registryImage from Docker Hub" >&2
       |  exit 1
       |}
       |echo "registry image ready"""".stripMargin

  private val unitScript =
    s"""mkdir -p $dataDir
       |cat > /etc/systemd/system/orphera-kolla-cache.service <<'UNIT'
       |[Unit]
       |Description=Kolla image pull-through cache (registry proxying quay.io) managed by Orphera
       |After=network-online.target
       |Wants=network-online.target
       |
       |[Service]
       |ExecStartPre=-/usr/bin/podman rm -f kolla-cache
       |ExecStart=/usr/bin/podman run --rm --name kolla-cache -p $port:5000 -e REGISTRY_PROXY_REMOTEURL=$remote -e REGISTRY_PROXY_TTL=8760h -v $dataDir:/var/lib/registry:z $registryImage
       |ExecStop=/usr/bin/podman stop -t 30 kolla-cache
       |Restart=on-failure
       |RestartSec=5
       |TimeoutStartSec=300
       |
       |[Install]
       |WantedBy=multi-user.target
       |UNIT
       |systemctl daemon-reload
       |systemctl enable orphera-kolla-cache
       |systemctl restart orphera-kolla-cache
       |sleep 5
       |if ! systemctl is-active --quiet orphera-kolla-cache; then
       |  echo "orphera-kolla-cache failed to start - see 'journalctl -xeu orphera-kolla-cache'" >&2
       |  exit 1
       |fi
       |echo "orphera-kolla-cache running on port $port"""".stripMargin

  private val healthScript =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/v2/)
       |[ "$$CODE" = "200" ]""".stripMargin

  // Pulling through localhost:<port> makes the registry fetch from quay.io and
  // keep the layers; the local podman copy is then deleted so the VM's own
  // image store does not hold a second copy. One script for the whole list,
  // since a stage task takes a single Task.
  private val warmScript =
    s"""set -e
       |for name in ${warmImages.mkString(" ")}; do
       |  podman pull --tls-verify=false localhost:$port/openstack.kolla/$$name:$tag
       |  podman rmi -f localhost:$port/openstack.kolla/$$name:$tag
       |  echo "cached openstack.kolla/$$name:$tag"
       |done""".stripMargin

  private val catalogScript =
    s"""curl -s http://localhost:$port/v2/_catalog
       |echo""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kolla-cache")(
      stage("install-kolla-cache", node)
        .task("install podman")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("pull the registry image")(
          Task.RunCommand(List("sh", "-c", pullRegistryScript), timeoutSeconds = 600)
        )
        .task("run the pull-through cache as a systemd-managed container")(
          Task.RunCommand(List("sh", "-c", unitScript), timeoutSeconds = 120)
        )
        .build,

      stage("confirm-kolla-cache-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript),
            pollIntervalSeconds = 3,
            timeoutSeconds = 60
          )
        )
        .task("pre-pull the Kolla images through the cache")(
          Task.RunCommand(List("sh", "-c", warmScript), timeoutSeconds = 3600)
        )
        .task("list the cached repositories")(
          Task.RunCommand(List("sh", "-c", catalogScript))
        )
        .build
    )
