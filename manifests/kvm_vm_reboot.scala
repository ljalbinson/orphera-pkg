// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Reboots a VM from its hypervisor, without going through the guest's own agent: for a
// VM whose Orphera agent or SSH is not answering. The playbook form of utils/reboot-vm.sh
// (which stays).
//
//   orphera cluster-playbook manifests/kvm_vm_reboot.scala --config config/tst7.yaml
//   ORPHERA_VM_MODE=hard orphera cluster-playbook manifests/kvm_vm_reboot.scala --config config/tst7.yaml
//   ORPHERA_VM_MODE=hard ORPHERA_VM_FORCE=1 ORPHERA_VM_WAIT=60 orphera cluster-playbook \
//       manifests/kvm_vm_reboot.scala --config config/tst7.yaml
//
// The same per-VM config file kvm_vm_start.scala takes; only `hypervisor` and `hostname`
// are used. One VM per run.
//
//   ORPHERA_VM_MODE   soft (default): virsh reboot, an ACPI request, so the guest shuts down
//                     cleanly and comes straight back; a guest that is not running its OS
//                     ignores it. Only a running VM is rebooted.
//                     hard: virsh shutdown, wait until it is off, then start it again, a full
//                     power cycle; also starts a VM that is shut off.
//   ORPHERA_VM_WAIT   with hard, seconds to wait for the shutdown (default 120)
//   ORPHERA_VM_FORCE  1, with hard: power the VM off at once (virsh destroy) if it has not
//                     shut down in that time; without it the VM is reported as failed and
//                     left running
//
// Does not wait for the guest to come back: `orphera uptime --nodes <vm>` shows when it has.
// Not run where this was written; the first run is the first test.
object kvm_vm_reboot extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "kvm_vm_reboot requires --config <vm config.yaml> " +
            "(e.g. --config config/tst7.yaml)"
        )

  private val hypervisorNode = params.hypervisor
  private val vmName = params.hostname

  require(
    vmName.matches("[A-Za-z0-9._-]+"),
    s"unusual characters in hostname '$vmName'; not running"
  )

  private val mode: String =
    sys.env.get("ORPHERA_VM_MODE").map(_.trim).filter(_.nonEmpty).getOrElse("soft")

  require(
    mode == "soft" || mode == "hard",
    s"ORPHERA_VM_MODE must be soft or hard, not '$mode'"
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

  private val softScript: String =
    """V="virsh --connect qemu:///system"
      |state=$($V domstate '@VM@' 2>/dev/null | head -1)
      |case "$state" in
      |  "")      echo "domain '@VM@' is not defined on this hypervisor" >&2; exit 1 ;;
      |  running) out=$($V reboot '@VM@' 2>&1) || { echo "$out" >&2; exit 1; }
      |           echo "'@VM@': reboot sent" ;;
      |  *)       echo "'@VM@' is $state, not running (use ORPHERA_VM_MODE=hard, or kvm_vm_start.scala)" >&2; exit 1 ;;
      |esac
      |""".stripMargin
      .replace("@VM@", vmName)

  private val hardScript: String =
    """V="virsh --connect qemu:///system"
      |state=$($V domstate '@VM@' 2>/dev/null | head -1)
      |[ -n "$state" ] || { echo "domain '@VM@' is not defined on this hypervisor" >&2; exit 1; }
      |how="shut down"
      |if [ "$state" != "shut off" ]; then
      |  out=$($V shutdown '@VM@' 2>&1) || { echo "$out" >&2; exit 1; }
      |  i=0
      |  while [ $i -lt @WAIT@ ]; do
      |    if [ "$($V domstate '@VM@' 2>/dev/null | head -1)" = "shut off" ]; then break; fi
      |    sleep 2; i=$((i + 2))
      |  done
      |  if [ "$($V domstate '@VM@' 2>/dev/null | head -1)" != "shut off" ]; then
      |    if [ @FORCE@ -eq 1 ]; then
      |      out=$($V destroy '@VM@' 2>&1) || { echo "$out" >&2; exit 1; }
      |      how="powered off after @WAIT@s"
      |    else
      |      echo "'@VM@' is still running after @WAIT@s (set ORPHERA_VM_FORCE=1 to power it off); not restarted" >&2
      |      exit 1
      |    fi
      |  fi
      |else
      |  how="was already shut off"
      |fi
      |out=$($V start '@VM@' 2>&1) || { echo "$how, but start failed: $out" >&2; exit 1; }
      |echo "'@VM@': $how, started"
      |""".stripMargin
      .replace("@VM@", vmName)
      .replace("@WAIT@", waitSeconds.toString)
      .replace("@FORCE@", if force then "1" else "0")

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-vm-reboot")(
      stage("reboot-vm", hypervisorNode)
        .task(s"reboot $vmName ($mode)")(
          Task.RunCommand(
            List("sh", "-c", if mode == "hard" then hardScript else softScript),
            timeoutSeconds = waitSeconds + 90
          )
        )
        .build
    )
