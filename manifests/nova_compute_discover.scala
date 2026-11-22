// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.kolla.*

// Maps the compute hosts of nova_compute.scala into cell1, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md). Apply it after nova_compute.scala: the hosts must have registered their
// nova-compute service, which is what nova_compute.scala waits for.
//
//   orphera apply manifests/nova_compute_discover.scala --check
//   orphera apply manifests/nova_compute_discover.scala
//
// It runs on the Nova control node (tst14) and uses the nova.conf that nova_single_node.scala keeps for the
// nova-api service, with the same image, in a one-off container. The step is skipped when `cell_v2
// list_hosts` already shows every compute host, so a second run changes nothing; the Nova scheduler also
// discovers hosts by itself every 60 s (discover_hosts_in_cells_interval), this step only avoids the wait.
// Keep `computeHosts` in step with nova_compute.scala.
object nova_compute_discover extends OrpheraApply:
  import Resources.*

  private val controlNode = "tst14"
  private val computeHosts = List("tst17", "tst18")

  private val registry = "quay.io/openstack.kolla"
  private val apiImage =
    Image("nova-api", Release.R2025_1, Base.UbuntuNoble, registry).ref
  private val confRoot = "/etc/kolla"
  private val logDir = "/var/log/kolla/nova"

  override def nodes: List[String] = List(controlNode)

  private val nm =
    s"""podman run --rm --network host --user root --entrypoint nova-manage -v $confRoot/nova-api/nova.conf:/etc/nova/nova.conf:ro,z -v $logDir:/var/log/kolla/nova:z $apiImage"""

  // True when cell_v2 list_hosts names every compute host.
  private val allMapped =
    s"""NM="$nm"
       |OUT=$$($$NM cell_v2 list_hosts 2>/dev/null) || exit 1
       |for h in ${computeHosts.mkString(
        " "
      )}; do printf '%s\\n' "$$OUT" | grep -qw "$$h" || exit 1; done""".stripMargin

  // The services may have registered a moment ago: discover, check, and try again for up to a minute.
  private val discoverScript =
    s"""NM="$nm"
       |for i in $$(seq 1 12); do
       |  $$NM cell_v2 discover_hosts --verbose
       |  OUT=$$($$NM cell_v2 list_hosts)
       |  MISSING=""
       |  for h in ${computeHosts.mkString(
        " "
      )}; do printf '%s\\n' "$$OUT" | grep -qw "$$h" || MISSING="$$MISSING $$h"; done
       |  if [ -z "$$MISSING" ]; then echo "compute hosts mapped into cell1"; exit 0; fi
       |  sleep 5
       |done
       |echo "not mapped after a minute:$$MISSING (is nova-compute registered? see orphera-compute-api compute-up)" >&2
       |exit 1""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    List(
      command(
        s"map ${computeHosts.mkString(", ")} into cell1 (nova-manage cell_v2 discover_hosts)",
        discoverScript,
        unless = allMapped,
        timeout = 240
      )
    )
