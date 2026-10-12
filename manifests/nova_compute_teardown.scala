// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes nova_compute.scala on tst17 and tst18: stops and removes the
// nova-compute and Open vSwitch agent containers and units, deletes the Open vSwitch
// bridges br-provider and br-int, and removes the config, state and helper. The
// stale nova-compute service record stays in Nova (delete it with
// `openstack compute service delete`, or leave it; it shows as down). Instances on
// the node are NOT deleted first (delete them through Nova), and /var/lib/nova, which
// holds their disks, is removed. podman, Open vSwitch, libvirt and QEMU stay installed.
// NOT restored: the address enp2s0 had from provisioning (10.10.6.29/30); give it one
// again by hand or re-provision the VM from its config. Keep `nodes` in step with
// nova_compute.scala. Safe to re-run when nothing is installed.
object nova_compute_teardown extends OrpheraClusterPlaybook:

  private val nodes = List("tst17", "tst18")

  private val nodeScript =
    """if command -v virsh >/dev/null 2>&1; then
      |  LEFT=$(virsh -c qemu:///system list --all --name 2>/dev/null | grep -c .)
      |  if [ "$LEFT" -gt 0 ]; then
      |    echo "WARNING: $LEFT libvirt domain(s) still defined on this node; delete the instances through Nova first" >&2
      |  fi
      |fi
      |for u in nova-compute neutron-openvswitch-agent; do
      |  systemctl disable --now orphera-$u 2>/dev/null || true
      |  rm -f /etc/systemd/system/orphera-$u.service
      |  if command -v podman >/dev/null 2>&1; then podman rm -f $u 2>/dev/null || true; fi
      |done
      |systemctl daemon-reload
      |if command -v ovs-vsctl >/dev/null 2>&1; then
      |  ovs-vsctl --if-exists del-br br-provider || true
      |  ovs-vsctl --if-exists del-br br-int || true
      |fi
      |rm -rf /etc/kolla/nova-compute /etc/kolla/nova-compute-src /etc/kolla/nova-compute-ca /etc/kolla/neutron-openvswitch-agent /etc/kolla/neutron-agent-src
      |rm -rf /var/lib/nova /var/lib/orphera/neutron /var/log/kolla/nova /var/log/kolla/neutron
      |rm -f /usr/local/sbin/orphera-compute-api
      |echo "nova-compute and the Open vSwitch agent, their units, bridges, config and state removed"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("nova-compute-teardown")(
      stage("teardown-compute", nodes*)
        .task("remove the containers, units, bridges, config and state")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build
    )
