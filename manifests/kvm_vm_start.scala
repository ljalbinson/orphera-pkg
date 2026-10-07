// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Starts a VM that already exists on its libvirt/KVM hypervisor — the
// companion to kvm_vm_provision.scala, and the way back up after
// `orphera shutdown` (or a hypervisor-side power-off) on a VM.
//
//   orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst0.yaml
//
// The SAME per-VM config file kvm_vm_provision.scala takes. Only three of its
// fields are used: `hypervisor` (which inventory node to run `virsh` on),
// `hostname` (the libvirt domain name) and `nic0.ipaddress` (what to probe for
// SSH once it is up). Unlike kvm_vm_provision.scala there are NO hardcoded
// fallbacks: --config is required, because "start some default VM on some
// default hypervisor" is never what anyone meant.
//
// Idempotent: running and reachable -> reported and left alone; paused ->
// resumed; shut off -> started; not defined on that hypervisor -> fails (this
// playbook never creates a VM — use kvm_vm_provision.scala for that). Never
// destroys or redefines anything.
//
// One deliberate exception to "leave a running VM alone": libvirt reports a
// guest that has HALTED (OS stopped, QEMU still alive) or hung as `running`,
// forever. So if the domain is `running` but the guest answers neither ping nor
// TCP 22 from the hypervisor for 24s, it is hard-reset (`virsh reset`, like the
// reset button) — found the hard way on tst0, which sat halted and "running"
// until reset by hand. A guest that is merely slow to boot is covered by the
// 24s window only if it was already running; a VM this playbook itself starts
// is never reset, it just gets the 180s SSH wait below.
//
// Needs the hypervisor to be an inventory node running the Orphera agent
// (same requirement as kvm_vm_provision.scala's stages). The guest itself does
// NOT need the agent: readiness is a plain SSH probe, as in provisioning.
object kvm_vm_start extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "kvm_vm_start requires --config <vm config.yaml> " +
            "(e.g. --config config/tst0.yaml)"
        )

  private val hypervisorNode = params.hypervisor
  private val vmName = params.hostname
  private val ipAddress = params.nic0.ipaddress

  // `$$` is a literal `$` in an s-interpolated string; the shell variables and
  // command substitutions below must reach `sh` untouched.
  private val startVmScript =
    s"""reachable() { ping -c1 -W1 $ipAddress >/dev/null 2>&1 || timeout 3 bash -c 'echo > /dev/tcp/$ipAddress/22' >/dev/null 2>&1; }
       |state=$$(virsh --connect qemu:///system domstate '$vmName' 2>/dev/null) || { echo "ERROR: domain '$vmName' is not defined on this hypervisor - provision it with kvm_vm_provision.scala" >&2; exit 1; }
       |case "$$state" in
       |  running)
       |    ok=0
       |    for i in 1 2 3 4 5 6 7 8; do
       |      if reachable; then ok=1; break; fi
       |      sleep 3
       |    done
       |    if [ "$$ok" = 1 ]; then
       |      echo "'$vmName' is already running and reachable"
       |    else
       |      echo "'$vmName' is marked running but answers neither ping nor ssh after 24s - hard-resetting it"
       |      virsh --connect qemu:///system reset '$vmName'
       |    fi ;;
       |  paused) virsh --connect qemu:///system resume '$vmName' && echo "'$vmName' resumed" ;;
       |  *) virsh --connect qemu:///system start '$vmName' && echo "'$vmName' started (was: $$state)" ;;
       |esac""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-vm-start")(
      stage("start-vm", hypervisorNode)
        .task(s"start $vmName if it is not already running")(
          Task.RunCommand(
            List("sh", "-c", startVmScript),
            timeoutSeconds = 60
          )
        )
        .build,
      // HealthCheck.Ssh carries its own `host`, so this stage's node list
      // (the hypervisor) only decides where the check is driven from, same as
      // kvm_vm_provision.scala's confirm-vm-reachable. 180s covers a cold boot
      // (sshd up ~11s, pubkey auth working ~60s after boot on a real run).
      stage("confirm-vm-reachable", hypervisorNode)
        .waitFor(
          HealthCheck.Ssh(
            onNode = vmName,
            host = ipAddress,
            sshUser = "localadmin",
            sshKeyPath = None,
            remoteCommand = "true",
            pollIntervalSeconds = 10,
            timeoutSeconds = 180
          )
        )
        .task(s"$vmName is up")(
          Task.Debug(s"$vmName ($ipAddress) is running and answering SSH.")
        )
        .build
    )
