// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Stops a VM on its libvirt/KVM hypervisor by asking the guest to shut down
// (virsh shutdown, an ACPI power-button press) and waiting until libvirt reports it
// shut off: the playbook form of utils/stop-vm.sh (which stays), the companion of
// kvm_vm_start.scala.
//
//   orphera cluster-playbook manifests/kvm_vm_stop.scala --config config/tst7.yaml
//   ORPHERA_VM_WAIT=300 orphera cluster-playbook manifests/kvm_vm_stop.scala --config config/tst7.yaml
//   ORPHERA_VM_FORCE=1 orphera cluster-playbook manifests/kvm_vm_stop.scala --config config/tst7.yaml
//
// The same per-VM config file kvm_vm_start.scala takes; only `hypervisor` and
// `hostname` are used. One VM per run (a shell loop does several).
//
//   ORPHERA_VM_WAIT   seconds to wait for the shutdown (default 120)
//   ORPHERA_VM_FORCE  1: after that time power the VM off at once (virsh destroy),
//                     like pulling the plug; without it a VM that has not shut down
//                     is reported as failed and left running
//
// A VM that is already shut off is left alone; one that is not defined on that
// hypervisor fails. Not run where this was written; the first run is the first test.
object kvm_vm_stop extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "kvm_vm_stop requires --config <vm config.yaml> " +
            "(e.g. --config config/tst7.yaml)"
        )

  private val hypervisorNode = params.hypervisor
  private val vmName = params.hostname

  require(
    vmName.matches("[A-Za-z0-9._-]+"),
    s"unusual characters in hostname '$vmName'; not running"
  )

  private val waitSeconds: Int =
    sys.env.get("ORPHERA_VM_WAIT").map(_.trim).filter(_.nonEmpty) match
      case None    => 120
      case Some(s) =>
        s.toIntOption.filter(_ >= 0).getOrElse {
          throw new RuntimeException(
            s"ORPHERA_VM_WAIT must be a number of seconds, not '$s'"
          )
        }

  private val force: Boolean =
    sys.env.get("ORPHERA_VM_FORCE").map(_.trim).contains("1")

  private val stopScript: String =
    """V="virsh --connect qemu:///system"
      |state=$($V domstate '@VM@' 2>/dev/null | head -1)
      |[ -n "$state" ] || { echo "domain '@VM@' is not defined on this hypervisor" >&2; exit 1; }
      |[ "$state" != "shut off" ] || { echo "'@VM@' is already shut off"; exit 0; }
      |out=$($V shutdown '@VM@' 2>&1) || { echo "$out" >&2; exit 1; }
      |i=0
      |while [ $i -lt @WAIT@ ]; do
      |  if [ "$($V domstate '@VM@' 2>/dev/null | head -1)" = "shut off" ]; then echo "'@VM@' shut down after ${i}s"; exit 0; fi
      |  sleep 2; i=$((i + 2))
      |done
      |if [ "$($V domstate '@VM@' 2>/dev/null | head -1)" = "shut off" ]; then echo "'@VM@' shut down"; exit 0; fi
      |if [ @FORCE@ -eq 1 ]; then
      |  out=$($V destroy '@VM@' 2>&1) || { echo "$out" >&2; exit 1; }
      |  echo "'@VM@' did not shut down in @WAIT@s; powered off"
      |else
      |  echo "'@VM@' is still running after @WAIT@s (set ORPHERA_VM_FORCE=1 to power it off)" >&2
      |  exit 1
      |fi
      |""".stripMargin
      .replace("@VM@", vmName)
      .replace("@WAIT@", waitSeconds.toString)
      .replace("@FORCE@", if force then "1" else "0")

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-vm-stop")(
      stage("stop-vm", hypervisorNode)
        .task(s"shut down $vmName")(
          Task.RunCommand(
            List("sh", "-c", stopScript),
            timeoutSeconds = waitSeconds + 60
          )
        )
        .build
    )
