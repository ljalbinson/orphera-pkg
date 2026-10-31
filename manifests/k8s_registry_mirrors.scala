// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*
import orphera.orchestrator.kolla.*

// Pull-through registry mirrors on tst9 for the Kubernetes cluster
// (tst30-tst39). kolla_cache.scala already runs one for quay.io on port 5000
// (the Cilium images come from there). A registry proxy mirrors exactly one
// upstream, so this adds three more instances beside it:
//
//   registry.k8s.io  tst9:5001   Kubernetes control-plane images, pause, CoreDNS
//   registry-1.docker.io (docker.io)  tst9:5002
//   ghcr.io          tst9:5003   cert-manager, Argo CD and others later
//
// Same design as kolla_cache.scala: stock registry:2 in proxy mode, read-only,
// plain HTTP on the LAN. containerd on the cluster nodes (k8s_node_prep.scala)
// lists each mirror first and the upstream registry as the fallback, so a
// stopped mirror slows nothing down.
//
// Disk: each instance stores under /var/lib/orphera/k8s-mirror-<name>.
// config/tst9.yaml has a 120 GB disk; the cluster's images add a few GB.
// Docker Hub limits anonymous pulls, which is the main reason to cache it.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_registry_mirrors extends OrpheraClusterPlaybook:

  private val node = "tst9"

  private case class Mirror(name: String, remote: String, port: Int)

  private val mirrors = List(
    Mirror("k8s-io", "https://registry.k8s.io", 5001),
    Mirror("docker-io", "https://registry-1.docker.io", 5002),
    Mirror("ghcr-io", "https://ghcr.io", 5003)
  )

  private def dataDir(m: Mirror) = s"/var/lib/orphera/k8s-mirror-${m.name}"

  private def container(m: Mirror) = Container(
    name = ContainerName(s"k8s-mirror-${m.name}"),
    image = ExternalImage(registryImage),
    mounts = List(Mount(dataDir(m), "/var/lib/registry", Access.ReadWrite)),
    env = List(
      "REGISTRY_PROXY_REMOTEURL" -> m.remote,
      "REGISTRY_PROXY_TTL" -> "8760h"
    ),
    network = Network.Published,
    ports = List(Port(m.port, 5000)),
    description =
      Some(s"Pull-through cache of ${m.remote} for the Kubernetes cluster, managed by Orphera"),
    settleSeconds = 5
  )

  private def healthScript(m: Mirror) =
    s"""CODE=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:${m.port}/v2/)
       |[ "$$CODE" = "200" ]""".stripMargin

  private val registryImage = "docker.io/library/registry:2"

  private val pullRegistryScript =
    s"""${Pull.shellFunction(
        "the registry image name in k8s_registry_mirrors.scala; the pull is from Docker Hub"
      )}
       |pull_retry $registryImage || exit 1
       |echo "registry image ready"""".stripMargin

  private val mkdirScript =
    s"mkdir -p ${mirrors.map(dataDir).mkString(" ")}"

  private def installStage =
    val base = stage("install-k8s-mirrors", node)
      .task("install podman")(
        Task.Install(packages = List("podman", "curl"), updateCache = true)
      )
      .task("pull the registry image")(
        Task.RunCommand(List("sh", "-c", pullRegistryScript), timeoutSeconds = 600)
      )
      .task("create the data directories")(
        Task.RunCommand(List("sh", "-c", mkdirScript))
      )
    mirrors
      .foldLeft(base) { (s, m) =>
        val c = container(m)
        s.task(s"write ${c.unitName}")(c.writeUnit)
          .task(s"start ${c.unitName}")(c.start)
      }
      .build

  // A stage holds one wait-for check, so each mirror gets its own stage.
  private def healthStages: List[Stage] =
    mirrors.map { m =>
      stage(s"confirm-k8s-mirror-${m.name}-healthy", node)
        .waitFor(
          HealthCheck.Command(
            onNode = node,
            command = List("sh", "-c", healthScript(m)),
            pollIntervalSeconds = 3,
            timeoutSeconds = 60
          )
        )
        .build
    }

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-registry-mirrors")((installStage :: healthStages)*)
