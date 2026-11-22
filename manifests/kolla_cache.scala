// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// Kolla image cache on tst9 (10.10.5.21), as a declarative description (OrpheraApply, docs/APPLY-DESIGN.md; it
// replaced the cluster playbook of the same name): a pull-through registry mirror of
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
// What it does: installs podman and curl, pulls the registry image, makes the data directory, writes the
// unit and starts it, waits for the registry API, then pre-pulls the images in `warmImages` through the cache
// so they are stored before any node asks for them (add services there as the OpenStack work grows). Each
// step that changes the node is a command with a guard that tests what it makes, so a second run changes
// nothing and `--check` shows what would change:
//
//   orphera apply manifests/kolla_cache.scala --check
//   orphera apply manifests/kolla_cache.scala
//
// Disk: the cache lives in /var/lib/orphera/kolla-cache. config/tst9.yaml
// gives the VM a 120 GB disk; a full Kolla set is on the order of 10 GB or
// more. Cached blobs expire after REGISTRY_PROXY_TTL (set to a year here).
//
object kolla_cache extends OrpheraApply:
  import Resources.*

  private val node = "tst9"
  private val port = 5000
  private val remote = "https://quay.io"
  private val dataDir = "/var/lib/orphera/kolla-cache"

  override def nodes: List[String] = List(node)

  // The registry as data: the unit file text comes from it
  // (orchestrator/.../kolla/Container.scala), checked by ContainerCheck. It is the stock
  // registry:2 image, not a Kolla image, and publishes a port instead of using the host network.
  private val cacheContainer = Container(
    name = ContainerName("kolla-cache"),
    image = ExternalImage("docker.io/library/registry:2"),
    mounts = List(Mount(dataDir, "/var/lib/registry", Access.ReadWrite)),
    env = List(
      "REGISTRY_PROXY_REMOTEURL" -> remote,
      "REGISTRY_PROXY_TTL" -> "8760h"
    ),
    network = Network.Published,
    ports = List(Port(port, 5000)),
    description = Some(
      "Kolla image pull-through cache (registry proxying quay.io) managed by Orphera"
    ),
    settleSeconds = 5
  )
  private val registryImage = cacheContainer.image.ref

  // Repository paths under quay.io/openstack.kolla and the tag to warm.
  // Keep the tag in step with keystone_single_node.scala.
  private val tag = "2025.1-ubuntu-noble"
  private val warmImages = List("keystone")

  private val hostPackages = List("podman", "curl")

  private val pullRegistryScript =
    s"""${Pull.shellFunction(
        "the registry image name in kolla_cache.scala; the pull is from Docker Hub"
      )}
       |pull_retry $registryImage || exit 1
       |echo "registry image ready"""".stripMargin

  private val answers =
    s"""[ "$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/v2/)" = "200" ]"""
  private val waitScript =
    s"""for i in $$(seq 1 30); do
       |  if $answers; then echo "the registry answers on $port"; exit 0; fi
       |  sleep 2
       |done
       |echo "the registry does not answer on $port" >&2
       |exit 1""".stripMargin

  // Pulling through localhost:<port> makes the registry fetch from quay.io and
  // keep the layers; the local podman copy is then deleted so the VM's own
  // image store does not hold a second copy. One script for the whole list.
  private val warmScript =
    s"""set -e
       |${Pull.shellFunction("warmImages in kolla_cache.scala")}
       |for name in ${warmImages.mkString(" ")}; do
       |  pull_retry --tls-verify=false localhost:$port/openstack.kolla/$$name:$tag
       |  podman rmi -f localhost:$port/openstack.kolla/$$name:$tag
       |  echo "cached openstack.kolla/$$name:$tag"
       |done
       |curl -s http://localhost:$port/v2/_catalog
       |echo""".stripMargin
  // True when the catalog lists every image to warm.
  private val warm =
    s"""C=$$(curl -s http://localhost:$port/v2/_catalog)
       |for name in ${warmImages.mkString(
        " "
      )}; do printf '%s' "$$C" | grep -q "openstack.kolla/$$name" || exit 1; done""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools.head
    val pulled = command(
      "pull the registry image",
      pullRegistryScript,
      unless = s"podman image exists $registryImage",
      timeout = 600
    ).after(podman)
    val data = directory(dataDir)
    val unit = service(cacheContainer.unitName)
    val unitFile = file(
      cacheContainer.unitPath,
      cacheContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unit)
    val running = unit.after(pulled, data, unitFile)
    val answering = command(
      "the registry answers",
      waitScript,
      unless = answers,
      timeout = 90
    ).after(running, tools.last)
    val warmed = command(
      "pre-pull the Kolla images through the cache",
      warmScript,
      unless = warm,
      timeout = 3600
    ).after(answering, podman)
    tools ++ List(pulled, data, unitFile, running, answering, warmed)
