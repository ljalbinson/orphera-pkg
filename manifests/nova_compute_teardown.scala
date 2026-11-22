// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// Undoes nova_compute.scala on tst17 and tst18, as a declarative description (OrpheraApply,
// docs/APPLY-DESIGN.md): stops and removes the nova-compute and Open vSwitch agent containers and units,
// removes the nova-compute service and the Neutron agents of the node from Nova and Neutron (through the
// helper, while it is still there), deletes the Open vSwitch bridges br-provider and br-int, and removes
// the config, state and helper. Instances on the node are NOT deleted first (delete them through Nova), and
// /var/lib/nova, which holds their disks, is removed. podman, Open vSwitch, libvirt and QEMU stay installed.
// NOT restored: the address enp2s0 had from provisioning (10.10.6.29/30); give it one again by hand or
// re-provision the VM from its config. Keep `nodes` in step with nova_compute.scala.
//
//   orphera apply manifests/nova_compute_teardown.scala --check
//   orphera apply manifests/nova_compute_teardown.scala
//
// Each step is a command that runs only while there is something to remove (`onlyIf`), so a second run
// changes nothing. The Nova and Neutron cleanup is best effort: a helper from before it had `known` and
// `unregister`, or an API that does not answer, leaves the records (they show as down; delete them with
// `openstack compute service delete` and `openstack network agent delete`).
object nova_compute_teardown extends OrpheraApply:
  import Resources.*

  private val computeNodes = List("tst17", "tst18")
  private val helper = "/usr/local/sbin/orphera-compute-api"
  private val units = List("nova-compute", "neutron-openvswitch-agent")
  private val unitList = units.mkString(" ")

  override def nodes: List[String] = computeNodes

  private val registered =
    s"""[ -x $helper ] && $helper known "$$(hostname -s)" >/dev/null 2>&1"""
  private val unregisterScript =
    s"""$helper unregister "$$(hostname -s)" || echo "nova/neutron cleanup failed (continuing)""""

  // True when ANY unit has a unit file, or is active or enabled, or a bridge exists.
  private val anyRunning =
    s"""for u in $unitList; do if [ -f /etc/systemd/system/orphera-$$u.service ] || systemctl is-active --quiet orphera-$$u || systemctl is-enabled --quiet orphera-$$u; then exit 0; fi; done
       |if command -v ovs-vsctl >/dev/null 2>&1; then
       |  ovs-vsctl br-exists br-provider 2>/dev/null && exit 0
       |  ovs-vsctl br-exists br-int 2>/dev/null && exit 0
       |fi
       |exit 1""".stripMargin
  private val stopScript =
    s"""if command -v virsh >/dev/null 2>&1; then
       |  LEFT=$$(virsh -c qemu:///system list --all --name 2>/dev/null | grep -c .)
       |  if [ "$$LEFT" -gt 0 ]; then
       |    echo "WARNING: $$LEFT libvirt domain(s) still defined on this node; delete the instances through Nova first" >&2
       |  fi
       |fi
       |for u in $unitList; do
       |  systemctl disable --now orphera-$$u 2>/dev/null || true
       |  rm -f /etc/systemd/system/orphera-$$u.service
       |  if command -v podman >/dev/null 2>&1; then podman rm -f $$u 2>/dev/null || true; fi
       |done
       |systemctl daemon-reload
       |if command -v ovs-vsctl >/dev/null 2>&1; then
       |  ovs-vsctl --if-exists del-br br-provider || true
       |  ovs-vsctl --if-exists del-br br-int || true
       |fi
       |echo "nova-compute and the Open vSwitch agent, their units and bridges removed"""".stripMargin

  // /etc/kolla/nova-compute-src and neutron-agent-src are where the cluster playbook kept its sources; they
  // are listed so that a node set up by the playbook is cleaned too.
  private val dirs = List(
    "/etc/kolla/nova-compute",
    "/etc/kolla/nova-compute-src",
    "/etc/kolla/nova-compute-ca",
    "/etc/kolla/neutron-openvswitch-agent",
    "/etc/kolla/neutron-agent-src",
    "/var/lib/nova",
    "/var/lib/orphera/neutron",
    "/var/log/kolla/nova",
    "/var/log/kolla/neutron"
  )

  def describe(target: Node): List[ApplyResource] =
    val stopped = command(
      "stop and remove the nova-compute and OVS agent containers, units and bridges",
      stopScript,
      onlyIf = anyRunning,
      timeout = 240
    )
    val unregistered = command(
      "remove the node's nova-compute service and Neutron agents",
      unregisterScript,
      onlyIf = registered,
      timeout = 120
    ).after(stopped)
    // Typed removal, no script: each directory with its contents, then the helper.
    val cleaned = (dirs.map(absentDirectory(_, recursive = true)) :+
      absentFile(helper)).map(_.after(unregistered))
    List(stopped, unregistered) ++ cleaned
